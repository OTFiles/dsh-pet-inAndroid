package com.dshpet.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.dshpet.android.chat.SseClient
import com.dshpet.android.data.PetConfig
import com.dshpet.android.data.applyHideRecentsPolicy
import com.dshpet.android.pet.Balance
import com.dshpet.android.pet.PetOverlayService
import com.dshpet.android.pet.Updater
import com.dshpet.android.util.mdBlur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置主界面（MD3）。对应桌面端"桌宠设置"各页：常规 / 桌宠行为 / 外观 / AI 对话 / 快捷启动 / 关于。
 * 所有改动即时写入 DataStore，桌宠服务实时生效；"隐藏后台"通过切换
 * LauncherNormal/LauncherHidden 两个 activity-alias 实现（android:excludeFromRecents）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SettingsScreen()
            }
        }
    }

    companion object {
        fun startIntent(ctx: Context): Intent =
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .applyHideRecentsPolicy()

        fun start(ctx: Context) {
            ctx.startActivity(startIntent(ctx))
        }

        /** 切换"隐藏后台"：动态启用/禁用两个 launcher alias（excludeFromRecents） */
        fun applyRecentsAlias(ctx: Context, hide: Boolean) {
            val pm = ctx.packageManager
            val normal = ComponentName(ctx, "$PACKAGE.LauncherNormal")
            val hidden = ComponentName(ctx, "$PACKAGE.LauncherHidden")
            fun set(comp: ComponentName, enabled: Boolean) {
                pm.setComponentEnabledSetting(
                    comp,
                    if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
            set(normal, !hide)
            set(hidden, hide)
        }
    }
}

private const val PACKAGE = "com.dshpet.android"

// ================================================================ 页面
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    val ctx = LocalContext.current
    val cfg = PetConfig.get(ctx)
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("常规", "桌宠", "外观", "AI 对话", "快捷", "关于")
    val blurCfg by cfg.flowBool("blur_enabled", false).collectAsState(initial = false)
    val blurOn = blurCfg && Build.VERSION.SDK_INT >= 31

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("dsh-pet 桌宠", fontWeight = FontWeight.SemiBold)
                        Text("Android 版 v${BuildConfig.VERSION_NAME}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    tabs.forEachIndexed { i, t ->
                        FilterChip(
                            selected = tab == i,
                            onClick = { tab = i },
                            label = { Text(t, fontSize = 12.sp) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .mdBlur(blurOn, radius = 24)
        ) {
            when (tab) {
                0 -> GeneralTab(ctx, cfg, scope)
                1 -> BehaviorTab(ctx, cfg, scope)
                2 -> AppearanceTab(ctx, cfg, scope)
                3 -> AiTab(ctx, cfg, scope)
                4 -> QuickLaunchTab(ctx, cfg, scope)
                else -> AboutTab(ctx, cfg, scope)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Card(shape = RoundedCornerShape(18.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Column(Modifier.padding(vertical = 4.dp)) { content() }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            subtitle?.let {
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) = SettingRow(title, subtitle, trailing = { Switch(checked, onToggle) })

// ================================================================ 常规
@Composable
private fun GeneralTab(ctx: android.content.Context, cfg: PetConfig, scope: kotlinx.coroutines.CoroutineScope) {
    val running by cfg.flowBool("pet_running", false).collectAsState(initial = false)
    val overlayGranted by cfg.flowBool("overlay_permission_granted", false).collectAsState(initial = false)
    val autoStart by cfg.flowBool("autostart", false).collectAsState(initial = false)
    val hideRecents by cfg.flowBool("hide_from_recents", true).collectAsState(initial = true)
    val batteryOpt by cfg.flowBool("battery_optimization", false).collectAsState(initial = false)

    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val granted = Settings.canDrawOverlays(ctx)
        scope.launch { cfg.setOverlayPermission(granted) }
        if (granted) PetOverlayService.ensureRunning(ctx)
    }

    LaunchedEffect(overlayGranted) {
        if (overlayGranted) {
            MainActivity.applyRecentsAlias(ctx, hideRecents)
            val pm = ctx.packageManager
            val receiver = ComponentName(ctx, "$PACKAGE.pet.BootReceiver")
            pm.setComponentEnabledSetting(
                receiver,
                if (autoStart) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section("桌宠") {
            SwitchRow("开启桌宠", if (running) "悬浮窗服务运行中" else "桌宠未启动", running) { on ->
                if (on) {
                    if (!Settings.canDrawOverlays(ctx)) {
                        scope.launch {
                            overlayLauncher.launch(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$PACKAGE"))
                            )
                        }
                    } else {
                        PetOverlayService.ensureRunning(ctx)
                    }
                } else {
                    PetOverlayService.stopAll(ctx)
                }
            }
            SettingRow(
                "悬浮窗权限",
                if (overlayGranted) "已授予（桌宠可显示在任意应用上）" else "未授予，桌宠无法显示",
                trailing = {
                    TextButton(onClick = {
                        overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$PACKAGE")))
                    }) { Text(if (overlayGranted) "重新申请" else "去授权") }
                },
            )
        }
        Section("后台行为") {
            SwitchRow("开机自启", "重启手机后自动显示桌宠", autoStart) { on ->
                scope.launch { cfg.setAutoStart(on) }
                val pm = ctx.packageManager
                val receiver = ComponentName(ctx, "$PACKAGE.pet.BootReceiver")
                pm.setComponentEnabledSetting(
                    receiver,
                    if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
            SwitchRow(
                "隐藏后台",
                "应用不显示在最近任务列表；切换后设置页会自动重启以立即生效",
                hideRecents,
            ) { on ->
                scope.launch {
                    cfg.setHideFromRecents(on)
                    PetConfig.hideRecentsCached = on
                    MainActivity.applyRecentsAlias(ctx, on)
                    // 任务在最近任务中的可见性创建时已固定，必须重建任务：
                    // 关闭旧任务 → 以正确标志重启设置页
                    val act = ctx as? android.app.Activity
                    if (act != null && !act.isFinishing) {
                        act.finishAndRemoveTask()
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            runCatching {
                                val i = Intent(act, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                if (on) i.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                                act.applicationContext.startActivity(i)
                            }
                        }, 350)
                    }
                }
            }
            SwitchRow(
                "忽略电池优化",
                if (batteryOpt) "已允许后台运行" else "建议开启，防止系统杀后台",
                batteryOpt,
            ) { on ->
                scope.launch { cfg.setBatteryOpt(on) }
                if (on) {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$PACKAGE"))
                        )
                    }
                }
            }
        }
        Section("启动") {
            SettingRow(
                "立即启动桌宠",
                "若已授予悬浮窗权限，此操作会立即显示桌宠",
                trailing = {
                    Button(onClick = { PetOverlayService.ensureRunning(ctx) }) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.width(16.dp))
                        Text("启动")
                    }
                },
            )
        }
    }
}

// ================================================================ 桌宠行为
@Composable
private fun BehaviorTab(ctx: android.content.Context, cfg: PetConfig, scope: kotlinx.coroutines.CoroutineScope) {
    val noMove by cfg.flowBool("no_move", false).collectAsState(initial = false)
    val lock by cfg.flowBool("lock_position", false).collectAsState(initial = false)
    val shiftDrag by cfg.flowBool("shift_drag", false).collectAsState(initial = false)
    val physics by cfg.flowBool("drag_physics", false).collectAsState(initial = false)
    val speed by cfg.flowDouble("playback_speed", PetConfig.DEFAULT_PLAYBACK_SPEED).collectAsState(initial = PetConfig.DEFAULT_PLAYBACK_SPEED)
    val gap by cfg.flowDouble("animation_gap_seconds", 0.0).collectAsState(initial = 0.0)
    val moveProb by cfg.flowDouble("move_probability", 0.20).collectAsState(initial = 0.20)
    val moveMinPx by cfg.flowInt("move_min_px", 60).collectAsState(initial = 60)
    val moveMaxPx by cfg.flowInt("move_max_px", 240).collectAsState(initial = 240)
    val clickSound by cfg.flowBool("click_sound_enabled", true).collectAsState(initial = true)
    val clickBalance by cfg.flowBool("click_show_balance", false).collectAsState(initial = false)
    val clickSelfTalk by cfg.flowBool("click_show_self_talk", false).collectAsState(initial = false)

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section("动作与移动") {
            SwitchRow("自动移动", "桌宠会朝面向方向散步（概率与距离可调）", !noMove) { on ->
                scope.launch { cfg.setNoMove(!on) }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("移动概率", Modifier.width(90.dp), fontSize = 13.sp)
                Slider(
                    value = moveProb.toFloat(), onValueChange = { scope.launch { cfg.setMoveProbability(it.toDouble()) } },
                    valueRange = 0f..0.9f,
                    modifier = Modifier.weight(1f),
                )
                Text("${(moveProb * 100).roundToInt()}%", Modifier.width(44.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("散步距离", Modifier.width(90.dp), fontSize = 13.sp)
                Slider(
                    value = moveMinPx.toFloat(), onValueChange = { scope.launch { cfg.setMoveRange(it.toInt(), maxOf(it.toInt(), moveMaxPx)) } },
                    valueRange = 10f..600f,
                    modifier = Modifier.weight(1f),
                )
                Text("${moveMinPx}px", Modifier.width(44.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("最大距离", Modifier.width(90.dp), fontSize = 13.sp)
                Slider(
                    value = moveMaxPx.toFloat(), onValueChange = { scope.launch { cfg.setMoveRange(minOf(moveMinPx, it.toInt()), it.toInt()) } },
                    valueRange = 30f..1200f,
                    modifier = Modifier.weight(1f),
                )
                Text("${moveMaxPx}px", Modifier.width(44.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            SwitchRow("锁定位置", "桌宠固定不动，点击互动仍有效", lock) { on -> scope.launch { cfg.setLockPosition(on) } }
            SwitchRow("仅长按可拖动", "需先长按桌宠才能拖动（触摸版 SHIFT 拖拽）", shiftDrag) { on ->
                scope.launch { cfg.setShiftDrag(on) }
            }
            SwitchRow("拖动物理", "松手抛出：惯性、重力、反弹、地面摩擦", physics) { on ->
                scope.launch { cfg.setDragPhysics(on) }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("播放速度", Modifier.width(90.dp), fontSize = 13.sp)
                Slider(
                    value = speed.toFloat(), onValueChange = { scope.launch { cfg.setPlaybackSpeed(it.toDouble()) } },
                    valueRange = 0.5f..2.0f, steps = 5,
                    modifier = Modifier.weight(1f),
                )
                Text("${speed}×", Modifier.width(44.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("动画间隔", Modifier.width(90.dp), fontSize = 13.sp)
                Slider(
                    value = gap.toFloat(), onValueChange = { scope.launch { cfg.setAnimGap(it.toDouble()) } },
                    valueRange = 0f..10f,
                    modifier = Modifier.weight(1f),
                )
                Text("${gap}s", Modifier.width(44.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
        Section("点击互动") {
            SwitchRow("点击音效", "点击桌宠时的 Q 弹音效", clickSound) { on -> scope.launch { cfg.setClickSound(on) } }
            SwitchRow("点击显示余额", "点击同时查询 DeepSeek 余额", clickBalance) { on -> scope.launch { cfg.setClickShowBalance(on) } }
            SwitchRow("点击自言自语", "点击时随机显示一条自言自语", clickSelfTalk) { on -> scope.launch { cfg.setClickShowSelfTalk(on) } }
        }
    }
}

// ================================================================ 外观
@Composable
private fun AppearanceTab(ctx: android.content.Context, cfg: PetConfig, scope: kotlinx.coroutines.CoroutineScope) {
    val scale by cfg.flowDouble("scale", 0.72).collectAsState(initial = 0.72)
    val menuScale by cfg.flowDouble("menu_scale", 1.0).collectAsState(initial = 1.0)
    val opacity by cfg.flowInt("pet_opacity", 100).collectAsState(initial = 100)
    val facing by cfg.flowString("facing", "left").collectAsState(initial = "left")
    val blur by cfg.flowBool("blur_enabled", false).collectAsState(initial = false)
    val bubbleStyle by cfg.flowString("self_talk_bubble_style", "classic_top").collectAsState(initial = "classic_top")
    val selfTalk by cfg.flowBool("self_talk_enabled", false).collectAsState(initial = false)
    val stMin by cfg.flowInt("self_talk_min_interval", 20).collectAsState(initial = 20)
    val stMax by cfg.flowInt("self_talk_max_interval", 60).collectAsState(initial = 60)

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section("大小与透明度") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5 to "小", 0.72 to "中", 0.85 to "大", 1.0 to "特大").forEach { (v, label) ->
                    FilterChip(selected = scale == v, onClick = { scope.launch { cfg.setScale(v) } }, label = { Text(label) })
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("菜单大小", Modifier.width(90.dp), fontSize = 13.sp)
                Slider(
                    value = menuScale.toFloat(), onValueChange = { scope.launch { cfg.setMenuScale(it.toDouble()) } },
                    valueRange = 0.7f..1.4f,
                    modifier = Modifier.weight(1f),
                )
                Text("${menuScale}×", Modifier.width(44.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("不透明度", Modifier.width(90.dp), fontSize = 13.sp)
                Slider(
                    value = opacity.toFloat(), onValueChange = { scope.launch { cfg.setPetOpacity(it.toInt()) } },
                    valueRange = 10f..100f,
                    modifier = Modifier.weight(1f),
                )
                Text("$opacity%", Modifier.width(44.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("left" to "朝左", "right" to "朝右").forEach { (v, label) ->
                    FilterChip(selected = facing == v, onClick = { scope.launch { cfg.setFacing(v) } }, label = { Text(label) })
                }
            }
        }
        Section("效果") {
            SwitchRow(
                "毛玻璃效果",
                if (Build.VERSION.SDK_INT >= 31) "菜单 / 聊天窗 / 设置面板背景模糊（默认关）"
                else "当前系统版本（<Android 12）不支持硬件模糊，将回退为半透明",
                blur,
            ) { on -> scope.launch { cfg.setBlur(on) } }
        }
        Section("自言自语气泡") {
            SwitchRow("允许自言自语", "随机间隔冒出可爱小气泡", selfTalk) { on -> scope.launch { cfg.setSelfTalk(on) } }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("间隔", Modifier.width(90.dp), fontSize = 13.sp)
                Slider(
                    value = stMin.toFloat(), onValueChange = { scope.launch { cfg.setSelfTalkMin(it.toInt()) } },
                    valueRange = 5f..300f,
                    modifier = Modifier.weight(1f),
                )
                Text("${stMin}s", Modifier.width(44.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            // 气泡样式：MD3 FilterChip 单选组 + 颜色预览
            Column(Modifier.padding(horizontal = 12.dp)) {
                Text(
                    "气泡样式",
                    Modifier.padding(start = 4.dp, top = 4.dp),
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        "classic_top" to 0xFFFFFFFF,
                        "soft_blue_top" to 0xFFD6E6FF,
                        "glass_right" to 0xCCFFFFFF,
                        "paper_left" to 0xFFFFF8E1,
                        "breath_bubble" to 0xFFE8F5FF,
                    ).forEach { (id, color) ->
                        FilterChip(
                            selected = bubbleStyle == id,
                            onClick = { scope.launch { cfg.setSelfTalkBubbleStyle(id) } },
                            label = { Text(styleLabel(id)) },
                            leadingIcon = {
                                Box(
                                    Modifier
                                        .padding(start = 2.dp)
                                        .size(14.dp)
                                        .background(
                                            androidx.compose.ui.graphics.Color(color),
                                            androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                                        )
                                        .border(
                                            0.5.dp,
                                            MaterialTheme.colorScheme.outline,
                                            androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                                        )
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun styleLabel(s: String): String = when (s) {
    "classic_top" -> "经典白"
    "soft_blue_top" -> "软萌蓝"
    "glass_right" -> "玻璃右侧"
    "paper_left" -> "纸片左侧"
    "breath_bubble" -> "呼吸圆泡"
    else -> s
}

// ================================================================ AI 对话
@Composable
private fun AiTab(ctx: android.content.Context, cfg: PetConfig, scope: kotlinx.coroutines.CoroutineScope) {
    val name by cfg.flowString("chat_provider_name", "DeepSeek").collectAsState(initial = "DeepSeek")
    val baseUrl by cfg.flowString("chat_base_url", "https://api.deepseek.com").collectAsState(initial = "https://api.deepseek.com")
    val chatPath by cfg.flowString("chat_chat_path", "/v1/chat/completions").collectAsState(initial = "/v1/chat/completions")
    val model by cfg.flowString("chat_model", "deepseek-chat").collectAsState(initial = "deepseek-chat")
    val apiKey by cfg.flowString("chat_api_key", "").collectAsState(initial = "")
    val temperature by cfg.flowDouble("chat_temperature", 0.7).collectAsState(initial = 0.7)
    val maxTokens by cfg.flowInt("chat_max_tokens", 2048).collectAsState(initial = 2048)
    val verifySsl by cfg.flowBool("chat_verify_ssl", true).collectAsState(initial = true)
    val timeout by cfg.flowInt("chat_timeout", 60).collectAsState(initial = 60)
    var testResult by remember { mutableStateOf<String?>(null) }
    var balanceResult by remember { mutableStateOf<String?>(null) }

    val curCfg = SseClient.ChatCfg(
        baseUrl, chatPath, model, apiKey, temperature, maxTokens, timeout, verifySsl,
    )

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section("服务配置（OpenAI 兼容）") {
            OutlinedTextField(name, { scope.launch { cfg.setChatProviderName(it) } }, label = { Text("服务商名称") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            OutlinedTextField(baseUrl, { scope.launch { cfg.setChatBaseUrl(it) } }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            OutlinedTextField(chatPath, { scope.launch { cfg.setChatPath(it) } }, label = { Text("聊天接口路径") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            OutlinedTextField(model, { scope.launch { cfg.setChatModel(it) } }, label = { Text("模型") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            OutlinedTextField(apiKey, { scope.launch { cfg.setChatApiKey(it) } }, label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("温度", Modifier.width(90.dp), fontSize = 13.sp)
                Slider(value = temperature.toFloat(), onValueChange = { scope.launch { cfg.setChatTemperature(it.toDouble()) } }, valueRange = 0f..2f, modifier = Modifier.weight(1f))
                Text("$temperature", Modifier.width(44.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("最大 Tokens", Modifier.width(90.dp), fontSize = 13.sp)
                Slider(value = maxTokens.toFloat(), onValueChange = { scope.launch { cfg.setChatMaxTokens(it.toInt()) } }, valueRange = 256f..8192f, steps = 30, modifier = Modifier.weight(1f))
                Text("$maxTokens", Modifier.width(44.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            SwitchRow("跳过 SSL 证书验证", "本地网关/自签名证书时开启", !verifySsl) { on ->
                scope.launch { cfg.setChatVerifySsl(!on) }
            }
        }
        Section("工具") {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    testResult = "测试中…"
                    SseClient.testConnection(curCfg) { r ->
                        testResult = r.fold({ it }, { "失败：${it.message}" })
                    }
                }) { Text("测试连接") }
                OutlinedButton(onClick = {
                    balanceResult = "查询中…"
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { Balance.fetch(baseUrl, apiKey, verifySsl) }
                        balanceResult = r.fold({ it }, { "失败：${it.message}" })
                    }
                }) { Text("查询余额") }
            }
            testResult?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            balanceResult?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            SettingRow("打开 AI 对话", "与欧鲸鲸聊天（需先配置 API Key）", trailing = {
                TextButton(onClick = { ctx.startActivity(Intent(ctx, com.dshpet.android.chat.ChatActivity::class.java)) }) { Text("打开") }
            })
        }
    }
}

// ================================================================ 快捷启动
@Composable
private fun QuickLaunchTab(ctx: android.content.Context, cfg: PetConfig, scope: kotlinx.coroutines.CoroutineScope) {
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var pickerOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { apps = cfg.quickLaunch() }
    val launchable = remember {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to (it.loadLabel(pm).toString()) }
            .distinctBy { it.first }
            .sortedBy { it.second }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section("快捷启动应用") {
            apps.forEach { (pkg, label) ->
                SettingRow(label, subtitle = pkg, trailing = {
                    IconButton(onClick = {
                        scope.launch {
                            cfg.setQuickLaunch(apps.filter { it.first != pkg }.map { "${it.first}|${it.second}" }.toSet())
                        }
                        pickerOpen = false
                    }) { Icon(Icons.Filled.Delete, "移除", tint = MaterialTheme.colorScheme.error) }
                })
            }
            if (apps.isEmpty()) {
                Text("（尚未添加，长按桌宠菜单 → 快捷启动 可使用）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
            OutlinedButton(onClick = { pickerOpen = true }, modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Filled.Search, null, Modifier.width(16.dp))
                Text(" 添加应用")
            }
        }
    }
    if (pickerOpen) {
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            title = { Text("选择要启动的应用") },
            text = {
                Column(Modifier.height(400.dp).verticalScroll(rememberScrollState())) {
                    launchable.forEach { (pkg, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                val updated = (apps + (pkg to label)).distinctBy { it.first }
                                apps = updated
                                scope.launch { cfg.setQuickLaunch(updated.map { "${it.first}|${it.second}" }.toSet()) }
                                pickerOpen = false
                            }.padding(vertical = 10.dp, horizontal = 4.dp),
                        ) {
                            Text(label, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickerOpen = false }) { Text("关闭") } },
        )
    }
}

// ================================================================ 关于
@Composable
private fun AboutTab(ctx: android.content.Context, cfg: PetConfig, scope: kotlinx.coroutines.CoroutineScope) {
    var updateInfo by remember { mutableStateOf<String?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section("版本") {
            SettingRow("dsh-pet 桌宠（Android 移植版）", subtitle = "v${BuildConfig.VERSION_NAME} · 基于 dsh-pet-indesktop v4.0.1 移植")
            SettingRow("查看日志", subtitle = "崩溃排查：浏览/复制/分享内置日志（按日期存储）", trailing = {
                TextButton(onClick = {
                    ctx.startActivity(Intent(ctx, com.dshpet.android.LogActivity::class.java))
                }) { Text("打开") }
            })
            SettingRow("检查更新", subtitle = updateInfo, trailing = {
                TextButton(onClick = {
                    updateInfo = "检查中…"
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { Updater.latestRelease() }
                        updateInfo = r.fold({ rel ->
                            if (Updater.isNewer(rel.tag, BuildConfig.VERSION_NAME)) "发现新版本 ${rel.tag}！点击右侧打开下载页"
                            else "已经是最新版本"
                        }, { "检查失败：${it.message}" })
                        r.getOrNull()?.let { _ ->
                            // 打开下载页
                        }
                    }
                }) { Text("检查") }
            }, onClick = { updateInfo?.let { if (it.contains("下载页")) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.RELEASES_URL))) } } })
        }
        Section("链接") {
            SettingRow("GitHub 仓库与 Releases", subtitle = Updater.RELEASES_URL, onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.RELEASES_URL))) }
            })
            SettingRow("桌面端原项目", subtitle = "github.com/MerZlin/dsh-pet-indesktop", onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/MerZlin/dsh-pet-indesktop"))) }
            })
        }
        Section("说明") {
            Text(
                "本应用为 dsh-pet 桌面桌宠的 Android 移植版：透明悬浮窗显示在任意应用之上；" +
                        "素材在构建时由 WebM 重编码为旁路 alpha 视频，手机上无需任何额外组件。\n\n" +
                        "许可：MIT（见桌面端仓库 LICENSE）。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
