package com.dshpet.android.pet

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshpet.android.data.PetConfig
import com.dshpet.android.util.mdBlur
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 长按桌宠弹出的 MD3 菜单（新版菜单布局，功能对齐桌面端 modern.json 分组）。
 */
class PetMenu(
    private val service: PetOverlayService,
    private val engine: PetEngine,
    private val onDismiss: () -> Unit,
) {
    private val ctx: Context = service
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private val density = ctx.resources.displayMetrics.density

    fun show() {
        if (view != null) return
        val composeView = ComposeView(ctx).apply {
            setContent { MenuRoot() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        }
        view = composeView
        params = lp
        composeView.setOnKeyListener { _, keyCode, _ ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                dismiss(); true
            } else false
        }
        composeView.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                dismiss(); true
            } else false
        }
        runCatching { wm.addView(composeView, lp) }
        // 菜单定位：桌宠上方（放不下放下方）
        composeView.post {
            val bw = composeView.measuredWidth
            val bh = composeView.measuredHeight
            val (sw, sh) = screenDp()
            val bwDp = px2dp(bw)
            val bhDp = px2dp(bh)
            val petCx = engine.winX + engine.winW / 2
            var x = petCx - bwDp / 2
            x = x.coerceIn(8f, sw - bwDp - 8f)
            var y = engine.winY - bhDp - 12f
            if (y < 0f) y = engine.winY + engine.winH + 12f
            y = y.coerceIn(0f, sh - bhDp)
            lp.x = dp2px(x.roundToInt()); lp.y = dp2px(y.roundToInt())
            runCatching { wm.updateViewLayout(composeView, lp) }
        }
    }

    fun dismiss() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
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

    // ================================================================ UI
    @Composable
    private fun MenuRoot() {
        val scope = rememberCoroutineScope()
        val cfg = PetConfig.get(ctx)
        var speedOpen by remember { mutableStateOf(false) }
        var sizeOpen by remember { mutableStateOf(false) }
        var animHubOpen by remember { mutableStateOf(false) }
        var quickOpen by remember { mutableStateOf(false) }
        var noMove by remember { mutableStateOf(engine.noMove) }
        var lock by remember { mutableStateOf(service.curLock) }
        var physics by remember { mutableStateOf(service.curPhysics) }
        val blurCfg by cfg.flowBool("blur_enabled", false).collectAsState(initial = false)
        val blurOn = blurCfg && Build.VERSION.SDK_INT >= 31
        fun run(action: suspend () -> Unit) {
            scope.launch { action(); }
            onDismiss()
        }

        Surface(
            modifier = Modifier
                .width(248.dp)
                .mdBlur(blurOn, radius = 22),
            shape = RoundedCornerShape(18.dp),
            color = if (blurOn) Color(0xE6FFFFFF) else MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
            ) {
                if (animHubOpen) {
                    AnimHub(cfg, onBack = { animHubOpen = false }) { name -> run { engine.switch(name) } }
                } else if (quickOpen) {
                    QuickLaunchList(cfg, onBack = { quickOpen = false }) { pkg ->
                        run {
                            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                            if (intent != null) runCatching { ctx.startActivity(intent) }
                        }
                    }
                } else {
                    // ---- 互动 ----
                    MenuGroup("互动")
                    MenuItem(Icons.Filled.Send, "AI 对话") { run { service.openChat() } }
                    MenuItem(Icons.Filled.Star, "欧鲸鲸（彩蛋）") { run { EasterEggPopup.showRandom(ctx) } }
                    // ---- 播放 ----
                    MenuGroup("播放")
                    MenuItem(Icons.Filled.PlayArrow, "动画集", badge = "91 段") { animHubOpen = true }
                    MenuItem(Icons.Filled.Refresh, "播放速度", badge = "${service.curSpeed}×") { speedOpen = true }
                    SpeedSubmenu(speedOpen, cfg) { v -> run { cfg.setPlaybackSpeed(v) } }
                    MenuItem(Icons.Filled.Home, "大小", badge = sizeLabel(engine.winW / 640.0)) { sizeOpen = true }
                    SizeSubmenu(sizeOpen, cfg) { v -> run { cfg.setScale(v) } }
                    // ---- 功能 ----
                    MenuGroup("功能")
                    ToggleItem(Icons.Filled.Build, "拖动物理", physics) {
                        physics = !physics; run { cfg.setDragPhysics(physics) }
                    }
                    MenuItem(Icons.Filled.Home, "回到右下角") { run { service.returnToCorner() } }
                    ToggleItem(Icons.Filled.Close, "不移动", noMove) {
                        noMove = !noMove; run { cfg.setNoMove(noMove) }
                    }
                    ToggleItem(Icons.Filled.Lock, "锁定位置", lock) {
                        lock = !lock; run { cfg.setLockPosition(lock) }
                    }
                    MenuItem(Icons.Filled.Add, "生小肥鱼（多开）") { run { service.spawnPet() } }
                    // ---- 工具 ----
                    MenuGroup("工具")
                    MenuItem(Icons.Filled.Star, "DeepSeek 余额") { run { service.showBalanceInBubble() } }
                    MenuItem(Icons.Filled.Refresh, "检查更新") { run { service.checkUpdate() } }
                    MenuItem(Icons.Filled.PlayArrow, "快捷启动") { quickOpen = true }
                    // ---- 设置 ----
                    MenuGroup("设置")
                    MenuItem(Icons.Filled.Settings, "桌宠设置") { run { service.openSettings() } }
                    HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                    // ---- 退出 ----
                    MenuItem(Icons.Filled.Close, "退出桌宠", danger = true) { run { service.quit() } }
                }
            }
        }
    }

    @Composable
    private fun MenuGroup(title: String) {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 2.dp),
        )
    }

    @Composable
    private fun MenuItem(
        icon: ImageVector,
        title: String,
        badge: String? = null,
        danger: Boolean = false,
        onClick: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon, contentDescription = null,
                tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(20.dp),
            )
            Text(
                text = title,
                fontSize = 14.sp,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f),
            )
            badge?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun ToggleItem(icon: ImageVector, title: String, checked: Boolean, onToggle: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(20.dp))
            Text(title, fontSize = 14.sp, modifier = Modifier.padding(start = 14.dp).weight(1f))
            Switch(checked = checked, onCheckedChange = { onToggle() }, modifier = Modifier.height(28.dp))
        }
    }

    @Composable
    private fun SpeedSubmenu(open: Boolean, cfg: PetConfig, onPick: (Double) -> Unit) {
        if (!open) return
        listOf(1.0, 1.25, 1.5, 1.75, 2.0).forEach { v ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(v) }
                    .padding(start = 40.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Text("${v}×", fontSize = 13.sp)
                if (service.curSpeed == v) {
                    Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.width(16.dp))
                }
            }
        }
    }

    @Composable
    private fun SizeSubmenu(open: Boolean, cfg: PetConfig, onPick: (Double) -> Unit) {
        if (!open) return
        listOf(0.5, 0.72, 0.85, 1.0).forEach { v ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(v) }
                    .padding(start = 40.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Text(sizeLabel(v), fontSize = 13.sp)
                if (engine.winW == (640.0 * v).toInt()) {
                    Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.width(16.dp))
                }
            }
        }
    }

    @Composable
    private fun AnimHub(cfg: PetConfig, onBack: () -> Unit, onPick: (String) -> Unit) {
        val all = engine.idles + engine.turns + engine.moves + engine.clicks + engine.acts
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onBack)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("◀ 返回", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                Text("动画集（点击播放）", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            HorizontalDivider(Modifier.padding(horizontal = 14.dp))
            val grouped = all.groupBy { name ->
                when (name) {
                    in engine.idles -> "待机"
                    in engine.turns -> "转向"
                    in engine.moves -> "移动"
                    in engine.clicks -> "点击回应"
                    in engine.acts -> "随机动作"
                    else -> "其他"
                }
            }
            for ((group, names) in grouped) {
                MenuGroup(group)
                names.forEach { name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(name) }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        Text(name, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
    }

    @Composable
    private fun QuickLaunchList(cfg: PetConfig, onBack: () -> Unit, onPick: (String) -> Unit) {
        var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        androidx.compose.runtime.LaunchedEffect(Unit) { apps = cfg.quickLaunch() }
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onBack)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("◀ 返回", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                Text("快捷启动", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            HorizontalDivider(Modifier.padding(horizontal = 14.dp))
            if (apps.isEmpty()) {
                Text("（在桌宠设置中添加应用）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
            }
            apps.forEach { (pkg, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(pkg) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(label, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }

    private fun sizeLabel(scale: Double): String = when (scale) {
        0.5 -> "小 (320dp)"
        0.72 -> "中 (462dp)"
        0.85 -> "大 (544dp)"
        else -> "特大 (640dp)"
    }
}
