package com.dshpet.android.pet

import android.app.Application
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshpet.android.chat.ChatRepo
import com.dshpet.android.chat.ChatViewModel
import com.dshpet.android.data.PetConfig
import com.dshpet.android.util.AppLog
import com.dshpet.android.util.attachComposeHost

/**
 * 快速对话气泡（上游 quick_chat.py 的 Android 版）。
 *
 * - 点击桌宠时头顶弹出的小型可聚焦窗口：单行输入 + 流式回复；
 * - 与悬浮对话窗/设置页共享同一会话（ChatViewModel/ChatRepo）；
 * - 回车发送；点击 ✕ 或窗口失焦超时关闭；
 * - 长回复滚动显示。
 */
class QuickChat(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private val vm: ChatViewModel = ChatViewModel(ctx.applicationContext as Application)

    fun show(anchorX: Int, anchorY: Int) {
        dismiss()
        val composeView = ComposeView(ctx).apply {
            attachComposeHost()
            setContent { QuickChatContent() }
        }
        val (sw, _) = screenPx()
        val w = (sw * 0.72f).toInt()
        val params = WindowManager.LayoutParams(
            w,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 可聚焦：直接输入
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 锚定桌宠头顶
            x = (anchorX - w / 2).coerceIn(8, sw - w - 8)
            y = (anchorY - 8).coerceAtLeast(8)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        // 外部点击关闭
        composeView.setOnTouchListener { _, ev ->
            if (ev.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) {
                dismiss(); true
            } else false
        }
        view = composeView
        lp = params
        try {
            wm.addView(composeView, params)
        } catch (e: Exception) {
            AppLog.log("QUICKCHAT", "快速对话创建失败: ${e.message}")
            view = null
        }
    }

    fun dismiss() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        vm.stopStream()
    }

    fun isShowing(): Boolean = view != null

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
    private fun QuickChatContent() {
        val current by vm.current.collectAsState()
        val streaming by vm.streaming.collectAsState()
        val streamText by vm.streamText.collectAsState()
        val messages = remember(current, streamText) { vm.displayedMessages() }
        var input by remember { mutableStateOf("") }

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 10.dp,
            modifier = Modifier.widthIn(max = 400.dp),
        ) {
            Column(Modifier.padding(10.dp)) {
                // 标题
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "💬 快速对话",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { dismiss() }, modifier = Modifier.size(26.dp)) {
                        Icon(Icons.Filled.Close, "关闭", modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // 回复区（最近几条，可滚动）
                val reply = messages.lastOrNull { it.role == "assistant" }
                if (reply != null) {
                    Text(
                        reply.content,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(androidx.compose.ui.unit.Dp.Unspecified)
                            .padding(vertical = 4.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                if (streaming) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("思考中…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // 输入行
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("问点什么…", fontSize = 13.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = {
                            if (input.isNotBlank()) {
                                vm.send(input)
                                input = ""
                            }
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(Icons.Filled.Send, "发送", tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
