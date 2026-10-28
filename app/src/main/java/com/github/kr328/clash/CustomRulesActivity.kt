package com.github.kr328.clash

import android.Manifest.permission.INTERNET
import android.content.pm.PackageManager
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.design.CustomRulesDesign
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.model.CustomRule
import com.github.kr328.clash.design.util.toAppInfo
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/**
 * 表单式自定义规则编辑页。
 *
 * 规则最终仍以 Clash 规则行的形式存进 override 的 prepend-rules，
 * 这里只负责在「结构化模型」和「规则行」之间转换。
 */
class CustomRulesActivity : BaseActivity<CustomRulesDesign>() {
    override suspend fun main() {
        val configuration = withClash { queryOverride(Clash.OverrideSlot.Persist) }

        val rules = withContext(Dispatchers.IO) {
            configuration.prependRules.orEmpty()
                .map(CustomRule::fromLine)
                .toMutableList()
        }

        defer {
            withClash {
                configuration.prependRules = rules
                    .map(CustomRule::toLine)
                    .filter(String::isNotBlank)
                    .ifEmpty { null }

                patchOverride(Clash.OverrideSlot.Persist, configuration)
            }
        }

        // 组名只有在内核加载过 profile 之后才拿得到；拿不到就让用户手动输入
        val groups = runCatching {
            withClash { queryProxyGroupNames(excludeNotSelectable = false) }
        }.getOrElse { emptyList() }

        // 注意：应用列表必须在 setContentDesign 之后异步加载。
        // loadApps 要给每个已安装应用 loadIcon()，冷启动要几秒，放在前面会导致首次进入白屏。
        val design = CustomRulesDesign(this, rules, groups, emptyList())

        setContentDesign(design)

        launch {
            val apps = withContext(Dispatchers.IO) {
                loadApps()
            }

            design.patchApps(apps)
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    if (it == Event.ServiceRecreated) {
                        finish()
                    }
                }
            }
        }
    }

    private fun loadApps(): List<AppInfo> {
        val pm = packageManager

        return pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            .asSequence()
            .filter { it.packageName != packageName }
            .filter { it.applicationInfo != null }
            .filter { it.requestedPermissions?.contains(INTERNET) == true }
            .map { it.toAppInfo(pm) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
