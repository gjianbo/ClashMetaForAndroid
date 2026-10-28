package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.AdapterAppBinding
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.model.TARGET_AUTO
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class AppAdapter(
    private val context: Context,
    private val selected: MutableSet<String>,
    private val routes: Map<String, String> = emptyMap(),
) : RecyclerView.Adapter<AppAdapter.Holder>() {
    class Holder(val binding: AdapterAppBinding) : RecyclerView.ViewHolder(binding.root)

    var apps: List<AppInfo> = emptyList()

    var onLongClick: ((AppInfo) -> Unit)? = null

    fun rebindAll() {
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterAppBinding
                .inflate(context.layoutInflater, context.root, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = apps[position]

        holder.binding.app = current
        holder.binding.selected = current.packageName in selected

        val route = routes[current.packageName]?.takeIf { it.isNotEmpty() }

        holder.binding.routeView.text = when {
            route == null -> current.packageName
            route.equals(TARGET_AUTO, ignoreCase = true) -> context.getString(
                R.string.app_route_summary,
                current.packageName,
                context.getString(R.string.rule_target_auto_short),
            )

            else -> context.getString(R.string.app_route_summary, current.packageName, route)
        }

        holder.binding.root.setOnClickListener {
            if (holder.binding.selected) {
                selected.remove(current.packageName)
                holder.binding.selected = false
            } else {
                selected.add(current.packageName)
                holder.binding.selected = true
            }
        }

        holder.binding.root.setOnLongClickListener {
            // 只有被选中的应用才需要指定出口，未选中的长按没有意义
            if (current.packageName in selected) {
                onLongClick?.invoke(current)

                true
            } else {
                false
            }
        }
    }

    override fun getItemCount(): Int {
        return apps.size
    }
}
