package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.core.widget.addTextChangedListener
import com.github.kr328.clash.design.adapter.AppAdapter
import com.github.kr328.clash.design.component.AccessControlMenu
import com.github.kr328.clash.design.databinding.DesignAccessControlBinding
import com.github.kr328.clash.design.databinding.DialogSearchBinding
import com.github.kr328.clash.design.dialog.FullScreenDialog
import com.github.kr328.clash.design.dialog.requestModelTextInput
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.model.TARGET_AUTO
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.resume

class AccessControlDesign(
    context: Context,
    uiStore: UiStore,
    private val selected: MutableSet<String>,
    private val routes: MutableMap<String, String> = mutableMapOf(),
) : Design<AccessControlDesign.Request>(context) {
    sealed class Request {
        object ReloadApps : Request()
        object SelectAll : Request()
        object SelectNone : Request()
        object SelectInvert : Request()
        object Import : Request()
        object Export : Request()

        /** 长按某个已选中的应用，请求为它指定出口策略组 */
        data class SelectRoute(val packageName: String, val label: String) : Request()
    }

    private val binding = DesignAccessControlBinding
        .inflate(context.layoutInflater, context.root, false)

    private val adapter = AppAdapter(context, selected, routes)

    private val menu: AccessControlMenu by lazy {
        AccessControlMenu(context, binding.menuView, uiStore, requests)
    }

    val apps: List<AppInfo>
        get() = adapter.apps

    override val root: View
        get() = binding.root

    suspend fun patchApps(apps: List<AppInfo>) {
        adapter.swapDataSet(adapter::apps, apps, false)
    }

    suspend fun rebindAll() {
        withContext(Dispatchers.Main) {
            adapter.rebindAll()
        }
    }

    /**
     * 为单个应用指定出口策略组。
     *
     * 返回值语义：null 表示取消；空串表示清除（跟随规则匹配）；其他为策略组名。
     */
    suspend fun requestSelectRoute(
        current: String?,
        groups: List<String>,
    ): String? {
        if (groups.isEmpty()) {
            return context.requestModelTextInput(
                initial = current ?: "",
                title = context.getString(R.string.access_control_route),
                reset = context.getText(R.string.reset),
                hint = context.getText(R.string.access_control_route_hint),
            )
        }

        return suspendCancellableCoroutine { ctx ->
            val values = mutableListOf(TARGET_AUTO)
            values.addAll(groups.filterNot { it.equals(TARGET_AUTO, ignoreCase = true) })

            val followIndex = values.size

            val labels = values.map {
                if (it == TARGET_AUTO) context.getString(R.string.rule_target_auto) else it
            }.toMutableList()

            labels.add(context.getString(R.string.access_control_route_follow_rules))

            val checked = values.indexOf(current ?: "").takeIf { it >= 0 } ?: followIndex

            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.access_control_route)
                .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                    if (!ctx.isCompleted) {
                        ctx.resume(if (which == followIndex) "" else values[which])
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
        }
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.mainList.recyclerList.also {
            it.bindAppBarElevation(binding.activityBarLayout)
            it.applyLinearAdapter(context, adapter)
        }

        binding.menuView.setOnClickListener {
            menu.show()
        }

        binding.searchView.setOnClickListener {
            launch {
                try {
                    requestSearch()
                } finally {
                    withContext(NonCancellable) {
                        rebindAll()
                    }
                }
            }
        }

        adapter.onLongClick = { app ->
            requests.trySend(Request.SelectRoute(app.packageName, app.label))
        }
    }

    private suspend fun requestSearch() {
        coroutineScope {
            val binding = DialogSearchBinding
                .inflate(context.layoutInflater, context.root, false)
            val adapter = AppAdapter(context, selected, routes)
            val dialog = FullScreenDialog(context)
            val filter = Channel<Unit>(Channel.CONFLATED)

            dialog.setContentView(binding.root)

            binding.surface = dialog.surface
            binding.mainList.applyLinearAdapter(context, adapter)
            binding.keywordView.addTextChangedListener {
                filter.trySend(Unit)
            }
            binding.closeView.setOnClickListener {
                dialog.dismiss()
            }

            dialog.setOnDismissListener {
                cancel()
            }

            dialog.setOnShowListener {
                binding.keywordView.requestTextInput()
            }

            dialog.show()

            while (isActive) {
                filter.receive()

                val keyword = binding.keywordView.text?.toString() ?: ""

                val apps: List<AppInfo> = if (keyword.isEmpty()) {
                    emptyList()
                } else {
                    withContext(Dispatchers.Default) {
                        apps.filter {
                            it.label.contains(keyword, ignoreCase = true) ||
                                    it.packageName.contains(keyword, ignoreCase = true)
                        }
                    }
                }

                adapter.patchDataSet(adapter::apps, apps, false, AppInfo::packageName)

                delay(200)
            }
        }
    }
}