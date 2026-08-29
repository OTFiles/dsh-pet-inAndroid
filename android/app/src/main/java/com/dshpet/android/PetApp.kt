package com.dshpet.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.dshpet.android.data.PetConfig
import com.dshpet.android.pet.PetOverlayService
import com.dshpet.android.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口：初始化日志/通知渠道；未捕获异常写入内置日志；
 * 若用户开启了"开机自启/自动启动"且授权过，拉起时确保桌宠服务在运行。
 */
class PetApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        installCrashHandler()
        AppLog.log("APP", "启动 v4.0.1")
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

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLog.log(
                "CRASH",
                "线程=${thread.name}\n${Log.getStackTraceString(throwable)}",
            )
            previous?.uncaughtException(thread, throwable)
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
