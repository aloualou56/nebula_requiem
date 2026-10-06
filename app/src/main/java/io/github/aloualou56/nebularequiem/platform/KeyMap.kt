package io.github.aloualou56.nebularequiem.platform

import android.view.InputDevice
import android.view.KeyEvent

/**
 * Translates Android key events into the key codes the game logic uses (names like "KeyW" and
 * "Space", plus "Pad_*" names for the standard gamepad buttons).
 */
object KeyMap {
    /** Physical keyboard code ("KeyW", "Space", "ArrowUp", …), or null for keys the game ignores. */
    fun keyboard(keyCode: Int): String? = when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> "Key" + ('A' + (keyCode - KeyEvent.KEYCODE_A))
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> "Digit" + (keyCode - KeyEvent.KEYCODE_0)
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> "Numpad" + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)
        KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
        KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
        KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
        KeyEvent.KEYCODE_SPACE -> "Space"
        KeyEvent.KEYCODE_SHIFT_LEFT -> "ShiftLeft"
        KeyEvent.KEYCODE_SHIFT_RIGHT -> "ShiftRight"
        KeyEvent.KEYCODE_CTRL_LEFT -> "ControlLeft"
        KeyEvent.KEYCODE_CTRL_RIGHT -> "ControlRight"
        KeyEvent.KEYCODE_ALT_LEFT -> "AltLeft"
        KeyEvent.KEYCODE_ALT_RIGHT -> "AltRight"
        KeyEvent.KEYCODE_ESCAPE -> "Escape"
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> "Enter"
        KeyEvent.KEYCODE_NUMPAD_ENTER -> "NumpadEnter"
        KeyEvent.KEYCODE_TAB -> "Tab"
        KeyEvent.KEYCODE_DEL -> "Backspace"
        else -> null
    }

    /** Standard-mapping gamepad button → the game's pad action names (A, B, X, Start, R2, D-pad). */
    fun pad(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> "Pad_dash"
        KeyEvent.KEYCODE_BUTTON_B -> "Pad_bomb"
        KeyEvent.KEYCODE_BUTTON_X -> "Pad_overdrive"
        KeyEvent.KEYCODE_BUTTON_START -> "Pad_pause"
        KeyEvent.KEYCODE_BUTTON_R2 -> "Pad_fire"
        KeyEvent.KEYCODE_DPAD_UP -> "Pad_up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "Pad_down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "Pad_left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "Pad_right"
        else -> null
    }

    /** True for buttons only a gamepad has (A/B/X/Y, shoulders, Start/Select, thumbs). */
    fun isGamepadButton(keyCode: Int) = KeyEvent.isGamepadButton(keyCode)

    /** Whether an event came from a game controller rather than a keyboard. */
    fun fromGamepad(e: KeyEvent): Boolean {
        val s = e.source
        if (s and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) return true
        if (s and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) return true
        // D-pad keys from a controller (not a keyboard's arrow keys)
        if (s and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD) {
            val dev = e.device ?: return false
            return dev.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC
        }
        return false
    }
}
