package com.dshpet.android.util

import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.Dp

/**
 * 毛玻璃效果开关（默认关闭）。
 * API 31+ 使用 RenderEffect 真模糊；低版本无法硬件模糊，回退为半透明纯色
 * （调用方配合半透明背景即可，UI 上仍是"磨砂"观感）。
 */
fun Modifier.mdBlur(enabled: Boolean, radius: Int = 18): Modifier {
    if (!enabled) return this
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return this.blur(radius = Dp(radius.toFloat()), edgeTreatment = androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded)
    }
    return this
}

/** 当前平台是否支持真毛玻璃（RenderEffect） */
val supportsRealBlur: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
