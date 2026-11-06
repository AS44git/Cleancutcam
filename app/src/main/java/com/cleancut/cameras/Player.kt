package com.cleancut.cameras

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.chaquo.python.PyObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private const val TAG = "CleancutCams"

/** Which stream a view asks for. Small tiles: the light sub stream. Big tile and full screen: main. */
enum class Quality(val streamType: Int) { TILE(2), BIG(1), FULL(1) }

/**
 * Plays one camera into a Surface: a background thread reads NAL units from the cloud
 * stream (camengine) and feeds the phone's video decoder. Reconnects by itself; a tile
 * whose sub stream shows no picture moves to the main stream.
 */
class Player(
    private val number: Int,
    private val quality: Quality,
    private val surface: Surface,
    private val listener: Listener,
) {
    interface Listener {
        /** null: playing. Otherwise a short status to show over the picture. */
        fun onStatus(text: String?)
        fun onVideoSize(width: Int, height: Int)
    }

    @Volatile private var running = true
    @Volatile private var stream: PyObject? = null
    private val thread = Thread({ run() }, "cam$number-$quality")

    fun start(): Player {
        thread.start()
        return this
    }

    fun stop() {
        running = false
        val s = stream
        stream = null
        // Closing the socket unblocks the reading thread; off the caller's thread, it may wait for Python.
        if (s != null) closer.execute { runCatching { s.callAttr("close") } }
        thread.interrupt()
    }

    private fun run() {
        var streamType = quality.streamType
        var failures = 0
        while (running) {
            var decoder: Decoder? = null
            var opened: PyObject? = null
            try {
                status(if (failures == 0) "Connecting…" else "Reconnecting…")
                val cam = Engine.camera(number) ?: throw IllegalStateException("no camera $number")
                opened = Engine.open(number, streamType, Store.codeFor(cam.serial))
                if (!running) break
                stream = opened
                decoder = Decoder(surface, listener::onVideoSize)
                val started = SystemClock.elapsedRealtime()
                var showing = false
                while (running) {
                    val chunk = opened.callAttr("read").toJava(ByteArray::class.java)
                    if (chunk.isEmpty()) break
                    decoder.feed(chunk)
                    if (!showing && decoder.framesShown > 0) {
                        showing = true
                        failures = 0
                        status(null)
                    }
                    if (!showing && streamType == 2 && SystemClock.elapsedRealtime() - started > 15_000) {
                        Log.i(TAG, "camera $number: no picture on the sub stream, using the main stream")
                        streamType = 1
                        break
                    }
                }
            } catch (e: Throwable) {
                if (!running) break
                failures++
                Log.w(TAG, "camera $number ($quality, stream $streamType): ${e.message}")
                // A stream type the camera keeps refusing: try the other one.
                if (failures == 3) streamType = if (streamType == 1) 2 else 1
                // An expired session shows up as repeated failures: sign in again.
                if (failures % 4 == 0) runCatching { Engine.relogin() }
                status("Camera unavailable, retrying…")
            } finally {
                decoder?.release()
                opened?.let { s -> runCatching { s.callAttr("close") } }
                stream = null
            }
            if (running) {
                val waitMs = if (failures == 0) 1_000L else minOf(30_000L, 3_000L * failures)
                try { Thread.sleep(waitMs) } catch (_: InterruptedException) { }
            }
        }
    }

    private fun status(text: String?) {
        if (running) listener.onStatus(text)
    }

    companion object {
        private val closer = Executors.newCachedThreadPool()
    }
}

/**
 * H.264 / H.265 Annex B into MediaCodec: finds the codec from the parameter sets, groups
 * NAL units into access units, waits for a key frame, and renders every decoded frame
 * straight to the Surface (live: nothing is held back).
 */
class Decoder(private val surface: Surface, private val onSize: (Int, Int) -> Unit) {
    @Volatile var framesShown = 0
        private set

    private var hevc: Boolean? = null
    private var vps: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var configuredSps: ByteArray? = null

    private var codec: MediaCodec? = null
    private var callbackThread: HandlerThread? = null
    private val inputs = LinkedBlockingQueue<Int>()
    @Volatile private var broken = false
    private var waitingForKey = true

    private val au = ByteArrayOutputStream()
    private var auHasPicture = false
    private var auIsKey = false

    fun feed(chunk: ByteArray) {
        var i = 0
        var start = -1
        var startLen = 0
        while (i + 3 <= chunk.size) {
            val sc = if (chunk[i] == 0.toByte() && chunk[i + 1] == 0.toByte()) {
                if (chunk[i + 2] == 1.toByte()) 3
                else if (i + 4 <= chunk.size && chunk[i + 2] == 0.toByte() && chunk[i + 3] == 1.toByte()) 4 else 0
            } else 0
            if (sc > 0) {
                if (start >= 0) nal(chunk.copyOfRange(start + startLen, i))
                start = i
                startLen = sc
                i += sc
            } else {
                i++
            }
        }
        if (start >= 0) nal(chunk.copyOfRange(start + startLen, chunk.size))
    }

