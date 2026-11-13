package com.example.bleapp

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.core.content.edit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private val MAC_FORMAT = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

        /**
         * Why the upload URL is checked before tracking starts: a malformed or
         * plain-http address fails silently. The upload just lands in the
         * offline cache and is retried forever, so the app looks like it is
         * working while no data ever reaches the doctor. Catching it at the
         * point of entry turns a silent failure into a visible one.
         *
         * Returns null when the URL is usable, otherwise the reason it is not.
         */
        fun serverUrlError(url: String): String? {
            val value = url.trim()
            return when {
                value.isEmpty() -> "Introduce la URL del servidor"
                // Patient data: the server redirects to HTTPS and Android
                // blocks cleartext by default, so http would never arrive.
                !value.startsWith("https://") -> "La URL debe empezar por https://"
                value.toHttpUrlOrNull() == null -> "La URL no es valida"
                else -> null
            }
        }

        /**
         * Non-blocking: the server rejects an untokened upload with 401, but a
         * server configured without a token is legitimate, so this warns
         * instead of refusing.
         */
        fun serverUrlWarning(url: String): String? {
            val value = url.trim()
            if (serverUrlError(value) != null) return null
            val token = value.toHttpUrlOrNull()?.queryParameter("token")
            return if (token.isNullOrEmpty()) "Aviso: falta ?token=... en la URL" else null
        }

        /**
         * Whether the app may talk to the device at all. From Android 12 that
         * is a runtime permission, and from Android 14 starting the
         * connected-device foreground service without it is a crash, so every
         * place that starts the service asks this first.
         */
        fun hasBluetoothPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

        /** Returns null when the MAC is usable, otherwise the reason it is not. */
        fun macAddressError(mac: String): String? {
            val value = mac.trim().uppercase()
            return when {
                value.isEmpty() -> "Introduce la MAC del dispositivo"
                !MAC_FORMAT.matches(value) -> "Formato: AA:BB:CC:DD:EE:FF"
                else -> null
            }
        }
    }

    private val prefs = application.getSharedPreferences("NiclaPrefs", Context.MODE_PRIVATE)

    private val _serverUrl = MutableStateFlow(prefs.getString("SERVER_URL", "") ?: "")
    val serverUrl = _serverUrl.asStateFlow()

    private val _macAddress = MutableStateFlow(
        prefs.getString("PAIRED_MAC", prefs.getString("SAVED_MAC", "")) ?: ""
    )
    val macAddress = _macAddress.asStateFlow()

    private val _isTracking = MutableStateFlow(prefs.getString("PAIRED_MAC", "")?.length == 17)
    val isTracking = _isTracking.asStateFlow()

    /** Trimmed on the way in: a trailing space pasted with the URL breaks the request. */
    fun setServerUrl(url: String) {
        val value = url.trim()
        _serverUrl.value = value
        prefs.edit { putString("SERVER_URL", value) }
    }

    fun setMacAddress(mac: String) {
        _macAddress.value = mac.uppercase()

        prefs.edit { putString("SAVED_MAC", mac.uppercase()) }
    }

    /** True when both fields are valid, so the UI can disable the connect button. */
    fun canStartTracking(): Boolean =
        serverUrlError(_serverUrl.value) == null && macAddressError(_macAddress.value) == null

    fun startTracking() {
        val mac = _macAddress.value
        val context = getApplication<Application>()

        // Refuse rather than start a service that can only fail: without a
        // usable URL every upload would sit in the cache unsent.
        if (!canStartTracking() || !hasBluetoothPermission(context)) return

        if (mac.length == 17) {
            prefs.edit { putString("PAIRED_MAC", mac).putString("SAVED_MAC", mac) }

            val deviceContext = ContextCompat.createDeviceProtectedStorageContext(context) ?: context
            val securePrefs = deviceContext.getSharedPreferences("NiclaPrefs", Context.MODE_PRIVATE)
            securePrefs.edit { putString("PAIRED_MAC", mac) }

            _isTracking.value = true

            val serviceIntent = Intent(context, BleBackgroundService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }

    fun stopTracking() {
        val context = getApplication<Application>()
        _isTracking.value = false

        prefs.edit().remove("PAIRED_MAC").apply()

        val deviceContext = ContextCompat.createDeviceProtectedStorageContext(context) ?: context
        val securePrefs = deviceContext.getSharedPreferences("NiclaPrefs", Context.MODE_PRIVATE)
        securePrefs.edit { remove("PAIRED_MAC") }

        val stopIntent = Intent(context, BleBackgroundService::class.java).apply {
            action = "ACTION_STOP_SERVICE"
        }
        context.startService(stopIntent)
    }
}