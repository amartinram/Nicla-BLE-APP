package com.example.bleapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class BleBackgroundService : Service() {

    private lateinit var bleManager: NiclaBleManager
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val CHANNEL_ID = "BleServiceChannel"

    private var dataBuffer = StringBuilder()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bleManager = NiclaBleManager(this)

        bleManager.onDataReceived = { data ->
            Log.d("BleService", "Nicla says: $data")

            when {
                data == "START" -> {
                    dataBuffer.clear()
                }
                data.startsWith("END:") -> {
                    val totalSteps = data.substringAfter("END:")
                    sendDataToWeb("User_01", totalSteps, dataBuffer.toString())
                }
                data.startsWith("BATT:") -> {
                    val battLevel = data.substringAfter("BATT:")
                    sendDataToWeb("User_01_Battery", battLevel, "")
                }
                else -> {
                    dataBuffer.append(data)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nicla Link Active")
            .setContentText("Running in background to stream data.")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()

        startForeground(1, notification)

        val prefs = getSharedPreferences("NiclaPrefs", Context.MODE_PRIVATE)
        val savedAddress = prefs.getString("PAIRED_MAC", "")

        val deviceAddress = intent?.getStringExtra("DEVICE_ADDRESS") ?: savedAddress

        if (!deviceAddress.isNullOrEmpty()) {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val device = bluetoothManager.adapter.getRemoteDevice(deviceAddress)

            // This handles the "Out of Range / In Range" magic perfectly
            bleManager.connect(device)
                .retry(3, 100)
                .useAutoConnect(true)
                .enqueue()
        } else {
            // If there's no MAC address saved anywhere, stop the service.
            stopSelf()
        }

        return START_STICKY // Tells Android to restart this service if it gets killed
    }

    private fun sendDataToWeb(userId: String, totalSteps: String, csvData: String) {
        val prefs = getSharedPreferences("NiclaPrefs", Context.MODE_PRIVATE)
        val url = prefs.getString("WEBHOOK_URL", "") ?: ""

        if (url.isEmpty() || !url.startsWith("http")) return

        val jsonPayload = """
            {
                "user_id": "$userId", 
                "total_steps": "$totalSteps", 
                "csv_data": "$csvData"
            }
        """.trimIndent()

        val body = jsonPayload.toRequestBody(jsonMediaType)
        val request = Request.Builder().url(url).post(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("BleService", "HTTP Fail: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                Log.d("BleService", "Success: Daily CSV row added to Google!")
                response.close()
            }
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "BLE Background", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.disconnect().enqueue()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}