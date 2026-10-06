package io.github.aloualou56.nebularequiem.core

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * §5 INPUT — keyboard, mouse, touch twin-sticks and gamepads, merged into actions. The platform
 * layer translates Android events into key codes ("KeyW", "Space", "Pad_dash", …)
 * and pointer calls in dp, all delivered on the game thread. Edge-triggered presses are latched per
 * frame and consumed by the first simulation step, so a tap is never lost or doubled.
 */

enum class InputAction(vararg codes: String) {
    UP("KeyW", "ArrowUp"), DOWN("KeyS", "ArrowDown"), LEFT("KeyA", "ArrowLeft"), RIGHT("KeyD", "ArrowRight"),
    DASH("Space", "ShiftLeft", "ShiftRight"), BOMB("KeyE", "KeyK"), OVERDRIVE("KeyQ", "KeyL"),
    FIRE("KeyJ"), PAUSE("Escape", "KeyP"), CONFIRM("Enter", "NumpadEnter");

    val keys: Array<out String> = codes
    val pad: String = "Pad_" + name.lowercase()
    val touch: String = "Touch_" + name.lowercase()
}

enum class AimSource { AUTO, MOUSE, STICK, PAD }

class Stick {
    var id = -1
    var ox = 0.0; var oy = 0.0; var x = 0.0; var y = 0.0; var dx = 0.0; var dy = 0.0
    var takeover = false; var takeX = 0.0; var takeY = 0.0
    var lastActive = -1e9; var releasedAt = -1e9; var heldAim = false
    /** Knob offset in dp (for drawing). */
    var kx = 0.0; var ky = 0.0
}

class TouchRec {
    var id = -1; var x = 0.0; var y = 0.0; var left = false
    var resting = false; var restX = 0.0; var restY = 0.0
    var order = 0L
}

object Input {
    const val PAD_FIRE = "Pad_fire"
    const val PAD_PAUSE = "Pad_pause"
    const val TOUCH_PAUSE = "Touch_pause"
    const val MOUSE_RIGHT = "MouseRight"
    /** Stick ring radius in dp. */
    const val STICK_R = 56.0

    val down = HashSet<String>()
    val pressed = HashSet<String>()

    var mouseX = 0.0; var mouseY = 0.0; var mouseDown = false; var mouseLastMove = -1e9; var mouseInside = false
    var touchMode = false
        private set
    var onTouchModeChanged: ((Boolean) -> Unit)? = null

    val moveStick = Stick()
    val aimStick = Stick()
    private val sticks = arrayOf(moveStick, aimStick)
    private val touches = Array(10) { TouchRec() }
    private var touchOrder = 0L
    /** Which ability buttons are held (dash, bomb, overdrive). */
    val touchButtons = BooleanArray(3)
    /** A touch press predicted the ability would be usable (latch it a little longer). */
    val btnOk = BooleanArray(3)

    var padLx = 0.0; var padLy = 0.0; var padRx = 0.0; var padRy = 0.0; var padLastActive = -1e9
    var padConnected = false
    var lastAimSource = AimSource.AUTO

    fun setTouchMode(on: Boolean) {
        if (touchMode == on) return
        touchMode = on
        onTouchModeChanged?.invoke(on)
    }

    /* ───────── keyboard / gamepad buttons ───────── */

    fun keyDown(code: String, repeat: Boolean, fromPad: Boolean = false) {
        if (!repeat) pressed.add(code)
        down.add(code)
        setTouchMode(false)
        if (fromPad) padConnected = true
    }

    fun keyUp(code: String) { down.remove(code) }

    fun isDown(code: String) = down.contains(code)

    /** Window lost focus: every control was just released. */
    fun releaseAll() {
        down.clear(); mouseDown = false
        padLx = 0.0; padLy = 0.0; padRx = 0.0; padRy = 0.0
        releaseSticks()
        for (i in 0 until 3) touchButtons[i] = false
    }

    /* ───────── gamepad sticks ───────── */

    private val dz = DoubleArray(2)
    /** Radial deadzone with rescale so motion starts smoothly at the deadzone edge. */
    fun deadzone(x: Double, y: Double, d: Double, out: DoubleArray): DoubleArray {
        val m = sqrt(x * x + y * y)
        if (m < d) { out[0] = 0.0; out[1] = 0.0; return out }
        val s = min(1.0, (m - d) / (1 - d)) / m
        out[0] = x * s; out[1] = y * s
        return out
    }

