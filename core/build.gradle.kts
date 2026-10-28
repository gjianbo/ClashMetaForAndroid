import android.databinding.tool.ext.capitalizeUS
import com.github.kr328.golang.GolangBuildTask
import com.github.kr328.golang.GolangPlugin

plugins {
    kotlin("android")
    id("com.android.library")
    id("kotlinx-serialization")
    id("golang-android")
}

val golangSource = file("src/main/golang/native")

golang {
    sourceSets {
        create("alpha") {
            tags.set(listOf("foss","with_gvisor","cmfa"))
            srcDir.set(file("src/foss/golang"))
        }
        create("meta") {
            tags.set(listOf("foss","with_gvisor","cmfa"))
            srcDir.set(file("src/foss/golang"))
        }
        all {
            fileName.set("libclash.so")
            packageName.set("cfa/native")
        }
    }
}

android {
    productFlavors {
        all {
            externalNativeBuild {
                cmake {
                    arguments("-DGO_SOURCE:STRING=${golangSource}")
                    arguments("-DGO_OUTPUT:STRING=${GolangPlugin.outputDirOf(project, null, null)}")
                    arguments("-DFLAVOR_NAME:STRING=$name")
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation(project(":common"))

    implementation(libs.androidx.core)
    implementation(libs.kotlin.coroutine)
    implementation(libs.kotlin.serialization.json)

    testImplementation("junit:junit:4.13.2")
}

afterEvaluate {
    tasks.withType(GolangBuildTask::class.java).forEach {
        it.inputs.dir(golangSource)
    }
}

val abis = listOf("arm64-v8a" to "Arm64V8a", "armeabi-v7a" to "ArmeabiV7a", "x86" to "X86", "x86_64" to "X8664")

androidComponents.onVariants { variant ->
    val cmakeName = if (variant.buildType == "debug") "Debug" else "RelWithDebInfo"

    abis.forEach { (abi, goAbi) ->
        tasks.configureEach {
            if (name.startsWith("buildCMake$cmakeName[$abi]")) {
                dependsOn("externalGolangBuild${variant.name.capitalizeUS()}$goAbi")
                println("Set up dependency: $name -> externalGolangBuild${variant.name.capitalizeUS()}$goAbi")
            }
        }
    }
}

// 上游 mihomo submodule 的自定义补丁：构建前自动应用，使 submodule 始终保持指向上游。
// 补丁存放在仓库根目录 patches/，按文件名顺序应用；已应用则跳过（幂等）。
val patchClashSources = tasks.register("patchClashSources") {
    val patchDir = rootProject.file("patches")
    val clashDir = file("src/foss/golang/clash")

    doLast {
        if (!patchDir.isDirectory) {
            return@doLast
        }

        val patches = patchDir.listFiles { f -> f.extension == "patch" }
            ?.sortedBy { it.name }

        if (patches.isNullOrEmpty()) {
            return@doLast
        }

        fun git(vararg args: String): Pair<Int, String> {
            val process = ProcessBuilder(listOf("git", "-C", clashDir.absolutePath) + args)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()

            return process.waitFor() to output
        }

        patches.forEach { patch ->
            val (applicable, _) = git("apply", "--check", patch.absolutePath)

            if (applicable == 0) {
                val (applied, output) = git("apply", patch.absolutePath)

                if (applied != 0) {
                    throw GradleException("应用补丁失败 ${patch.name}:\n$output")
                }

                logger.lifecycle("patchClashSources: 已应用 ${patch.name}")

                return@forEach
            }

            val (alreadyApplied, _) = git("apply", "--reverse", "--check", patch.absolutePath)

            if (alreadyApplied == 0) {
                logger.lifecycle("patchClashSources: 已应用过，跳过 ${patch.name}")
            } else {
                throw GradleException(
                    "补丁 ${patch.name} 无法应用，可能上游代码已变更，请手动处理后重试"
                )
            }
        }
    }
}

tasks.configureEach {
    if (name.startsWith("externalGolangBuild")) {
        dependsOn(patchClashSources)
    }
}

// golang-android 插件会把构建类型名当作 Go 构建标签追加进去（debug 变体 → `-tags ...,debug`）。
// 而 sing 系库（metacubex/sing 的 common/debug）在 `debug` 标签下会把缓冲区泄漏检查打开：
// GC 时若发现有 Buffer 未被 Release，直接 panic("leaking buffer")，整个 Go 进程 abort。
// 表现就是 debug 包用一阵子后 VPN 突然消失（日志无任何报错，只有 tombstone）。
// release 构建只会静默 Release 回收，所以官方版正常。这里去掉该标签，让 debug 与 release 行为一致。
tasks.withType(GolangBuildTask::class.java).configureEach {
    doFirst {
        val line = commandLine
        val index = line.indexOf("-tags")

        if (index >= 0 && index + 1 < line.size) {
            val tags = line[index + 1]
                .split(",")
                .filterNot { it == "debug" }

            commandLine = line.toMutableList().apply {
                set(index + 1, tags.joinToString(","))
            }

            logger.lifecycle("stripDebugTag: ${line[index + 1]} -> ${tags.joinToString(",")}")
        }
    }
}

// 反向应用补丁，使 submodule 恢复到上游干净状态。
// 在执行 git submodule update / 切换上游版本前运行。
tasks.register("revertClashPatches") {
    val patchDir = rootProject.file("patches")
    val clashDir = file("src/foss/golang/clash")

    doLast {
        val patches = patchDir.listFiles { f -> f.extension == "patch" }
            ?.sortedByDescending { it.name }

        if (patches.isNullOrEmpty()) {
            return@doLast
        }

        fun git(vararg args: String): Pair<Int, String> {
            val process = ProcessBuilder(listOf("git", "-C", clashDir.absolutePath) + args)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()

            return process.waitFor() to output
        }

        patches.forEach { patch ->
            val (applicable, _) = git("apply", "--reverse", "--check", patch.absolutePath)

            if (applicable != 0) {
                logger.lifecycle("revertClashPatches: 未应用，跳过 ${patch.name}")

                return@forEach
            }

            val (reverted, output) = git("apply", "--reverse", patch.absolutePath)

            if (reverted != 0) {
                throw GradleException("还原补丁失败 ${patch.name}:\n$output")
            }

            logger.lifecycle("revertClashPatches: 已还原 ${patch.name}")
        }
    }
}