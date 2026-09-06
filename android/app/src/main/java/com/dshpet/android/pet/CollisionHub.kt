package com.dshpet.android.pet

import android.os.Handler
import android.os.Looper
import com.dshpet.android.util.AppLog
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 多开桌宠碰撞物理（上游 collision.py 的 Android 版）。
 *
 * 桌面端用 QLocalServer 跨进程 IPC；Android 多服务实例同进程，
 * 直接单例共享坐标/速度——无需 IPC。
 *
 * - 33ms tick：圆-圆碰撞检测（AABB broad-phase）+ 冲量求解
 *   + 位置分离（逆质量分摊、60% 重叠上限）
 * - 恢复系数 0.82 / 切向摩擦 0.08 / 质量按窗口面积加权 clamp 0.5..2.5
 * - 拖拽/锁定 = 无限质量；每 tick 有 9000px/s 冲量上限
 * - 预测反弹：高速穿越由 33ms tick 内的扫掠近似覆盖（步长足够小）
 */
object CollisionHub {

    /** 参与碰撞的实例状态 */
    class Member(
        val id: Int,
        @Volatile var x: Double,        // 窗口左上 x（px）
        @Volatile var y: Double,
        @Volatile var w: Int,
        @Volatile var h: Int,
        @Volatile var vx: Double = 0.0, // px/s
        @Volatile var vy: Double = 0.0,
        @Volatile var infiniteMass: Boolean = false,  // 拖拽/锁定中
        @Volatile var active: Boolean = true,
    ) {
        val radiusX: Double get() = w * 0.32   // 鱼身内容约占窗口 65% 宽
        val radiusY: Double get() = h * 0.40
        val mass: Double
            get() = if (infiniteMass) Double.POSITIVE_INFINITY
            else (w * h / (640.0 * 360.0)).coerceIn(0.5, 2.5)
        val centerX: Double get() = x + w / 2.0
        val centerY: Double get() = y + h / 2.0
    }

    private const val TICK_MS = 33L
    private const val RESTITUTION = 0.82
    private const val FRICTION = 0.08
    private const val IMPULSE_CAP = 9000.0
    private const val MIN_APPROACH_SPEED = 80.0
    private const val MAX_SEPARATION_RATIO = 0.6
    private const val MIN_SEPARATION = 1.0
    private const val MAX_SEPARATION = 12.0
    private const val SEPARATION_SLOP = 0.5

    private val members = mutableMapOf<Int, Member>()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var enabled = true
    @Volatile private var ticking = false

    /** 碰撞回调：碰撞力度超过阈值时（碰撞音效门槛） */
    var onImpact: ((impactSpeed: Double) -> Unit)? = null

    /** 成员被撞后把新位置写回窗口（按实例 id 注册，服务侧各管各的） */
    private val movedCallbacks = mutableMapOf<Int, (Member) -> Unit>()

    @Synchronized
    fun setOnMoved(id: Int, cb: ((Member) -> Unit)?) {
        if (cb != null) movedCallbacks[id] = cb else movedCallbacks.remove(id)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (members.isEmpty()) {
                ticking = false
                return
            }
            try {
                tick()
            } catch (e: Throwable) {
                AppLog.log("COLLISION", "tick 异常: ${e.message}")
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    @Synchronized
    fun register(m: Member) {
        members[m.id] = m
        ensureTicking()
    }

    @Synchronized
    fun unregister(id: Int) {
        members.remove(id)
        movedCallbacks.remove(id)
    }

    fun setEnabled(on: Boolean) {
        enabled = on
    }

    fun activeIds(): List<Int> = synchronized(members) { members.keys.toList() }

    private fun ensureTicking() {
        if (!ticking) {
            ticking = true
            handler.post(ticker)
        }
    }

    /** @return true=发生有效碰撞 */
    private fun tick(): Boolean {
        if (!enabled) return false
        val list = synchronized(members) { members.values.filter { it.active } }
        var collided = false
        var maxImpact = 0.0

        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                val a = list[i]; val b = list[j]
                val r = resolvePair(a, b) ?: continue
                collided = true
                maxImpact = max(maxImpact, r)
            }
        }
        if (collided && maxImpact > 300) {
            // 碰撞音效门槛（上游同款）
            onImpact?.invoke(maxImpact)
        }
        if (collided) {
            // 位置/速度变化写回（各服务把成员位置应用到窗口）
            val cbs = synchronized(movedCallbacks) { movedCallbacks.toMap() }
            list.forEach { m -> cbs[m.id]?.invoke(m) }
        }
        return collided
    }

