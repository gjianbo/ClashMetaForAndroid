package com.github.kr328.clash.design.model

import androidx.annotation.StringRes
import com.github.kr328.clash.design.R

/**
 * 规则目标占位符，表示「跟随当前订阅」。
 *
 * 由内核在每次加载 profile 时换算成当前配置里可用的出口策略组，
 * 这样规则不会因为切换订阅、组名消失而失效。
 */
const val TARGET_AUTO = "AUTO"

/**
 * 一条手动规则的结构化表示。
 *
 * 落盘时仍然序列化成 Clash 的规则行（`TYPE,payload,target`），所以内核侧不需要感知这个模型；
 * 只有 UI 用结构化形式来避免手写错误。
 *
 * [type] 为 null 表示这一行是用户之前手写、且无法被表单映射的复杂规则（例如 AND / SUB-RULE），
 * 这种行原样保留，不做结构化编辑。
 */
data class CustomRule(
    val type: RuleType?,
    val payload: String,
    val target: String,
    val noResolve: Boolean = false,
) {
    val isStructured: Boolean
        get() = type != null

    fun toLine(): String {
        val t = type ?: return payload

        return buildString {
            append(t.key).append(',').append(payload).append(',').append(target)

            if (noResolve) {
                append(",no-resolve")
            }
        }
    }

    companion object {
        fun fromLine(line: String): CustomRule {
            val items = line.split(',').map(String::trim)

            if (items.size < 3) {
                return CustomRule(null, line, "")
            }

            val type = RuleType.fromKey(items[0]) ?: return CustomRule(null, line, "")

            // 值里本身可能带逗号（正则类规则），这里只取第 2、3 段，够表单用
            val noResolve = items.drop(3).any { it.equals("no-resolve", ignoreCase = true) }

            return CustomRule(type, items[1], items[2], noResolve)
        }
    }
}

enum class RuleType(
    val key: String,
    @StringRes val title: Int,
    val needsApp: Boolean = false,
) {
    Domain("DOMAIN", R.string.rule_type_domain),
    DomainSuffix("DOMAIN-SUFFIX", R.string.rule_type_domain_suffix),
    DomainKeyword("DOMAIN-KEYWORD", R.string.rule_type_domain_keyword),
    ProcessName("PROCESS-NAME", R.string.rule_type_process_name, needsApp = true),
    IpCidr("IP-CIDR", R.string.rule_type_ip_cidr),
    ;

    companion object {
        fun fromKey(key: String): RuleType? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}
