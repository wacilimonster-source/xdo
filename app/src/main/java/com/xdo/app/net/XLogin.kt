package com.xdo.app.net

import com.xdo.app.data.AppPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 跨界面共享的 X 登录态（Cookie 字符串）。
 *
 * 单一数据源：设置页与 WebView 登录页都读写此单例，
 * 避免受 Compose Navigation 按 destination 作用域 ViewModel 的影响
 * （不同 destination 的 viewModel() 实例不同，但单例始终一致）。
 * 持久化交由 AppPrefs 负责。
 */
object XLogin {
    private val _cookie = MutableStateFlow("")
    val cookie: StateFlow<String> = _cookie.asStateFlow()

    fun loadFrom(prefs: AppPrefs) {
        _cookie.value = prefs.xCookie
    }

    fun applyCookie(value: String, prefs: AppPrefs) {
        val v = value.trim()
        _cookie.value = v
        prefs.xCookie = v
    }

    fun clear(prefs: AppPrefs) {
        _cookie.value = ""
        prefs.xCookie = ""
    }
}
