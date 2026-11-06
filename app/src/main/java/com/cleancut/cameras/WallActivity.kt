package com.cleancut.cameras

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/** The wall: cameras in a layout; tap one for full screen, long-press to rearrange. */
class WallActivity : AppCompatActivity() {
    private lateinit var wall: WallLayout
    private lateinit var bar: LinearLayout
    private lateinit var loading: View
    private lateinit var loadingText: TextView
    private val tiles = mutableListOf<CamView>()
    private val layoutButtons = mutableMapOf<String, TextView>()
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Store.hasAccount) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        if (Engine.loggedIn) showWall() else signIn()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.bg)) }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = dp(6f); setPadding(p, p, p, p)
        }
        for (spec in WallSpec.ALL) {
            val b = chip(spec.label) { setLayout(spec.key) }
            layoutButtons[spec.key] = b
            bar.addView(b)
        }
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(chip("Cameras") { pickCameras() })
        bar.addView(chip("⋮") { v -> menu(v) })
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(bar)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val content = FrameLayout(this)
        wall = WallLayout(this)
        content.addView(wall, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        loading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(ProgressBar(this@WallActivity))
            loadingText = TextView(this@WallActivity).apply {
                setTextColor(getColor(R.color.muted)); textSize = 14f; gravity = Gravity.CENTER
                setPadding(0, dp(12f), 0, 0)
            }
            addView(loadingText)
        }
        content.addView(loading, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun chip(text: String, onClick: (View) -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.muted))
        textSize = 13.5f
        typeface = Typeface.DEFAULT_BOLD
        val ph = dp(12f); val pv = dp(7f); setPadding(ph, pv, ph, pv)
        background = GradientDrawable().apply { cornerRadius = dp(8f).toFloat(); setColor(Color.TRANSPARENT) }
        setOnClickListener(onClick)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(2f)
        }
    }

    private fun signIn() {
        loading.visibility = View.VISIBLE
        loadingText.text = "Signing in to Hik-Connect…"
        io.execute {
            val result = runCatching { Engine.login(Store.user!!, Store.password!!) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { showWall() }.onFailure { e ->
                    loadingText.text = "Couldn't sign in: ${e.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: e}\n\nTap to try again"
                    loading.setOnClickListener { loading.setOnClickListener(null); signIn() }
                }
            }
        }
    }

    private fun showWall() {
        loading.visibility = View.GONE
        if (Store.order.isEmpty()) {
            val cams = Engine.cameras
            // This account's home cameras (37-44); otherwise the first eight.
            Store.order = if (cams.size >= 44) (37..44).toList() else cams.take(8).map { it.n }
        }
        render()
        if (started) tiles.forEach { it.setActive(true) }
    }

    private fun spec() = WallSpec.of(Store.layout)

    private fun render() {
        val spec = spec()
        wall.spec = spec
        layoutButtons.forEach { (key, b) ->
            val on = key == spec.key
            b.setTextColor(if (on) Color.WHITE else getColor(R.color.muted))
            (b.background as GradientDrawable).setColor(if (on) getColor(R.color.accent) else Color.TRANSPARENT)
        }
        val order = Store.order.filter { Engine.camera(it) != null }
        while (tiles.size < spec.slots) {
            val t = CamView(this)
            tiles += t
            wall.addView(t)
        }
        while (tiles.size > spec.slots) {
            val t = tiles.removeAt(tiles.size - 1)
            t.setActive(false)
            wall.removeView(t)
        }
        tiles.forEachIndexed { i, t ->
            val cam = order.getOrNull(i)?.let { Engine.camera(it) }
            val q = if (i == 0 && spec.big > 1) Quality.BIG else Quality.TILE
            t.bind(cam, q)
            t.setOnClickListener { cam?.let { openFull(it.n) } }
            t.setOnLongClickListener { v -> cam?.let { tileMenu(v, i) }; true }
            if (started) t.setActive(true)
        }
    }

    private fun setLayout(key: String) {
        Store.layout = key
        render()
    }

    private fun openFull(n: Int) {
        startActivity(Intent(this, FullActivity::class.java)
            .putExtra("n", n)
            .putExtra("order", Store.order.toIntArray()))
    }

    private fun tileMenu(anchor: View, slot: Int) {
        PopupMenu(this, anchor).apply {
            if (slot != 0) menu.add(0, 1, 0, "Make big")
            menu.add(0, 2, 1, "Remove from the wall")
            setOnMenuItemClickListener { item ->
                val order = Store.order.toMutableList()
                when (item.itemId) {
                    1 -> if (slot < order.size) { val x = order[slot]; order[slot] = order[0]; order[0] = x }
                    2 -> if (slot < order.size) order.removeAt(slot)
                }
                Store.order = order
                render()
                true
            }
        }.show()
    }

    private fun menu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Encryption codes…")
            menu.add(0, 2, 1, "Sign out")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> editCodes()
                    2 -> {
                        Store.signOut()
                        tiles.forEach { it.setActive(false) }
                        startActivity(Intent(this@WallActivity, LoginActivity::class.java))
                        finish()
                    }
                }
                true
            }
        }.show()
    }

    private fun editCodes() {
        val input = EditText(this).apply {
            setText(Store.codes ?: "")
            hint = "SERIAL=CODE, SERIAL=CODE"
            setSingleLine(false)
        }
        AlertDialog.Builder(this)
            .setTitle("Other recorders' codes")
            .setMessage("Only for recorders whose video encryption code differs from the one you signed in with.")
            .setView(FrameLayout(this).apply { val p = dp(20f); setPadding(p, 0, p, 0); addView(input) })
            .setPositiveButton("Save") { _, _ ->
                Store.codes = input.text.toString().replace(" ", "")
                tiles.forEach { it.setActive(false) }
                tiles.forEach { it.setActive(true) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- choose cameras ---------------------------------------------------------
    private fun pickCameras() {
        val cams = Engine.cameras
        if (cams.isEmpty()) return
        val search = EditText(this).apply { hint = "Search cameras or recorders"; setSingleLine() }
        val list = ListView(this)
        val adapter = PickerAdapter(cams)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val cam = adapter.rows[position] as? Cam ?: return@setOnItemClickListener
            val order = Store.order.toMutableList()
            if (!order.remove(cam.n)) order.add(cam.n)
            Store.order = order
            adapter.notifyDataSetChanged()
            render()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { adapter.filter(s?.toString() ?: "") }
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(16f); setPadding(p, dp(4f), p, 0)
            addView(TextView(this@WallActivity).apply {
                text = "Tap cameras in the order you want them; the first is the big tile. Tap again to remove."
                setTextColor(getColor(R.color.muted)); textSize = 12.5f
            })
            addView(search)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420f)))
        }
        AlertDialog.Builder(this)
            .setTitle("Cameras on the wall")
            .setView(box)
            .setPositiveButton("Done", null)
            .show()
    }

    private inner class PickerAdapter(private val all: List<Cam>) : BaseAdapter() {
        var rows: List<Any> = group(all)
            private set

        private fun group(cams: List<Cam>): List<Any> {
            val out = mutableListOf<Any>()
            cams.groupBy { it.recorder }.forEach { (rec, list) -> out += rec; out += list }
            return out
        }

        fun filter(q: String) {
            val needle = q.trim().lowercase()
            rows = group(if (needle.isEmpty()) all else all.filter { "${it.name} ${it.recorder}".lowercase().contains(needle) })
            notifyDataSetChanged()
        }

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun isEnabled(position: Int) = rows[position] is Cam
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Cam) 1 else 0

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            if (row !is Cam) {
                return (convertView as? TextView ?: TextView(this@WallActivity)).apply {
                    text = (row as String).uppercase()
                    setTextColor(getColor(R.color.muted)); textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(4f), dp(14f), 0, dp(6f))
                }
            }
            val v = convertView as? LinearLayout ?: LinearLayout(this@WallActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
                addView(TextView(this@WallActivity).apply {
                    gravity = Gravity.CENTER; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(dp(22f), dp(22f)).apply { marginEnd = dp(12f) }
                })
                addView(TextView(this@WallActivity).apply { textSize = 15f; setTextColor(getColor(R.color.text)) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@WallActivity).apply { textSize = 12f; setTextColor(getColor(R.color.muted)) })
            }
            val pos = Store.order.indexOf(row.n)
            (v.getChildAt(0) as TextView).apply {
                text = if (pos >= 0) "${pos + 1}" else ""
                setTextColor(if (pos >= 0) Color.WHITE else getColor(R.color.muted))
                background = GradientDrawable().apply {
                    cornerRadius = dp(5f).toFloat()
                    setColor(if (pos >= 0) getColor(R.color.accent) else getColor(R.color.line))
                }
            }
            (v.getChildAt(1) as TextView).text = row.name
            (v.getChildAt(2) as TextView).text = "#${row.n}"
            return v
        }
    }

    // --- lifecycle: streams only while the wall is on screen -----------------------
    override fun onStart() {
        super.onStart()
        started = true
        if (Engine.loggedIn) {
            if (tiles.isEmpty()) render()
            tiles.forEach { it.setActive(true) }
        }
    }

    override fun onStop() {
        started = false
        tiles.forEach { it.setActive(false) }
        super.onStop()
    }

    companion object {
        val io = Executors.newSingleThreadExecutor()
    }
}
