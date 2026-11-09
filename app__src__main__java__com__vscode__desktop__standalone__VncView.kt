package com.vscode.desktop.standalone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.SparseIntArray
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Android key codes -> X11 keysyms. Kept free of any Android object so it can be unit-tested on a normal JVM. */
object VncKeys {
    const val BACKSPACE = 0xFF08
    const val TAB = 0xFF09
    const val RETURN = 0xFF0D
    const val ESCAPE = 0xFF1B
    const val DELETE = 0xFFFF
    const val HOME = 0xFF50
    const val LEFT = 0xFF51
    const val UP = 0xFF52
    const val RIGHT = 0xFF53
    const val DOWN = 0xFF54
    const val PAGE_UP = 0xFF55
    const val PAGE_DOWN = 0xFF56
    const val END = 0xFF57
    const val INSERT = 0xFF63
    const val F1 = 0xFFBE
    const val SHIFT_L = 0xFFE1
    const val SHIFT_R = 0xFFE2
    const val CONTROL_L = 0xFFE3
    const val CONTROL_R = 0xFFE4
    const val CAPS_LOCK = 0xFFE5
    const val ALT_L = 0xFFE9
    const val ALT_R = 0xFFEA
    const val SUPER_L = 0xFFEB
    const val SUPER_R = 0xFFEC

    /** Keys that do not produce a character (0 = not such a key: the caller asks the key's character instead). */
    fun keysymForKeyCode(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> RETURN
        KeyEvent.KEYCODE_DEL -> BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> DELETE
        KeyEvent.KEYCODE_TAB -> TAB
        KeyEvent.KEYCODE_ESCAPE -> ESCAPE
        KeyEvent.KEYCODE_DPAD_UP -> UP
        KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
        KeyEvent.KEYCODE_MOVE_HOME -> HOME
        KeyEvent.KEYCODE_MOVE_END -> END
        KeyEvent.KEYCODE_PAGE_UP -> PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> PAGE_DOWN
        KeyEvent.KEYCODE_INSERT -> INSERT
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> F1 + (code - KeyEvent.KEYCODE_F1)
        KeyEvent.KEYCODE_SHIFT_LEFT -> SHIFT_L
        KeyEvent.KEYCODE_SHIFT_RIGHT -> SHIFT_R
        KeyEvent.KEYCODE_CTRL_LEFT -> CONTROL_L
        KeyEvent.KEYCODE_CTRL_RIGHT -> CONTROL_R
        KeyEvent.KEYCODE_ALT_LEFT -> ALT_L
        KeyEvent.KEYCODE_ALT_RIGHT -> ALT_R
        KeyEvent.KEYCODE_META_LEFT -> SUPER_L
        KeyEvent.KEYCODE_META_RIGHT -> SUPER_R
        KeyEvent.KEYCODE_CAPS_LOCK -> CAPS_LOCK
        KeyEvent.KEYCODE_NUM_LOCK -> 0xFF7F
        KeyEvent.KEYCODE_SCROLL_LOCK -> 0xFF14
        KeyEvent.KEYCODE_BREAK -> 0xFF13
        KeyEvent.KEYCODE_SYSRQ -> 0xFF15
        else -> 0
    }

    fun isModifier(sym: Int): Boolean = sym in SHIFT_L..SUPER_R // Shift, Control, Caps Lock, Meta, Alt, Super

    /** Unicode code point -> keysym (Latin-1 maps to itself, everything else is 0x01000000 + code point). */
    fun keysymForCodePoint(cp: Int): Int = when {
        cp == '\n'.code || cp == '\r'.code -> RETURN
        cp == '\t'.code -> TAB
        cp < 0x20 || cp == 0x7F || cp in 0x80..0x9F -> 0
        cp < 0x100 -> cp
        else -> 0x01000000 + cp
    }
}

/**
 * The native Mode 1 screen: draws the virtual desktop that [RfbClient] receives from Xtigervnc and turns touch, mouse,
 * stylus and keyboard input into VNC input. No WebView, no noVNC, no websockify.
 *
 * Touch (finger):
 *   tap = left click · touch-and-hold, then drag = select / drag (left button held) · drag = scroll (with momentum)
 *   two-finger tap = right click · two-finger drag = scroll
 * Mouse / touchpad / S Pen: pointer, buttons (S Pen button = right click) and the wheel work as on a computer.
 * Keyboard: hardware keys go through unchanged (Ctrl, Alt, Shift, Win, arrows, F1-F12 ...); the on-screen keyboard types
 * text, and the optional key row adds Esc / Tab / Ctrl / Alt / arrows ... which a phone keyboard does not have.
 * If the connection drops (e.g. the X server restarts) it reconnects by itself.
 */
