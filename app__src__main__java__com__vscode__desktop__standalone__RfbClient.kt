package com.vscode.desktop.standalone

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * A small VNC (RFB 3.3 / 3.7 / 3.8) client for the virtual desktop that Mode 1 runs on 127.0.0.1:5901.
 *
 * It replaces the web page (noVNC + websockify + a browser): the picture arrives over one plain local TCP socket as
 * raw pixels, so there is no JPEG / zlib work, no WebSocket framing and no Python proxy in between. That is what
 * makes it feel immediate. Pure Kotlin, nothing from Android in here (so it can be tested on a normal JVM).
 *
 *  - security: "None" only (the server only listens on 127.0.0.1)
 *  - encodings: Raw, CopyRect, DesktopSize, Extended Clipboard (UTF-8 text in both directions)
 *  - pixels are requested as 32-bit little-endian true colour, so they can be copied straight into an ARGB int array
 *
 * One instance = one connection. [start] runs the connection on its own thread; the [Listener] is called from that
 * thread. [pixels] (guarded by [lock]) always holds the current picture.
 */
class RfbClient(
    private val host: String,
    private val port: Int,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected(width: Int, height: Int, name: String)

        /** The remote screen changed size; [pixels] was replaced by a new array. */
        fun onResized(width: Int, height: Int)

        /** The rectangle (in [pixels] coordinates) holds new picture data. */
        fun onDamage(x: Int, y: Int, w: Int, h: Int)

        fun onBell()

        /** Text copied on the remote side. */
        fun onServerClipboard(text: String)

        /** The connection ended. [error] is null when [close] was called on purpose. */
        fun onClosed(error: String?)
    }

    /** Guards [pixels] / [width] / [height] between the reader thread and whoever draws them. */
    val lock = Any()

    @Volatile
    var width = 0
        private set

    @Volatile
    var height = 0
        private set

    /** ARGB, row after row, [width] ints per row. Read it only while holding [lock]. */
    @Volatile
    var pixels: IntArray = IntArray(0)
        private set

    @Volatile
    var serverName: String = ""
        private set

    @Volatile
    private var closed = false

    @Volatile
    private var ready = false

    private var socket: Socket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream

    private val outQueue = LinkedBlockingQueue<ByteArray>()
    private var writer: Thread? = null

    // reusable row buffer for Raw rectangles
    private var rowBytes = ByteArray(0)
    private var rowInts: IntBuffer = IntBuffer.allocate(0)

    // ---- clipboard state ----
    private val clipLock = Any()
    private var pendingClipboard: String? = null // text we announced and may be asked for
    private var serverClipActions = 0 // action bits (byte 3 of the flags) the server told us it supports

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    fun start(): Thread {
        val t = Thread({ run() }, "rfb-reader")
        t.isDaemon = true
        t.start()
        return t
    }

    fun close() {
        closed = true
        ready = false
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        outQueue.offer(EMPTY) // wake the writer so it can end
    }

    val isReady: Boolean get() = ready

    private fun run() {
        var error: String? = null
        try {
            connectAndHandshake()
            messageLoop()
        } catch (e: Throwable) {
            if (!closed) {
                error = when (e) {
                    is EOFException -> "The desktop closed the connection"
                    else -> e.message ?: e.javaClass.simpleName
                }
            }
        } finally {
            closed = true
            ready = false
            try {
                socket?.close()
            } catch (_: IOException) {
            }
            outQueue.offer(EMPTY)
            listener.onClosed(error)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Handshake
    // ---------------------------------------------------------------------------------------------

    private fun connectAndHandshake() {
        val s = Socket()
        s.tcpNoDelay = true // every key press / mouse move goes out at once
        try {
            s.receiveBufferSize = 1 shl 20
        } catch (_: Exception) {
        }
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        socket = s
        input = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 18))
        output = BufferedOutputStream(s.getOutputStream(), 1 shl 14)

        // ProtocolVersion: "RFB 003.008\n"
        val ver = ByteArray(12)
        input.readFully(ver)
        val verText = String(ver, Charsets.ISO_8859_1)
        if (!verText.startsWith("RFB ")) throw IOException("That port is not a VNC server")
        val minor = verText.substring(8, 11).trim().toIntOrNull() ?: 3
        val use = when {
            minor >= 8 -> 8
            minor == 7 -> 7
            else -> 3
        }
        output.write("RFB 003.00$use\n".toByteArray(Charsets.ISO_8859_1))
        output.flush()

        // Security
        if (use == 3) {
            val type = input.readInt()
            if (type == 0) throw IOException(readReason())
            if (type != 1) throw IOException("The VNC server wants a password (security type $type)")
        } else {
            val n = input.readUnsignedByte()
            if (n == 0) throw IOException(readReason())
            val types = ByteArray(n)
            input.readFully(types)
            if (types.none { it.toInt() == 1 }) {
                throw IOException("The VNC server wants a password (security types ${types.joinToString()})")
            }
            output.write(1)
            output.flush()
        }
        if (use >= 8) {
            val result = input.readInt()
            if (result != 0) throw IOException(readReason().ifEmpty { "VNC security handshake failed" })
        }

        // ClientInit: share the desktop
        output.write(1)
        output.flush()

        // ServerInit
        val w = input.readUnsignedShort()
        val h = input.readUnsignedShort()
        skipFully(16) // the server's own pixel format: we ask for ours below
        val nameLen = input.readInt()
        if (nameLen < 0 || nameLen > 65536) throw IOException("Bad VNC server name length")
        val nameBytes = ByteArray(nameLen)
        input.readFully(nameBytes)
        serverName = String(nameBytes, Charsets.UTF_8)
        if (w <= 0 || h <= 0 || w > MAX_DIMENSION || h > MAX_DIMENSION) throw IOException("Bad desktop size ${w}x$h")
        allocate(w, h)

        // SetPixelFormat: 32 bpp, depth 24, little endian, true colour, R/G/B = bits 16/8/0
        val pf = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        pf.put(0) // message type
        pf.put(0).put(0).put(0) // padding
        pf.put(32) // bits per pixel
        pf.put(24) // depth
        pf.put(0) // big endian = false
        pf.put(1) // true colour
        pf.putShort(255).putShort(255).putShort(255) // max values
        pf.put(16).put(8).put(0) // shifts
        pf.put(0).put(0).put(0) // padding
        output.write(pf.array())

        // SetEncodings, best first
        val encodings = intArrayOf(ENC_COPYRECT, ENC_RAW, ENC_DESKTOP_SIZE, ENC_EXTENDED_CLIPBOARD)
        val se = ByteBuffer.allocate(4 + 4 * encodings.size).order(ByteOrder.BIG_ENDIAN)
        se.put(2).put(0).putShort(encodings.size.toShort())
        for (e in encodings) se.putInt(e)
        output.write(se.array())

        // first full picture
        output.write(updateRequest(false, 0, 0, w, h))
        output.flush()

        val wt = Thread({ writerLoop() }, "rfb-writer")
        wt.isDaemon = true
        writer = wt
        wt.start()
        ready = true
        listener.onConnected(w, h, serverName)
    }

    private fun readReason(): String {
        val len = input.readInt()
        if (len < 0 || len > 4096) return ""
        val b = ByteArray(len)
        input.readFully(b)
        return String(b, Charsets.UTF_8)
    }

    // ---------------------------------------------------------------------------------------------
    // Receiving
    // ---------------------------------------------------------------------------------------------

    private fun messageLoop() {
        while (!closed) {
            when (val type = input.readUnsignedByte()) {
                MSG_FRAMEBUFFER_UPDATE -> readFramebufferUpdate()
                MSG_SET_COLOUR_MAP -> {
                    input.readUnsignedByte()
                    input.readUnsignedShort()
                    val n = input.readUnsignedShort()
                    skipFully(n * 6)
                }
                MSG_BELL -> listener.onBell()
                MSG_SERVER_CUT_TEXT -> readServerCutText()
                else -> throw IOException("Unsupported VNC message type $type")
            }
        }
    }

    private fun readFramebufferUpdate() {
        input.readUnsignedByte() // padding
        val rects = input.readUnsignedShort()
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = 0
        var maxY = 0
        var resized = false
        for (i in 0 until rects) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val w = input.readUnsignedShort()
            val h = input.readUnsignedShort()
            val enc = input.readInt()
            when (enc) {
                ENC_RAW -> {
                    checkRect(x, y, w, h)
                    readRaw(x, y, w, h)
                }
                ENC_COPYRECT -> {
                    checkRect(x, y, w, h)
                    val sx = input.readUnsignedShort()
                    val sy = input.readUnsignedShort()
                    checkRect(sx, sy, w, h)
                    copyRect(sx, sy, x, y, w, h)
                }
                ENC_DESKTOP_SIZE -> {
                    if (w <= 0 || h <= 0 || w > MAX_DIMENSION || h > MAX_DIMENSION) throw IOException("Bad desktop size ${w}x$h")
                    allocate(w, h)
                    resized = true
                    listener.onResized(w, h)
                    minX = 0
                    minY = 0
                    maxX = w
                    maxY = h
                    continue
                }
                else -> throw IOException("Unsupported VNC encoding $enc")
            }
            if (w > 0 && h > 0) {
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x + w > maxX) maxX = x + w
                if (y + h > maxY) maxY = y + h
            }
        }
        // ask for the next change straight away (a full picture again after a resize)
        enqueue(if (resized) updateRequest(false, 0, 0, width, height) else updateRequest(true, 0, 0, width, height))
        if (maxX > minX && maxY > minY) listener.onDamage(minX, minY, maxX - minX, maxY - minY)
    }

    private fun checkRect(x: Int, y: Int, w: Int, h: Int) {
        if (x < 0 || y < 0 || w < 0 || h < 0 || x + w > width || y + h > height) {
            throw IOException("Rectangle $x,$y ${w}x$h is outside the ${width}x$height screen")
        }
    }

    private fun allocate(w: Int, h: Int) {
        synchronized(lock) {
            width = w
            height = h
            val fresh = IntArray(w * h)
            java.util.Arrays.fill(fresh, ALPHA)
            pixels = fresh
        }
    }

    private fun readRaw(x: Int, y: Int, w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val need = w * 4
        if (rowBytes.size < need) {
            rowBytes = ByteArray(need)
            rowInts = ByteBuffer.wrap(rowBytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        }
        val w4 = w
        for (row in 0 until h) {
            input.readFully(rowBytes, 0, need)
            synchronized(lock) {
                val px = pixels
                val base = (y + row) * width + x
                rowInts.rewind()
                rowInts.get(px, base, w4)
                for (i in base until base + w4) px[i] = px[i] or ALPHA
            }
        }
    }

    private fun copyRect(sx: Int, sy: Int, dx: Int, dy: Int, w: Int, h: Int) {
        if (w == 0 || h == 0) return
        synchronized(lock) {
            val px = pixels
            val stride = width
            if (dy > sy) {
                for (r in h - 1 downTo 0) System.arraycopy(px, (sy + r) * stride + sx, px, (dy + r) * stride + dx, w)
            } else {
                for (r in 0 until h) System.arraycopy(px, (sy + r) * stride + sx, px, (dy + r) * stride + dx, w)
            }
        }
    }

    private fun skipFully(n: Int) {
        var left = n
        while (left > 0) {
            val s = input.skipBytes(left)
            if (s <= 0) {
                input.readUnsignedByte()
                left--
            } else {
                left -= s
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Clipboard
    // ---------------------------------------------------------------------------------------------

    private fun readServerCutText() {
        skipFully(3)
        val len = input.readInt()
        if (len >= 0) {
            if (len > MAX_CLIPBOARD_BYTES) {
                skipFully(len)
                return
            }
            val b = ByteArray(len)
            input.readFully(b)
            listener.onServerClipboard(String(b, Charsets.ISO_8859_1))
            return
        }
        if (len == Int.MIN_VALUE) throw IOException("Bad clipboard message")
        val n = -len
        if (n < 4) throw IOException("Bad extended clipboard message")
        if (n > MAX_CLIPBOARD_BYTES) {
            skipFully(n)
            return
        }
        val flags = input.readInt()
        val rest = ByteArray(n - 4)
        input.readFully(rest)
        handleExtendedClipboard(flags, rest)
    }

    private fun handleExtendedClipboard(flags: Int, data: ByteArray) {
        val actions = flags ushr 24 // 1 caps, 2 request, 4 peek, 8 notify, 16 provide
        val formats = flags and 0xFFFF
        when {
            actions and ACT_CAPS != 0 -> {
                synchronized(clipLock) { serverClipActions = actions }
                // answer with ours: every action, text format, no unsolicited-size limit
                val msg = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                msg.put((ACT_CAPS or ACT_REQUEST or ACT_PEEK or ACT_NOTIFY or ACT_PROVIDE).toByte())
                msg.put(0).put(0).put(FORMAT_TEXT.toByte())
                msg.putInt(0)
                enqueue(clientCutText(msg.array(), extended = true))
            }
            actions == ACT_REQUEST -> {
                val text = synchronized(clipLock) { if (serverClipActions and ACT_PROVIDE != 0) pendingClipboard else null }
                if (text != null && formats and FORMAT_TEXT != 0) enqueue(provideMessage(text))
            }
            actions == ACT_PEEK -> {
                val has = synchronized(clipLock) { pendingClipboard != null }
                enqueue(flagsMessage(ACT_NOTIFY, if (has) FORMAT_TEXT else 0))
            }
            actions == ACT_NOTIFY -> {
                val canRequest = synchronized(clipLock) { serverClipActions and ACT_REQUEST != 0 }
                if (canRequest && formats and FORMAT_TEXT != 0) enqueue(flagsMessage(ACT_REQUEST, FORMAT_TEXT))
            }
            actions == ACT_PROVIDE -> {
                if (formats and FORMAT_TEXT == 0) return
                val text = parseProvide(formats, data) ?: return
                listener.onServerClipboard(text)
            }
        }
    }

    /** The text out of a Provide message: a zlib stream of (uint32 length, bytes) per format. */
    private fun parseProvide(formats: Int, data: ByteArray): String? {
        var text: String? = null
        InflaterInputStream(ByteArrayInputStream(data)).use { z ->
            val din = DataInputStream(z)
            for (bit in 0..15) {
                val format = 1 shl bit
                if (formats and format == 0) continue
                val size = din.readInt()
                if (size < 0 || size > MAX_CLIPBOARD_BYTES) throw IOException("Bad clipboard size")
                val chunk = ByteArray(size)
                din.readFully(chunk)
                if (format == FORMAT_TEXT) {
                    var s = String(chunk, Charsets.UTF_8)
                    if (s.endsWith("\u0000")) s = s.substring(0, s.length - 1)
                    text = s.replace("\r\n", "\n")
                }
            }
        }
        return text
    }

    /** Tell the desktop that this text is now on the Android clipboard. */
    fun sendClipboard(text: String) {
        if (!ready || text.isEmpty()) return
        val t = if (text.length > MAX_CLIPBOARD_CHARS) text.substring(0, MAX_CLIPBOARD_CHARS) else text
        val extended = synchronized(clipLock) {
            val ok = serverClipActions and ACT_CAPS != 0 && serverClipActions and ACT_NOTIFY != 0
            if (ok) pendingClipboard = t
            ok
        }
        if (extended) {
            enqueue(flagsMessage(ACT_NOTIFY, FORMAT_TEXT))
        } else {
            // classic message: Latin-1 only, anything else becomes '?'
            val b = ByteArray(t.length)
            for (i in t.indices) b[i] = if (t[i].code < 256) t[i].code.toByte() else '?'.code.toByte()
            enqueue(clientCutText(b, extended = false))
        }
    }

    private fun flagsMessage(action: Int, formats: Int): ByteArray {
        val flags = ByteArray(4)
        flags[0] = action.toByte()
        flags[3] = formats.toByte()
        return clientCutText(flags, extended = true)
    }

    private fun provideMessage(text: String): ByteArray {
        // line ends as CRLF and a terminating NUL, as the protocol wants
        val dos = text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n") + "\u0000"
        val utf8 = dos.toByteArray(Charsets.UTF_8)
        val raw = ByteArrayOutputStream(utf8.size + 4)
        val dout = DataOutputStreamCompat(raw)
        dout.writeInt(utf8.size)
        raw.write(utf8)
        val packed = ByteArrayOutputStream()
        DeflaterOutputStream(packed, Deflater()).use { it.write(raw.toByteArray()) }
        val body = packed.toByteArray()
        val data = ByteArray(4 + body.size)
        data[0] = ACT_PROVIDE.toByte()
        data[3] = FORMAT_TEXT.toByte()
        System.arraycopy(body, 0, data, 4, body.size)
        return clientCutText(data, extended = true)
    }

    private fun clientCutText(data: ByteArray, extended: Boolean): ByteArray {
        val m = ByteBuffer.allocate(8 + data.size).order(ByteOrder.BIG_ENDIAN)
        m.put(MSG_CLIENT_CUT_TEXT.toByte()).put(0).put(0).put(0)
        m.putInt(if (extended) -data.size else data.size)
        m.put(data)
        return m.array()
    }

    // ---------------------------------------------------------------------------------------------
    // Sending
    // ---------------------------------------------------------------------------------------------

    /** [buttons]: bit 0 left, bit 1 middle, bit 2 right, bits 3..6 wheel up / down / left / right. */
    fun sendPointer(x: Int, y: Int, buttons: Int) {
        if (!ready) return
        val cx = x.coerceIn(0, maxOf(width - 1, 0))
        val cy = y.coerceIn(0, maxOf(height - 1, 0))
        enqueue(byteArrayOf(MSG_POINTER.toByte(), buttons.toByte(), (cx shr 8).toByte(), cx.toByte(), (cy shr 8).toByte(), cy.toByte()))
    }

    /** [keysym] is an X11 keysym (Unicode characters beyond Latin-1 use 0x01000000 + code point). */
    fun sendKey(keysym: Int, down: Boolean) {
        if (!ready) return
        enqueue(
            byteArrayOf(
                MSG_KEY.toByte(), if (down) 1 else 0, 0, 0,
                (keysym ushr 24).toByte(), (keysym ushr 16).toByte(), (keysym ushr 8).toByte(), keysym.toByte()
            )
        )
    }

    /** Ask the desktop for a full, non-incremental picture (e.g. after the app came back to the front). */
    fun requestFullUpdate() {
        if (!ready) return
        enqueue(updateRequest(false, 0, 0, width, height))
    }

    private fun updateRequest(incremental: Boolean, x: Int, y: Int, w: Int, h: Int): ByteArray = byteArrayOf(
        MSG_UPDATE_REQUEST.toByte(), if (incremental) 1 else 0,
        (x shr 8).toByte(), x.toByte(), (y shr 8).toByte(), y.toByte(),
        (w shr 8).toByte(), w.toByte(), (h shr 8).toByte(), h.toByte()
    )

    private fun enqueue(msg: ByteArray) {
        if (!closed) outQueue.offer(msg)
    }

    private fun writerLoop() {
        val batch = ArrayList<ByteArray>()
        val merged = ArrayList<ByteArray>()
        try {
            while (!closed) {
                val first = outQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                batch.clear()
                batch.add(first)
                outQueue.drainTo(batch)
                merged.clear()
                for (m in batch) {
                    if (m.isEmpty()) continue
                    // a run of mouse moves with the same buttons only needs its last position
                    if (isPointer(m) && merged.isNotEmpty()) {
                        val last = merged[merged.size - 1]
                        if (isPointer(last) && last[1] == m[1]) {
                            merged[merged.size - 1] = m
                            continue
                        }
                    }
                    merged.add(m)
                }
                for (m in merged) output.write(m)
                output.flush()
            }
        } catch (e: IOException) {
            if (!closed) {
                try {
                    socket?.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun isPointer(m: ByteArray) = m.size == 6 && m[0].toInt() == MSG_POINTER

    private class DataOutputStreamCompat(private val out: OutputStream) {
        fun writeInt(v: Int) {
            out.write(v ushr 24)
            out.write(v ushr 16)
            out.write(v ushr 8)
            out.write(v)
        }
    }

    companion object {
        private val EMPTY = ByteArray(0)
        private const val ALPHA = -0x1000000 // 0xFF000000
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val MAX_DIMENSION = 16384
        private const val MAX_CLIPBOARD_BYTES = 16 * 1024 * 1024
        private const val MAX_CLIPBOARD_CHARS = 250_000

        private const val MSG_FRAMEBUFFER_UPDATE = 0
        private const val MSG_SET_COLOUR_MAP = 1
        private const val MSG_BELL = 2
        private const val MSG_SERVER_CUT_TEXT = 3
        private const val MSG_KEY = 4
        private const val MSG_POINTER = 5
        private const val MSG_CLIENT_CUT_TEXT = 6
        private const val MSG_UPDATE_REQUEST = 3

        private const val ENC_RAW = 0
        private const val ENC_COPYRECT = 1
        private const val ENC_DESKTOP_SIZE = -223
        private const val ENC_EXTENDED_CLIPBOARD = -1063131698

        private const val FORMAT_TEXT = 1
        private const val ACT_CAPS = 1
        private const val ACT_REQUEST = 2
        private const val ACT_PEEK = 4
        private const val ACT_NOTIFY = 8
        private const val ACT_PROVIDE = 16
    }
}
