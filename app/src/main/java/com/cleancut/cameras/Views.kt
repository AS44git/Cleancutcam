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
 * One camera on a TextureView, fitted inside the view with its own aspect ratio, with
 * its name and a status line. Plays while active and its surface exists.
 */
open class CamView(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener, Player.Listener {
    val texture = TextureView(context)
    private val name = TextView(context)
    private val status = TextView(context)
    private var surface: Surface? = null
    private var player: Player? = null
    private var videoW = 0
    private var videoH = 0

    var cam: Cam? = null
        private set
    var quality: Quality = Quality.TILE
        private set
    private var active = false

    /** Extra zoom/pan on top of the fit (full screen). */
    val zoom = Matrix()

    init {
        setBackgroundColor(context.getColor(R.color.tile))
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        texture.surfaceTextureListener = this
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
        stopPlayer()
        cam = camera
        quality = q
        videoW = 0
        videoH = 0
        name.text = camera?.name ?: ""
        name.visibility = if (camera == null) View.GONE else View.VISIBLE
        status.text = if (camera == null) "Empty · tap to add a camera" else ""
        maybeStart()
    }

    fun setNameVisible(visible: Boolean) {
        name.visibility = if (visible && cam != null) View.VISIBLE else View.GONE
    }

    fun setActive(on: Boolean) {
        active = on
        if (on) maybeStart() else stopPlayer()
    }

    private fun maybeStart() {
        val c = cam ?: return
        val s = surface ?: return
        if (!active || player != null) return
        status.text = "Connecting…"
        player = Player(c.n, quality, s, this).start()
    }

    private fun stopPlayer() {
        player?.stop()
        player = null
    }

    // --- Player.Listener (background threads) --------------------------------
    override fun onStatus(text: String?) {
        post { status.text = text ?: "" }
    }

    override fun onVideoSize(width: Int, height: Int) {
        post {
            videoW = width
            videoH = height
            applyTransform()
        }
    }

    // --- surface -------------------------------------------------------------
    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(st)
        applyTransform()
        maybeStart()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        applyTransform()
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        stopPlayer()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { applyTransform() }
    }

    /** Fit the video inside the view (letterboxed), then the zoom on top. */
    fun applyTransform() {
        val vw = texture.width.toFloat()
        val vh = texture.height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val m = Matrix()
        if (videoW > 0 && videoH > 0) {
            val videoAspect = videoW.toFloat() / videoH
            val viewAspect = vw / vh
            if (videoAspect > viewAspect) m.setScale(1f, viewAspect / videoAspect, vw / 2, vh / 2)
            else m.setScale(videoAspect / viewAspect, 1f, vw / 2, vh / 2)
        }
        m.postConcat(zoom)
        texture.setTransform(m)
    }

    /** The picture's rectangle inside the view, before zoom (for limiting pans). */
    fun fittedSize(): Pair<Float, Float> {
        val vw = texture.width.toFloat()
        val vh = texture.height.toFloat()
        if (videoW <= 0 || videoH <= 0) return vw to vh
        val videoAspect = videoW.toFloat() / videoH
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
