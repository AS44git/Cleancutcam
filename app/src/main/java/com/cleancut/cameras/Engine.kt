package com.cleancut.cameras

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray

class CamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        Store.init(this)
    }
}

data class Cam(val n: Int, val name: String, val recorder: String, val serial: String, val channel: Int)

/** The Hik-Connect side, in Python (camengine.py), behind a small Kotlin face. */
object Engine {
    @Volatile var cameras: List<Cam> = emptyList()
        private set

    private val module: PyObject get() = Python.getInstance().getModule("camengine")

    /** Logs in (network: call off the main thread) and returns the account's cameras. */
    fun login(user: String, password: String): List<Cam> {
        val json = module.callAttr("login", user, password).toString()
        val array = JSONArray(json)
        val list = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Cam(o.getInt("n"), o.getString("name"), o.getString("recorder"),
                o.getString("serial"), o.getInt("channel"))
        }
        cameras = list
        return list
    }

    val loggedIn: Boolean get() = cameras.isNotEmpty()

    fun relogin() {
        module.callAttr("relogin")
    }

    fun camera(n: Int): Cam? = cameras.getOrNull(n - 1)

    /** Opens a live stream (network). The result's read() gives NAL units, close() ends it. */
    fun open(n: Int, streamType: Int, code: String?): PyObject =
        module.callAttr("Stream", n, streamType, code)
}

/** Saved sign-in (encrypted on the phone) and the wall's layout. */
object Store {
    private lateinit var secure: SharedPreferences
    private lateinit var plain: SharedPreferences

    fun init(context: Context) {
        plain = context.getSharedPreferences("wall", Context.MODE_PRIVATE)
        secure = try {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                context, "account", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // A broken keystore entry (after a restore, say): start over with a clean file.
            context.deleteSharedPreferences("account")
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                context, "account", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }

    var user: String?
        get() = secure.getString("user", null)
        set(v) = secure.edit().putString("user", v).apply()
    var password: String?
        get() = secure.getString("password", null)
        set(v) = secure.edit().putString("password", v).apply()
    /** The video encryption code used for every recorder. */
    var code: String?
        get() = secure.getString("code", null)
        set(v) = secure.edit().putString("code", v).apply()
    /** Codes for recorders whose code differs, as SERIAL=CODE,SERIAL=CODE. */
    var codes: String?
        get() = secure.getString("codes", null)
        set(v) = secure.edit().putString("codes", v).apply()

    val hasAccount: Boolean get() = !user.isNullOrEmpty() && !password.isNullOrEmpty()

    fun codeFor(serial: String): String? {
        codes?.split(',')?.forEach { pair ->
            val (s, c) = pair.split('=', limit = 2).let { if (it.size == 2) it[0].trim() to it[1].trim() else "" to "" }
            if (s == serial && c.isNotEmpty()) return c
        }
        return code?.takeIf { it.isNotEmpty() }
    }

    fun signOut() {
        secure.edit().clear().apply()
    }

    var layout: String
        get() = plain.getString("layout", "7+1") ?: "7+1"
        set(v) = plain.edit().putString("layout", v).apply()

    /** The wall's cameras, by number, spot by spot (the first is the big tile); 0 is an empty spot. */
    var order: List<Int>
        get() = plain.getString("order", null)?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
        set(v) = plain.edit().putString("order", v.dropLastWhile { it <= 0 }.joinToString(",")).apply()

    /** Whether a wall was ever saved (an empty one stays empty). */
    val hasOrder: Boolean get() = plain.contains("order")
}
