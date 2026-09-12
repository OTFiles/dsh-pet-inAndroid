package com.dshpet.android.pet

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
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
 * - 悬浮胶囊：emoji 图标 + 自定义文本 + 时间 + 呼吸状态灯；
 * - 全部配置（风格/emoji/文本/宽高）经 Flow 响应式读取，改动即时生效；
 * - 拖动 + 顶部吸附（24dp 阈值）+ 位置记忆；
 * - 单击切换桌宠显示/隐藏；
 * - 尺寸可调：宽（屏宽百分比）/ 高（dp），updateViewLayout 动态应用。
 */
class DynamicIsland(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private val config = PetConfig.get(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val density = ctx.resources.displayMetrics.density
    @Volatile private var shown = false

    val isShowing: Boolean get() = shown && view != null

    // ================================================================ 窗口管理
    fun show() {
        if (shown) return
        shown = true  // 立即置位：防 launch 期间重复调用穿透
        scope.launch {
            val savedX = config.islandX()
            val savedY = config.islandY()
            val wPct = config.islandWidthPct()
            val hDp = config.islandHeightDp()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                addWindow(savedX, savedY, wPct, hDp)
            }
        }
    }

    fun dismiss() {
        shown = false
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private fun addWindow(savedX: Int, savedY: Int, wPct: Int, hDp: Int) {
        if (shown) return
        val composeView = androidx.compose.ui.platform.ComposeView(ctx).apply {
            attachComposeHost()
            setContent { IslandContent() }
        }
        val (sw, _) = screenPx()
        val w = (sw * wPct / 100f).toInt().coerceAtLeast(180 * density.toInt())
        val h = (hDp * density).toInt().coerceIn((36 * density).toInt(), (96 * density).toInt())
        val params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 默认贴顶居中；有记忆位置则恢复（但不高于 4dp——顶部吸附后正常状态）
            x = if (savedX >= 0) savedX else (sw - w) / 2
            y = if (savedY >= 0) savedY.coerceAtLeast((4 * density).toInt()) else (12 * density).toInt()
        }
        // 触摸：短按=切换桌宠；拖动=移动窗口（松手吸附+记忆）
        var downX = 0f; var downY = 0f; var lastX = 0f; var lastY = 0f; var moved = false
        composeView.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    lastX = ev.rawX; lastY = ev.rawY
                    moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - lastX
                    val dy = ev.rawY - lastY
                    if (moved || hypot(ev.rawX - downX, ev.rawY - downY) > 10) {
                        moved = true
                        onDrag(dx, dy)
                    }
                    lastX = ev.rawX; lastY = ev.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) snapAndSave() else togglePet()
                    true
                }
                else -> false
            }
        }
        view = composeView
        lp = params
        shown = true
        try {
            wm.addView(composeView, params)
            AppLog.log("ISLAND", "灵动岛已显示 ${w}x$h @(${params.x},${params.y})")
        } catch (e: Exception) {
            AppLog.log("ISLAND", "灵动岛创建失败: ${e.message}")
            view = null
            shown = false
        }
    }

    private fun onDrag(dx: Float, dy: Float) {
        val p = lp ?: return
        val v = view ?: return
        p.x = (p.x + dx.toInt()).coerceAtLeast(0)
        p.y = (p.y + dy.toInt()).coerceAtLeast(0)
        runCatching { wm.updateViewLayout(v, p) }
    }

    /** 顶部吸附（24dp 阈值）+ 位置记忆 */
    private fun snapAndSave() {
        val p = lp ?: return
        val v = view ?: return
        if (p.y < 24 * density) p.y = (8 * density).toInt()
        runCatching { wm.updateViewLayout(v, p) }
        scope.launch { config.setIslandPos(p.x, p.y) }
    }

    private fun togglePet() {
        val intent = android.content.Intent(ctx, PetOverlayService::class.java)
            .setAction(if (PetOverlayService.petHidden) "show" else "hide")
        runCatching { ctx.startService(intent) }
    }

    /** 尺寸变化（设置滑杆）：动态改窗口宽高，保持中心 */
    fun applySize(wPct: Int, hDp: Int) {
        val p = lp ?: return
        val v = view ?: return
        val (sw, _) = screenPx()
        val oldW = p.width
        val oldH = p.height
        val w = (sw * wPct / 100f).toInt().coerceAtLeast(180 * density.toInt())
        val h = (hDp * density).toInt().coerceIn((36 * density).toInt(), (96 * density).toInt())
        if (w == oldW && h == oldH) return
        // 保持中心不跳动
        p.x = p.x + (oldW - w) / 2
        p.y = p.y + (oldH - h) / 2
        p.width = w
        p.height = h
        runCatching { wm.updateViewLayout(v, p) }
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
    private fun IslandContent() {
        val style by config.flowString("island_style", "dark").collectAsState(initial = "dark")
        val emoji by config.flowString("island_emoji", "🐳").collectAsState(initial = "🐳")
        val text by config.flowString("island_text", "").collectAsState(initial = "")
        val wPct by config.flowInt("island_width_pct", 50).collectAsState(initial = 50)
        val hDp by config.flowInt("island_height_dp", 54).collectAsState(initial = 54)

        // 尺寸变化时动态应用（无需重建窗口）
        LaunchedEffect(wPct, hDp) { applySize(wPct, hDp) }

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
                    // 占位：手势由窗口 onTouch 统一处理（拖动+单击语义）
                    awaitPointerEventScope { }
                },
            shape = RoundedCornerShape(50),
            color = bg,
            // 不用系统阴影：TRANSLUCENT 悬浮窗四角会渲染灰色残影
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(emoji, fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                if (text.isNotBlank()) {
                    Text(
                        text, fontSize = 13.sp, color = fg,
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
