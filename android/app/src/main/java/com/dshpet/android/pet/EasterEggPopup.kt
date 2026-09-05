package com.dshpet.android.pet

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import kotlin.random.Random
import kotlin.math.roundToInt

/**
 * 欧鲸鲸彩蛋弹窗：从素材池随机取一张图片，随机位置显示，点按关闭。
 * 可多开层叠（与原 fun_image_popup 行为一致）。
 */
class EasterEggPopup private constructor(
    private val ctx: Context,
    private val imageName: String,
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view: ImageView
    private var params: WindowManager.LayoutParams? = null

    init {
        val bitmap = try {
            val stream = ctx.assets.open("pet/easter/$imageName")
            BitmapFactory.decodeStream(stream)
        } catch (e: Exception) {
            null
        }
        view = ImageView(ctx).apply {
            setImageBitmap(bitmap)
            setOnClickListener { dismiss() }
            adjustViewBounds = true
            setBackgroundColor(0x00000000.toInt())
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        params = lp
        // 尺寸限制：最大 60% 屏宽
        val density = ctx.resources.displayMetrics.density
        val screenW = ctx.resources.displayMetrics.widthPixels
        val maxW = (screenW * 0.6f).toInt()
        if (bitmap != null && bitmap.width > maxW) {
            val scale = maxW.toFloat() / bitmap.width
            lp.width = maxW
            lp.height = (bitmap.height * scale).roundToInt()
        } else {
            lp.width = bitmap?.width ?: 300
            lp.height = bitmap?.height ?: 300
        }
        val bounds = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            android.graphics.Rect(0, 0, ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels)
        }
        // 防护：宽/高 >= 屏幕时区间为空 → Random.nextInt 崩溃
        val rangeX = (bounds.width() - lp.width).coerceAtLeast(0)
        val rangeY = (bounds.height() - lp.height).coerceAtLeast(0)
        lp.x = if (rangeX > 0) Random.nextInt(0, rangeX) else 0
        lp.y = if (rangeY > 0) Random.nextInt(0, rangeY) else 0
    }

    fun show() {
        runCatching { wm.addView(view, params) }
    }

    fun dismiss() {
        runCatching { wm.removeView(view) }
    }

    companion object {
        fun availableImages(ctx: Context): List<String> {
            return try {
                ctx.assets.list("pet/easter")?.filter { it.endsWith(".jpg") || it.endsWith(".png") } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun showRandom(ctx: Context) {
            // 彩蛋是装饰功能：任何异常都只记日志，绝不带崩进程
            try {
                val imgs = availableImages(ctx)
                if (imgs.isEmpty()) {
                    com.dshpet.android.util.AppLog.log("EASTER", "彩蛋图片池为空，跳过")
                    return
                }
                EasterEggPopup(ctx, imgs[Random.nextInt(imgs.size)]).show()
            } catch (e: Throwable) {
                com.dshpet.android.util.AppLog.log("EASTER", "彩蛋弹窗失败: ${e.message}")
            }
        }
    }
}