    /**
     * 椭圆碰撞（简化为两轴半径的缩放圆）：narrow-phase 冲量 + 位置分离。
     * @return 碰撞冲击速度；null=未碰撞
     */
    private fun resolvePair(a: Member, b: Member): Double? {
        val dx = b.centerX - a.centerX
        val dy = b.centerY - a.centerY
        // 相对接近速度（法线分量，正=靠近）
        val rvx = b.vx - a.vx
        val rvy = b.vy - a.vy
        val approach = -(rvx * dx + rvy * dy) / max(hypot(dx, dy), 1e-6)

        // 椭圆近似：把 dx/dy 归一到「单位半径和」空间
        val rx = a.radiusX + b.radiusX
        val ry = a.radiusY + b.radiusY
        val nx = dx / max(rx, 1e-6)
        val ny = dy / max(ry, 1e-6)
        val distScaled = hypot(nx, ny)
        if (distScaled >= 1.0) return null  // 未重叠

        // 真实法线（单位向量）
        val dist = max(hypot(dx, dy), 1e-6)
        val ux = dx / dist
        val uy = dy / dist

        val aInf = a.infiniteMass
        val bInf = b.infiniteMass
        if (aInf && bInf) {
            // 双无限质量：只做位置分离（对半）
            separate(a, b, ux, uy, 0.5)
            return approach
        }

        // 冲量求解
        if (approach > MIN_APPROACH_SPEED) {
            val invA = if (aInf) 0.0 else 1.0 / a.mass
            val invB = if (bInf) 0.0 else 1.0 / b.mass
            val invSum = invA + invB
            if (invSum > 0) {
                var j = -(1.0 + RESTITUTION) * (rvx * ux + rvy * uy) / invSum
                // 冲量上限（每质量 9000px/s）
                j = j.coerceIn(-IMPULSE_CAP / invSum, IMPULSE_CAP / invSum)
                val jx = j * ux
                val jy = j * uy
                if (!aInf) {
                    a.vx -= jx * invA; a.vy -= jy * invA
                    a.x -= jx * invA * (TICK_MS / 1000.0) * 0.5
                    a.y -= jy * invA * (TICK_MS / 1000.0) * 0.5
                }
                if (!bInf) {
                    b.vx += jx * invB; b.vy += jy * invB
                    b.x += jx * invB * (TICK_MS / 1000.0) * 0.5
                    b.y += jy * invB * (TICK_MS / 1000.0) * 0.5
                }
            }
        }
        // 位置分离（逆质量分摊）
        val invA2 = if (aInf) 0.0 else 1.0 / a.mass
        val invB2 = if (bInf) 0.0 else 1.0 / b.mass
        val share = if (invA2 + invB2 > 0) invB2 / (invA2 + invB2) else 0.5
        separate(a, b, ux, uy, share)
        return approach
    }

    /** 位置分离：每次最多 60% 重叠，min 1px / max 12px */
    private fun separate(a: Member, b: Member, ux: Double, uy: Double, bShare: Double) {
        val rx = a.radiusX + b.radiusX
        val ry = a.radiusY + b.radiusY
        val dx = b.centerX - a.centerX
        val dy = b.centerY - a.centerY
        val nx = dx / max(rx, 1e-6)
        val ny = dy / max(ry, 1e-6)
        val overlap = (1.0 - hypot(nx, ny)) * min(hypot(dx, dy), min(rx, ry))
        if (overlap <= SEPARATION_SLOP) return
        val sep = overlap.coerceIn(MIN_SEPARATION, MAX_SEPARATION) * MAX_SEPARATION_RATIO
        if (a.infiniteMass && b.infiniteMass) {
            a.x -= ux * sep / 2; a.y -= uy * sep / 2
            b.x += ux * sep / 2; b.y += uy * sep / 2
        } else if (a.infiniteMass) {
            b.x += ux * sep; b.y += uy * sep
        } else if (b.infiniteMass) {
            a.x -= ux * sep; a.y -= uy * sep
        } else {
            a.x -= ux * sep * (1 - bShare); a.y -= uy * sep * (1 - bShare)
            b.x += ux * sep * bShare; b.y += uy * sep * bShare
        }
    }
}
