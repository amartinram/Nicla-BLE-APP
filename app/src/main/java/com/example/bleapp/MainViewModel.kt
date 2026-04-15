package com.example.bleapp

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("NiclaPrefs", Context.MODE_PRIVATE)

    private val _serverUrl = MutableStateFlow(prefs.getString("SERVER_URL", "") ?: "")
    val serverUrl = _serverUrl.asStateFlow()

    private val _macAddress = MutableStateFlow(prefs.getString("PAIRED_MAC", "") ?: "")
    val macAddress = _macAddress.asStateFlow()

    private val _isTracking = MutableStateFlow(prefs.getString("PAIRED_MAC", "")?.length == 17)
    val isTracking = _isTracking.asStateFlow()

    fun setServerUrl(url: String) {
        _serverUrl.value = url
        prefs.edit().putString("SERVER_URL", url).apply()
    }

    fun setMacAddress(mac: String) {
        _macAddress.value = mac.uppercase()
    }

    fun startTracking() {
        val mac = _macAddress.value
        val app = getApplication<Application>()

        if (mac.length == 17) {
            prefs.edit().putString("PAIRED_MAC", mac).apply()

            val deviceContext = ContextCompat.createDeviceProtectedStorageContext(app) ?: app
            val securePrefs = deviceContext.getSharedPreferences("NiclaPrefs", Context.MODE_PRIVATE)
            securePrefs.edit().putString("PAIRED_MAC", mac).apply()

            _isTracking.value = true

            val serviceIntent = Intent(app, BleBackgroundService::class.java)
            ContextCompat.startForegroundService(app, serviceIntent)
        }
    }

    fun stopTracking() {
        val app = getApplication<Application>()
        _isTracking.value = false
        _macAddress.value = ""
        prefs.edit().remove("PAIRED_MAC").apply()

        val stopIntent = Intent(app, BleBackgroundService::class.java).apply {
            action = "ACTION_STOP_SERVICE"
        }
        app.startService(stopIntent)
    }
}