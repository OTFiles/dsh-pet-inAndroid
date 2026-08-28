package com.dshpet.android.pet

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import com.dshpet.android.data.PetConfig
import com.dshpet.android.util.mdBlur
import kotlin.math.roundToInt

/**
 * 桌宠气语气泡：独立的非触控悬浮窗口，锚定在桌宠正上方。
 * 支持多种气泡样式与可选的毛玻璃背景（默认关）。
 */
class SpeechBubble(
    private val ctx: Context,
    private val engine: PetEngine,
    private val config: PetConfig,
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var hideRunnable: Runnable? = null
    private val density = ctx.resources.displayMetrics.density

    /** 由服务侧更新的配置缓存（服务在协程里读取 DataStore 后写入） */
    @Volatile
    var bubbleStyle: String = "classic_top"
    @Volatile
    var blurOn: Boolean = false

    fun showText(text: String, durationMs: Long = 6000) {
        hideRunnable?.let { handler.removeCallbacks(it) }
        if (view == null) {
            val composeView = ComposeView(ctx).apply {
                setContent {
                    BubbleContent(
                        text = text,
                        style = bubbleStyle,
                        blur = blurOn,
                    )
                }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            view = composeView
            params = lp
            runCatching { wm.addView(composeView, lp) }
        } else {
            (view as ComposeView).setContent {
                BubbleContent(
                    text = text,
                    style = bubbleStyle,
                    blur = blurOn,
                )
            }
        }
        view?.visibility = View.VISIBLE
        // 等布局完成后再定位（首次 measure 前尺寸为 0）
        view?.post { reposition() }
        hideRunnable = Runnable { hide() }
        handler.postDelayed(hideRunnable!!, durationMs.coerceAtLeast(1000))
    }

    fun hide() {
        hideRunnable?.let { handler.removeCallbacks(it) }
        view?.visibility = View.GONE
    }

    fun dismiss() {
        hideRunnable?.let { handler.removeCallbacks(it) }
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    /** 锚定：气泡底边中心 = 桌宠顶边中心 */
    private fun reposition() {
        val v = view ?: return
        val lp = params ?: return
        v.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val bw = v.measuredWidth
        val bh = v.measuredHeight
        val (sw, sh) = screenDp()
        val bwDp = px2dp(bw)
        val bhDp = px2dp(bh)
        val petCenterX = engine.winX + engine.winW / 2
        var x = petCenterX - bwDp / 2
        x = x.coerceIn(8f, sw - bwDp - 8f)
        var y = engine.winY - bhDp - 8f
        if (y < 0f) y = engine.winY + engine.winH + 8f // 顶部放不下则放下方
        y = y.coerceIn(0f, sh - bhDp)
        lp.x = dp2px(x.roundToInt())
        lp.y = dp2px(y.roundToInt())
        runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun screenDp(): Pair<Int, Int> {
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            android.graphics.Rect(0, 0, ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels)
        }
        return px2dp(bounds.width()).roundToInt() to px2dp(bounds.height()).roundToInt()
    }

    private fun dp2px(v: Int): Int = (v * density).roundToInt()
    private fun px2dp(v: Int): Float = v / density

    @Composable
    private fun BubbleContent(text: String, style: String, blur: Boolean) {
        var visible by androidx.compose.runtime.remember { mutableStateOf(true) }
        LaunchedEffect(Unit) { visible = true }
        val (bg, fg, radius, tail) = when (style) {
            "soft_blue_top" -> BubbleStyle(Color(0xFFD6E6FF), Color(0xFF1B3A6B), 14f, true)
            "glass_right" -> BubbleStyle(Color(0xCCFFFFFF), Color(0xFF22272E), 16f, true)
            "breath_bubble" -> BubbleStyle(Color(0xFFE8F5FF), Color(0xFF12355B), 22f, true)
            "paper_left" -> BubbleStyle(Color(0xFFFFF8E1), Color(0xFF5D4A1E), 12f, false)
            else -> BubbleStyle(Color(0xFFFFFFFF), Color(0xFF2B2F36), 14f, true) // classic_top
        }
        Box(
            modifier = Modifier
                .width(230.dp)
                .padding(8.dp)
                .shadow(6.dp, RoundedCornerShape(radius.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(radius.dp))
                    .background(
                        if (blur) Brush.linearGradient(listOf(Color(0xCCFFFFFF), Color(0xCCEAF2FF)))
                        else androidx.compose.ui.graphics.SolidColor(bg)
                    )
                    .mdBlur(blur, radius = 18)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = text,
                    color = fg,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 气泡尾巴
            if (tail) {
                Canvas(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 2.dp)
                ) {
                    val w = size.width
                    val p = Path().apply {
                        moveTo(w / 2 - 10.dp.toPx(), 0f)
                        lineTo(w / 2 + 10.dp.toPx(), 0f)
                        lineTo(w / 2, 10.dp.toPx())
                        close()
                    }
                    drawPath(p, bg)
                }
            }
        }
    }

    private data class BubbleStyle(
        val bg: Color,
        val fg: Color,
        val radius: Float,
        val tail: Boolean,
    )
}
