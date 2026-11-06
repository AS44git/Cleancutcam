package com.cleancut.cameras

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.min

fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

/**
 * The shape a picture is meant to be seen at. These recorders send 16:9 pictures squeezed
 * into narrower frames (main 960x1080 is half of 1920x1080, sub 352x288 is CIF) without
 * saying so in the stream, so anything narrower than 5:4 is shown as 16:9.
 */
fun displayAspect(w: Int, h: Int): Float {
    val a = w.toFloat() / h
    return if (a < 1.25f) 16f / 9f else a
}

/**
 * One camera, fitted inside the view at its real shape, with its name and a status line.
 * Plays while active and its surface exists.
 *
 * The big tile and full screen play the sharp main stream, which can take several seconds
 * to show (it sends a whole picture only every few seconds). So they also start the quick
 * sub stream on a layer on top: a picture at once, swapped for the sharp one as soon as
 * that plays. If the sharp stream never comes, or drops for good, the quick one stays.
 */
open class CamView(context: Context) : FrameLayout(context) {
    private val name = TextView(context)
    private val status = TextView(context)
    private val main = Layer()
    private val preview = Layer()
    private var mainText: String? = null

    var cam: Cam? = null
        private set
    var quality: Quality = Quality.TILE
        private set
    private var active = false
    private val usesPreview get() = quality != Quality.TILE

    /** Extra zoom/pan on top of the fit (full screen). */
    val zoom = Matrix()

