package com.github.kr328.clash

import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.design.TailscaleStatusDesign
import com.github.kr328.clash.design.TsPing
import com.github.kr328.clash.design.TsStatus
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import org.json.JSONObject

class TailscaleStatusActivity : BaseActivity<TailscaleStatusDesign>() {
    override suspend fun main() {
        val design = TailscaleStatusDesign(this)

        setContentDesign(design)

        var override: ConfigurationOverride? = null

        suspend fun probe(address: String): TsPing {
            return runCatching {
                val response = withClash { pingTailscale(address) }
                val result = JSONObject(response)

                TsPing(
                    latencyMs = (result.optDouble("latency") * 1000).toInt(),
                    endpoint = result.optString("endpoint"),
                    error = result.optString("err"),
                )
            }.getOrElse {
                TsPing(error = it.message ?: "failed")
            }
        }

        design.onConfigChanged = {
            launch {
                val current = override ?: return@launch

                withClash {
                    patchOverride(Clash.OverrideSlot.Persist, current)
                }
            }
        }

        design.onPing = { address ->
            launch {
                val ping = probe(address)

                val message = if (ping.error.isNotEmpty()) {
                    "Ping $address 失败: ${ping.error}"
                } else {
                    "Ping $address: ${ping.latencyMs}ms"
                }

                design.showToast(message, ToastDuration.Short)
                design.updatePing(address, ping)
            }
        }

        suspend fun refresh() {
            val current = withClash { queryOverride(Clash.OverrideSlot.Persist) }
            override = current
            design.update(current.tailscale)

            val response = withClash { queryTailscaleStatus() }
            val parsed = TsStatus.parse(response)
            design.update(parsed)

            // tsnet 是懒启动的：首次读取时往往还停在 Starting，这里后台轮询到 Running
            if (parsed.state == "Starting") {
                launch {
                    repeat(3) {
                        delay(1200)

                        val again = TsStatus.parse(withClash { queryTailscaleStatus() })
                        design.update(again)

                        if (again.state != "Starting") return@launch
                    }
                }
            }

            // 自动探测在线终端，拿到延迟与直连/中继
            parsed.peers
                .filter { it.online && it.ips.isNotEmpty() }
                .forEach { peer ->
                    launch {
                        val address = peer.ips.first()
                        design.updatePing(address, probe(address))
                    }
                }
        }

        launch { refresh() }

        while (isActive) {
            select<Unit> {
                events.onReceive { }

                design.requests.onReceive {
                    when (it) {
                        TailscaleStatusDesign.Request.Refresh -> launch { refresh() }
                    }
                }
            }
        }
    }
}
