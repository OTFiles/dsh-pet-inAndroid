package com.dshpet.android.pet

import android.app.Application
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshpet.android.chat.ChatRepo
import com.dshpet.android.chat.ChatViewModel
import com.dshpet.android.data.PetConfig
import com.dshpet.android.util.AppLog
import com.dshpet.android.util.attachComposeHost
import com.dshpet.android.util.mdBlur
import kotlinx.coroutines.launch

/**
 * 悬浮 AI 对话窗口（长按菜单入口）。
 *
 * - TYPE_APPLICATION_OVERLAY 可聚焦窗口：软键盘直接输入；
 * - 标题栏可拖动，右上角关闭；会话侧栏精简为顶部切换；
 * - 复用 ChatViewModel/ChatRepo（会话持久化与全屏版共享）。
 */
class PetChatWindow(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: ComposeView? = null
    private var lp: WindowManager.LayoutParams? = null
    private val vm: ChatViewModel = ChatViewModel(ctx.applicationContext as Application)
    private val config = PetConfig.get(ctx)

    fun show() {
        if (view != null) {
            view?.visibility = View.VISIBLE
            return
        }
        val composeView = ComposeView(ctx).apply {
            attachComposeHost()
            setContent { ChatFloatingContent() }
        }
        val (sw, sh) = screenPx()
        val w = (sw * 0.88f).toInt()
        val h = (sh * 0.62f).toInt().coerceAtLeast(420)
        val params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 可聚焦：不设 FLAG_NOT_FOCUSABLE，软键盘可用
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (sw - w) / 2
            y = (sh * 0.16f).toInt()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        view = composeView
        lp = params
        // 外部点击不关闭（输入中误触代价高），由标题栏关闭
        composeView.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) true else false
        }
        try {
            wm.addView(composeView, params)
        } catch (e: Exception) {
            AppLog.log("CHAT", "悬浮对话窗口创建失败: ${e.message}")
            view = null
        }
    }

    fun dismiss() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        vm.stopStream()
    }

    private fun onWindowDrag(dx: Float, dy: Float) {
        val p = lp ?: return
        val v = view ?: return
        p.x += dx.toInt()
        p.y += dy.toInt()
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
    private fun ChatFloatingContent() {
        val blurCfg by config.flowBool("blur_enabled", false).collectAsState(initial = false)
        val blur = blurCfg && Build.VERSION.SDK_INT >= 31
        MaterialTheme {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .mdBlur(blur, radius = 24),
                shape = RoundedCornerShape(20.dp),
                color = if (blur) Color(0x99FFFFFF) else MaterialTheme.colorScheme.surface,
                shadowElevation = 12.dp,
            ) {
                Column {
                    ChatHeader()
                    MessageList(Modifier.weight(1f))
                    InputBar()
                }
            }
        }
    }

    @Composable
    private fun ChatHeader() {
        val sessions by vm.sessions.collectAsState()
        val current by vm.current.collectAsState()
        var sessionsOpen by remember { mutableStateOf(false) }

        Surface(color = MaterialTheme.colorScheme.primaryContainer) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        // 整条标题栏可拖动窗口
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onWindowDrag(dragAmount.x, dragAmount.y)
                            }
                        }
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Menu, contentDescription = "拖动",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        current?.title ?: "AI 对话",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp),
                    )
                    IconButton(onClick = { sessionsOpen = !sessionsOpen }) {
                        Icon(
                            if (sessionsOpen) Icons.Filled.Close else Icons.Filled.Add,
                            contentDescription = "会话",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    IconButton(onClick = { dismiss() }) {
                        Icon(
                            Icons.Filled.Close, contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                if (sessionsOpen && sessions.isNotEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                    ) {
                        sessions.forEach { s ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = {
                                    vm.selectSession(s.id)
                                    sessionsOpen = false
                                }, modifier = Modifier.weight(1f)) {
                                    Text(
                                        s.title, maxLines = 1,
                                        color = if (s.id == current?.id) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                IconButton(onClick = { vm.deleteSession(s.id) }, modifier = Modifier.size(30.dp)) {
                                    Icon(
                                        Icons.Filled.Delete, "删除",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MessageList(modifier: Modifier) {
        val current by vm.current.collectAsState()
        val streaming by vm.streaming.collectAsState()
        val streamText by vm.streamText.collectAsState()
        val messages = remember(current, streamText) { vm.displayedMessages() }
        val listState = rememberLazyListState()
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }
        Box(modifier) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            "开始和欧鲸鲸聊天吧～\n（需先在桌宠设置中配置 API Key）",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 40.dp),
                        )
                    }
                }
                items(messages, key = { it.ts to it.content.length }) { m ->
                    MessageBubble(m, streaming && m === messages.lastOrNull())
                }
            }
        }
    }

    @Composable
    private fun MessageBubble(m: ChatRepo.Message, isStreamingTail: Boolean) {
        val isUser = m.role == "user"
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        ) {
            Surface(
                color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(
                    topStart = 14.dp, topEnd = 14.dp,
                    bottomStart = if (isUser) 14.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 14.dp,
                ),
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        m.content,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (isStreamingTail) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("思考中…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun InputBar() {
        var input by remember { mutableStateOf("") }
        val streaming by vm.streaming.collectAsState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("输入消息…", fontSize = 13.sp) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                shape = RoundedCornerShape(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (streaming) vm.stopStream()
                    else if (input.isNotBlank()) {
                        vm.send(input)
                        input = ""
                    }
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.primary),
            ) {
                Icon(
                    if (streaming) Icons.Filled.Close else Icons.Filled.Send,
                    contentDescription = if (streaming) "停止" else "发送",
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}
