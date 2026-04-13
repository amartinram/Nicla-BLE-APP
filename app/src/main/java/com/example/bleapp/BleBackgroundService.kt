package com.example.bleapp

import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.StringBuilder
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class BleBackgroundService : Service() {

    private lateinit var bleManager: NiclaBleManager
    private val dataBuffer = StringBuilder()

    // --- NEW: THE BLUETOOTH TOGGLE LISTENER ---
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)

                if (state == BluetoothAdapter.STATE_ON) {
                    Log.d("BleService", "User turned Bluetooth ON. Forcing reconnect...")
                    connectToNicla() // Restart the connection engine!
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    Log.d("BleService", "User turned Bluetooth OFF. Going to sleep...")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bleManager = NiclaBleManager(this)

        // Register our Bluetooth Toggle Listener
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothStateReceiver, filter)

        val prefs = getSharedPreferences("NiclaPrefs", MODE_PRIVATE)

        bleManager.onDataReceived = { bytes ->
            val rawMac = prefs.getString("PAIRED_MAC", "Unknown_Device") ?: "Unknown_Device"
            val deviceId = rawMac.replace(":", "_")
            val textRepresentation = String(bytes, Charsets.UTF_8)

            when {
                textRepresentation == "START" -> { dataBuffer.clear() }
                textRepresentation.startsWith("END:") -> {
                    val totalSteps = textRepresentation.substringAfter("END:")
                    sendDataToGoogleNative(deviceId, totalSteps, dataBuffer.toString())
                }
                textRepresentation.startsWith("BATT:") -> {
                    val battLevel = textRepresentation.substringAfter("BATT:")
                    sendDataToGoogleNative("${deviceId}_Battery", battLevel, "")
                }
                else -> {
                    for (byte in bytes) {
                        val stepsInMinute = byte.toInt() and 0xFF
                        dataBuffer.append("$stepsInMinute,")
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_STOP_SERVICE") {
            bleManager.disconnect().enqueue()
            bleManager.close()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = NotificationCompat.Builder(this, "NICLA_CHANNEL")
            .setContentTitle("Nicla Tracker Active")
            .setContentText("Listening for data in background...")
            .setSmallIcon(R.drawable.ic_dialog_info)
            .build()

        startForeground(1, notification)

        // Start the initial connection
        connectToNicla()

        return START_STICKY
    }

    // --- NEW: EXTRACTED CONNECTION LOGIC ---
    private fun connectToNicla() {
        val prefs = getSharedPreferences("NiclaPrefs", MODE_PRIVATE)
        val savedMac = prefs.getString("PAIRED_MAC", null)

        if (savedMac != null && savedMac.length == 17) {
            val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter

            // Only try to connect if the physical Bluetooth radio is actually ON
            if (adapter != null && adapter.isEnabled) {
                try {
                    val device = adapter.getRemoteDevice(savedMac)
                    bleManager.connect(device)
                        .retry(3, 100)
                        .useAutoConnect(true)
                        .enqueue()
                    Log.d("BleService", "Auto-Connect engine started for $savedMac")
                } catch (e: Exception) {
                    Log.e("BleService", "Error connecting: ${e.message}")
                }
            }
        }
    }

    private fun sendDataToGoogleNative(sheetName: String, steps: String, csv: String) {
        val prefs = getSharedPreferences("NiclaPrefs", MODE_PRIVATE)
        val webhookUrl = prefs.getString("SERVER_URL", "") ?: return
        if (webhookUrl.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(webhookUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true

                val postData = "sheetName=${URLEncoder.encode(sheetName, "UTF-8")}" +
                        "&steps=${URLEncoder.encode(steps, "UTF-8")}" +
                        "&logData=${URLEncoder.encode(csv, "UTF-8")}"

                val bytes = postData.toByteArray(Charsets.UTF_8)
                connection.setRequestProperty("Content-Length", bytes.size.toString())
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

                connection.outputStream.write(bytes)
                val responseCode = connection.responseCode
                Log.d("BleService", "Google Received Data! HTTP Code: $responseCode")
                connection.disconnect()
            } catch (e: Exception) {
                Log.e("BleService", "Native Network Error: ${e.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("NICLA_CHANNEL", "Nicla Sync", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up our listener so the app doesn't leak memory!
        unregisterReceiver(bluetoothStateReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}