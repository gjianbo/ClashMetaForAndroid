package com.github.kr328.clash.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.design.databinding.DesignTailscaleStatusBinding
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.json.JSONObject

data class TsPeer(
    val hostname: String,
    val dnsName: String,
    val os: String,
    val ips: List<String>,
    val online: Boolean,
    val relay: String,
    val curAddr: String,
    val lastSeen: String,
    val rxBytes: Long,
    val txBytes: Long,
    val active: Boolean,
    val exitNode: Boolean,
)

data class TsStatus(
    val found: Boolean = false,
    val state: String = "",
    val version: String = "",
    val suffix: String = "",
    val self: TsPeer? = null,
    val peers: List<TsPeer> = emptyList(),
    val health: List<String> = emptyList(),
    val error: String = "",
) {
    companion object {
        fun parse(json: String): TsStatus {
            return runCatching {
                val o = JSONObject(json)

                TsStatus(
                    found = o.optBoolean("found"),
                    state = o.optString("state"),
                    version = o.optString("version"),
                    suffix = o.optString("suffix"),
                    self = o.optJSONObject("self")?.let(::peerOf),
                    peers = o.optJSONArray("peers")?.let { arr ->
                        (0 until arr.length()).map { peerOf(arr.getJSONObject(it)) }
                    } ?: emptyList(),
                    health = o.optJSONArray("health")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList(),
                    error = o.optString("error"),
                )
            }.getOrElse {
                TsStatus(error = it.message ?: "parse error")
            }
        }

        private fun peerOf(o: JSONObject) = TsPeer(
            hostname = o.optString("hostname"),
            dnsName = o.optString("dnsName"),
            os = o.optString("os"),
            ips = o.optJSONArray("ips")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            online = o.optBoolean("online"),
            relay = o.optString("relay"),
            curAddr = o.optString("curAddr"),
            lastSeen = o.optString("lastSeen"),
            rxBytes = o.optLong("rxBytes"),
            txBytes = o.optLong("txBytes"),
            active = o.optBoolean("active"),
            exitNode = o.optBoolean("exitNode"),
        )
    }
}

data class TsPing(
    val latencyMs: Int = 0,
    val endpoint: String = "",
    val error: String = "",
)

class TailscaleStatusDesign(context: Context) : Design<TailscaleStatusDesign.Request>(context) {
    enum class Request {
        Refresh
    }

    private val binding = DesignTailscaleStatusBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    var onPing: ((String) -> Unit)? = null
    var onConfigChanged: (() -> Unit)? = null

