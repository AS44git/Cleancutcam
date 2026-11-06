package com.cleancut.cameras

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Sign in with the Hik-Connect account; saved encrypted on the phone. */
class LoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val user = field("Hik-Connect username", InputType.TYPE_CLASS_TEXT)
        val pass = field("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val code = field("Video encryption code (if the recorder uses one)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        Store.user?.let { user.setText(it) }
        Store.code?.let { code.setText(it) }
        val error = TextView(this).apply { setTextColor(0xFFFF6B6B.toInt()); textSize = 13.5f }
        val progress = ProgressBar(this).apply { visibility = View.GONE }
        val button = Button(this).apply { text = "Sign in" }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = dp(24f); setPadding(p, dp(56f), p, p)
            addView(TextView(this@LoginActivity).apply {
                text = "Cameras"; textSize = 26f; typeface = Typeface.DEFAULT_BOLD; setTextColor(getColor(R.color.text))
            })
            addView(TextView(this@LoginActivity).apply {
                text = "Sign in with your Hik-Connect account. It stays on this phone, encrypted."
                textSize = 14f; setTextColor(getColor(R.color.muted)); gravity = Gravity.CENTER
                setPadding(0, dp(8f), 0, dp(24f))
            })
            listOf(user, pass, code).forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
            addView(error)
            addView(progress)
            addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16f) })
        }
        setContentView(ScrollView(this).apply { addView(box) })

        button.setOnClickListener {
            val u = user.text.toString().trim()
            val p = pass.text.toString()
            if (u.isEmpty() || p.isEmpty()) {
                error.text = "Enter the username and password."
                return@setOnClickListener
            }
            button.isEnabled = false
            progress.visibility = View.VISIBLE
            error.text = ""
            WallActivity.io.execute {
                val result = runCatching { Engine.login(u, p) }
                runOnUiThread {
                    progress.visibility = View.GONE
                    button.isEnabled = true
                    result.onSuccess {
                        Store.user = u
                        Store.password = p
                        Store.code = code.text.toString().trim()
                        startActivity(Intent(this, WallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    }.onFailure { e ->
                        val msg = e.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: e.toString()
                        error.text = "Couldn't sign in: $msg"
                    }
                }
            }
        }
    }

    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        inputType = type
        setSingleLine()
    }
}