    fun padAxes(lx: Double, ly: Double, rx: Double, ry: Double) {
        deadzone(lx, ly, 0.2, dz); padLx = dz[0]; padLy = dz[1]
        deadzone(rx, ry, 0.3, dz); padRx = dz[0]; padRy = dz[1]
        if (padRx != 0.0 || padRy != 0.0) { padLastActive = Env.now(); lastAimSource = AimSource.PAD }
        if (padLx != 0.0 || padLy != 0.0 || padRx != 0.0 || padRy != 0.0) { padConnected = true; setTouchMode(false) }
    }

    /* ───────── mouse ───────── */

    fun mouseMove(x: Double, y: Double) {
        mouseX = x; mouseY = y; mouseLastMove = Env.now(); mouseInside = true
        lastAimSource = AimSource.MOUSE
    }

    fun mouseButtons(primary: Boolean, secondary: Boolean, onCanvas: Boolean, wasPrimary: Boolean, wasSecondary: Boolean) {
        setTouchMode(false)
        if (primary && !wasPrimary && onCanvas) { mouseDown = true; mouseLastMove = Env.now(); lastAimSource = AimSource.MOUSE }
        if (!primary) mouseDown = false
        if (secondary && !wasSecondary && onCanvas) pressed.add(MOUSE_RIGHT)
    }

    fun mouseLeave() { mouseInside = false }

    /* ───────── touch twin-sticks ───────── */

    private fun rec(id: Int): TouchRec? { for (t in touches) if (t.id == id) return t; return null }

    /** A touch landed in the playfield (not on a button) while a run is live. */
    fun touchStart(id: Int, x: Double, y: Double, playing: Boolean) {
        setTouchMode(true)
        if (!playing) return
        val free = rec(-1) ?: return
        free.id = id; free.x = x; free.y = y; free.left = x < World.dpW * 0.5; free.resting = false; free.order = ++touchOrder
        startStick(id, x, y)
    }

    fun touchMove(id: Int, x: Double, y: Double, playing: Boolean) {
        val t = rec(id)
        if (t != null) {
            t.x = x; t.y = y
            // A contact set aside as resting that now moves on purpose is a thumb again; if its half's
            // stick is free it takes it, measured from where it rested.
            if (t.resting && hypot(t.x - t.restX, t.y - t.restY) >= 12) {
                val rx = t.restX; val ry = t.restY
                t.resting = false
                val stick = if (t.left) moveStick else aimStick
                if (playing && stick.id == -1 && (t.x < World.dpW * 0.5) == t.left) {
                    grabStick(stick, id, rx, ry, false)
                    stick.takeover = true; stick.takeX = t.x; stick.takeY = t.y
                }
            }
        }
        moveStickTo(id, x, y)
    }

    fun touchEnd(id: Int) {
        rec(id)?.let { it.id = -1 }
        endStick(id)
    }

    private fun startStick(id: Int, x: Double, y: Double) {
        val left = x < World.dpW * 0.5
        val stick = if (left) moveStick else aimStick
        if (stick.id != -1) {
            if (!stick.takeover) return
            // The displaced contact goes dormant: it can take over again only once it has moved.
            rec(stick.id)?.let { it.resting = true; it.restX = it.x; it.restY = it.y }
        }
        grabStick(stick, id, x, y, true)
        moveStickTo(id, x, y)
        Haptics.play(Haptic.TICK)
    }

    private fun grabStick(stick: Stick, id: Int, x: Double, y: Double, clampBase: Boolean) {
        val m = STICK_R + 4
        stick.id = id; stick.takeover = false
        stick.ox = if (clampBase) clamp(x, m, max(m, World.dpW - m)) else x
        stick.oy = if (clampBase) clamp(y, m, max(m, World.dpH - m)) else y
        stick.x = x; stick.y = y; stick.dx = 0.0; stick.dy = 0.0; stick.kx = 0.0; stick.ky = 0.0
        if (stick === aimStick) stick.lastActive = Env.now()
    }

