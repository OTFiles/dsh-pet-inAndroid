package com.dshpet.android.pet

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshpet.android.data.PetConfig
import com.dshpet.android.util.AppLog
import com.dshpet.android.util.attachComposeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

/**
 * 灵动岛胶囊窗口（上游 dynamic_island.py 的 Android 版）。
 *
 * - 悬浮胶囊：emoji 图标 + 自定义文本 + 时间；状态灯
 * - 整体可拖动，顶部吸附（24px 阈值），位置记忆；
 * - 单击切换桌宠显示/隐藏（配合 PetOverlayService）；
 * - 深色/浅色/玻璃三种风格。
 */
class DynamicIsland(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private val config = PetConfig.get(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var savedX = -1
    private var savedY = -1

    fun show() {
        if (view != null) return
        scope.launch {
            savedX = config.islandX()
            savedY = config.islandY()
            val style = config.islandStyle()
            val emoji = config.islandEmoji()
            val text = config.islandText()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                addWindow(style, emoji, text)
            }
        }
    }

    fun dismiss() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private fun addWindow(style: String, emoji: String, customText: String) {
        val composeView = androidx.compose.ui.platform.ComposeView(ctx).apply {
            attachComposeHost()
            setContent { IslandContent(style, emoji, customText) }
        }
        val (sw, sh) = screenPx()
        val w = (sw * 0.5f).toInt()
        val h = (54 * ctx.resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (savedX >= 0) savedX else (sw - w) / 2
            y = if (savedY >= 0) savedY else (48 * ctx.resources.displayMetrics.density).toInt()
        }
        // 单击（非拖动）切换桌宠可见性
        var downX = 0f; var downY = 0f; var moved = false
        composeView.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(ev.rawX - downX, ev.rawY - downY) > 12) moved = true
                    if (moved) onDrag(ev.rawX - downX, ev.rawY - downY).also {
                        downX = ev.rawX; downY = ev.rawY
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePet()
                    else snapToTop()
                    true
                }
                else -> false
            }
        }
        view = composeView
        lp = params
        try {
            wm.addView(composeView, params)
        } catch (e: Exception) {
            AppLog.log("ISLAND", "灵动岛创建失败: ${e.message}")
            view = null
        }
    }

    private fun onDrag(dx: Float, dy: Float) {
        val p = lp ?: return
        val v = view ?: return
        p.x += dx.toInt()
        p.y += dy.toInt()
        runCatching { wm.updateViewLayout(v, p) }
    }

    /** 顶部吸附（24px 阈值内贴顶）+ 位置记忆 */
    private fun snapToTop() {
        val p = lp ?: return
        val v = view ?: return
        val (_, _) = screenPx()
        if (p.y < 24 * ctx.resources.displayMetrics.density) {
            p.y = (16 * ctx.resources.displayMetrics.density).toInt()
        }
        runCatching { wm.updateViewLayout(v, p) }
        scope.launch {
            config.setIslandPos(p.x, p.y)
        }
    }

    private fun togglePet() {
        // 单击 → 桌宠主实例显示/隐藏切换
        val intent = android.content.Intent(ctx, PetOverlayService::class.java)
            .setAction(if (PetOverlayService.petHidden) "show" else "hide")
        runCatching { ctx.startService(intent) }
    }

    private fun screenPx(): Pair<Int, Int> {
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            android.graphics.Rect(0, 0, ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels)
        }
        return bounds.width() to bounds.height()
    }

    // ================================================================ UI
    @Composable
    private fun IslandContent(style: String, emoji: String, customText: String) {
        val (bg, fg) = when (style) {
            "light" -> Color(0xF2FFFFFF) to Color(0xFF1B1F26)
            "glass" -> Color(0x99FFFFFF) to Color(0xFF22272E)
            else -> Color(0xF21C1E24) to Color(0xFFEAEAF0)  // dark 默认
        }
        val time = remember { mutableStateOf(currentTime()) }
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(15_000)
                time.value = currentTime()
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { _, _ -> } // 手势由窗口 onTouch 处理
                },
            shape = RoundedCornerShape(27.dp),
            color = bg,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(emoji, fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                if (customText.isNotBlank()) {
                    Text(
                        customText, fontSize = 13.sp, color = fg,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(
                    time.value, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium, color = fg,
                )
                Spacer(Modifier.width(8.dp))
                // 状态灯（呼吸绿点）
                Box(
                    Modifier
                        .size(8.dp)
                        .background(Color(0xFF34C759), CircleShape)
                )
            }
        }
    }

    private fun currentTime(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
}
