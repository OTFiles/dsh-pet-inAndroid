package com.dshpet.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshpet.android.util.AppLog
import java.io.File

/**
 * 内置日志查看器：按日期列出日志文件，可查看 / 复制 / 分享。
 * 入口：设置 → 关于 → 查看日志。
 */
class LogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                LogScreen()
            }
        }
    }
}

@Composable
private fun LogScreen() {
    val ctx = LocalContext.current
    var selected by remember { mutableStateOf<File?>(null) }
    val files = remember { AppLog.files() }

    fun copyAll() {
        val text = AppLog.crashLog()
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dshpet日志", text))
        Toast.makeText(ctx, "已复制全部日志到剪贴板", Toast.LENGTH_SHORT).show()
    }

    fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { ctx.startActivity(Intent.createChooser(send, "分享日志")) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("应用日志") },
                navigationIcon = {
                    IconButton(onClick = { (ctx as? android.app.Activity)?.finish() }) {
                        Icon(Icons.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { copyAll() }) { Icon(Icons.Filled.Share, "复制") }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { padding ->
        if (files.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("暂无日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        val current = selected
        if (current == null) {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(files) { f ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .clickable { selected = f },
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                f.name.removeSuffix(".log"),
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Text("${f.length() / 1024} KB", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = { copyAll() }, modifier = Modifier.padding(16.dp)) {
                        Text("复制全部日志")
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("dshpet日志", AppLog.read(current)))
                        Toast.makeText(ctx, "已复制 ${current.name}", Toast.LENGTH_SHORT).show()
                    }) { Text("复制此文件") }
                    Button(onClick = { share(AppLog.read(current)) }) { Text("分享") }
                    OutlinedButton(onClick = { selected = null }) { Text("返回列表") }
                }
                Text(
                    AppLog.read(current),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                )
            }
        }
    }
}
