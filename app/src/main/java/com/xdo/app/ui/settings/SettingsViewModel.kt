package com.xdo.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xdo.app.BuildConfig
import com.xdo.app.XDoApp
import com.xdo.app.dl.AppPrefsHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefsHolder.get(app)
    private val dao = (app as XDoApp).db.recordDao()

    private val _defaultQualityIndex = MutableStateFlow(prefs.defaultQualityIndex)
    val defaultQualityIndex: StateFlow<Int> = _defaultQualityIndex.asStateFlow()

    private val _wifiOnly = MutableStateFlow(prefs.wifiOnly)
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private val _snack = MutableStateFlow<String?>(null)
    val snack: StateFlow<String?> = _snack.asStateFlow()

    /** S-04：清空确认弹窗带记录数 */
    private val _recordCount = MutableStateFlow(0)
    val recordCount: StateFlow<Int> = _recordCount.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { _recordCount.value = dao.count() }
        }
    }

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

    /** S-04：清空后反馈带数量（记录数在删除前统计） */
    fun clearHistory(onCleared: () -> Unit) {
        viewModelScope.launch {
            val n = runCatching { dao.count() }.getOrDefault(0)
            com.xdo.app.dl.AppHolder.downloadManager?.clearHistory()
            _snack.value = "已清空 $n 条下载记录"
            _recordCount.value = 0
            onCleared()
        }
    }

    val versionName: String = BuildConfig.VERSION_NAME
}