    /** One TextureView with its own stream. */
    private inner class Layer : TextureView.SurfaceTextureListener {
        val texture = TextureView(context).also { it.surfaceTextureListener = this }
        var surface: Surface? = null
        var player: Player? = null
        var videoW = 0
        var videoH = 0
        var showing = false
        private var gen = 0

        val running get() = player != null

        fun start(c: Cam, q: Quality) {
            val s = surface ?: return
            if (player != null) return
            showing = false
            val g = ++gen
            player = Player(c.n, q, s, object : Player.Listener {
                override fun onStatus(text: String?) {
                    post { if (g == gen) layerStatus(this@Layer, text) }
                }

                override fun onVideoSize(width: Int, height: Int) {
                    post {
                        if (g == gen) {
                            videoW = width
                            videoH = height
                            applyTransform()
                        }
                    }
                }
            }).start()
        }

        fun stop() {
            gen++
            player?.stop()
            player = null
            showing = false
        }

        fun fit(zoom: Matrix) {
            val vw = texture.width.toFloat()
            val vh = texture.height.toFloat()
            if (vw <= 0 || vh <= 0) return
            val m = Matrix()
            if (videoW > 0 && videoH > 0) {
                // The TextureView stretches the picture to the view; scale it back to its shape.
                val videoAspect = displayAspect(videoW, videoH)
                val viewAspect = vw / vh
                if (videoAspect > viewAspect) m.setScale(1f, viewAspect / videoAspect, vw / 2, vh / 2)
                else m.setScale(videoAspect / viewAspect, 1f, vw / 2, vh / 2)
            }
            m.postConcat(zoom)
            texture.setTransform(m)
        }

        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
            surface = Surface(st)
            applyTransform()
            maybeStart()
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
            applyTransform()
        }

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            stop()
            surface?.release()
            surface = null
            return true
        }

        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    init {
        setBackgroundColor(context.getColor(R.color.tile))
        addView(main.texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // On top of the main picture; it shows nothing until it has a picture of its own.
        addView(preview.texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        name.apply {
            setTextColor(Color.WHITE)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            val pad = context.dp(6f)
            setPadding(pad, pad / 2, pad, pad / 2)
            background = GradientDrawable().apply { cornerRadius = context.dp(5f).toFloat(); setColor(0x8C000000.toInt()) }
        }
        addView(name, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            val m = context.dp(7f); setMargins(m, m, m, m)
        })
        status.apply {
            setTextColor(context.getColor(R.color.muted))
            textSize = 12f
            gravity = Gravity.CENTER
        }
        addView(status, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    fun bind(camera: Cam?, q: Quality) {
        if (camera == cam && q == quality) return
        stopPlayers()
        cam = camera
        quality = q
        for (l in listOf(main, preview)) { l.videoW = 0; l.videoH = 0 }
        mainText = null
        name.text = camera?.name ?: ""
        name.visibility = if (camera == null) View.GONE else View.VISIBLE
        status.text = if (camera == null) "＋\nAdd camera" else ""
        status.textSize = if (camera == null) 15f else 12f
        status.setTextColor(context.getColor(if (camera == null) R.color.text else R.color.muted))
        maybeStart()
    }

    fun setNameVisible(visible: Boolean) {
        name.visibility = if (visible && cam != null) View.VISIBLE else View.GONE
    }

    fun setActive(on: Boolean) {
        active = on
        if (on) maybeStart() else stopPlayers()
    }

    private fun maybeStart() {
        val c = cam ?: return
        if (!active) return
        val first = !main.running && !preview.running
        main.start(c, quality)
        if (usesPreview && !main.showing && !preview.running) {
            preview.texture.visibility = View.VISIBLE
            preview.start(c, Quality.PREVIEW)
        }
        if (first && (main.running || preview.running)) status.text = "Connecting…"
    }

    private fun stopPlayers() {
        main.stop()
        preview.stop()
        preview.texture.visibility = View.VISIBLE
    }

    private fun layerStatus(layer: Layer, text: String?) {
        layer.showing = text == null
        if (layer === main) {
            mainText = text
            if (text == null && preview.running) {
                // The sharp picture is playing: the quick one is no longer needed.
                preview.stop()
                preview.texture.visibility = View.INVISIBLE
            } else if (text != null && text.contains("unavailable") && usesPreview && !preview.running) {
                // The sharp stream keeps failing: bring the quick one back meanwhile.
                cam?.let { c ->
                    if (active) {
                        preview.texture.visibility = View.VISIBLE
                        preview.start(c, Quality.PREVIEW)
                    }
                }
            }
        }
        status.text = if (main.showing || preview.showing) "" else (mainText ?: text ?: "")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { applyTransform() }
    }

    /** Fit the pictures inside the view (letterboxed), then the zoom on top. */
    fun applyTransform() {
        main.fit(zoom)
        preview.fit(zoom)
    }

    /** The picture's rectangle inside the view, before zoom (for limiting pans). */
    fun fittedSize(): Pair<Float, Float> {
        val l = if (main.videoW > 0) main else preview
        val vw = l.texture.width.toFloat()
        val vh = l.texture.height.toFloat()
        if (l.videoW <= 0 || l.videoH <= 0) return vw to vh
        val videoAspect = displayAspect(l.videoW, l.videoH)
        val viewAspect = vw / vh
        return if (videoAspect > viewAspect) vw to vw / videoAspect else vh * videoAspect to vh
    }
}

/** Wall layouts: a grid, optionally with the first tile spanning big×big cells. */
data class WallSpec(val key: String, val label: String, val cols: Int, val rows: Int, val big: Int) {
    val slots: Int get() = cols * rows - (big * big - 1)

    companion object {
        val ALL = listOf(
            WallSpec("7+1", "7+1", 4, 4, 3),
            WallSpec("1+5", "1+5", 3, 3, 2),
            WallSpec("2x2", "2×2", 2, 2, 1),
            WallSpec("3x3", "3×3", 3, 3, 1),
            WallSpec("4x4", "4×4", 4, 4, 1),
        )

        fun of(key: String) = ALL.firstOrNull { it.key == key } ?: ALL[0]
    }
}

/**
 * Lays its children out as the wall: cells keep a 16:9 shape and the grid is centred in
 * the space, so tiles look right in portrait and landscape alike.
 */
class WallLayout(context: Context) : ViewGroup(context) {
    var spec: WallSpec = WallSpec.ALL[0]
        set(value) {
            field = value
            requestLayout()
        }
    private val gap = context.dp(3f)

    private fun cells(): List<IntArray> {
        // [col, row, colSpan, rowSpan] per slot, row by row, around the big tile.
        val taken = Array(spec.rows) { BooleanArray(spec.cols) }
        val out = mutableListOf<IntArray>()
        if (spec.big > 1) {
            for (r in 0 until spec.big) for (c in 0 until spec.big) taken[r][c] = true
            out += intArrayOf(0, 0, spec.big, spec.big)
        }
        for (r in 0 until spec.rows) for (c in 0 until spec.cols) {
            if (!taken[r][c]) out += intArrayOf(c, r, 1, 1)
        }
        return out
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val (cellW, cellH) = cellSize(w, h)
        val list = cells()
        for (i in 0 until childCount) {
            val cell = list.getOrNull(i) ?: intArrayOf(0, 0, 1, 1)
            val cw = cellW * cell[2] + gap * (cell[2] - 1)
            val ch = cellH * cell[3] + gap * (cell[3] - 1)
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
        }
    }

    private fun cellSize(w: Int, h: Int): Pair<Int, Int> {
        val maxW = (w - gap * (spec.cols + 1)) / spec.cols
        val maxH = (h - gap * (spec.rows + 1)) / spec.rows
        val cellW = min(maxW, maxH * 16 / 9)
        return cellW to cellW * 9 / 16
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val (cellW, cellH) = cellSize(w, h)
        val gridW = cellW * spec.cols + gap * (spec.cols - 1)
        val gridH = cellH * spec.rows + gap * (spec.rows - 1)
        val left = (w - gridW) / 2
        val top = (h - gridH) / 2
        val list = cells()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val cell = list.getOrNull(i)
            if (cell == null) {
                child.layout(0, 0, 0, 0)
                continue
            }
            val x = left + cell[0] * (cellW + gap)
            val y = top + cell[1] * (cellH + gap)
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }
}
