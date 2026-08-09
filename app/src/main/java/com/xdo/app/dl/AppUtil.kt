package com.xdo.app.dl

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object AppUtil {

    fun isMetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        // 没有 NOT_METERED 能力 → 视为计费网络
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun isWifiOnly(context: Context): Boolean =
        AppPrefsHolder.get(context).wifiOnly
}

object AppPrefsHolder {
    private var prefs: com.xdo.app.data.AppPrefs? = null
    fun init(prefs: com.xdo.app.data.AppPrefs) { this.prefs = prefs }
    fun get(context: Context): com.xdo.app.data.AppPrefs =
        prefs ?: com.xdo.app.data.AppPrefs(context.applicationContext).also { prefs = it }
}