package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendProfileLoaded
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import java.util.*

class ConfigurationModule(service: Service) : Module<ConfigurationModule.LoadException>(service) {
    data class LoadException(val message: String)

    private val store = ServiceStore(service)
    private val reload = Channel<Unit>(Channel.CONFLATED)

    /**
     * 把「按应用上网」里配置的出口策略组翻译成 PROCESS-NAME 规则，写进 session override。
     *
     * 之所以走 session 而不是 persist：这些规则是每次启动按当前勾选的包重新算出来的，
     * 存盘没有意义，而 persist slot 属于用户在「覆写」页面手写的内容。
     * 内核侧会把两个 slot 的 prepend-rules 合并后前置到 profile 的规则之前。
     */
    private fun applyAccessControlRoutes() {
        val packages = store.accessControlPackages
        val routes = store.accessControlRoutes.orEmpty()
            .filterKeys { it in packages }
        val rules = routes.map { (packageName, group) -> "PROCESS-NAME,$packageName,$group" }

        val session = Clash.queryOverride(Clash.OverrideSlot.Session)
        if (session.prependRules == rules.ifEmpty { null }) {
            return
        }

        Clash.patchOverride(
            Clash.OverrideSlot.Session,
            session.copy(prependRules = rules.ifEmpty { null })
        )
    }

    override suspend fun run() {
        val broadcasts = receiveBroadcast {
            addAction(Intents.ACTION_PROFILE_CHANGED)
            addAction(Intents.ACTION_OVERRIDE_CHANGED)
        }

        var loaded: UUID? = null

        reload.trySend(Unit)

        while (true) {
            val changed: UUID? = select {
                broadcasts.onReceive {
                    if (it.action == Intents.ACTION_PROFILE_CHANGED)
                        UUID.fromString(it.getStringExtra(Intents.EXTRA_UUID))
                    else
                        null
                }
                reload.onReceive {
                    null
                }
            }

            try {
                val current = store.activeProfile
                    ?: throw NullPointerException("No profile selected")

                if (current == loaded && changed != null && changed != loaded)
                    continue

                loaded = current

                val active = ImportedDao().queryByUUID(current)
                    ?: throw NullPointerException("No profile selected")

                Clash.setAgeSecretKey(active.ageSecretKey?.takeIf { it.isNotBlank() })

                applyAccessControlRoutes()

                Clash.load(service.importedDir.resolve(active.uuid.toString())).await()

                val remove = SelectionDao().querySelections(active.uuid)
                    .filterNot { Clash.patchSelector(it.proxy, it.selected) }
                    .map { it.proxy }

                SelectionDao().removeSelections(active.uuid, remove)

                StatusProvider.currentProfile = active.name

                service.sendProfileLoaded(current)

                Log.d("Profile ${active.name} loaded")
            } catch (e: Exception) {
                return enqueueEvent(LoadException(e.message ?: "Unknown"))
            }
        }
    }
}
