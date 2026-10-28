package com.github.kr328.clash.design

import android.content.Context
import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import com.github.kr328.clash.design.adapter.AppPickerAdapter
import com.github.kr328.clash.design.adapter.CustomRuleAdapter
import com.github.kr328.clash.design.databinding.DesignCustomRulesBinding
import com.github.kr328.clash.design.databinding.DialogSearchBinding
import com.github.kr328.clash.design.dialog.FullScreenDialog
import com.github.kr328.clash.design.dialog.requestModelTextInput
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.model.CustomRule
import com.github.kr328.clash.design.model.RuleType
import com.github.kr328.clash.design.model.TARGET_AUTO
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 表单式的自定义规则编辑页。
 *
 * 设计目标：用户只可能填错"域名字符串"这一项，其余（匹配方式、目标策略组、要匹配哪个应用）
 * 全部从列表里选，避免手写 Clash 规则行时把策略组名写错导致配置加载失败。
 */
class CustomRulesDesign(
    context: Context,
    private val rules: MutableList<CustomRule>,
    private val groupNames: List<String>,
    apps: List<AppInfo>,
) : Design<Unit>(context) {
    private val binding = DesignCustomRulesBinding
        .inflate(context.layoutInflater, context.root, false)

    private var apps: List<AppInfo> = apps

    private var appLabels: Map<String, String> = apps.associate { it.packageName to it.label }

    private val adapter = CustomRuleAdapter(
        context = context,
        rules = rules,
        appLabelOf = { packageName -> appLabels[packageName] },
        onEdit = ::editRule,
        onDelete = ::deleteRule,
    )

    override val root: View
        get() = binding.root

    /**
     * 应用列表在页面显示之后才异步加载完成，这里补进来，让 PROCESS-NAME 规则能显示应用名。
     */
    suspend fun patchApps(apps: List<AppInfo>) {
        this.apps = apps
        this.appLabels = apps.associate { it.packageName to it.label }

        withContext(Dispatchers.Main) {
            adapter.rebindAll()
        }
    }

    private val defaultTarget: String
        get() = TARGET_AUTO

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.mainList.recyclerList.also {
            it.bindAppBarElevation(binding.activityBarLayout)
            it.applyLinearAdapter(context, adapter)
        }

        binding.addView.setOnClickListener {
            editRule(-1)
        }

        refresh()
    }

    private fun refresh() {
        adapter.rebindAll()

        binding.emptyView.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun deleteRule(index: Int) {
        if (index !in rules.indices) {
            return
        }

        rules.removeAt(index)

        refresh()
    }

    private fun editRule(index: Int) {
        val existing = rules.getOrNull(index)

        // 复杂规则（AND / SUB-RULE / MATCH 等）表单表示不了，只能删除后重建，避免被误改写
        if (existing != null && !existing.isStructured) {
            showToastAsync(R.string.custom_rule_unsupported)

            return
        }

        this@CustomRulesDesign.launch {
            val edited = requestRuleDialog(existing) ?: return@launch

            if (index in rules.indices) {
                rules[index] = edited
            } else {
                rules.add(edited)
            }

            refresh()
        }
    }

    private suspend fun requestRuleDialog(existing: CustomRule?): CustomRule? {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_custom_rule, null)

        val typeView = view.findViewById<TextView>(R.id.type_view)
        val payloadView = view.findViewById<EditText>(R.id.payload_view)
        val pickAppView = view.findViewById<TextView>(R.id.pick_app_view)
        val targetView = view.findViewById<TextView>(R.id.target_view)

        var type = existing?.type ?: RuleType.DomainSuffix
        var target = existing?.target?.takeIf { it.isNotBlank() } ?: defaultTarget
        var payload = existing?.payload.orEmpty()
        var pickedApp = existing
            ?.takeIf { it.type == RuleType.ProcessName }
            ?.let { rule -> apps.firstOrNull { it.packageName == rule.payload } }

        fun syncViews() {
            val needsApp = type.needsApp

            payloadView.visibility = if (needsApp) View.GONE else View.VISIBLE
            pickAppView.visibility = if (needsApp) View.VISIBLE else View.GONE

            if (needsApp) {
                pickAppView.text = pickedApp?.label ?: context.getString(R.string.rule_payload_pick_app)
                pickAppView.alpha = if (pickedApp == null) 0.6f else 1f
            } else {
                pickAppView.alpha = 1f

                if (payloadView.text?.toString() != payload) {
                    payloadView.setText(payload)
                }
            }

            typeView.text = context.getString(type.title)
            targetView.text = targetLabel(target)
        }

        payloadView.addTextChangedListener {
            payload = it?.toString().orEmpty()
        }

        typeView.setOnClickListener {
            this@CustomRulesDesign.launch {
                val picked = chooseType(type) ?: return@launch

                if (picked != type) {
                    // 包名和域名/IP 不通用，切换匹配方式时清掉旧值，避免留下无效内容
                    payload = ""
                    pickedApp = null

                    type = picked

                    syncViews()
                }
            }
        }

        pickAppView.setOnClickListener {
            this@CustomRulesDesign.launch {
                val picked = chooseApp() ?: return@launch

                pickedApp = picked
                payload = picked.packageName

                syncViews()
            }
        }

        targetView.setOnClickListener {
            this@CustomRulesDesign.launch {
                val picked = chooseTarget(target) ?: return@launch

                target = picked

                syncViews()
            }
        }

        syncViews()

        return suspendCancellableCoroutine { ctx ->
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(if (existing == null) R.string.custom_rule_add else R.string.custom_rules)
                .setView(view)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create()

            dialog.setOnShowListener {
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                    payload = if (type.needsApp) payload else payloadView.text?.toString().orEmpty()

                    if (payload.isBlank()) {
                        showToastAsync(R.string.rule_payload_empty)

                        return@setOnClickListener
                    }

                    if (!ctx.isCompleted) {
                        ctx.resume(CustomRule(type, payload, target))
                    }

                    dialog.dismiss()
                }
            }

            dialog.setOnDismissListener {
                if (!ctx.isCompleted) {
                    ctx.resume(null)
                }
            }

            ctx.invokeOnCancellation {
                dialog.dismiss()
            }

            dialog.show()
        }
    }

    private suspend fun chooseType(current: RuleType): RuleType? {
        val types = RuleType.entries
        val items = types.map { context.getString(it.title) }
        val checked = types.indexOf(current).coerceAtLeast(0)

        val picked = suspendCancellableCoroutine<Int?> { ctx ->
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.rule_field_type)
                .setSingleChoiceItems(items.toTypedArray(), checked) { dialog, which ->
                    if (!ctx.isCompleted) {
                        ctx.resume(which)
                    }

                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel) { _, _ -> }
                .show()

            dialog.setOnDismissListener {
                if (!ctx.isCompleted) {
                    ctx.resume(null)
                }
            }

            ctx.invokeOnCancellation {
                dialog.dismiss()
            }
        } ?: return null

        return types.getOrNull(picked)
    }

    private suspend fun chooseTarget(current: String): String? {
        val builtins = listOf(TARGET_AUTO, "DIRECT", "REJECT", "GLOBAL")
        val values = (builtins + groupNames.filterNot { it in builtins }).toMutableList()
        val customIndex = values.size

        val labels = values.map(::targetLabel).toMutableList()

        labels.add(context.getString(R.string.rule_target_custom))

        val checked = values.indexOf(current).takeIf { it >= 0 } ?: customIndex

        val picked = suspendCancellableCoroutine<Int?> { ctx ->
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.rule_field_target)
                .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                    if (!ctx.isCompleted) {
                        ctx.resume(which)
                    }

                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel) { _, _ -> }
                .show()

            dialog.setOnDismissListener {
                if (!ctx.isCompleted) {
                    ctx.resume(null)
                }
            }

            ctx.invokeOnCancellation {
                dialog.dismiss()
            }
        } ?: return null

        if (picked != customIndex) {
            return values[picked]
        }

        return context.requestModelTextInput(
            initial = current,
            title = context.getString(R.string.rule_field_target),
            reset = null,
            hint = context.getText(R.string.rule_field_target),
        )?.takeIf { it.isNotBlank() }
    }

    private fun targetLabel(target: String): String = when (target.uppercase()) {
        TARGET_AUTO -> context.getString(R.string.rule_target_auto)
        "DIRECT" -> context.getString(R.string.rule_target_direct)
        "REJECT" -> context.getString(R.string.rule_target_reject)
        "GLOBAL" -> context.getString(R.string.rule_target_global)
        else -> target
    }

    private suspend fun chooseApp(): AppInfo? {
        return suspendCancellableCoroutine { ctx ->
            val binding = DialogSearchBinding
                .inflate(context.layoutInflater, context.root, false)
            val dialog = FullScreenDialog(context)

            val adapter = AppPickerAdapter(context) { app ->
                if (!ctx.isCompleted) {
                    ctx.resume(app)
                }

                dialog.dismiss()
            }

            dialog.setContentView(binding.root)

            binding.surface = dialog.surface
            binding.mainList.applyLinearAdapter(context, adapter)
            binding.closeView.setOnClickListener {
                dialog.dismiss()
            }

            adapter.patchApps(apps)

            binding.keywordView.addTextChangedListener { text ->
                val keyword = text?.toString().orEmpty()

                adapter.patchApps(
                    if (keyword.isBlank()) {
                        apps
                    } else {
                        apps.filter {
                            it.label.contains(keyword, ignoreCase = true) ||
                                    it.packageName.contains(keyword, ignoreCase = true)
                        }
                    }
                )
            }

            dialog.setOnDismissListener {
                if (!ctx.isCompleted) {
                    ctx.resume(null)
                }
            }

            ctx.invokeOnCancellation {
                dialog.dismiss()
            }

            dialog.show()
        }
    }

    private fun showToastAsync(resId: Int) {
        this@CustomRulesDesign.launch {
            showToast(resId, com.github.kr328.clash.design.ui.ToastDuration.Short)
        }
    }
}