@SuppressLint("ClickableViewAccessibility")
class VncView(context: Context) : View(context) {

    // ---- hooks for the screen that hosts this view (all called on the main thread) ----
    /** Text copied inside VS Code. */
    var onDesktopClipboard: ((String) -> Unit)? = null

    /** The picture connection is up: a good moment to hand over the Android clipboard. */
    var onSessionReady: (() -> Unit)? = null

    /** A paste shortcut is about to be sent: refresh the desktop's clipboard from Android's first. */
    var onBeforePaste: (() -> Unit)? = null

    /** The Ctrl / Alt / Shift / Win latches changed (to repaint their buttons). */
    var onLatchChanged: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // ---------------------------------------------------------------------------------------------
    // Connection
    // ---------------------------------------------------------------------------------------------
    private var host = "127.0.0.1"
    private var port = 5901
    private var wantRun = false
    private var paused = false
    private var gaveUp = false
    private var generation = 0
    private var client: RfbClient? = null
    private var failCount = 0
    private var everConnected = false
    private var connectedAt = 0L
    private var statusLine = "Starting the desktop…"

    @Volatile
    private var frameReady = false

    private val retryRunnable = Runnable { connectNow() }

    fun start(host: String = "127.0.0.1", port: Int = 5901) {
        this.host = host
        this.port = port
        if (wantRun && client != null) return
        wantRun = true
        gaveUp = false
        failCount = 0
        if (!paused) connectNow()
    }

    fun stop() {
        wantRun = false
        halt()
        statusLine = "Starting the desktop…"
        everConnected = false
    }

    /** Drops the connection (nothing else) and ignores whatever the old connection still reports. */
    private fun halt() {
        generation++
        main.removeCallbacks(retryRunnable)
        stopFling()
        main.removeCallbacks(longPressRunnable)
        resetInputState()
        val c = client
        client = null
        frameReady = false
        c?.close()
        invalidate()
    }

    private fun connectNow() {
        if (!wantRun || paused) return
        val gen = ++generation
        frameReady = false
        statusLine = if (everConnected) "Reconnecting to the desktop…" else "Connecting to the desktop…"
        val c = RfbClient(host, port, Session(gen))
        client = c
        c.start()
        invalidate()
    }

    private fun handleClosed(error: String?) {
        client = null
        frameReady = false
        resetInputState()
        if (!wantRun || paused) return
        if (SystemClock.elapsedRealtime() - connectedAt > 5000) failCount = 0
        failCount++
        val limit = if (everConnected) 90 else 150
        if (failCount > limit) {
            gaveUp = true
            statusLine = "Could not reach the desktop.\nTap the screen to try again.\n${error ?: ""}"
            invalidate()
            return
        }
        statusLine = (if (everConnected) "Reconnecting to the desktop…" else "Connecting to the desktop…") +
            (if (failCount > 6 && !error.isNullOrEmpty()) "\n$error" else "")
        invalidate()
        main.postDelayed(retryRunnable, if (failCount < 5) 300L else 1000L)
    }

    private fun retryIfGaveUp() {
        if (gaveUp && wantRun) {
            gaveUp = false
            failCount = 0
            connectNow()
        }
    }

