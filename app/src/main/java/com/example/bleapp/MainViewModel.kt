package com.example.bleapp

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow


class MainViewModel(private val context: Context) : ViewModel() {
    private val prefs = context.getSharedPreferences("NiclaPrefs", Context.MODE_PRIVATE)

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
        if (mac.length == 17) {
            // Save to normal storage for UI
            prefs.edit().putString("PAIRED_MAC", mac).apply()

            // Save to Secure Boot storage so BootReceiver can read it before PIN is entered!
            val deviceContext = ContextCompat.createDeviceProtectedStorageContext(context) ?: context
            val securePrefs = deviceContext.getSharedPreferences("NiclaPrefs", Context.MODE_PRIVATE)
            securePrefs.edit().putString("PAIRED_MAC", mac).apply()

            _isTracking.value = true

            val serviceIntent = Intent(context, BleBackgroundService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }

    fun stopTracking() {
        _isTracking.value = false
        _macAddress.value = ""
        prefs.edit().remove("PAIRED_MAC").apply()

        val stopIntent = Intent(context, BleBackgroundService::class.java).apply {
            action = "ACTION_STOP_SERVICE"
        }
        context.startService(stopIntent)
    }
}