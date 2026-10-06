package io.github.aloualou56.nebularequiem.platform

import android.annotation.SuppressLint
import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import io.github.aloualou56.nebularequiem.R
import kotlin.math.abs
import kotlin.math.max

/**
 * The game's only view: a SurfaceView the game thread draws into with a hardware canvas, and the
 * entry point for touch, mouse/stylus, wheel and gamepad-axis input. Events are converted to dp
 * (the original's CSS pixels) and queued for the game thread.
 */
@SuppressLint("ViewConstructor")
class GameView(context: Context, private val loop: GameLoop) : SurfaceView(context), SurfaceHolder.Callback {
    private val q get() = loop.input
    private val density get() = resources.displayMetrics.density

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        isHapticFeedbackEnabled = false
        contentDescription = context.getString(R.string.game_surface_description)
    }

    override fun surfaceCreated(h: SurfaceHolder) = loop.surfaceCreated(h)
    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) = loop.surfaceChanged(h, width, height)
    override fun surfaceDestroyed(h: SurfaceHolder) = loop.surfaceDestroyed()

    /* ─────────────────────────── touch ─────────────────────────── */

    /** Whether the current gesture is a mouse's or pen's, decided when it starts so no pointer loses its UP. */
    private var mouseGesture = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) mouseGesture = isMouseLike(e, 0)
        if (mouseGesture) return mouse(e)
        val d = density
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                q.post(InputQueue.DOWN, e.getPointerId(i), e.getX(i) / d, e.getY(i) / d, flag = false, t = e.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                // Android batches the samples taken between frames into one event: replay them all,
                // with their own times, so scroll flings measure the finger's real speed.
                for (h in 0 until e.historySize) {
                    val ht = e.getHistoricalEventTime(h)
                    for (i in 0 until e.pointerCount) q.post(InputQueue.MOVE, e.getPointerId(i), e.getHistoricalX(i, h) / d, e.getHistoricalY(i, h) / d, t = ht)
                }
                for (i in 0 until e.pointerCount) q.post(InputQueue.MOVE, e.getPointerId(i), e.getX(i) / d, e.getY(i) / d, t = e.eventTime)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                // FLAG_CANCELED: the system decided this contact was accidental (a palm).
                if (e.flags and MotionEvent.FLAG_CANCELED != 0) q.post(InputQueue.CANCEL, e.getPointerId(i))
                else q.post(InputQueue.UP, e.getPointerId(i), e.getX(i) / d, e.getY(i) / d, t = e.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> q.post(InputQueue.CANCEL_ALL)
        }
        return true
    }

    /* ─────────────────────────── mouse & stylus (the original's 'mouse' / 'pen') ─────────────────────────── */

    private var primary = false
    private var secondary = false
    private var penContact = false

    private fun isMouseLike(e: MotionEvent, i: Int): Boolean {
        val t = e.getToolType(i)
        return t == MotionEvent.TOOL_TYPE_MOUSE || t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER ||
            e.isFromSource(InputDevice.SOURCE_MOUSE)
    }

    /** Mirrors the original's e.buttons bitmask sync: chorded presses and out-of-order releases. */
    private fun mouse(e: MotionEvent): Boolean {
        val d = density
        val x = e.x / d; val y = e.y / d
        val action = e.actionMasked
        val ended = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        val stylus = e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS || e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
        val b = e.buttonState
        // A pen's tip contact counts as the primary button and its barrel button as the secondary.
        if (stylus) { if (action == MotionEvent.ACTION_DOWN) penContact = true; if (ended) penContact = false }
        val p = !ended && ((b and MotionEvent.BUTTON_PRIMARY) != 0 || (stylus && penContact))
        val s = !ended && ((b and MotionEvent.BUTTON_SECONDARY) != 0 || (b and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0)
        val t = e.eventTime
        q.post(InputQueue.HOVER, 0, x, y, t = t)
        if (p != primary || s != secondary) q.post(InputQueue.MOUSE_BUTTONS, flag = p, flag2 = s, t = t)
        // flag2 marks a pen: it scrolls and stops flings like a finger
        if (p && !primary) q.post(InputQueue.DOWN, MOUSE_ID, x, y, flag = true, flag2 = stylus, t = t)
        else if (p) {
            for (h in 0 until e.historySize) q.post(InputQueue.MOVE, MOUSE_ID, e.getHistoricalX(h) / d, e.getHistoricalY(h) / d, t = e.getHistoricalEventTime(h))
            q.post(InputQueue.MOVE, MOUSE_ID, x, y, t = t)
        }
        else if (primary) q.post(if (action == MotionEvent.ACTION_CANCEL) InputQueue.CANCEL else InputQueue.UP, MOUSE_ID, x, y, t = t)
        primary = p; secondary = s
        return true
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        val d = density
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> q.post(InputQueue.HOVER, 0, e.x / d, e.y / d)
            MotionEvent.ACTION_HOVER_EXIT -> q.post(InputQueue.MOUSE_LEAVE)
        }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            when (e.actionMasked) {
                MotionEvent.ACTION_SCROLL -> {
                    val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    if (v != 0f) q.post(InputQueue.WHEEL, y = -v * WHEEL_DP)
                    return true
                }
                // Consumed so a secondary click is never turned into a system Back.
                MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> return mouse(e)
            }
        }
        return super.onGenericMotionEvent(e)
    }

    /* ─────────────────────────── gamepad axes ─────────────────────────── */

    private var fireDown = false
    private var hatX = 0; private var hatY = 0

    /** Joystick motion (sticks, analog triggers, hat D-pad). Returns true when handled. */
    fun joystick(e: MotionEvent): Boolean {
        if (!e.isFromSource(InputDevice.SOURCE_JOYSTICK) || e.actionMasked != MotionEvent.ACTION_MOVE) return false
        val dev = e.device
        val lx = e.getAxisValue(MotionEvent.AXIS_X); val ly = e.getAxisValue(MotionEvent.AXIS_Y)
        // Right stick: most pads report Z/RZ; some (and many Bluetooth HID pads) use RX/RY.
        val useZ = dev == null || dev.getMotionRange(MotionEvent.AXIS_Z, e.source) != null
        val rx = e.getAxisValue(if (useZ) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX)
        val ry = e.getAxisValue(if (useZ) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY)
        q.post(InputQueue.PAD_AXES, 0, lx, ly, rx, ry)

        // R2 → fire, with a little hysteresis so a resting finger doesn't chatter
        val trig = max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
        val fire = if (fireDown) trig > 0.15f else trig > 0.3f
        if (fire != fireDown) { fireDown = fire; q.post(if (fire) InputQueue.KEY_DOWN else InputQueue.KEY_UP, code = "Pad_fire", flag2 = true) }

        // hat switch → D-pad buttons
        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X).let { if (abs(it) > 0.5f) (if (it > 0) 1 else -1) else 0 }
        val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y).let { if (abs(it) > 0.5f) (if (it > 0) 1 else -1) else 0 }
        if (hx != hatX) { hatButton(hatX, "Pad_left", "Pad_right", false); hatButton(hx, "Pad_left", "Pad_right", true); hatX = hx }
        if (hy != hatY) { hatButton(hatY, "Pad_up", "Pad_down", false); hatButton(hy, "Pad_up", "Pad_down", true); hatY = hy }
        return true
    }

    private fun hatButton(dir: Int, neg: String, pos: String, down: Boolean) {
        if (dir == 0) return
        q.post(if (down) InputQueue.KEY_DOWN else InputQueue.KEY_UP, code = if (dir < 0) neg else pos, flag2 = true)
    }

    /** Lift every held stick, trigger and button (window focus lost). */
    fun resetHeld() { primary = false; secondary = false; penContact = false; fireDown = false; hatX = 0; hatY = 0 }

    companion object {
        const val MOUSE_ID = 1000
        /** One wheel notch scrolls about as far as a browser's (100 CSS px ≈ 3 lines). */
        private const val WHEEL_DP = 80f
    }
}
