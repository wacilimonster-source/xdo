package com.xdo.app.data

import android.content.Context
import android.content.SharedPreferences

class AppPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("xdo_prefs", Context.MODE_PRIVATE)

    var defaultQualityIndex: Int
        get() = sp.getInt(KEY_QUALITY_INDEX, -1)
        set(v) = sp.edit().putInt(KEY_QUALITY_INDEX, v).apply()

    var wifiOnly: Boolean
        get() = sp.getBoolean(KEY_WIFI_ONLY, true)
        set(v) = sp.edit().putBoolean(KEY_WIFI_ONLY, v).apply()

    var lastAutoUpdateCheck: Long
        get() = sp.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_UPDATE_CHECK, v).apply()

    private companion object {
        const val KEY_QUALITY_INDEX = "default_quality_index"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
    }
}