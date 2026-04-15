package com.example.bleapp

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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class BleBackgroundService : Service() {

    private lateinit var bleManager: NiclaBleManager

    private val packetMutex = Mutex()
    private val dataBuffer = mutableListOf<Int>()
    private var expectedBytes = 0
    private var receivedBytes = 0
    private var currentTotalSteps = 0L

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    Log.d("BleService", "Bluetooth ON. Reconnecting...")
                    connectToNicla()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bleManager = NiclaBleManager(this)

        registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        val prefs = getSharedPreferences("NiclaPrefs", MODE_PRIVATE)

        bleManager.onDataReceived = { bytes ->
            val rawMac = prefs.getString("PAIRED_MAC", "Unknown_Device") ?: "Unknown_Device"
            val deviceId = rawMac.replace(":", "_")

            serviceScope.launch {

                packetMutex.withLock {

                    if (bytes.size == 9 && bytes[0] == 0xAA.toByte() && bytes[1] == 0xBB.toByte()) {

                        expectedBytes = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)

                        currentTotalSteps = ((bytes[4].toLong() and 0xFF) shl 24) or
                                ((bytes[5].toLong() and 0xFF) shl 16) or
                                ((bytes[6].toLong() and 0xFF) shl 8) or
                                (bytes[7].toLong() and 0xFF)

                        val battLevel = bytes[8].toInt() and 0xFF

                        dataBuffer.clear()
                        receivedBytes = 0

                        sendDataToGoogle("${deviceId}_Battery", battLevel.toString(), "")
                        Log.d("BleService", "Header received. Expecting $expectedBytes bytes of data.")
                    }

                    else {
                        for (byte in bytes) {
                            dataBuffer.add(byte.toInt() and 0xFF)
                            receivedBytes++
                        }


                        if (receivedBytes >= expectedBytes && expectedBytes > 0) {
                            Log.d("BleService", "Payload complete. Sending to Webhook.")

                            val csv = dataBuffer.joinToString(",")
                            sendDataToGoogle(deviceId, currentTotalSteps.toString(), csv)

                            expectedBytes = 0
                            receivedBytes = 0
                            dataBuffer.clear()
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_STOP_SERVICE") {
            bleManager.disconnect().enqueue()
            bleManager.close()
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = NotificationCompat.Builder(this, "NICLA_CHANNEL")
            .setContentTitle("Nicla Tracker Active")
            .setContentText("Listening for data in background...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(1, notification)
        connectToNicla()

        return START_STICKY
    }

    private fun connectToNicla() {
        val prefs = getSharedPreferences("NiclaPrefs", MODE_PRIVATE)
        val savedMac = prefs.getString("PAIRED_MAC", null)

        if (savedMac != null && savedMac.length == 17) {
            val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter

            if (adapter != null && adapter.isEnabled) {
                try {
                    val device = adapter.getRemoteDevice(savedMac)
                    bleManager.connect(device)
                        .retry(3, 100)
                        .useAutoConnect(true)
                        .enqueue()
                } catch (e: Exception) {
                    Log.e("BleService", "Error connecting: ${e.message}")
                }
            }
        }
    }

    private fun sendDataToGoogle(sheetName: String, steps: String, csv: String) {
        val prefs = getSharedPreferences("NiclaPrefs", MODE_PRIVATE)
        val webhookUrl = prefs.getString("SERVER_URL", "") ?: return
        if (webhookUrl.isEmpty()) return

        serviceScope.launch {
            try {
                val body = FormBody.Builder()
                    .add("sheetName", sheetName)
                    .add("steps", steps)
                    .add("logData", csv)
                    .build()

                val request = Request.Builder().url(webhookUrl).post(body).build()

                httpClient.newCall(request).execute().use { response ->
                    Log.d("BleService", "Google HTTP Code: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e("BleService", "Network Error: ${e.message}")
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
        serviceScope.cancel()
        unregisterReceiver(bluetoothStateReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}