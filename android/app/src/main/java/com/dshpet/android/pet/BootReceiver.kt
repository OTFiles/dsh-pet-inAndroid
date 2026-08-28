package com.dshpet.android.pet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.dshpet.android.data.PetConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机自启：BOOT_COMPLETED 后若用户开启了"开机自启"且已授予悬浮窗权限，
 * 自动启动桌宠服务（对应桌面端开机自启功能）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        CoroutineScope(Dispatchers.IO).launch {
            val cfg = PetConfig.get(context.applicationContext)
            if (cfg.autoStart() && Settings.canDrawOverlays(context.applicationContext)) {
                PetOverlayService.ensureRunning(context.applicationContext, persist = false)
            }
        }
    }
}
