package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.AdapterCustomRuleBinding
import com.github.kr328.clash.design.model.CustomRule
import com.github.kr328.clash.design.model.RuleType
import com.github.kr328.clash.design.model.TARGET_AUTO
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class CustomRuleAdapter(
    private val context: Context,
    private val rules: MutableList<CustomRule>,
    private val appLabelOf: (String) -> String?,
    private val onEdit: (Int) -> Unit,
    private val onDelete: (Int) -> Unit,
) : RecyclerView.Adapter<CustomRuleAdapter.Holder>() {
    class Holder(val binding: AdapterCustomRuleBinding) : RecyclerView.ViewHolder(binding.root)

    fun rebindAll() {
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterCustomRuleBinding
                .inflate(context.layoutInflater, context.root, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val rule = rules[position]
        val type = rule.type

        holder.binding.deleteView.visibility = View.VISIBLE

        holder.binding.titleView.text = when {
            type == null -> rule.payload
            type == RuleType.ProcessName -> appLabelOf(rule.payload) ?: rule.payload
            else -> rule.payload
        }

        holder.binding.summaryView.text = buildString {
            append(type?.let { context.getString(it.title) } ?: context.getString(R.string.custom_rules))
            append("  ·  ")
            append(
                if (rule.target.equals(TARGET_AUTO, ignoreCase = true)) {
                    context.getString(R.string.rule_target_auto_short)
                } else {
                    rule.target
                }
            )
        }

        holder.binding.root.setOnClickListener {
            onEdit(holder.bindingAdapterPosition)
        }

        holder.binding.deleteView.setOnClickListener {
            onDelete(holder.bindingAdapterPosition)
        }
    }

    override fun getItemCount(): Int = rules.size
}
