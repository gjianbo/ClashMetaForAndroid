package com.github.kr328.clash

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.componentName
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.design.AppSettingsDesign
import com.github.kr328.clash.design.model.Behavior
import com.github.kr328.clash.design.store.UiStore.Companion.mainActivityAlias
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.ApplicationObserver
import com.github.kr328.clash.util.ConfigBackup
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.UUID
import com.github.kr328.clash.design.R

class AppSettingsActivity : BaseActivity<AppSettingsDesign>(), Behavior {
    override suspend fun main() {
        val design = AppSettingsDesign(
            this,
            uiStore,
            ServiceStore(this),
            this,
            clashRunning,
            ::onHideIconChange,
        )

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ClashStart, Event.ClashStop, Event.ServiceRecreated ->
                            recreate()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        AppSettingsDesign.Request.ReCreateAllActivities -> {
                            ApplicationObserver.createdActivities.forEach { activity ->
                                activity.recreate()
                            }
                        }
                        AppSettingsDesign.Request.ExportBackup -> launch {
                            exportBackup(design)
                        }
                        AppSettingsDesign.Request.ImportBackup -> launch {
                            importBackup(design)
                        }
                    }
                }
            }
        }
    }

    override var autoRestart: Boolean
        get() {
            val status = packageManager.getComponentEnabledSetting(
                RestartReceiver::class.componentName
            )

            return status == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        set(value) {
            val status = if (value)
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED

            packageManager.setComponentEnabledSetting(
                RestartReceiver::class.componentName,
                status,
                PackageManager.DONT_KILL_APP,
            )
        }

    private suspend fun exportBackup(design: AppSettingsDesign) {
        val uri = startActivityForResult(
            ActivityResultContracts.CreateDocument(ConfigBackup.MIME_TYPE),
            ConfigBackup.suggestFileName(),
        ) ?: return

        try {
            val override = withClash { queryOverride(Clash.OverrideSlot.Persist) }

            val profiles = withProfile { queryAll() }.map { profile ->
                ConfigBackup.ProfileSnapshot(
                    name = profile.name,
                    type = profile.type,
                    source = profile.source,
                    interval = profile.interval,
                    ageSecretKey = profile.ageSecretKey,
                    active = profile.active,
                )
            }

            withContext(Dispatchers.IO) {
                ConfigBackup.export(this@AppSettingsActivity, uri, override, profiles)
            }

            design.showToast(R.string.backup_export_success, ToastDuration.Short)
        } catch (e: Exception) {
            Log.w("Export backup", e)

            design.showToast(R.string.backup_export_failed, ToastDuration.Long)
        }
    }

    private suspend fun importBackup(design: AppSettingsDesign) {
        val uri = startActivityForResult(
            ActivityResultContracts.OpenDocument(),
            arrayOf("*/*"),
        ) ?: return

        try {
            val result = withContext(Dispatchers.IO) {
                ConfigBackup.import(this@AppSettingsActivity, uri)
            }

            withClash {
                patchOverride(Clash.OverrideSlot.Persist, result.override)
            }

            // 本地文件类型的订阅恢复不了（原文件可能已经不在），只重建 URL 订阅
            val restorable = result.profiles.filter {
                it.type == Profile.Type.Url && it.source.isNotBlank()
            }

            if (restorable.isEmpty()) {
                design.showToast(R.string.backup_import_success, ToastDuration.Long)

                return
            }

            design.showToast(R.string.backup_import_restoring, ToastDuration.Short)

            var restored = 0

            withProfile {
                val existing = queryAll().map { it.source }.toSet()

                for (snapshot in restorable) {
                    if (snapshot.source in existing) {
                        continue
                    }

                    var uuid: UUID? = null

                    try {
                        uuid = create(
                            snapshot.type,
                            snapshot.name,
                            snapshot.source,
                            snapshot.ageSecretKey,
                        )

                        patch(
                            uuid,
                            snapshot.name,
                            snapshot.source,
                            snapshot.interval,
                            snapshot.ageSecretKey,
                        )

                        commit(uuid, null)

                        if (snapshot.active) {
                            queryByUUID(uuid)?.let { setActive(it) }
                        }

                        restored++
                    } catch (e: Exception) {
                        Log.w("Restore profile ${snapshot.name}", e)

                        // 下载失败时清掉 pending 记录，否则订阅列表里会留一个卡住的空条目
                        uuid?.let { runCatching { release(it) } }
                    }
                }
            }

            design.showToast(
                getString(R.string.backup_import_success_count, restored),
                ToastDuration.Long,
            )
        } catch (e: Exception) {
            Log.w("Import backup", e)

            design.showToast(R.string.backup_import_failed, ToastDuration.Long)
        }
    }

    private fun onHideIconChange(hide: Boolean) {
        val newState = if (hide) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        packageManager.setComponentEnabledSetting(
            mainActivityAlias,
            newState,
            PackageManager.DONT_KILL_APP
        )
    }
}