    /** Called by the picture connection (reader thread). Everything is handed to the main thread. */
    private inner class Session(private val gen: Int) : RfbClient.Listener {
        private fun alive() = gen == generation

        override fun onConnected(width: Int, height: Int, name: String) {
            if (!alive()) return
            main.post {
                if (!alive()) return@post
                everConnected = true
                connectedAt = SystemClock.elapsedRealtime()
                statusLine = "Loading the picture…"
                updateMatrix()
                invalidate()
                onSessionReady?.invoke()
            }
        }

        override fun onResized(width: Int, height: Int) {
            if (!alive()) return
            main.post {
                if (alive()) {
                    focusFbY = height / 2f
                    updateMatrix()
                    invalidate()
                }
            }
        }

        override fun onDamage(x: Int, y: Int, w: Int, h: Int) {
            if (!alive()) return
            synchronized(damageLock) {
                if (dR <= dL || dB <= dT) {
                    dL = x; dT = y; dR = x + w; dB = y + h
                } else {
                    if (x < dL) dL = x
                    if (y < dT) dT = y
                    if (x + w > dR) dR = x + w
                    if (y + h > dB) dB = y + h
                }
            }
            frameReady = true
            postInvalidateOnAnimation()
        }

        override fun onBell() {}

        override fun onServerClipboard(text: String) {
            if (!alive()) return
            main.post { if (alive()) onDesktopClipboard?.invoke(text) }
        }

        override fun onClosed(error: String?) {
            main.post { if (alive()) handleClosed(error) }
        }
    }

