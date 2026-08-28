package com.dshpet.android.pet

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.random.Random

/**
 * 动画链状态机 —— 与原桌面端 window.py 1:1 移植：
 *  - 每个动画一次性播放，播完按概率选下一个：30% 待机 / 10% 转向 / 40% 动作 / 20% 移动；
 *  - 转向播完翻转朝向（facing=right 时水平镜像，含文字动画不镜像）；
 *  - 点击回应/拖拽播完先回待机缓冲，待机播完再进随机链；
 *  - 移动：动画提供走路姿态，位置由 ticker 驱动，前后各 2s 不动，中间按进度插值；
 *  - 可选"动画间隔"：动作/移动播完先放一段待机/转向，间隔结束再进随机链。
 */
class PetEngine(
    private val catalog: PetCatalog,
    /** 播放动画回调 */
    private val play: (String) -> Unit,
    /** 移动窗口回调（dp） */
    private val moveTo: (Int, Int) -> Unit,
    /** 朝向变化回调（镜像翻转） */
    private val onFacingChanged: (String) -> Unit,
    private val onMoveFinished: () -> Unit,
    private val random: Random = Random.Default,
) {
    // 几何常量（与原 catalog.py 一致，单位 dp）
    val idles = catalog.idles
    val turns = catalog.turns
    val moves = catalog.moves
    val clicks = catalog.clicks
    val dragName = catalog.drag
    val acts = catalog.acts

    /** 是否禁用自动移动（"不移动"） */
    var noMove = false
    var animationGapSeconds = 0.0
    var playbackSpeed = 1.0

    var facing = "left"
        private set

    fun setFacing(f: String) {
        facing = f
        onFacingChanged(f)
    }

    var anim: String? = null
        private set

    /** 当前窗口位置（dp，窗口左上角） */
    var winX = 0
        private set
    var winY = 0
        private set

    /** 窗口尺寸（dp） */
    var winW = 640
        private set
    var winH = 360
        private set

    fun setWindowSize(w: Int, h: Int) {
        winW = w
        winH = h
    }

    /** 屏幕可用区域（dp） */
    var screenLeft = 0
    var screenTop = 0
    var screenRight = 0
    var screenBottom = 0

    private var dragging = false
    private var gapActive = false
    private var gapDeadline = 0L
    private var movePlan: MovePlan? = null

    private data class MovePlan(
        val name: String,
        val startX: Int, val targetX: Int,
        val startY: Int, val targetY: Int,
        val durationMs: Long,
    )

    // ============================== 主入口 ==============================

    /** 首次进入：播放待机 */
    fun start() {
        switch(idles.firstOrNull() ?: turns.firstOrNull() ?: acts.firstOrNull() ?: return)
    }

    /** 主动播放某段动画（菜单"动画集"手动播放、点击、拖拽等） */
    fun switch(name: String) {
        anim = name
        play(name)
    }

    /** 动画播放完毕（由视图回调） */
    fun onAnimEnded(name: String) {
        val drag = dragName
        if (drag != null && name == drag && dragging) {
            switch(drag) // 拖拽中：悬空反馈循环
            return
        }
        if (name in turns) {
            facing = if (facing == "left") "right" else "left"
            onFacingChanged(facing)
        }
        if (drag != null && name == drag || name in clicks) {
            gapActive = false
            switch(idles.randomOrNull() ?: return)
            return
        }
        if (gapActive) {
            if (name in idles || name in turns) {
                playGapStep()
            } else {
                pickNext() // 异常兜底
            }
            return
        }
        if (animationGapSeconds > 0 && (name in acts || name in moves)) {
            startGap()
            return
        }
        pickNext()
    }

    // ============================== 动画链 ==============================

    private fun pickNext() {
        if (acts.isEmpty()) {
            idles.firstOrNull()?.let { if (it != anim) switch(it) }
            return
        }
        val roll = random.nextDouble()
        when {
            roll < P_IDLE -> {
                if (idles.isNotEmpty()) switch(idles.pick(exclude = anim))
                else switch(acts.pick(exclude = anim))
            }
            roll < P_TURN -> {
                if (turns.isNotEmpty()) switch(turns.pick(exclude = anim))
                else switch(acts.pick(exclude = anim))
            }
            roll < P_ACTS -> switch(acts.pick(exclude = anim))
            else -> {
                // 20% 移动（不移动模式或空间不足回退动作）
                if (noMove || !tryMove()) switch(acts.pick(exclude = anim))
            }
        }
    }

    // ============================== 动画间隔 ==============================

    private fun startGap() {
        if (animationGapSeconds <= 0 || (idles.isEmpty() && turns.isEmpty())) {
            pickNext()
            return
        }
        gapActive = true
        gapDeadline = System.currentTimeMillis() + (animationGapSeconds * 1000).toLong()
        playGapStep()
    }

    private fun playGapStep() {
        val pool = idles + turns
        if (pool.isNotEmpty()) switch(pool.pick(exclude = anim))
    }

    /** 由外部 ticker 调用：间隔结束则关闭 gap 状态 */
    fun tickGap() {
        if (gapActive && System.currentTimeMillis() >= gapDeadline) gapActive = false
    }

    // ============================== 移动 ==============================

    /** 朝 facing 方向计划一次移动；屏幕空间不足返回 false */
    fun tryMove(name: String? = null): Boolean {
        if (movePlan != null) return true
        if (moves.isEmpty()) return false
        val dirSign = if (facing == "right") 1 else -1
        val cx = winX + winW / 2.0
        val distance = random.nextInt(MOVE_MIN_PX, MOVE_MAX_PX + 1).toDouble()
        val targetCx = cx + dirSign * distance
        val halfW = winW / 2.0
        val leftBound = screenLeft + MOVE_MARGIN + halfW
        val rightBound = screenRight - MOVE_MARGIN - halfW
        if (targetCx < leftBound || targetCx > rightBound) return false

        val moveName = name ?: moves.pick()
        val durationMs = (catalog.durations[moveName] ?: 10.0) * 1000.0
        movePlan = MovePlan(
            name = moveName,
            startX = winX,
            targetX = (targetCx - halfW).toInt(),
            startY = winY,
            targetY = wanderTargetY(winY, screenTop, screenBottom, winH),
            durationMs = durationMs.toLong(),
        )
        switch(moveName)
        return true
    }

    /** 手动触发移动（菜单）：打断当前移动，空间不足则原地播放走路姿态 */
    fun triggerMove(name: String) {
        cancelMove()
        gapActive = false
        if (!tryMove(name)) switch(name)
    }

    /** 移动 ticker（~30fps）：位置跟随播放进度插值 */
    fun tickMove() {
        val plan = movePlan ?: return
        val tSec = currentPositionSec()
        val dur = plan.durationMs / 1000.0
        val (x, y) = when {
            tSec <= MOVE_LEAD_SEC -> plan.startX to plan.startY
            tSec >= dur - MOVE_TAIL_SEC -> plan.targetX to plan.targetY
            else -> {
                val progress = (tSec - MOVE_LEAD_SEC) / kotlin.math.max(0.1, dur - MOVE_LEAD_SEC - MOVE_TAIL_SEC)
                val x = plan.startX + ((plan.targetX - plan.startX) * progress).toInt()
                val y = plan.startY + ((plan.targetY - plan.startY) * progress).toInt()
                x to y
            }
        }
        setPosition(x, y)
        if (tSec >= dur - MOVE_TAIL_SEC) {
            movePlan = null
            onMoveFinished()
        }
    }

    fun cancelMove() {
        movePlan = null
    }

    fun movePlanActive(): Boolean = movePlan != null

    // ============================== 交互 ==============================

    /** 点击：随机点击回应动画 + Q 弹（由服务触发音效/挤压） */
    fun onTap() {
        if (clicks.isEmpty()) return
        cancelMove()
        switch(clicks.pick())
    }

    fun onDragStart() {
        dragging = true
        cancelMove()
        gapActive = false
        dragName?.let { switch(it) }
    }

    fun onDragEnd() {
        dragging = false
        if (idles.isNotEmpty()) switch(idles.pick()) // 回待机缓冲
    }

    /** 返回当前应否镜像（facing=right 且非"含文字动画"） */
    fun shouldMirror(name: String): Boolean =
        facing == "right" && name !in catalog.noMirror

    // ============================== 辅助 ==============================

    fun setPosition(x: Int, y: Int) {
        winX = x
        winY = y
        moveTo(x, y)
    }

    fun durationOf(name: String): Double = catalog.durations[name] ?: 10.0

    private fun currentPositionSec(): Double = currentPositionSecProvider()

    var currentPositionSecProvider: () -> Double = { 0.0 }

    private fun wanderTargetY(currentY: Int, top: Int, bottom: Int, h: Int): Int {
        val margin = MOVE_MARGIN
        val lo = top + margin
        val hi = bottom - margin - h
        if (hi <= lo) return currentY
        val range = kotlin.math.max(80.0, (hi - lo) * 0.3)
        val half = range / 2
        val center = currentY.coerceIn(lo + half.toInt(), hi - half.toInt())
        val delta = random.nextDouble() * range - half
        return (center + delta).toInt().coerceIn(lo, hi)
    }

    private fun <T> List<T>.pick(exclude: String? = null): T {
        val filtered = if (exclude != null) this.filter { it != exclude } else this
        return (if (filtered.isNotEmpty()) filtered else this).let {
            it[random.nextInt(it.size)]
        }
    }

    private fun <T> List<T>.randomOrNull(): T? = if (isEmpty()) null else this[random.nextInt(size)]

    companion object {
        const val P_IDLE = 0.30
        const val P_TURN = 0.40
        const val P_ACTS = 0.80
        const val MOVE_MIN_PX = 60
        const val MOVE_MAX_PX = 240
        const val MOVE_MARGIN = 20
        const val MOVE_LEAD_SEC = 2.0
        const val MOVE_TAIL_SEC = 2.0
        const val DRAG_THRESHOLD = 5

        // 抛掷物理（与原 physics.py 一致，单位 dp/s）
        const val GRAVITY = 1400.0
        const val RESTITUTION = 0.78
        const val GROUND_FRICTION = 2.5
        const val REST_VY = 40.0
        const val REST_VX = 15.0
        const val MAX_THROW_SPEED = 3600.0
        const val DEAD_ZONE_SPEED = 500.0
        const val TRAIL_KEEP_SEC = 0.15
        const val RELEASE_WINDOW_SEC = 0.12
        const val RELEASE_STALE_SEC = 0.15
        const val MIN_SPAN_SEC = 0.02
        const val SEG_MIN_DT = 0.008
        const val PEAK_WEIGHT = 0.5
        const val ACCEL_REF = 8000.0
        const val ACCEL_GAIN_MAX = 0.6

        fun softClampSpeed(speed: Double, cap: Double = MAX_THROW_SPEED): Double =
            if (speed <= 0) 0.0 else cap * (1.0 - exp(-speed / cap))

        /** 由拖拽轨迹估算松手初速（1:1 移植 estimate_release_velocity） */
        fun estimateReleaseVelocity(
            trail: List<Triple<Long, Float, Float>>, nowMs: Long
        ): Pair<Double, Double> {
            if (trail.isEmpty()) return 0.0 to 0.0
            if (nowMs - trail.last().first > (RELEASE_STALE_SEC * 1000).toLong()) return 0.0 to 0.0
            val cutoff = nowMs - (RELEASE_WINDOW_SEC * 1000).toLong()
            val win = trail.filter { it.first >= cutoff }
            if (win.size < 2) return 0.0 to 0.0
            val (t0, x0, y0) = win.first()
            val (t1, x1, y1) = win.last()
            val spanSec = (t1 - t0) / 1000.0
            if (spanSec < MIN_SPAN_SEC) return 0.0 to 0.0
            val dx = x1 - x0
            val dy = y1 - y0
            val baseVx = dx / spanSec
            val baseVy = dy / spanSec
            val baseSpeed = hypot(baseVx, baseVy)

            val segSpeeds = mutableListOf<Pair<Double, Long>>()
            var (px, py, pt) = Triple(x0, y0, t0)
            for ((t, x, y) in win.drop(1)) {
                val dt = (t - pt) / 1000.0
                if (dt >= SEG_MIN_DT) {
                    segSpeeds.add(hypot(x - px, y - py) / dt to t)
                    px = x; py = y; pt = t
                }
            }
            val peakSpeed = segSpeeds.maxOfOrNull { it.first } ?: baseSpeed

            var accel = 0.0
            if (segSpeeds.size >= 2) {
                val span = (segSpeeds.last().second - segSpeeds.first().second) / 1000.0
                accel = (segSpeeds.last().first - segSpeeds.first().first) / kotlin.math.max(span, MIN_SPAN_SEC)
            }
            val speed = (1.0 - PEAK_WEIGHT) * baseSpeed + PEAK_WEIGHT * peakSpeed
            val gain = 1.0 + kotlin.math.min(kotlin.math.max(accel, 0.0) / ACCEL_REF, 1.0) * ACCEL_GAIN_MAX
            val finalSpeed = softClampSpeed(speed * gain)
            if (baseSpeed < 1e-6) return 0.0 to finalSpeed
            return (baseVx / baseSpeed * finalSpeed) to (baseVy / baseSpeed * finalSpeed)
        }

        /** 抛掷单步积分 + 边界反弹（1:1 移植 throw_step） */
        fun throwStep(
            px: Double, py: Double, vx: Double, vy: Double, dt: Double,
            left: Int, top: Int, right: Int, bottom: Int,
        ): ThrowResult {
            var pxx = px; var pyy = py; var vxx = vx; var vyy = vy
            vyy += GRAVITY * dt
            pxx += vxx * dt
            pyy += vyy * dt
            var bounced = false
            if (pxx < left) { pxx = left.toDouble(); vxx = kotlin.math.abs(vxx) * RESTITUTION; bounced = true }
            else if (pxx > right) { pxx = right.toDouble(); vxx = -kotlin.math.abs(vxx) * RESTITUTION; bounced = true }
            if (pyy < top) { pyy = top.toDouble(); vyy = kotlin.math.abs(vyy) * RESTITUTION; bounced = true }
            else if (pyy >= bottom) {
                pyy = bottom.toDouble()
                vxx *= kotlin.math.max(0.0, 1.0 - GROUND_FRICTION * dt)
                if (kotlin.math.abs(vyy) < REST_VY) vyy = 0.0
                else vyy = -kotlin.math.abs(vyy) * RESTITUTION
                bounced = true
            }
            return ThrowResult(pxx, pyy, vxx, vyy, bounced)
        }

        fun isAtRest(
            py: Double, vx: Double, vy: Double, bottom: Int, bounced: Boolean, speed: Double
        ): Boolean {
            if (py >= bottom - 1 && kotlin.math.abs(vy) < 1 && kotlin.math.abs(vx) < REST_VX) return true
            return bounced && speed < REST_VY && kotlin.math.abs(vy) < 1
        }
    }

    data class ThrowResult(
        val px: Double, val py: Double, val vx: Double, val vy: Double, val bounced: Boolean
    )
}
