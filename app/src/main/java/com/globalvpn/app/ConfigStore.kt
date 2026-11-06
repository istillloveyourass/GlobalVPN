package com.globalvpn.app

import android.content.Context
import android.content.SharedPreferences

/** Stores one WireGuard config (text) per country in app-private storage. */
object ConfigStore {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("globalvpn", Context.MODE_PRIVATE)
    }

    fun get(code: String): String? = prefs.getString("cfg_$code", null)

    fun has(code: String): Boolean = prefs.contains("cfg_$code")

    fun put(code: String, text: String) {
        prefs.edit().putString("cfg_$code", text.trim() + "\n").apply()
    }

    fun remove(code: String) {
        prefs.edit().remove("cfg_$code").apply()
    }

    var lastCountry: String?
        get() = prefs.getString("last_country", null)
        set(value) {
            prefs.edit().putString("last_country", value).apply()
        }
}
