package com.dshpet.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.dshpet.android.data.PetConfig
import com.dshpet.android.pet.PetOverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口：初始化通知渠道；若用户开启了"开机自启/自动启动"且授权过，
 * 在应用被拉起时确保桌宠服务在运行（防 OEM 杀后台后无感知）。
 */
class PetApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createChannels()
        appScope.launch {
            val cfg = PetConfig.get(this@PetApp)
            // 同步 launcher 入口（隐藏后台开关）与开机自启接收器状态
            MainActivity.applyRecentsAlias(this@PetApp, cfg.hideFromRecents())
            setBootReceiverEnabled(cfg.autoStart())
            if (cfg.autoStart() && cfg.overlayPermissionGranted()) {
                PetOverlayService.ensureRunning(this@PetApp, persist = false)
            }
        }
    }

    private fun setBootReceiverEnabled(enabled: Boolean) {
        val pm = packageManager
        val receiver = android.content.ComponentName(this, "$packageName.pet.BootReceiver")
        pm.setComponentEnabledSetting(
            receiver,
            if (enabled) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP,
        )
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        val pet = NotificationChannel(
            PetOverlayService.CHANNEL_ID, getString(R.string.notification_channel_pet),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.fgs_description)
            setShowBadge(false)
        }
        val chat = NotificationChannel(
            "chat", getString(R.string.notification_chat_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(pet)
            nm.createNotificationChannel(chat)
        }
    }
}