    private fun nal(n: ByteArray) {
        if (n.isEmpty() || (n[0].toInt() and 0x80) != 0) return
        val b0 = n[0].toInt() and 0xFF
        if (hevc == null) {
            hevc = when {
                (b0 and 0x1F) == 7 -> false
                ((b0 shr 1) and 0x3F) == 32 -> true
                else -> return // wait for the first parameter set
            }
        }
        val isHevc = hevc == true
        val type = if (isHevc) (b0 shr 1) and 0x3F else b0 and 0x1F
        val isPicture: Boolean
        val isKey: Boolean
        val firstSliceOfPicture: Boolean
        val startsNewAu: Boolean
        if (isHevc) {
            isPicture = type < 32
            isKey = type in 16..23
            firstSliceOfPicture = isPicture && n.size > 2 && (n[2].toInt() and 0x80) != 0
            when (type) {
                32 -> vps = n
                33 -> sps = n
                34 -> pps = n
            }
            startsNewAu = type == 35 || (auHasPicture && (type in 32..34 || type == 39 || firstSliceOfPicture))
        } else {
            isPicture = type in 1..5
            isKey = type == 5
            firstSliceOfPicture = isPicture && n.size > 1 && (n[1].toInt() and 0x80) != 0
            when (type) {
                7 -> sps = n
                8 -> pps = n
            }
            startsNewAu = type == 9 || (auHasPicture && (type in 6..8 || firstSliceOfPicture))
        }
        if (startsNewAu) flushAu()
        au.write(START_CODE)
        au.write(n)
        if (isPicture) auHasPicture = true
        if (isKey) auIsKey = true
    }

    private fun flushAu() {
        val hasPicture = auHasPicture
        val key = auIsKey
        val bytes = au.toByteArray()
        au.reset()
        auHasPicture = false
        auIsKey = false
        if (!hasPicture) return
        if (broken) {
            releaseCodec()
            broken = false
        }
        if (codec == null || (key && sps != null && !sps.contentEquals(configuredSps))) {
            if (!key) return
            if (!configure()) return
        }
        if (waitingForKey && !key) return
        val c = codec ?: return
        val index = inputs.poll(60, TimeUnit.MILLISECONDS) ?: run {
            // The decoder is behind: drop this picture and pick up again at the next key frame.
            waitingForKey = true
            return
        }
        try {
            val buffer: ByteBuffer = c.getInputBuffer(index) ?: return
            buffer.clear()
            if (bytes.size > buffer.capacity()) {
                c.queueInputBuffer(index, 0, 0, 0, 0)
                waitingForKey = true
                return
            }
            buffer.put(bytes)
            c.queueInputBuffer(index, 0, bytes.size, SystemClock.elapsedRealtimeNanos() / 1000,
                if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            waitingForKey = false
        } catch (e: Exception) {
            Log.w(TAG, "decoder input: ${e.message}")
            broken = true
            waitingForKey = true
        }
    }

    private fun configure(): Boolean {
        releaseCodec()
        val isHevc = hevc == true
        val s = sps ?: return false
        val p = pps ?: return false
        if (isHevc && vps == null) return false
        val mime = if (isHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, 1920, 1080).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
            if (isHevc) {
                setByteBuffer("csd-0", ByteBuffer.wrap(START_CODE + vps!! + START_CODE + s + START_CODE + p))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(START_CODE + s))
                setByteBuffer("csd-1", ByteBuffer.wrap(START_CODE + p))
            }
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        // The hardware decoder first; software ones if it refuses (too many running, say).
        val names = decoderNames(mime)
        for (name in names) {
            val thread = HandlerThread("dec-$name").also { it.start() }
            var c: MediaCodec? = null
            try {
                c = MediaCodec.createByCodecName(name)
                inputs.clear()
                c.setCallback(object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                        inputs.offer(index)
                    }

                    override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                        try {
                            codec.releaseOutputBuffer(index, info.size > 0)
                            if (info.size > 0) framesShown++
                        } catch (_: Exception) { }
                    }

                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                        Log.w(TAG, "decoder error: ${e.diagnosticInfo}")
                        broken = true
                    }

                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                        var w = format.getInteger(MediaFormat.KEY_WIDTH)
                        var h = format.getInteger(MediaFormat.KEY_HEIGHT)
                        if (format.containsKey("crop-right") && format.containsKey("crop-left")) {
                            w = format.getInteger("crop-right") - format.getInteger("crop-left") + 1
                        }
                        if (format.containsKey("crop-bottom") && format.containsKey("crop-top")) {
                            h = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
                        }
                        onSize(w, h)
                    }
                }, Handler(thread.looper))
                c.configure(format, surface, null, 0)
                c.start()
                codec = c
                callbackThread = thread
                configuredSps = s
                waitingForKey = true
                return true
            } catch (e: Exception) {
                Log.w(TAG, "decoder $name unavailable: ${e.message}")
                runCatching { c?.release() }
                thread.quitSafely()
            }
        }
        return false
    }

    private fun decoderNames(mime: String): List<String> {
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
        val hardware = all.filter { info ->
            if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
            else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
        }
        return (hardware + (all - hardware.toSet())).map { it.name }
    }

    private fun releaseCodec() {
        val c = codec
        codec = null
        if (c != null) {
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        callbackThread?.quitSafely()
        callbackThread = null
        inputs.clear()
    }

    fun release() {
        releaseCodec()
    }

    companion object {
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