    private fun moveStickTo(id: Int, x: Double, y: Double) {
        for (stick in sticks) {
            if (stick.id != id) continue
            stick.x = x; stick.y = y
            if (stick.takeover && hypot(stick.x - stick.takeX, stick.y - stick.takeY) >= 12) stick.takeover = false
            val R = STICK_R; val m = R + 4
            var dx = stick.x - stick.ox; var dy = stick.y - stick.oy
            var d = sqrt(dx * dx + dy * dy)
            if (d > R) {
                // Drag the base along behind the thumb, but never into the edge margin.
                val k = (d - R) / d
                stick.ox = clamp(stick.ox + dx * k, min(m, stick.ox), max(max(m, World.dpW - m), stick.ox))
                stick.oy = clamp(stick.oy + dy * k, min(m, stick.oy), max(max(m, World.dpH - m), stick.oy))
                dx = stick.x - stick.ox; dy = stick.y - stick.oy
                d = sqrt(dx * dx + dy * dy)
            }
            if (d > R) { dx *= R / d; dy *= R / d }
            stick.dx = dx / R; stick.dy = dy / R
            stick.kx = dx; stick.ky = dy
            if (stick === aimStick) { stick.lastActive = Env.now(); lastAimSource = AimSource.STICK }
        }
    }

    private fun endStick(id: Int) {
        for (stick in sticks) {
            if (stick.id != id) continue
            stick.releasedAt = Env.now()
            if (stick === aimStick) stick.heldAim = hypot(stick.dx, stick.dy) > 0.25
            stick.id = -1; stick.dx = 0.0; stick.dy = 0.0
            // The newest other finger still down on this half takes over from where it is.
            val left = stick === moveStick; val half = World.dpW * 0.5
            var best: TouchRec? = null
            for (t in touches) {
                if (t.id == -1 || t.id == id) continue
                if (t.resting && hypot(t.x - t.restX, t.y - t.restY) < 12) continue
                if (t.left == left && (t.x < half) == left && (best == null || t.order > best.order)) best = t
            }
            if (best != null) {
                grabStick(stick, best.id, best.x, best.y, false)
                stick.takeover = true; stick.takeX = best.x; stick.takeY = best.y
            }
        }
    }

    fun releaseSticks() {
        for (t in touches) t.id = -1
        for (s in sticks) {
            if (s.id != -1) s.releasedAt = Env.now()
            s.id = -1; s.dx = 0.0; s.dy = 0.0
        }
    }

    /** Ability buttons (0 dash, 1 bomb, 2 overdrive). */
    fun buttonDown(key: Int, usable: Boolean) {
        setTouchMode(true)
        touchButtons[key] = true
        pressed.add(BUTTON_CODES[key])
        btnOk[key] = usable
        Haptics.play(if (usable) Haptic.PRESS else Haptic.DENY)
    }
    fun buttonUp(key: Int) { touchButtons[key] = false }
    fun pauseTapped() { pressed.add(TOUCH_PAUSE); Haptics.play(Haptic.PRESS) }

    private val BUTTON_CODES = arrayOf(InputAction.DASH.touch, InputAction.BOMB.touch, InputAction.OVERDRIVE.touch)

    /** Stick response: radial dead zone, then a gentle curve (fine control near centre, full speed at the ring). */
    fun stickCurve(x: Double, y: Double, out: Vec2, dz: Double = 0.1, gamma: Double = 1.25): Vec2 {
        val m = sqrt(x * x + y * y)
        if (m <= dz) { out.x = 0.0; out.y = 0.0; return out }
        val k = min(1.0, (m - dz) / (1 - dz)).pow(gamma) / m
        out.x = x * k; out.y = y * k
        return out
    }
    private val curve = Vec2()

    fun any(codes: Array<out String>): Boolean { for (c in codes) if (down.contains(c)) return true; return false }

    fun hit(a: InputAction): Boolean {
        for (c in a.keys) if (pressed.contains(c)) return true
        if (pressed.contains(a.pad) || pressed.contains(a.touch)) return true
        if (a == InputAction.DASH && pressed.contains(MOUSE_RIGHT)) return true
        return false
    }

    fun held(a: InputAction): Boolean = any(a.keys) || down.contains(a.pad)

    /** Writes the movement intent (|v| ≤ 1) into out. */
    fun moveVector(out: Vec2): Vec2 {
        var x = 0.0; var y = 0.0
        if (held(InputAction.LEFT)) x -= 1.0
        if (held(InputAction.RIGHT)) x += 1.0
        if (held(InputAction.UP)) y -= 1.0
        if (held(InputAction.DOWN)) y += 1.0
        val c = stickCurve(moveStick.dx, moveStick.dy, curve)
        x += padLx + c.x; y += padLy + c.y
        val m = sqrt(x * x + y * y)
        if (m > 1) { x /= m; y /= m }
        out.x = x; out.y = y
        return out
    }

    fun endFrame() { pressed.clear() }
}
