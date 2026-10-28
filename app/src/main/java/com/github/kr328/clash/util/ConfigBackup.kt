package com.github.kr328.clash.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地备份 / 恢复。
 *
 * 只写用户选定的本地文件（走 SAF），不涉及任何网络或云存储。
 * 覆盖内容：
 * - 覆写设置（含自定义规则 prepend-rules）
 * - 按应用上网：模式、勾选的应用、每个应用的出口策略组
 * - 网络开关（IPv6、DNS 劫持、系统代理、TUN 栈等）
 * - 订阅列表（名称、类型、订阅地址、更新间隔、是否当前使用）
 */
object ConfigBackup {
    private const val TAG = "ConfigBackup"
    private const val FORMAT_VERSION = 1

    const val MIME_TYPE = "application/json"

    data class ProfileSnapshot(
        val name: String,
        val type: Profile.Type,
        val source: String,
        val interval: Long,
        val ageSecretKey: String?,
        val active: Boolean,
    )

    data class ImportResult(
        val override: ConfigurationOverride,
        val profiles: List<ProfileSnapshot>,
    )

    fun suggestFileName(): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())

        return "cmfa-backup-$stamp.json"
    }

    fun export(
        context: Context,
        uri: Uri,
        override: ConfigurationOverride,
        profiles: List<ProfileSnapshot>,
    ) {
        val store = ServiceStore(context)

        val root = JSONObject().apply {
            put("version", FORMAT_VERSION)
            put("exportedAt", System.currentTimeMillis())

            put("override", JSONObject(ConfigurationOverride.toJsonString(override)))

            put(
                "accessControl", JSONObject().apply {
                    put("mode", store.accessControlMode.name)
                    put("packages", JSONArray(store.accessControlPackages.toList()))
                    put("routes", JSONObject(store.accessControlRoutes.orEmpty()))
                }
            )

            put(
                "network", JSONObject().apply {
                    put("bypassPrivateNetwork", store.bypassPrivateNetwork)
                    put("allowIpv6", store.allowIpv6)
                    put("dnsHijacking", store.dnsHijacking)
                    put("systemProxy", store.systemProxy)
                    put("tunStackMode", store.tunStackMode)
                    put("dynamicNotification", store.dynamicNotification)
                }
            )

            put(
                "profiles", JSONArray().apply {
                    profiles.forEach { profile ->
                        put(
                            JSONObject().apply {
                                put("name", profile.name)
                                put("type", profile.type.name)
                                put("source", profile.source)
                                put("interval", profile.interval)
                                put("active", profile.active)
                                profile.ageSecretKey?.let { put("ageSecretKey", it) }
                            }
                        )
                    }
                }
            )
        }

        val resolver = context.contentResolver
            ?: throw IllegalStateException("ContentResolver unavailable")

        // 用默认模式（"w"）打开：部分 SAF provider 不接受 "wt"，会抛异常导致导出失败
        resolver.openOutputStream(uri)?.use { stream ->
            stream.write(root.toString(2).toByteArray())
            stream.flush()
        } ?: throw IllegalStateException("Cannot open output stream")

        Log.d(TAG, "Exported backup to $uri, ${profiles.size} profile(s)")
    }

    /**
     * 读回备份并写入 ServiceStore 那部分设置，返回覆写配置和订阅列表交给调用方处理。
     */
    fun import(context: Context, uri: Uri): ImportResult {
        val resolver = context.contentResolver
            ?: throw IllegalStateException("ContentResolver unavailable")

        val text = resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: throw IllegalStateException("Cannot open input stream")

        val root = JSONObject(text)

        val version = root.optInt("version", 0)
        if (version <= 0 || version > FORMAT_VERSION) {
            throw IllegalArgumentException("Unsupported backup version: $version")
        }

        val override = ConfigurationOverride.fromJsonString(
            root.getJSONObject("override").toString()
        ) ?: throw IllegalArgumentException("Corrupted override section")

        val store = ServiceStore(context)

        root.optJSONObject("accessControl")?.let { section ->
            AccessControlMode.values()
                .firstOrNull { it.name == section.optString("mode") }
                ?.let { store.accessControlMode = it }

            section.optJSONArray("packages")?.let { array ->
                store.accessControlPackages = buildSet {
                    for (index in 0 until array.length()) {
                        array.optString(index).takeIf { it.isNotEmpty() }?.let(::add)
                    }
                }
            }

            section.optJSONObject("routes")?.let { routes ->
                store.accessControlRoutes = routes.keys().asSequence()
                    .associateWith { routes.optString(it) }
                    .filterValues { it.isNotEmpty() }
            }
        }

        root.optJSONObject("network")?.let { section ->
            if (section.has("bypassPrivateNetwork")) {
                store.bypassPrivateNetwork = section.optBoolean("bypassPrivateNetwork")
            }
            if (section.has("allowIpv6")) {
                store.allowIpv6 = section.optBoolean("allowIpv6")
            }
            if (section.has("dnsHijacking")) {
                store.dnsHijacking = section.optBoolean("dnsHijacking")
            }
            if (section.has("systemProxy")) {
                store.systemProxy = section.optBoolean("systemProxy")
            }
            section.optString("tunStackMode").takeIf { it.isNotEmpty() }?.let {
                store.tunStackMode = it
            }
            if (section.has("dynamicNotification")) {
                store.dynamicNotification = section.optBoolean("dynamicNotification")
            }
        }

        val profiles = buildList {
            root.optJSONArray("profiles")?.let { array ->
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue

                    val source = item.optString("source")
                    val name = item.optString("name").takeIf { it.isNotEmpty() } ?: continue

                    add(
                        ProfileSnapshot(
                            name = name,
                            type = Profile.Type.values()
                                .firstOrNull { it.name == item.optString("type") }
                                ?: Profile.Type.Url,
                            source = source,
                            interval = item.optLong("interval", 0L),
                            ageSecretKey = item.optString("ageSecretKey").takeIf { it.isNotEmpty() },
                            active = item.optBoolean("active", false),
                        )
                    )
                }
            }
        }

        Log.d(TAG, "Imported backup from $uri, ${profiles.size} profile(s)")

        return ImportResult(override, profiles)
    }
}
