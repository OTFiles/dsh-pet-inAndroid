package com.dshpet.android.pet

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.media.SoundPool
import androidx.core.app.NotificationCompat
import com.dshpet.android.MainActivity
import com.dshpet.android.R
import com.dshpet.android.chat.ChatActivity
import com.dshpet.android.data.PetConfig
import com.dshpet.android.data.PetState
import com.dshpet.android.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 桌宠悬浮窗前台服务（每只桌宠一个实例，多开互不干扰）。
 *
 * - TYPE_APPLICATION_OVERLAY 透明窗口，显示在任意应用之上；
 * - 手势：点击 = 点击回应动画 + Q 弹；拖动 = 跟手移动（可物理抛掷）；
 *   长按 = MD3 菜单；"仅长按可拖动"模式下长按后拖动才生效；
 * - 动画链、位置持久化（按屏幕比例换算 dp）、多实例隔离；
 * - 前台通知提供：显示/隐藏、AI 对话、设置、退出。
 */
class PetOverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "pet"
        const val EXTRA_INSTANCE = "instance_id"
        private const val TAG = "PetOverlay"

        fun intent(ctx: Context, instanceId: Int = 0): Intent =
            Intent(ctx, PetOverlayService::class.java).putExtra(EXTRA_INSTANCE, instanceId)

        fun ensureRunning(ctx: Context, instanceId: Int = 0, persist: Boolean = true) {
            try {
                if (persist) ctx.startForegroundService(intent(ctx, instanceId))
                else ctx.startService(intent(ctx, instanceId))
            } catch (e: Exception) {
                // 8.0+ 后台启动前台服务限制：忽略（用户需从设置页手动启动）
            }
        }

        fun stopAll(ctx: Context) {
            synchronized(activeInstances) {
                val ids = activeInstances.toList()
                ids.forEach { ctx.stopService(intent(ctx, it)) }
                ctx.stopService(intent(ctx, 0))
            }
        }

        private val activeInstances = mutableSetOf<Int>()
        /** 本进程内某实例初始化失败：避免 START_STICKY 无限重启循环 */
        private val initFailed = mutableSetOf<Int>()
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
                kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
                    // 协程内异常不得让应用崩溃，写入内置日志
                    AppLog.log("SCOPE", android.util.Log.getStackTraceString(e))
                }
    )
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var config: PetConfig
    private var instanceId = 0
    private var stateStore: PetState? = null

    private lateinit var wm: WindowManager
    private var container: View? = null
    private lateinit var videoView: PetVideoView
    private var params: WindowManager.LayoutParams? = null
    private var bubble: SpeechBubble? = null
    private var menuWindow: PetMenu? = null
    private var easterEggs = mutableListOf<EasterEggPopup>()

    private lateinit var engine: PetEngine
    private lateinit var catalog: PetCatalog
    private var soundPool: SoundPool? = null
    private var clickSoundId = 0

    // ---- 运行时设置快照（协程监听 DataStore 更新）----
    private var density = 1f
    private var curScale = 0.72
    private var curOpacity = 100
    internal var curSpeed = 1.0
    private var curNoMove = false
    internal var curLock = false
    private var curShiftDrag = false
    internal var curPhysics = false
    private var curGap = 0.0
    private var curSelfTalk = false
    private var curSelfTalkTexts: List<String> = emptyList()
    private var curSelfTalkDurationMs = 3200L
    private var curSelfTalkMinSec = 20
    private var curSelfTalkMaxSec = 60
    private var selfTalkRunnable: Runnable? = null
    private var curClickSound = true
    private var curClickBalance = false
    private var curClickSelfTalk = false
    private var visible = true
    private var consecutiveErrors = 0
    private var settingsJobs = mutableListOf<Job>()

    

    // ---- 手势状态 ----
    private var downX = 0f
    private var downY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var grabOffsetX = 0f  // 按下时固定：手指 - 窗口左上角（px）
    private var grabOffsetY = 0f
    private var dragging = false
    private var longPressFired = false
    private var justDragged = false
    private var pressActive = false
    private val longPressRunnable = Runnable { onLongPress() }
    private var trail = mutableListOf<Triple<Long, Float, Float>>() // (timeMs, xDp, yDp)
    private var physPos = doubleArrayOf(0.0, 0.0)
    private var physVel = doubleArrayOf(0.0, 0.0)
    private var physMode: String? = null // null / drag / throw
    private var dragTargetX = 0
    private var dragTargetY = 0
    private var lastMoveMs = 0L

    // ---- 定时器 ----
    private val moveTicker = object : Runnable {
        override fun run() {
            if (engine.movePlanActive() || physMode != null) {
                engine.tickMove()
                tickPhysics()
                uiHandler.postDelayed(this, 33)
            }
        }
    }

    // ================================================================ 生命周期
    override fun onCreate() {
        super.onCreate()
        config = PetConfig.get(this)
        density = resources.displayMetrics.density
        AppLog.log("SVC", "onCreate instance=$instanceId")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instanceId = intent?.getIntExtra(EXTRA_INSTANCE, 0) ?: 0
        AppLog.log("SVC", "onStartCommand instance=$instanceId action=${intent?.action}")
        synchronized(activeInstances) { activeInstances.add(instanceId) }
        if (intent?.action == "quit") {
            quit()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch { config.setPetRunning(true) }
        startForeground(1000 + instanceId, buildNotification())
        if (synchronized(initFailed) { initFailed.contains(instanceId) }) {
            // 上次初始化失败：不再重试，避免重启循环
            stopSelf()
            return START_NOT_STICKY
        }
        if (container == null) {
            scope.launch {
                try {
                    initPet()
                } catch (e: Throwable) {
                    AppLog.log("INIT", "初始化失败: ${e.message}\n${android.util.Log.getStackTraceString(e)}")
                    synchronized(initFailed) { initFailed.add(instanceId) }
                    showBubble("桌宠初始化失败：${e.message}")
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        synchronized(activeInstances) { activeInstances.remove(instanceId) }
        scope.cancel()
        uiHandler.removeCallbacksAndMessages(null)
        settingsJobs.forEach { it.cancel() }
        savePosition()
        removeMenu()
        bubble?.dismiss()
        easterEggs.forEach { it.dismiss() }
        easterEggs.clear()
        container?.let { runCatching { wm.removeView(it) } }
        runCatching { videoView.release() }
        soundPool?.release()
        CoroutineScope(Dispatchers.Main).launch { config.setPetRunning(false) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ================================================================ 初始化
    private suspend fun initPet() {
        AppLog.log("SVC", "initPet start instance=$instanceId")
        stateStore = PetState(this, instanceId)
        catalog = PetCatalog.load(this)
        AppLog.log("SVC", "catalog 加载 ${catalog.names.size} 段动画")
        engine = PetEngine(
            catalog = catalog,
            play = { name -> playAnimation(name) },
            moveTo = { x, y -> moveWindow(x, y) },
            onFacingChanged = { f -> videoView.setMirror(engine.shouldMirror(engine.anim ?: "")); persistFacing(f) },
            onMoveFinished = { savePosition() },
        ).apply {
            noMove = curNoMove
            animationGapSeconds = curGap
            currentPositionSecProvider = { videoView.currentPositionMs() / 1000.0 }
        }

        // 窗口尺寸（物理像素，带上限保护：异常大 density 设备不溢出屏幕）
        curScale = config.scale()
        val (ww, wh) = windowSizePx()
        engine.setWindowSize(ww, wh)

        buildWindow()

        // 手势
        installGestures()

        // 音效
        setupSound()

        // 恢复位置与朝向
        val st = stateStore!!.load()
        val (sx, sy) = screenPx()
        val savedX = st.x; val savedY = st.y
        val defaultX = sx - engine.winW - 24
        val defaultY = sy - engine.winH - 24
        engine.setFacing(st.facing.ifEmpty { "left" })
        videoView.setMirror(engine.shouldMirror(engine.anim ?: ""))
        // 多开错位：新实例向右上偏移，避免与母体重叠
        val offsetX = if (instanceId > 0) (instanceId * 48).coerceAtMost(engine.winW / 2) else 0
        val offsetY = if (instanceId > 0) (instanceId * 32).coerceAtMost(engine.winH / 2) else 0
        engine.setPosition(
            if (savedX >= 0) savedX.coerceIn(0, maxOf(0, sx - engine.winW))
            else (defaultX - offsetX).coerceAtLeast(0),
            if (savedY >= 0) savedY.coerceIn(0, maxOf(0, sy - engine.winH))
            else (defaultY - offsetY).coerceAtLeast(0),
        )

        // 气泡
        bubble = SpeechBubble(this, engine, config)
        bubble?.bubbleStyle = config.selfTalkBubbleStyle()
        bubble?.blurOn = config.blurEnabled() && Build.VERSION.SDK_INT >= 31

        // 自言自语素材缓存（供非协程回调使用）
        curSelfTalkTexts = config.selfTalkTexts().toList()
        curSelfTalkDurationMs = (config.selfTalkDuration() * 1000).toLong()
        curSelfTalk = config.selfTalkEnabled()
        curSelfTalkMinSec = config.selfTalkMin().coerceAtLeast(5)
        curSelfTalkMaxSec = config.selfTalkMax().coerceAtLeast(curSelfTalkMinSec)

        // 设置监听（改动即时生效）
        observeSettings()

        // 启动动画链
        engine.start()
        uiHandler.postDelayed(moveTicker, 33)
        scheduleSelfTalk()
        applyOpacity()
        AppLog.log("SVC", "initPet 完成，窗口 ${engine.winX},${engine.winY} ${engine.winW}x${engine.winH}")
    }

    // ================================================================ 自言自语
    private fun scheduleSelfTalk() {
        selfTalkRunnable?.let { uiHandler.removeCallbacks(it) }
        if (!curSelfTalk) return
        val min = curSelfTalkMinSec.coerceAtLeast(5)
        val max = curSelfTalkMaxSec.coerceAtLeast(min)
        val delay = (min + kotlin.random.Random.nextDouble() * (max - min)) * 1000
        val r = Runnable {
            showRandomSelfTalk()
            scheduleSelfTalk()
        }
        selfTalkRunnable = r
        uiHandler.postDelayed(r, delay.toLong())
    }

    private fun showRandomSelfTalk() {
        if (curSelfTalkTexts.isEmpty()) return
        val text = curSelfTalkTexts[kotlin.random.Random.nextInt(curSelfTalkTexts.size)]
        bubble?.showText(text, durationMs = curSelfTalkDurationMs.coerceIn(1000, 30000))
    }

    private fun buildWindow() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val w = engine.winW
        val h = engine.winH
        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "dsh-pet-$instanceId"
        }
        params = lp

        // 纯代码创建视图（避免 XML inflate 自定义 View 的兼容性问题）
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        videoView = PetVideoView(this)
        root.addView(videoView, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        AppLog.log("SVC", "buildWindow ${w}x$h 纯代码创建视图")
        videoView.setListener(object : PetVideoView.Listener {
            override fun onVideoEnded(name: String) {
                consecutiveErrors = 0
                engine.onAnimEnded(name)
                videoView.setMirror(engine.shouldMirror(engine.anim ?: ""))
            }

            override fun onVideoError(name: String, msg: String) {
                // 出错时推进动画链，避免停摆；连续出错则停止并提示（防死循环）
                AppLog.log("VIDEO", "播放失败 [$name]: $msg")
                consecutiveErrors++
                if (consecutiveErrors > 8) {
                    videoView.pausePlay()
                    showBubble("动画加载失败：$msg")
                } else {
                    engine.onAnimEnded(name)
                }
            }
        })
        container = root
        wm.addView(root, lp)
    }

    // ================================================================ 动画播放
    private fun playAnimation(name: String) {
        try {
            val path = catalog.files[name] ?: return
            videoView.play(name, path, curSpeed.toFloat())
            videoView.setMirror(engine.shouldMirror(name))
            // 隐藏时不播（省电）：显示时恢复
            if (!visible) videoView.pausePlay()
        } catch (e: Throwable) {
            AppLog.log("PLAY", "playAnimation($name) 异常: ${e.message}")
        }
    }

    // ================================================================ 手势
    private fun installGestures() {
        val v = container ?: return
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressActive = true
                    dragging = false
                    longPressFired = false
                    justDragged = false
                    downX = ev.x; downY = ev.y
                    downRawX = ev.rawX; downRawY = ev.rawY
                    grabOffsetX = downRawX - engine.winX
                    grabOffsetY = downRawY - engine.winY
                    lastMoveMs = System.currentTimeMillis()
                    trail.clear()
                    trail.add(Triple(lastMoveMs, ev.rawX, ev.rawY))
                    physPos = doubleArrayOf(engine.winX.toDouble(), engine.winY.toDouble())
                    physVel = doubleArrayOf(0.0, 0.0)
                    physMode = null
                    engine.cancelMove()
                    if (curLock) {
                        // 锁定位置：不响应拖动，点击仍有效
                        return@setOnTouchListener true
                    }
                    // 长按菜单（默认 500ms；"仅长按可拖动"时同样先长按）
                    uiHandler.removeCallbacks(longPressRunnable)
                    uiHandler.postDelayed(longPressRunnable, if (curShiftDrag) 300 else 500)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!pressActive) return@setOnTouchListener true
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    val threshold = (PetEngine.DRAG_THRESHOLD * curScale * density).coerceAtLeast(12.0)
                    if (!dragging && hypot(dx.toDouble(), dy.toDouble()) > threshold) {
                        if (curShiftDrag && !longPressFired) {
                            // 仅长按可拖动：未长按的拖动被忽略，且取消按压状态
                            pressActive = false
                            uiHandler.removeCallbacks(longPressRunnable)
                            return@setOnTouchListener true
                        }
                        uiHandler.removeCallbacks(longPressRunnable)
                        if (curLock) return@setOnTouchListener true
                        dragging = true
                        engine.onDragStart()
                    }
                    if (dragging) {
                        if (curPhysics) {
                            val now = System.currentTimeMillis()
                            trail.add(Triple(now, ev.rawX, ev.rawY))
                            val cutoff = now - (PetEngine.TRAIL_KEEP_SEC * 1000).toLong()
                            trail = trail.filter { it.first >= cutoff }.toMutableList()
                            dragTargetX = (ev.rawX - grabOffsetX).toInt()
                            dragTargetY = (ev.rawY - grabOffsetY).toInt()
                            physMode = "drag"
                        } else {
                            moveWindow(
                                (ev.rawX - grabOffsetX).toInt(),
                                (ev.rawY - grabOffsetY).toInt(),
                            )
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    uiHandler.removeCallbacks(longPressRunnable)
                    if (dragging) {
                        justDragged = true
                        uiHandler.postDelayed({ justDragged = false }, 150)
                        engine.onDragEnd()
                        if (curPhysics) {
                            val now = System.currentTimeMillis()
                            val (vx, vy) = PetEngine.estimateReleaseVelocity(trail, now)
                            if (hypot(vx, vy) < PetEngine.DEAD_ZONE_SPEED) {
                                physMode = null
                                savePosition()
                            } else {
                                physVel = doubleArrayOf(vx, vy)
                                physMode = "throw"
                            }
                        } else {
                            savePosition()
                        }
                    } else if (!longPressFired && !justDragged) {
                        onTap()
                    }
                    dragging = false
                    pressActive = false
                    true
                }
                else -> true
            }
        }
    }

    private fun onLongPress() {
        if (!pressActive) return
        longPressFired = true
        if (curShiftDrag) {
            // 仅长按可拖动：长按后进入"待拖动"状态，松手未移动则弹菜单
            return
        }
        openMenu()
    }

    private fun onTap() {
        engine.onTap()
        squash()
        if (curClickSound) playClickSound()
        if (curClickBalance) {
            showBalanceInBubble()
        } else if (curClickSelfTalk && curSelfTalk) {
            showRandomSelfTalk()
        }
    }

    // ================================================================ Q 弹
    private var squashAnim: android.animation.ValueAnimator? = null

    private fun squash() {
        squashAnim?.cancel()
        videoView.pivotX = videoView.width / 2f
        videoView.pivotY = videoView.height.toFloat()
        val anim = android.animation.ValueAnimator.ofFloat(1f, 0.78f, 1.06f, 1f).apply {
            duration = 240
            addUpdateListener {
                videoView.scaleY = it.animatedValue as Float
            }
        }
        squashAnim = anim
        anim.start()
    }

    // ================================================================ 移动窗口
    private fun moveWindow(xPx: Int, yPx: Int) {
        val lp = params ?: return
        val c = container ?: return
        val (sw, sh) = screenPx()
        // 屏幕比窗口还小时取 0，避免空区间 coerce 崩溃
        val maxX = maxOf(0, sw - engine.winW)
        val maxY = maxOf(0, sh - engine.winH)
        val cx = xPx.coerceIn(0, maxX)
        val cy = yPx.coerceIn(0, maxY)
        lp.x = cx
        lp.y = cy
        runCatching { wm.updateViewLayout(c, lp) }
        engine.syncPosition(cx, cy)
    }

    private fun tickPhysics() {
        val mode = physMode ?: return
        val (sw, sh) = screenPx()
        val left = 0
        val top = 0
        val right = maxOf(0, sw - engine.winW)
        val bottom = maxOf(0, sh - engine.winH)
        if (right <= left || bottom <= top) { physMode = null; return }
        if (mode == "drag") {
            val k = 200.0; val c = 30.0
            val dt = 0.016
            val tx = dragTargetX.toDouble()
            val ty = dragTargetY.toDouble()
            val vx = physVel[0] + ((tx - physPos[0]) * k - physVel[0] * c) * dt
            val vy = physVel[1] + ((ty - physPos[1]) * k - physVel[1] * c) * dt
            physVel[0] = vx; physVel[1] = vy
            physPos[0] += vx * dt; physPos[1] += vy * dt
            moveWindow(physPos[0].roundToInt(), physPos[1].roundToInt())
        } else {
            val dt = 0.016
            val r = PetEngine.throwStep(
                physPos[0], physPos[1], physVel[0], physVel[1], dt,
                left, top, right, bottom,
            )
            physPos = doubleArrayOf(r.px, r.py)
            physVel = doubleArrayOf(r.vx, r.vy)
            moveWindow(physPos[0].roundToInt(), physPos[1].roundToInt())
            val speed = hypot(r.vx, r.vy)
            if (PetEngine.isAtRest(r.py, r.vx, r.vy, bottom, r.bounced, speed)) {
                physMode = null
                savePosition()
            }
        }
    }

    // ================================================================ 设置监听
    private fun observeSettings() {
        fun watch(
            flow: kotlinx.coroutines.flow.Flow<*>,
            apply: suspend (Any?) -> Unit,
        ) {
            settingsJobs.add(scope.launch {
                flow.collect { apply(it) }
            })
        }
        val c = config
        watch(c.flowDouble("scale", 0.72)) { v ->
            curScale = (v as Double)
            // 缩放变化：重建窗口尺寸，保留脚底位置
            val oldW = engine.winW
            val (ww, wh) = windowSizePx()
            engine.setWindowSize(ww, wh)
            if (oldW != engine.winW) {
                val lp = params ?: return@watch
                val c = container ?: return@watch
                lp.width = ww
                lp.height = wh
                runCatching { wm.updateViewLayout(c, lp) }
                // 保持右下角锚点
                val (sw, sh) = screenPx()
                engine.setPosition(
                    (sw - engine.winW - 24).coerceAtLeast(0),
                    (sh - engine.winH - 24).coerceAtLeast(0),
                )
            }
        }
        watch(c.flowInt("pet_opacity", 100)) { v -> curOpacity = (v as Int).coerceIn(10, 100); applyOpacity() }
        watch(c.flowDouble("playback_speed", 1.0)) { v -> curSpeed = v as Double; videoView.setPlaybackSpeed(curSpeed.toFloat()) }
        watch(c.flowBool("no_move", false)) { v -> curNoMove = v as Boolean; engine.noMove = curNoMove }
        watch(c.flowBool("lock_position", false)) { v -> curLock = v as Boolean }
        watch(c.flowBool("shift_drag", false)) { v -> curShiftDrag = v as Boolean }
        watch(c.flowBool("drag_physics", false)) { v -> curPhysics = v as Boolean }
        watch(c.flowDouble("animation_gap_seconds", 0.0)) { v -> curGap = v as Double; engine.animationGapSeconds = curGap }
        watch(c.flowBool("click_sound_enabled", true)) { v -> curClickSound = v as Boolean }
        watch(c.flowBool("click_show_balance", false)) { v -> curClickBalance = v as Boolean }
        watch(c.flowBool("click_show_self_talk", false)) { v -> curClickSelfTalk = v as Boolean }
        watch(c.flowBool("self_talk_enabled", false)) { v ->
            curSelfTalk = v as Boolean
            if (!curSelfTalk) bubble?.hide()
            else scheduleSelfTalk()
        }
        watch(c.flowString("facing", "left")) { v ->
            engine.setFacing(v as String)
            videoView.setMirror(engine.shouldMirror(engine.anim ?: ""))
        }
        watch(c.flowStringSet("self_talk_texts", emptySet())) { v ->
            curSelfTalkTexts = (v as Set<*>).filterIsInstance<String>().toList()
        }
        watch(c.flowString("self_talk_bubble_style", "classic_top")) { v ->
            bubble?.bubbleStyle = v as String
        }
        watch(c.flowBool("blur_enabled", false)) { v ->
            bubble?.blurOn = (v as Boolean) && Build.VERSION.SDK_INT >= 31
        }
        watch(c.flowDouble("self_talk_duration_seconds", 3.2)) { v ->
            curSelfTalkDurationMs = ((v as Double) * 1000).toLong()
        }
        watch(c.flowInt("self_talk_min_interval", 20)) { v ->
            curSelfTalkMinSec = (v as Int).coerceAtLeast(5)
            scheduleSelfTalk()
        }
        watch(c.flowInt("self_talk_max_interval", 60)) { v ->
            curSelfTalkMaxSec = (v as Int).coerceAtLeast(curSelfTalkMinSec)
            scheduleSelfTalk()
        }
    }

    private fun applyOpacity() {
        val lp = params ?: return
        val c = container ?: return
        lp.alpha = curOpacity / 100f
        runCatching { wm.updateViewLayout(c, lp) }
    }



    fun showBubble(text: String, durationMs: Long = 6000) {
        uiHandler.post { bubble?.showText(text, durationMs) }
    }

    // ================================================================ 菜单
    private fun openMenu() {
        removeMenu()
        menuWindow = PetMenu(
            service = this,
            engine = engine,
            onDismiss = { removeMenu() },
        ).apply { show() }
    }

    fun removeMenu() {
        menuWindow?.dismiss()
        menuWindow = null
    }

    // ================================================================ 其他功能
    fun spawnPet() {
        scope.launch {
            val id = config.nextInstanceId()
            PetOverlayService.ensureRunning(this@PetOverlayService, id)
            showBubble("一只新的小肥鱼诞生啦！", 4000)
        }
    }

    fun openSettings() {
        MainActivity.start(this)
    }

    fun openChat() {
        startActivity(Intent(this, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun showBalanceInBubble() {
        scope.launch {
            val apiKey = config.chatApiKey()
            if (apiKey.isBlank()) {
                showBubble("未配置 AI API Key，无法查询余额", 6000)
                return@launch
            }
            showBubble("让我看看余额…", 6000)
            val r = withContext(Dispatchers.IO) {
                Balance.fetch(config.chatBaseUrl(), apiKey, config.chatVerifySsl(), config.chatTimeout())
            }
            r.fold(
                onSuccess = { showBubble(it, 6000) },
                onFailure = { showBubble("余额查询失败：${it.message}", 7000) },
            )
        }
    }

    fun checkUpdate() {
        scope.launch {
            showBubble("正在检查更新…", 6000)
            val r = withContext(Dispatchers.IO) { Updater.latestRelease() }
            r.fold(
                onSuccess = { rel ->
                    val current = com.dshpet.android.BuildConfig.VERSION_NAME
                    if (Updater.isNewer(rel.tag, current)) {
                        showBubble("发现新版本 v${rel.tag}（当前 $current）。可前往设置-关于下载更新。", 9000)
                    } else {
                        showBubble("已经是最新版本（$current）啦", 6000)
                    }
                },
                onFailure = { showBubble("检查更新失败：${it.message}", 7000) },
            )
        }
    }

    fun toggleVisible() {
        visible = !visible
        container?.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) videoView.resumePlay() else videoView.pausePlay()
        bubble?.hide()
    }

    /** 回到右下角（默认角落） */
    fun returnToCorner() {
        engine.cancelMove()
        val (sw, sh) = screenPx()
        engine.setPosition(
            (sw - engine.winW - 24).coerceAtLeast(0),
            (sh - engine.winH - 24).coerceAtLeast(0),
        )
        savePosition()
    }

    fun quit() {
        stopAll(this)
        stopSelf()
    }

    // ================================================================ 位置持久化
    private fun savePosition() {
        scope.launch {
            stateStore?.save(PetState.State(engine.winX, engine.winY, engine.facing))
        }
    }

    private fun persistFacing(f: String) {
        scope.launch { config.setFacing(f) }
    }

    // ================================================================ 音效
    private fun setupSound() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            soundPool = SoundPool.Builder().setMaxStreams(3).build()
        } else {
            @Suppress("DEPRECATION")
            soundPool = SoundPool(3, android.media.AudioManager.STREAM_MUSIC, 0)
        }
        clickSoundId = try {
            val afd = assets.openFd("pet/sounds/click.wav")
            soundPool?.load(afd, 1) ?: 0
        } catch (e: Exception) { 0 }
    }

    private fun playClickSound() {
        if (clickSoundId != 0) {
            soundPool?.play(clickSoundId, 1f, 1f, 1, 0, 1f)
        }
    }

    // ================================================================ 通知
    private fun buildNotification(): Notification {
        val openSettings = PendingIntent.getActivity(
            this, 0, MainActivity.startIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openChat = PendingIntent.getActivity(
            this, 1, Intent(this, ChatActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val quit = PendingIntent.getService(
            this, 2, intent(this, instanceId).setAction("quit"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pet)
            .setContentTitle(if (instanceId == 0) "dsh-pet 桌宠" else "dsh-pet 桌宠 #$instanceId")
            .setContentText("桌宠正在陪伴你 · 点击打开设置")
            .setContentIntent(openSettings)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "AI 对话", openChat)
            .addAction(0, "退出", quit)
            .build()
        return n
    }

    // ================================================================ 工具
    /** 屏幕物理像素 */
    private fun screenPx(): Pair<Int, Int> {
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            android.graphics.Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        }
        return bounds.width() to bounds.height()
    }

    /** 桌宠窗口物理像素尺寸：按屏幕宽度比例（scale=0.72 → 42% 屏宽），
     *  与设备 density 无关，异常大密度设备也不会溢出。 */
    private fun windowSizePx(): Pair<Int, Int> {
        val (sw, _) = screenPx()
        val frac = 0.42 * curScale / 0.72
        val w = (sw * frac).toInt().coerceIn(160, sw * 92 / 100)
        val h = w * 9 / 16
        return w to h
    }

    fun requireOverlayPermission(): Boolean = Settings.canDrawOverlays(this)
}
