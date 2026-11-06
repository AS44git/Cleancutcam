package com.cleancut.cameras

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One camera full screen, on its sharp main stream. Pinch or double-tap to zoom, drag to
 * look around, swipe left/right for the other cameras on the wall, back to return.
 */
class FullActivity : AppCompatActivity() {
    private lateinit var view: CamView
    private lateinit var label: TextView
    private var order = intArrayOf()
    private var number = 0
    private var scale = 1f
    private var panX = 0f
    private var panY = 0f
    /** Two fingers were down in this gesture: a pinch, never a swipe. */
    private var multiTouch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Engine.loggedIn) {
            // The app was restarted in the background: the wall signs in again first.
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        number = savedInstanceState?.getInt("n") ?: intent.getIntExtra("n", 1)
        order = intent.getIntArrayExtra("order") ?: intArrayOf(number)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        view = CamView(this).apply {
            setBackgroundColor(Color.BLACK)
            setNameVisible(false)
        }
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        label = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f
            val p = dp(8f); setPadding(p + 2, p / 2 + 2, p + 2, p / 2 + 2)
            background = GradientDrawable().apply { cornerRadius = dp(7f).toFloat(); setColor(0x80000000.toInt()) }
        }
        root.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            val m = dp(14f); setMargins(m, m, m, m)
        })
        setContentView(root)
        immersive()
        show(number)

        val scaler = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoomBy(d.scaleFactor, d.focusX, d.focusY)
                return true
            }
        })
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scale > 1.01f) resetZoom() else zoomBy(2.5f, e.x, e.y)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                flashLabel()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (scale <= 1.01f || e2.pointerCount > 1) return false
                panX -= dx
                panY -= dy
                clampAndApply()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (scale > 1.01f || e1 == null || multiTouch) return false
                val dx = e2.x - e1.x
                if (abs(dx) > dp(80f) && abs(dx) > abs(e2.y - e1.y) * 1.5f) {
                    step(if (dx < 0) 1 else -1)
                    return true
                }
                return false
            }
        })
        view.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) multiTouch = false
            if (ev.pointerCount > 1) multiTouch = true
            scaler.onTouchEvent(ev)
            if (!scaler.isInProgress) gestures.onTouchEvent(ev)
            true
        }
    }

    private fun show(n: Int) {
        number = n
        resetZoom()
        val cam = Engine.camera(n)
        view.bind(cam, Quality.FULL)
        label.text = cam?.name ?: "Camera $n"
        flashLabel()
        if (!isStopped) view.setActive(true)
    }

    private fun step(delta: Int) {
        if (order.size < 2) return
        var i = order.indexOf(number)
        i = if (i < 0) 0 else (i + delta + order.size) % order.size
        view.setActive(false)
        show(order[i])
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val newScale = max(1f, min(6f, scale * factor))
        val applied = newScale / scale
        // Keep the point under the fingers where it is.
        val cx = view.width / 2f
        val cy = view.height / 2f
        panX = (panX - (fx - cx)) * applied + (fx - cx)
        panY = (panY - (fy - cy)) * applied + (fy - cy)
        scale = newScale
        clampAndApply()
    }

    private fun resetZoom() {
        scale = 1f; panX = 0f; panY = 0f
        clampAndApply()
    }

    private fun clampAndApply() {
        val (pw, ph) = view.fittedSize()
        val maxX = max(0f, (pw * scale - view.width) / 2f)
        val maxY = max(0f, (ph * scale - view.height) / 2f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
        view.zoom.reset()
        view.zoom.postScale(scale, scale, view.width / 2f, view.height / 2f)
        view.zoom.postTranslate(panX, panY)
        view.applyTransform()
    }

    private fun flashLabel() {
        label.animate().cancel()
        label.alpha = 1f
        label.animate().alpha(0f).setStartDelay(2500).setDuration(600).start()
    }

    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    private var isStopped = true

    override fun onStart() {
        super.onStart()
        isStopped = false
        if (::view.isInitialized) view.setActive(true)
    }

    override fun onStop() {
        isStopped = true
        if (::view.isInitialized) view.setActive(false)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("n", number)
    }
}
