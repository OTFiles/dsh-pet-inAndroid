package com.dshpet.android

import android.app.ActivityManager
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
        AppLog.log("APP", "启动 v${BuildConfig.VERSION_NAME}")
        logMemory("进程创建")
        createChannels()
        appScope.launch {
            val cfg = PetConfig.get(this@PetApp)
            // 同步 launcher 入口（隐藏后台开关）与开机自启接收器状态
            PetConfig.hideRecentsCached = cfg.hideFromRecents()
            MainActivity.applyRecentsAlias(this@PetApp, cfg.hideFromRecents())
            setBootReceiverEnabled(cfg.autoStart())
            if (cfg.autoStart() && cfg.overlayPermissionGranted()) {
                PetOverlayService.ensureRunning(this@PetApp, persist = false)
            }
        }
    }

    /** 内存诊断：当前进程可用堆 + 系统低内存状态 */
    private fun logMemory(tag: String) {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576
        val maxMb = rt.maxMemory() / 1048576
        val am = getSystemService(ActivityManager::class.java)
        val lowRam = am?.isLowRamDevice ?: false
        val mi = android.app.ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val sysLow = mi.lowMemory
        val availMb = mi.availMem / 1048576
        AppLog.log(
            "MEM",
            "$tag: 堆已用 ${usedMb}MB / 上限 ${maxMb}MB, lowRamDevice=$lowRam, 系统低内存=$sysLow, 系统可用内存≈${availMb}MB"
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // level>=TRIM_MEMORY_COMPLETE(80)/UI_HIDDEN(20) 前的各级都记录
        AppLog.log("MEM", "onTrimMemory level=$level " +
                when {
                    level >= 80 -> "(COMPLETE: 进程随时可能被杀)"
                    level >= 60 -> "(MODERATE)"
                    level >= 40 -> "(BACKGROUND)"
                    level >= 20 -> "(UI_HIDDEN)"
                    level >= 15 -> "(TRIM_MEMORY_RUNNING_CRITICAL: 后台内存紧张)"
                    else -> "(RUNNING_LOW/LOW)"
                })
        logMemory("onTrimMemory")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        AppLog.log("MEM", "onLowMemory: 系统整体内存耗尽，进程濒临被杀")
        logMemory("onLowMemory")
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val rt = Runtime.getRuntime()
            AppLog.log(
                "CRASH",
                "线程=${thread.name}\n${Log.getStackTraceString(throwable)}\n" +
                        "[内存快照] 堆已用${(rt.totalMemory() - rt.freeMemory()) / 1048576}MB/上限${rt.maxMemory() / 1048576}MB",
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
