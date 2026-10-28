package com.github.kr328.clash.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 验证本地备份依赖的序列化往返：导出写进 JSON 的内容，必须能被原样读回来。
 *
 * 只覆盖 toJsonString / fromJsonString，不触碰 Parcel 相关代码路径。
 */
class ConfigurationOverrideBackupTest {
    @Test
    fun `round trip keeps custom rules and scalar fields`() {
        val original = ConfigurationOverride(
            httpPort = 7890,
            mode = TunnelState.Mode.Global,
            findProcessMode = ConfigurationOverride.FindProcessMode.Always,
            prependRules = listOf(
                "PROCESS-NAME,com.openai.chatgpt,AUTO",
                "DOMAIN-SUFFIX,chatgpt.com,DIRECT",
            ),
        )

        val restored = ConfigurationOverride.fromJsonString(
            ConfigurationOverride.toJsonString(original)
        )

        assertNotNull(restored)
        assertEquals(7890, restored!!.httpPort)
        assertEquals(TunnelState.Mode.Global, restored.mode)
        assertEquals(ConfigurationOverride.FindProcessMode.Always, restored.findProcessMode)
        assertEquals(original.prependRules, restored.prependRules)
    }

    @Test
    fun `round trip keeps nested sections`() {
        val original = ConfigurationOverride().apply {
            dns.nameServer = listOf("https://doh.pub/dns-query")
            dns.enhancedMode = ConfigurationOverride.DnsEnhancedMode.FakeIp
            sniffer.enable = true
            sniffer.forceDnsMapping = false
            tailscale.enable = true
            tailscale.hostname = "cmfa-node"
            app.appendSystemDns = true
        }

        val restored = ConfigurationOverride.fromJsonString(
            ConfigurationOverride.toJsonString(original)
        )

        assertNotNull(restored)
        assertEquals(listOf("https://doh.pub/dns-query"), restored!!.dns.nameServer)
        assertEquals(ConfigurationOverride.DnsEnhancedMode.FakeIp, restored.dns.enhancedMode)
        assertEquals(true, restored.sniffer.enable)
        assertEquals(false, restored.sniffer.forceDnsMapping)
        assertEquals(true, restored.tailscale.enable)
        assertEquals("cmfa-node", restored.tailscale.hostname)
        assertEquals(true, restored.app.appendSystemDns)
    }

    @Test
    fun `absent fields stay null so nothing is forced onto the profile`() {
        val restored = ConfigurationOverride.fromJsonString("{}")

        assertNotNull(restored)
        assertNull(restored!!.httpPort)
        assertNull(restored.mode)
        assertNull(restored.prependRules)
        assertNull(restored.findProcessMode)
    }

    @Test
    fun `corrupted input returns null instead of throwing`() {
        assertNull(ConfigurationOverride.fromJsonString("not a json"))

        // 注意 httpPort 的 JSON key 是 "port"（@SerialName），写错 key 会被 ignoreUnknownKeys 吃掉
        assertNull(ConfigurationOverride.fromJsonString("""{"port":"wrong type"}"""))
    }

    @Test
    fun `unknown keys are ignored so older or newer backups still load`() {
        val restored = ConfigurationOverride.fromJsonString(
            """{"port":7890,"future-option":true}"""
        )

        assertNotNull(restored)
        assertEquals(7890, restored!!.httpPort)
    }
}
