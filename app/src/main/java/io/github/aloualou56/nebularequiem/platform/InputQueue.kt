package io.github.aloualou56.nebularequiem.platform

import android.os.SystemClock

/**
 * Input events handed from the UI thread to the game thread. Android delivers input on the main
 * thread while the game state lives on the game thread, so events are copied into pooled records
 * here and replayed, in order, at the start of the next frame. No allocation after warm-up.
 */
class InputQueue {
    class Ev {
        var type = 0
        var id = 0
        var x = 0f; var y = 0f; var z = 0f; var w = 0f
        var flag = false; var flag2 = false
        var code: String? = null
        /** When it happened (SystemClock.uptimeMillis, as MotionEvent.getEventTime). */
        var t = 0L
    }

    private val lock = Any()
    private var pending = ArrayList<Ev>(64)
    private var draining = ArrayList<Ev>(64)
    private val pool = ArrayList<Ev>(64)

    private fun obtain(type: Int): Ev {
        val e = if (pool.isEmpty()) Ev() else pool.removeAt(pool.size - 1)
        e.type = type; e.id = 0; e.x = 0f; e.y = 0f; e.z = 0f; e.w = 0f; e.flag = false; e.flag2 = false; e.code = null; e.t = 0L
        return e
    }

    /** UI thread: append an event. */
    fun post(type: Int, id: Int = 0, x: Float = 0f, y: Float = 0f, z: Float = 0f, w: Float = 0f,
             flag: Boolean = false, flag2: Boolean = false, code: String? = null, t: Long = SystemClock.uptimeMillis()) {
        synchronized(lock) {
            if (pending.size >= MAX_PENDING) {
                // The game thread is stalled: keep discrete events, drop the oldest continuous ones.
                val i = pending.indexOfFirst { it.type == MOVE || it.type == HOVER || it.type == PAD_AXES }
                if (i >= 0) pool.add(pending.removeAt(i)) else return
            }
            val e = obtain(type)
            e.id = id; e.x = x; e.y = y; e.z = z; e.w = w; e.flag = flag; e.flag2 = flag2; e.code = code; e.t = t
            pending.add(e)
        }
    }

    /** Game thread: replay every queued event in order. */
    inline fun drain(handle: (Ev) -> Unit) {
        val list = swap()
        try { for (i in 0 until list.size) handle(list[i]) } finally { recycle(list) }
    }

    fun swap(): ArrayList<Ev> {
        synchronized(lock) {
            val t = pending; pending = draining; draining = t
            return t
        }
    }

    fun recycle(list: ArrayList<Ev>) {
        synchronized(lock) {
            for (e in list) { e.code = null; pool.add(e) }
            list.clear()
        }
    }

    companion object {
        const val DOWN = 1          // pointer down (id, x, y dp, flag = mouse)
        const val MOVE = 2          // pointer move (id, x, y)
        const val UP = 3            // pointer up (id, x, y)
        const val CANCEL = 4        // pointer cancelled (id)
        const val CANCEL_ALL = 5    // gesture cancelled: lift everything
        const val HOVER = 6         // mouse hover (x, y)
        const val MOUSE_LEAVE = 7
        const val MOUSE_BUTTONS = 8 // flag = primary, flag2 = secondary
        const val WHEEL = 9         // y = scroll in dp
        const val KEY_DOWN = 10     // code, flag = repeat, flag2 = from gamepad
        const val KEY_UP = 11       // code
        const val PAD_AXES = 12     // x, y (left stick), z, w (right stick)
        const val FOCUS_LOST = 13
        const val PAD_LINKED = 14
        private const val MAX_PENDING = 512
    }
}