    // Stop the picture while the app is not on screen (no CPU, no battery), take it up again when it comes back.
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            if (!paused) {
                paused = true
                if (wantRun) halt()
            }
        } else if (paused) {
            paused = false
            if (wantRun) {
                failCount = 0
                gaveUp = false
                connectNow()
            }
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) resetInputState() // no key or mouse button may stay "held" on the desktop
    }

    // ---------------------------------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------------------------------
    private val damageLock = Any()
    private var dL = 0
    private var dT = 0
    private var dR = 0
    private var dB = 0

    private var bmp: Bitmap? = null
    private var bmpW = 0
    private var bmpH = 0
    private var bmpOwner: RfbClient? = null

    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val statusPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFFD4D4D4.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 16f * context.resources.displayMetrics.scaledDensity
    }

    private var scale = 1f
    private var offX = 0f
    private var offY = 0f
    private var panY = 0f
    private var focusFbY = 0f
    private var keyboardUp = false
    private var maxHeightSeen = 0
    private var lastWidthSeen = 0

    /** While the on-screen keyboard is up: true = squeeze the whole desktop in, false = fit the width and follow the pointer. */
    var keyboardFitAll = false
        private set

    val keyboardVisible: Boolean get() = keyboardUp

    fun toggleKeyboardFit() {
        keyboardFitAll = !keyboardFitAll
        updateMatrix()
        invalidate()
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(BG)
        // The desktop draws its own mouse pointer into the picture; do not show Android's as well.
        try {
            pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
        } catch (_: Throwable) {
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != lastWidthSeen) maxHeightSeen = h else if (h > maxHeightSeen) maxHeightSeen = h
        lastWidthSeen = w
        keyboardUp = h < maxHeightSeen * 0.8f
        if (!keyboardUp) panY = 0f
        updateMatrix()
        invalidate()
    }

    private fun fbWidth(): Int = client?.width ?: 0
    private fun fbHeight(): Int = client?.height ?: 0

    private fun updateMatrix() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        val fw = fbWidth().toFloat()
        val fh = fbHeight().toFloat()
        if (vw <= 0f || vh <= 0f || fw <= 0f || fh <= 0f) {
            scale = 1f
            offX = 0f
            offY = 0f
            return
        }
        val fitAll = min(vw / fw, vh / fh)
        if (keyboardUp && !keyboardFitAll) {
            val s = snapScale(max(fitAll, vw / fw))
            scale = s
            offX = ((vw - fw * s) / 2f).roundToInt().toFloat()
            if (fh * s <= vh) {
                panY = 0f
                offY = ((vh - fh * s) / 2f).roundToInt().toFloat()
            } else {
                val focusV = focusFbY * s + panY
                if (focusV < vh * 0.12f || focusV > vh * 0.72f) panY += vh * 0.4f - focusV
                panY = panY.coerceIn(vh - fh * s, 0f)
                offY = panY.roundToInt().toFloat()
            }
        } else {
            val s = snapScale(fitAll)
            scale = s
            offX = ((vw - fw * s) / 2f).roundToInt().toFloat()
            offY = ((vh - fh * s) / 2f).roundToInt().toFloat()
            panY = 0f
        }
    }

    /** Within half a percent of 1:1 counts as 1:1: no resampling, so text stays as sharp as the desktop drew it. */
    private fun snapScale(s: Float): Float = if (abs(s - 1f) < 0.005f) 1f else s

    private fun toFbX(vx: Float): Int {
        val s = if (scale > 0f) scale else 1f
        return ((vx - offX) / s).toInt().coerceIn(0, max(fbWidth() - 1, 0))
    }

    private fun toFbY(vy: Float): Int {
        val s = if (scale > 0f) scale else 1f
        return ((vy - offY) / s).toInt().coerceIn(0, max(fbHeight() - 1, 0))
    }

    /** Remember where the user is working so the zoomed view (keyboard up) can follow. */
    private fun noteFocus(fbY: Int) {
        focusFbY = fbY.toFloat()
        if (keyboardUp && !keyboardFitAll) {
            updateMatrix()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(BG)
        val c = client
        if (c != null && frameReady && drawFrame(canvas, c)) return
        drawStatus(canvas)
    }

    private fun drawFrame(canvas: Canvas, c: RfbClient): Boolean {
        val b: Bitmap
        synchronized(c.lock) {
            val w = c.width
            val h = c.height
            if (w <= 0 || h <= 0) return false
            var full = false
            var bm = bmp
            if (bm == null || bmpW != w || bmpH != h) {
                bmp = null
                bm?.recycle()
                bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bm.setHasAlpha(false)
                bmp = bm
                bmpW = w
                bmpH = h
                full = true
                updateMatrix()
            }
            if (bmpOwner !== c) {
                bmpOwner = c
                full = true
            }
            val r = takeDirty(w, h, full)
            if (r != null) bm.setPixels(c.pixels, r.top * w + r.left, w, r.left, r.top, r.width(), r.height())
            b = bm
        }
        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(scale, scale)
        paint.isFilterBitmap = abs(scale - 1f) > 0.002f
        canvas.drawBitmap(b, 0f, 0f, paint)
        canvas.restore()
        return true
    }

    private fun takeDirty(w: Int, h: Int, full: Boolean): Rect? {
        synchronized(damageLock) {
            val hadDamage = dR > dL && dB > dT
            val r = if (full) Rect(0, 0, w, h) else if (hadDamage) Rect(dL, dT, dR, dB) else null
            dL = 0; dT = 0; dR = 0; dB = 0
            if (r == null) return null
            if (!r.intersect(0, 0, w, h)) return null
            return r
        }
    }

    private fun drawStatus(canvas: Canvas) {
        val lines = statusLine.split('\n').filter { it.isNotEmpty() }
        val lineHeight = statusPaint.textSize * 1.4f
        var y = height / 2f - lineHeight * (lines.size - 1) / 2f
        for (l in lines) {
            canvas.drawText(l, width / 2f, y, statusPaint)
            y += lineHeight
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Touch, mouse and pen
    // ---------------------------------------------------------------------------------------------
    private enum class Fm { IDLE, PENDING, SCROLL, DRAG, TWO, IGNORE }

    private var fm = Fm.IDLE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var tracker: VelocityTracker? = null
    private var accX = 0f
    private var accY = 0f
    private var axisLock = 0 // 0 undecided, 1 vertical, 2 horizontal
    private var sumDx = 0f
    private var sumDy = 0f
    private var twoStartTime = 0L
    private var twoMoved = false
    private var twoStartX = 0f
    private var twoStartY = 0f
    private var twoX = 0f
    private var twoY = 0f
    private var directMask = 0
    private var wheelAccV = 0f
    private var wheelAccH = 0f

    private fun readyClient(): RfbClient? {
        val c = client
        return if (c != null && c.isReady) c else null
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!isFocused) requestFocus()
        val tool = ev.getToolType(0)
        return if (tool == MotionEvent.TOOL_TYPE_MOUSE || tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) {
            directPointer(ev)
        } else {
            fingerTouch(ev)
        }
    }

    // ----- mouse, touchpad, S Pen -----

    private fun buttonMask(ev: MotionEvent, tool: Int, defaultLeft: Boolean): Int {
        val bs = ev.buttonState
        var m = 0
        if (bs and MotionEvent.BUTTON_PRIMARY != 0) m = m or 1
        if (bs and MotionEvent.BUTTON_TERTIARY != 0) m = m or 2
        if (bs and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) m = m or 4
        if (m == 0 && defaultLeft) m = 1
        // a pen touching the screen always reports "primary"; with its barrel button held it means right click only
        if (tool != MotionEvent.TOOL_TYPE_MOUSE && m and 4 != 0) m = m and 1.inv()
        return m
    }

    private fun directPointer(ev: MotionEvent): Boolean {
        val c = readyClient()
        if (c == null) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) retryIfGaveUp()
            return true
        }
        val tool = ev.getToolType(0)
        val x = toFbX(ev.x)
        val y = toFbY(ev.y)
        val mask = when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> buttonMask(ev, tool, true)
            else -> 0
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) noteFocus(y)
        directMask = mask
        c.sendPointer(x, y, mask)
        return true
    }

    override fun onHoverEvent(ev: MotionEvent): Boolean {
        val c = readyClient() ?: return true
        if (ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE || ev.actionMasked == MotionEvent.ACTION_HOVER_ENTER) {
            directMask = 0
            c.sendPointer(toFbX(ev.x), toFbY(ev.y), 0)
        }
        return true
    }

    override fun onGenericMotionEvent(ev: MotionEvent): Boolean {
        if (!ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return super.onGenericMotionEvent(ev)
        val c = readyClient() ?: return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                val x = toFbX(ev.x)
                val y = toFbY(ev.y)
                wheelAccV += ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
                wheelAccH += ev.getAxisValue(MotionEvent.AXIS_HSCROLL)
                val any = wheelAccV >= 1f || wheelAccV <= -1f || wheelAccH >= 1f || wheelAccH <= -1f
                if (any) c.sendPointer(x, y, directMask)
                while (wheelAccV >= 1f) { wheelNotch(c, x, y, WHEEL_UP); wheelAccV -= 1f }
                while (wheelAccV <= -1f) { wheelNotch(c, x, y, WHEEL_DOWN); wheelAccV += 1f }
                while (wheelAccH >= 1f) { wheelNotch(c, x, y, WHEEL_RIGHT); wheelAccH -= 1f }
                while (wheelAccH <= -1f) { wheelNotch(c, x, y, WHEEL_LEFT); wheelAccH += 1f }
                return true
            }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                val mask = buttonMask(ev, ev.getToolType(0), false)
                directMask = mask
                c.sendPointer(toFbX(ev.x), toFbY(ev.y), mask)
                return true
            }
        }
        return super.onGenericMotionEvent(ev)
    }

    private fun wheelNotch(c: RfbClient, x: Int, y: Int, button: Int) {
        c.sendPointer(x, y, directMask or button)
        c.sendPointer(x, y, directMask)
    }

    // ----- fingers -----

    private val longPressRunnable = Runnable {
        val c = readyClient()
        if (c != null && fm == Fm.PENDING) {
            fm = Fm.DRAG
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val x = toFbX(downX)
            val y = toFbY(downY)
            noteFocus(y)
            c.sendPointer(x, y, 0)
            c.sendPointer(x, y, 1)
        }
    }

    private fun fingerTouch(ev: MotionEvent): Boolean {
        val c = readyClient()
        if (c == null) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) retryIfGaveUp()
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopFling()
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                fm = Fm.PENDING
                downX = ev.x; downY = ev.y; lastX = ev.x; lastY = ev.y
                accX = 0f; accY = 0f; sumDx = 0f; sumDy = 0f; axisLock = 0
                main.removeCallbacks(longPressRunnable)
                main.postDelayed(longPressRunnable, LONG_PRESS_MS)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (fm == Fm.DRAG) return true // keep dragging with the first finger
                main.removeCallbacks(longPressRunnable)
                stopFling()
                if (ev.pointerCount == 2) {
                    fm = Fm.TWO
                    twoStartTime = ev.eventTime
                    twoMoved = false
                    twoX = (ev.getX(0) + ev.getX(1)) / 2f
                    twoY = (ev.getY(0) + ev.getY(1)) / 2f
                    twoStartX = twoX; twoStartY = twoY
                    accX = 0f; accY = 0f; sumDx = 0f; sumDy = 0f; axisLock = 0
                } else {
                    fm = Fm.IGNORE
                }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                when (fm) {
                    Fm.PENDING -> if (dist(ev.x, ev.y, downX, downY) > touchSlop) {
                        main.removeCallbacks(longPressRunnable)
                        fm = Fm.SCROLL
                        lastX = downX; lastY = downY
                        scrollBy(c, ev.x - lastX, ev.y - lastY, ev.x, ev.y)
                        lastX = ev.x; lastY = ev.y
                    }
                    Fm.SCROLL -> {
                        scrollBy(c, ev.x - lastX, ev.y - lastY, ev.x, ev.y)
                        lastX = ev.x; lastY = ev.y
                    }
                    Fm.DRAG -> c.sendPointer(toFbX(ev.x), toFbY(ev.y), 1)
                    Fm.TWO -> if (ev.pointerCount >= 2) {
                        val cx = (ev.getX(0) + ev.getX(1)) / 2f
                        val cy = (ev.getY(0) + ev.getY(1)) / 2f
                        if (!twoMoved && dist(cx, cy, twoStartX, twoStartY) > touchSlop) twoMoved = true
                        if (twoMoved) scrollBy(c, cx - twoX, cy - twoY, cx, cy)
                        twoX = cx; twoY = cy
                    }
                    else -> {}
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (fm == Fm.TWO) {
                    if (!twoMoved && ev.eventTime - twoStartTime < 500) clickAt(c, twoX, twoY, 4)
                    fm = Fm.IGNORE
                }
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(longPressRunnable)
                tracker?.addMovement(ev)
                when (fm) {
                    Fm.PENDING -> clickAt(c, downX, downY, 1)
                    Fm.SCROLL -> startFling(c, ev.x, ev.y)
                    Fm.DRAG -> c.sendPointer(toFbX(ev.x), toFbY(ev.y), 0)
                    else -> {}
                }
                fm = Fm.IDLE
                if (!flinging) {
                    tracker?.recycle()
                    tracker = null
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPressRunnable)
                if (fm == Fm.DRAG) c.sendPointer(toFbX(lastX), toFbY(lastY), 0)
                fm = Fm.IDLE
                tracker?.recycle()
                tracker = null
            }
        }
        return true
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun clickAt(c: RfbClient, vx: Float, vy: Float, button: Int) {
        val x = toFbX(vx)
        val y = toFbY(vy)
        noteFocus(y)
        c.sendPointer(x, y, 0)
        c.sendPointer(x, y, button)
        c.sendPointer(x, y, 0)
    }

    /**
     * Turns finger movement (view pixels) into mouse-wheel notches under the finger, "natural" direction: the page
     * follows the finger. The first clear direction (up/down or sideways) is kept until the finger lifts.
     */
    private fun scrollBy(c: RfbClient, dx: Float, dy: Float, atX: Float, atY: Float) {
        val s = if (scale > 0f) scale else 1f
        if (axisLock == 0) {
            sumDx += dx
            sumDy += dy
            if (abs(sumDx) > touchSlop || abs(sumDy) > touchSlop) axisLock = if (abs(sumDy) >= abs(sumDx)) 1 else 2
            if (axisLock == 0) return
            accX += sumDx / s
            accY += sumDy / s
        } else {
            accX += dx / s
            accY += dy / s
        }
        val x = toFbX(atX)
        val y = toFbY(atY)
        var moved = false
        fun notch(button: Int) {
            if (!moved) {
                moved = true
                c.sendPointer(x, y, 0)
            }
            c.sendPointer(x, y, button)
            c.sendPointer(x, y, 0)
        }
        if (axisLock == 1) {
            while (accY <= -SCROLL_STEP_FB) { notch(WHEEL_DOWN); accY += SCROLL_STEP_FB }
            while (accY >= SCROLL_STEP_FB) { notch(WHEEL_UP); accY -= SCROLL_STEP_FB }
        } else {
            while (accX <= -SCROLL_STEP_FB) { notch(WHEEL_RIGHT); accX += SCROLL_STEP_FB }
            while (accX >= SCROLL_STEP_FB) { notch(WHEEL_LEFT); accX -= SCROLL_STEP_FB }
        }
    }

    // ----- momentum -----
    private var flinging = false
    private var flingVx = 0f
    private var flingVy = 0f
    private var flingAtX = 0f
    private var flingAtY = 0f
    private var flingLast = 0L

    private val flingRunnable = object : Runnable {
        override fun run() {
            val c = readyClient()
            if (!flinging || c == null) {
                stopFling()
                return
            }
            val now = SystemClock.uptimeMillis()
            val dt = ((now - flingLast).coerceIn(1L, 50L)) / 1000f
            flingLast = now
            val decay = exp(-dt / FLING_TAU)
            flingVx *= decay
            flingVy *= decay
            val v = if (axisLock == 2) abs(flingVx) else abs(flingVy)
            if (v < FLING_STOP_SPEED) {
                stopFling()
                return
            }
            scrollBy(c, flingVx * dt, flingVy * dt, flingAtX, flingAtY)
            postOnAnimation(this)
        }
    }

    private fun startFling(c: RfbClient, atX: Float, atY: Float) {
        val t = tracker ?: return
        t.computeCurrentVelocity(1000, 8000f)
        val vx = t.xVelocity
        val vy = t.yVelocity
        val v = if (axisLock == 2) abs(vx) else abs(vy)
        tracker?.recycle()
        tracker = null
        if (axisLock == 0 || v < FLING_MIN_SPEED) return
        flinging = true
        flingVx = vx
        flingVy = vy
        flingAtX = atX
        flingAtY = atY
        flingLast = SystemClock.uptimeMillis()
        postOnAnimation(flingRunnable)
    }

    private fun stopFling() {
        flinging = false
        removeCallbacks(flingRunnable)
        if (fm == Fm.IDLE) {
            tracker?.recycle()
            tracker = null
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Keyboard
    // ---------------------------------------------------------------------------------------------
    private val downKeys = SparseIntArray()
    private var latched = 0

    fun isLatched(mod: Int): Boolean = latched and mod != 0

    fun toggleLatch(mod: Int) {
        latched = latched xor mod
        onLatchChanged?.invoke()
    }

    private fun consumeLatches(): Int {
        val l = latched
        if (l != 0) {
            latched = 0
            onLatchChanged?.invoke()
        }
        return l
    }

    private fun modifierKeysyms(mods: Int): IntArray {
        val out = ArrayList<Int>(4)
        if (mods and MOD_CTRL != 0) out.add(VncKeys.CONTROL_L)
        if (mods and MOD_ALT != 0) out.add(VncKeys.ALT_L)
        if (mods and MOD_SHIFT != 0) out.add(VncKeys.SHIFT_L)
        if (mods and MOD_SUPER != 0) out.add(VncKeys.SUPER_L)
        return out.toIntArray()
    }

    /** Presses and releases one key, with the latched modifiers (and [extraMods]) held around it. */
    fun tapKey(keysym: Int, extraMods: Int = 0) {
        val c = readyClient() ?: return
        val mods = consumeLatches() or extraMods
        var sym = keysym
        // Ctrl+"C" typed from an upper-case soft key must be Ctrl+c, not Ctrl+Shift+c
        if (mods and (MOD_CTRL or MOD_ALT) != 0 && mods and MOD_SHIFT == 0 && sym in 'A'.code..'Z'.code) sym += 32
        if (sym == 'v'.code && mods and MOD_CTRL != 0) onBeforePaste?.invoke()
        val m = modifierKeysyms(mods)
        for (k in m) c.sendKey(k, true)
        c.sendKey(sym, true)
        c.sendKey(sym, false)
        for (i in m.indices.reversed()) c.sendKey(m[i], false)
    }

    private fun typeText(text: String) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val sym = VncKeys.keysymForCodePoint(cp)
            if (sym != 0) tapKey(sym)
        }
    }

    /** Long or multi-line text from the keyboard's clipboard / paste: let the desktop paste it, so indentation survives. */
    private fun pasteText(text: String) {
        val c = readyClient() ?: return
        c.sendClipboard(text)
        tapKey('v'.code, MOD_CTRL)
    }

    fun sendClipboardToDesktop(text: String) {
        readyClient()?.sendClipboard(text)
    }

    private fun keysymFor(code: Int, ev: KeyEvent): Int {
        val special = VncKeys.keysymForKeyCode(code)
        if (special != 0) return special
        val u = ev.getUnicodeChar(ev.metaState and (KeyEvent.META_SHIFT_MASK or KeyEvent.META_CAPS_LOCK_ON))
        if (u and KeyCharacterMap.COMBINING_ACCENT != 0) return 0
        return VncKeys.keysymForCodePoint(u and KeyCharacterMap.COMBINING_ACCENT_MASK)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val c = readyClient() ?: return super.onKeyDown(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        val sym = keysymFor(keyCode, event)
        if (sym == 0) return super.onKeyDown(keyCode, event)
        if (!VncKeys.isModifier(sym)) {
            val ctrl = event.isCtrlPressed
            if ((ctrl && sym == 'v'.code) || (event.isShiftPressed && sym == VncKeys.INSERT)) onBeforePaste?.invoke()
        }
        downKeys.put(keyCode, sym)
        c.sendKey(sym, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val idx = downKeys.indexOfKey(keyCode)
        if (idx < 0) return super.onKeyUp(keyCode, event)
        val sym = downKeys.valueAt(idx)
        downKeys.removeAt(idx)
        readyClient()?.sendKey(sym, false)
        return true
    }

    /** Lets go of every key and mouse button that might still be held on the desktop. */
    private fun resetInputState() {
        val c = client
        if (c != null && c.isReady) {
            for (i in 0 until downKeys.size()) c.sendKey(downKeys.valueAt(i), false)
            if (directMask != 0 || fm == Fm.DRAG) c.sendPointer(toFbX(lastX), toFbY(lastY), 0)
        }
        downKeys.clear()
        directMask = 0
        if (fm == Fm.DRAG || fm == Fm.PENDING || fm == Fm.TWO) fm = Fm.IDLE
    }

    // ----- on-screen keyboard -----

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // plain, visible text without suggestions: the keyboard then sends each letter at once instead of a "word being composed"
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_ACTION_NONE
        return Ime(this)
    }

    fun toggleKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        if (keyboardUp) {
            imm.hideSoftInputFromWindow(windowToken, 0)
        } else {
            requestFocus()
            imm.showSoftInput(this, 0)
        }
    }

    private inner class Ime(target: View) : BaseInputConnection(target, false) {
        private var composing = ""

        /** Makes the desktop show [newText] in place of what is being composed: only the changed tail is retyped. */
        private fun rewriteComposing(newText: String) {
            var p = 0
            val limit = min(composing.length, newText.length)
            while (p < limit && composing[p] == newText[p]) p++
            // do not split a surrogate pair
            if (p > 0 && p < composing.length && Character.isHighSurrogate(composing[p - 1])) p--
            val removed = composing.substring(p)
            repeat(removed.codePointCount(0, removed.length)) { tapKey(VncKeys.BACKSPACE) }
            typeText(newText.substring(p))
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            val t = text.toString()
            if (composing.isEmpty() && (t.length > 40 || (t.length > 1 && t.indexOf('\n') >= 0))) {
                pasteText(t)
            } else {
                rewriteComposing(t)
            }
            composing = ""
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            val t = text.toString()
            rewriteComposing(t)
            composing = t
            return true
        }

        override fun finishComposingText(): Boolean {
            composing = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) { tapKey(VncKeys.BACKSPACE) }
            repeat(afterLength) { tapKey(VncKeys.DELETE) }
            composing = composing.dropLast(min(beforeLength, composing.length))
            return true
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
            deleteSurroundingText(beforeLength, afterLength)

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            composing = ""
            val sym = keysymFor(event.keyCode, event)
            if (sym != 0) tapKey(sym)
            return true
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            tapKey(VncKeys.RETURN)
            return true
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean = true
        override fun setSelection(start: Int, end: Int): Boolean = true
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
        override fun getSelectedText(flags: Int): CharSequence? = null
    }

    companion object {
        const val MOD_CTRL = 1
        const val MOD_ALT = 2
        const val MOD_SHIFT = 4
        const val MOD_SUPER = 8

        private const val BG = 0xFF1E1E1E.toInt()
        private const val LONG_PRESS_MS = 450L

        // RFB pointer buttons 4..7 (bits 3..6 of the button mask)
        private const val WHEEL_UP = 8
        private const val WHEEL_DOWN = 16
        private const val WHEEL_LEFT = 32
        private const val WHEEL_RIGHT = 64

        /** One wheel notch per this many desktop pixels of finger movement (a notch scrolls about this much in VS Code). */
        private const val SCROLL_STEP_FB = 48f
        private const val FLING_MIN_SPEED = 700f
        private const val FLING_STOP_SPEED = 90f
        private const val FLING_TAU = 0.38f
    }
}
