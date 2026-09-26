package com.headless.android.input

import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import com.headless.android.BinderCodes
import com.headless.android.HeadlessLog
import com.headless.android.privilege.PrivilegeBackend

/**
 * Direct Binder-level input injection via [android.hardware.input.IInputManager.injectInputEvent] (#22).
 *
 * Replaces per-action shell process spawning (`input -d <displayId> ...`) which cost
 * ~1.3-2.5s per action in benchmarks. Direct Binder injection executes in <10ms.
 */
class BinderInputInjector(
    private val privilegeBackend: PrivilegeBackend,
    private val displayId: Int
) {
    companion object {
        private const val OP = "BinderInputInjector"
        private const val DESCRIPTOR = "android.hardware.input.IInputManager"
        private const val MODE_WAIT_FOR_FINISH = 2
        private const val INVALID_UID = -1

        private val setDisplayIdMethod by lazy {
            try {
                InputEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType).apply {
                    isAccessible = true
                }
            } catch (e: Throwable) {
                HeadlessLog.w(OP, "InputEvent.setDisplayId not found via reflection", e)
                null
            }
        }
    }

    private val inputBinder: IBinder by lazy {
        privilegeBackend.getSystemServiceBinder("input")
    }

    private val transactionCode: Int by lazy {
        BinderCodes.inputInject()
    }

    /**
     * Injects an [InputEvent] directly through the input subsystem targeting [displayId].
     * Returns true if injection succeeded and was dispatched.
     */
    fun inject(event: InputEvent, mode: Int = MODE_WAIT_FOR_FINISH): Boolean {
        try {
            setDisplayIdMethod?.invoke(event, displayId)
        } catch (_: Throwable) {}

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(1) // non-null InputEvent token
            event.writeToParcel(data, 0)
            data.writeInt(mode)
            val transactSuccess = inputBinder.transact(transactionCode, data, reply, 0)
            if (!transactSuccess) {
                HeadlessLog.w(OP, "Binder transaction returned false for $event (code=$transactionCode)")
                return false
            }
            reply.readException()
            val result = reply.readInt() != 0
            HeadlessLog.i(OP, "inject result=$result for code=$transactionCode")
            return result
        } catch (e: Throwable) {
            HeadlessLog.w(OP, "Direct input injection failed: ${e.javaClass.simpleName} - ${e.message}", e)
            return false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun tap(x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_DOWN, x, y, 0
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        val up = MotionEvent.obtain(
            now, now + 50, MotionEvent.ACTION_UP, x, y, 0
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }

        try {
            val downOk = inject(down)
            val upOk = inject(up)
            return downOk && upOk
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val start = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            start, start, MotionEvent.ACTION_DOWN, x1, y1, 0
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        if (!inject(down)) {
            down.recycle()
            return false
        }

        val steps = (durationMs / 16).coerceAtLeast(2).toInt()
        for (i in 1..steps) {
            val progress = i.toFloat() / steps
            val currentX = x1 + (x2 - x1) * progress
            val currentY = y1 + (y2 - y1) * progress
            val moveTime = start + (durationMs * progress).toLong()
            val move = MotionEvent.obtain(
                start, moveTime, MotionEvent.ACTION_MOVE, currentX, currentY, 0
            ).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            inject(move, mode = 0) // ASYNC for smooth move interpolation
            move.recycle()
            try { Thread.sleep(16) } catch (_: InterruptedException) {}
        }

        val end = start + durationMs
        val up = MotionEvent.obtain(
            start, end, MotionEvent.ACTION_UP, x2, y2, 0
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        val result = inject(up)
        down.recycle()
        up.recycle()
        return result
    }

    fun pressKey(keyCode: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        val up = KeyEvent(now, now + 20, KeyEvent.ACTION_UP, keyCode, 0)
        return inject(down) && inject(up)
    }
}
