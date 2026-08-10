package com.xdo.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xdo.app.BuildConfig
import com.xdo.app.dl.AppPrefsHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefsHolder.get(app)

    private val _defaultQualityIndex = MutableStateFlow(prefs.defaultQualityIndex)
    val defaultQualityIndex: StateFlow<Int> = _defaultQualityIndex.asStateFlow()

    private val _wifiOnly = MutableStateFlow(prefs.wifiOnly)
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private val _snack = MutableStateFlow<String?>(null)
    val snack: StateFlow<String?> = _snack.asStateFlow()

    fun consumeSnack() {
        _snack.value = null
    }

    fun setDefaultQuality(index: Int) {
        prefs.defaultQualityIndex = index
        _defaultQualityIndex.value = index
    }

    fun setWifiOnly(value: Boolean) {
        prefs.wifiOnly = value
        _wifiOnly.value = value
    }

    fun clearHistory(onCleared: () -> Unit) {
        viewModelScope.launch {
            com.xdo.app.dl.AppHolder.downloadManager?.clearHistory()
            _snack.value = "历史已清空"
            onCleared()
        }
    }

    val versionName: String = BuildConfig.VERSION_NAME
}
