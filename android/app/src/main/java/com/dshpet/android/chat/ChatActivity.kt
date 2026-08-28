package com.dshpet.android.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dshpet.android.data.PetConfig
import com.dshpet.android.util.mdBlur
import kotlinx.coroutines.launch

/**
 * AI 对话界面（MD3）：会话侧栏 + 消息流 + SSE 流式输出。
 * 毛玻璃背景可开关（默认关）。
 */
class ChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: ChatViewModel = viewModel()
            val cfg = PetConfig.get(applicationContext)
            val blurCfg by cfg.flowBool("blur_enabled", false).collectAsState(initial = false)
            val blur = blurCfg && android.os.Build.VERSION.SDK_INT >= 31
            MaterialTheme {
                ChatScreen(vm, blur)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(vm: ChatViewModel, blur: Boolean) {
    val sessions by vm.sessions.collectAsState()
    val current by vm.current.collectAsState()
    val streaming by vm.streaming.collectAsState()
    val streamText by vm.streamText.collectAsState()
    val messages = remember(current, streamText) { vm.displayedMessages() }

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val drawerState = androidx.compose.material3.rememberDrawerState(DrawerValue.Closed)

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "会话",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
                sessions.forEach { s ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                vm.selectSession(s.id)
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                s.title,
                                maxLines = 1,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (s.id == current?.id) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(onClick = { vm.deleteSession(s.id) }) {
                            Icon(Icons.Filled.Delete, "删除会话", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(current?.title ?: "AI 对话") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, "会话列表")
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.newSession() }) {
                            Icon(Icons.Filled.Add, "新对话")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (blur) Color.Transparent else MaterialTheme.colorScheme.surface,
                    ),
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .mdBlur(blur, radius = 24)
                    .background(
                        if (blur) Color(0x88FFFFFF) else MaterialTheme.colorScheme.background
                    ),
            ) {
                Column(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (messages.isEmpty()) {
                            item {
                                Text(
                                    "开始和欧鲸鲸聊天吧～\n（AI 对话需要先在桌宠设置中配置 API Key）",
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 60.dp),
                                )
                            }
                        }
                        items(messages, key = { it.ts to it.content.length }) { m ->
                            MessageBubble(m, streaming && m === messages.lastOrNull())
                        }
                    }
                    InputBar(
                        input = input,
                        onInput = { input = it },
                        streaming = streaming,
                        onSend = {
                            vm.send(input)
                            input = ""
                        },
                        onStop = { vm.stopStream() },
                    )
                }
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
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    m.content,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (isStreamingTail) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .width(12.dp)
                                .height(12.dp),
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
private fun InputBar(
    input: String,
    onInput: (String) -> Unit,
    streaming: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInput,
            placeholder = { Text("输入消息…") },
            modifier = Modifier.weight(1f),
            maxLines = 4,
            shape = RoundedCornerShape(22.dp),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = { if (streaming) onStop() else onSend() },
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
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
