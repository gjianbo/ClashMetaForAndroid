package com.github.kr328.clash.service.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.service.PreferenceProvider
import com.github.kr328.clash.service.model.AccessControlMode
import java.util.*

class ServiceStore(context: Context) {
    private val store = Store(
        PreferenceProvider
            .createSharedPreferencesFromContext(context)
            .asStoreProvider()
    )

    var activeProfile: UUID? by store.typedString(
        key = "active_profile",
        from = { if (it.isBlank()) null else UUID.fromString(it) },
        to = { it?.toString() ?: "" }
    )

    var bypassPrivateNetwork: Boolean by store.boolean(
        key = "bypass_private_network",
        defaultValue = true
    )

    var accessControlMode: AccessControlMode by store.enum(
        key = "access_control_mode",
        defaultValue = AccessControlMode.AcceptAll,
        values = AccessControlMode.values()
    )

    var accessControlPackages by store.stringSet(
        key = "access_control_packages",
        defaultValue = emptySet()
    )

    /**
     * 「按应用上网」里为单个应用指定的出口策略组：包名 -> 策略组名。
     *
     * 只对同时出现在 [accessControlPackages] 里的包生效。保存在 SharedPreferences 内，
     * 由 [com.github.kr328.clash.service.clash.module.ConfigurationModule] 在每次加载
     * profile 前翻译成 `PROCESS-NAME,<包名>,<策略组>` 规则，写入 session override 的
     * prepend-rules。
     */
    var accessControlRoutes: Map<String, String>? by store.typedString(
        key = "access_control_routes",
        from = { raw ->
            raw.lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && it.contains('=') }
                .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
                .filterValues(String::isNotEmpty)
        },
        to = { routes ->
            routes.orEmpty()
                .filterValues(String::isNotEmpty)
                .entries
                .joinToString("\n") { "${it.key}=${it.value}" }
        }
    )

    var dnsHijacking by store.boolean(
        key = "dns_hijacking",
        defaultValue = true
    )

    var systemProxy by store.boolean(
        key = "system_proxy",
        defaultValue = true
    )

    var allowBypass by store.boolean(
        key = "allow_bypass",
        defaultValue = true
    )

    var allowIpv6 by store.boolean(
        key = "allow_ipv6",
        defaultValue = false
    )

    var tunStackMode by store.string(
        key = "tun_stack_mode",
        defaultValue = "mips"
    )

    var dynamicNotification by store.boolean(
        key = "dynamic_notification",
        defaultValue = true
    )
}