    private var config: ConfigurationOverride.Tailscale = ConfigurationOverride.Tailscale()
    private var status: TsStatus? = null
    private val pings = mutableMapOf<String, TsPing>()

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)
        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        binding.activityBarLayout
            .findViewById<TextView>(R.id.activity_bar_title_view)
            ?.text = context.getString(R.string.tailscale)
    }

    fun requestRefresh() {
        requests.trySend(Request.Refresh)
    }

    fun update(config: ConfigurationOverride.Tailscale) {
        this.config = config
        render()
    }

    fun update(status: TsStatus) {
        this.status = status
        pings.clear()
        render()
    }

    fun updatePing(address: String, ping: TsPing) {
        pings[address] = ping
        render()
    }

    private fun render() {
        val content = binding.content
        content.removeAllViews()

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        renderConfig(column)
        renderStatus(column)

        content.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun renderConfig(content: LinearLayout) {
        content.addView(section(context.getString(R.string.tailscale_settings)))
        content.addView(note(context.getString(R.string.tailscale_restart_hint)))

        content.addView(
            row(
                title = context.getString(R.string.tailscale_enable),
                value = when (config.enable) {
                    true -> context.getString(R.string.enabled)
                    false -> context.getString(R.string.disabled)
                    null -> context.getString(R.string.dont_modify)
                },
            ) {
                promptTriState(context.getString(R.string.tailscale_enable), config.enable) {
                    config.enable = it
                    commit()
                }
            }
        )

        content.addView(
            row(
                title = context.getString(R.string.tailscale_auth_key),
                value = mask(config.authKey),
            ) {
                promptText(
                    context.getString(R.string.tailscale_auth_key),
                    config.authKey.orEmpty(),
                    "tskey-auth-...",
                ) {
                    config.authKey = it.ifEmpty { null }
                    commit()
                }
            }
        )

        content.addView(
            row(
                title = context.getString(R.string.tailscale_hostname),
                value = config.hostname.orEmpty().ifEmpty { context.getString(R.string.tailscale_default) },
            ) {
                promptText(
                    context.getString(R.string.tailscale_hostname),
                    config.hostname.orEmpty(),
                    "cmfa-phone",
                ) {
                    config.hostname = it.ifEmpty { null }
                    commit()
                }
            }
        )

        content.addView(
            row(
                title = context.getString(R.string.tailscale_name),
                value = config.name.orEmpty().ifEmpty { context.getString(R.string.tailscale_default) },
            ) {
                promptText(
                    context.getString(R.string.tailscale_name),
                    config.name.orEmpty(),
                    "TS",
                ) {
                    config.name = it.ifEmpty { null }
                    commit()
                }
            }
        )

        content.addView(
            row(
                title = context.getString(R.string.tailscale_accept_routes),
                value = when (config.acceptRoutes) {
                    true -> context.getString(R.string.enabled)
                    false -> context.getString(R.string.disabled)
                    null -> context.getString(R.string.dont_modify)
                },
            ) {
                promptTriState(context.getString(R.string.tailscale_accept_routes), config.acceptRoutes) {
                    config.acceptRoutes = it
                    commit()
                }
            }
        )

        content.addView(
            row(
                title = context.getString(R.string.tailscale_routes),
                value = config.routes?.joinToString(", ").orEmpty()
                    .ifEmpty { context.getString(R.string.dont_modify) },
            ) {
                promptText(
                    context.getString(R.string.tailscale_routes),
                    config.routes?.joinToString(", ").orEmpty(),
                    "192.168.1.0/24",
                ) {
                    config.routes = it.split(",")
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .ifEmpty { null }
                    commit()
                }
            }
        )
    }

    private fun renderStatus(content: LinearLayout) {
        val current = status
            ?: return

        if (!current.found) {
            content.addView(
                note(current.error.ifEmpty { context.getString(R.string.tailscale_not_configured) })
            )
            return
        }

        content.addView(section(context.getString(R.string.tailscale_self)))

        val self = current.self
        if (self != null) {
            val ip = self.ips.firstOrNull().orEmpty()

            content.addView(
                infoRow(
                    title = fqdnOf(self, current.suffix),
                    subtitle = listOf(ip, current.state, current.version)
                        .filter(String::isNotEmpty)
                        .joinToString("  ·  "),
                    copyValue = ip.ifEmpty { null },
                    copyLabel = context.getString(R.string.tailscale_copied, ip),
                )
            )
        }

        content.addView(
            section("${context.getString(R.string.tailscale_peers)} (${current.peers.size})")
        )

        if (current.peers.isEmpty()) {
            content.addView(note(context.getString(R.string.tailscale_no_peers)))
        } else {
            current.peers
                .sortedWith(compareByDescending<TsPeer> { it.online }.thenBy { it.hostname })
                .forEach { peer ->
                    val ip = peer.ips.firstOrNull().orEmpty()

                    content.addView(
                        infoRow(
                            title = fqdnOf(peer, current.suffix),
                            subtitle = listOf(ip, peerStatus(peer, ip), peer.os)
                                .filter(String::isNotEmpty)
                                .joinToString("  ·  "),
                            copyValue = ip.ifEmpty { null },
                            copyLabel = context.getString(R.string.tailscale_copied, ip),
                            onClick = if (peer.online && ip.isNotEmpty()) {
                                { onPing?.invoke(ip) }
                            } else {
                                null
                            },
                        )
                    )
                }
        }

        if (current.health.isNotEmpty()) {
            content.addView(section("Health"))
            current.health.forEach { content.addView(note(it)) }
        }
    }

    private fun commit() {
        render()
        onConfigChanged?.invoke()
    }

    private fun fqdnOf(peer: TsPeer, suffix: String): String {
        if (peer.dnsName.isNotEmpty()) return peer.dnsName

        return if (suffix.isNotEmpty()) "${peer.hostname}.$suffix" else peer.hostname
    }

    private fun peerStatus(peer: TsPeer, ip: String): String {
        if (!peer.online) return context.getString(R.string.tailscale_offline)

        val ping = pings[ip]

        return when {
            ping == null ->
                context.getString(R.string.tailscale_online)

            ping.error.isNotEmpty() ->
                context.getString(R.string.tailscale_online) + " · " +
                    context.getString(R.string.tailscale_probe_failed)

            ping.endpoint.isNotEmpty() ->
                context.getString(R.string.tailscale_direct) + " · " + ping.latencyMs + "ms"

            else ->
                context.getString(R.string.tailscale_relay) + " · " + ping.latencyMs + "ms"
        }
    }

    private fun infoRow(
        title: String,
        subtitle: String,
        copyValue: String? = null,
        copyLabel: String = "",
        onClick: (() -> Unit)? = null,
    ): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setBackgroundResource(selectableItemBackground)
                setOnClickListener { onClick() }
            }
        }

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        texts.addView(TextView(context).apply {
            text = title
            textSize = 15f
            setTextColor(themeTextColor(android.R.attr.textColorPrimary))
        })

        if (subtitle.isNotEmpty()) {
            texts.addView(TextView(context).apply {
                text = subtitle
                textSize = 12f
                setPadding(0, dp(2), 0, 0)
                setTextColor(themeTextColor(android.R.attr.textColorSecondary))
            })
        }

        container.addView(texts)

        if (copyValue != null) {
            container.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_baseline_content_copy)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    themeColor(android.R.attr.textColorSecondary)
                )
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setBackgroundResource(selectableItemBackgroundBorderless)
                isClickable = true
                isFocusable = true
                contentDescription = copyLabel
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                setOnClickListener { copyToClipboard(copyValue, copyLabel) }
            })
        }

        return container
    }

    private fun copyToClipboard(text: String, label: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        manager.setPrimaryClip(ClipData.newPlainText(label, text))

        launch {
            showToast(label, ToastDuration.Short)
        }
    }

    private fun themeColor(attr: Int): Int {
        val typed = context.obtainStyledAttributes(intArrayOf(attr))
        val color = typed.getColor(0, 0)

        typed.recycle()

        return color
    }

    private fun mask(key: String?): String {
        if (key.isNullOrEmpty()) return context.getString(R.string.dont_modify)

        return if (key.length <= 8) "****" else key.take(8) + "..." + key.takeLast(4)
    }

    private fun section(title: String): TextView {
        return TextView(context).apply {
            text = title
            textSize = 12f
            setPadding(dp(20), dp(20), dp(20), dp(6))
            setTextColor(themeTextColor(android.R.attr.textColorPrimary))
        }
    }

    private fun note(message: String): TextView {
        return TextView(context).apply {
            text = message
            textSize = 13f
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setTextColor(themeTextColor(android.R.attr.textColorSecondary))
        }
    }

    private fun row(
        title: String,
        value: String,
        clickable: Boolean = true,
        onClick: (() -> Unit)? = null,
    ): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))

            if (clickable && onClick != null) {
                isClickable = true
                isFocusable = true
                setBackgroundResource(selectableItemBackground)
                setOnClickListener { onClick() }
            }
        }

        container.addView(TextView(context).apply {
            text = title
            textSize = 15f
            setTextColor(themeTextColor(android.R.attr.textColorPrimary))
        })

        if (value.isNotEmpty()) {
            container.addView(TextView(context).apply {
                text = value
                textSize = 12f
                setPadding(0, dp(2), 0, 0)
                setTextColor(themeTextColor(android.R.attr.textColorSecondary))
            })
        }

        return container.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun promptText(title: String, value: String, hint: String, onResult: (String) -> Unit) {
        val input = EditText(context).apply {
            setText(value)
            this.hint = hint
            setSingleLine()
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> onResult(input.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.reset) { _, _ -> onResult("") }
            .show()
    }

    private fun promptTriState(title: String, value: Boolean?, onResult: (Boolean?) -> Unit) {
        val labels = arrayOf(
            context.getString(R.string.dont_modify),
            context.getString(R.string.enabled),
            context.getString(R.string.disabled),
        )

        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setSingleChoiceItems(labels, when (value) {
                true -> 1
                false -> 2
                null -> 0
            }) { dialog, which ->
                onResult(
                    when (which) {
                        1 -> true
                        2 -> false
                        else -> null
                    }
                )
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun themeTextColor(attr: Int): android.content.res.ColorStateList? {
        val typed = context.obtainStyledAttributes(intArrayOf(attr))
        val colors = typed.getColorStateList(0)

        typed.recycle()

        return colors
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private val selectableItemBackground: Int
        get() {
            val out = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            val res = out.getResourceId(0, 0)

            out.recycle()

            return res
        }

    private val selectableItemBackgroundBorderless: Int
        get() {
            val out = context.obtainStyledAttributes(
                intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
            )
            val res = out.getResourceId(0, 0)

            out.recycle()

            return res
        }
}
