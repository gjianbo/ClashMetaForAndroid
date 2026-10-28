package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.databinding.AdapterAppPickerBinding
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class AppPickerAdapter(
    private val context: Context,
    private val onPick: (AppInfo) -> Unit,
) : RecyclerView.Adapter<AppPickerAdapter.Holder>() {
    class Holder(val binding: AdapterAppPickerBinding) : RecyclerView.ViewHolder(binding.root)

    var apps: List<AppInfo> = emptyList()

    fun patchApps(apps: List<AppInfo>) {
        this.apps = apps
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterAppPickerBinding
                .inflate(context.layoutInflater, context.root, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = apps[position]

        holder.binding.iconView.setBackgroundDrawable(current.icon)
        holder.binding.labelView.text = current.label
        holder.binding.packageView.text = current.packageName

        holder.binding.root.setOnClickListener {
            onPick(current)
        }
    }

    override fun getItemCount(): Int = apps.size
}
