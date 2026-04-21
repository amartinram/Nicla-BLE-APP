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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
    private val uploadMutex = Mutex()
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
                    connectToNicla()
                }
            }
        }
    }

    private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            uploadCachedData()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bleManager = NiclaBleManager(this)

        registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        uploadCachedData()

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

                        sendDataToGoogleNative("${deviceId}_Battery", battLevel.toString(), "")
                    } else {
                        for (byte in bytes) {
                            dataBuffer.add(byte.toInt() and 0xFF)
                            receivedBytes++
                        }

                        if (receivedBytes >= expectedBytes && expectedBytes > 0) {
                            val csv = dataBuffer.joinToString(",")
                            val steps = currentTotalSteps.toString()

                            saveToLocalCache(deviceId, steps, csv)
                            bleManager.sendAck()
                            uploadCachedData()

                            expectedBytes = 0
                            receivedBytes = 0
                            dataBuffer.clear()
                        }
                    }
                }
            }
        }
    }

    private fun saveToLocalCache(sheetName: String, steps: String, csv: String) {
        val cachePrefs = getSharedPreferences("NiclaCache", MODE_PRIVATE)
        val timestamp = System.currentTimeMillis().toString()
        val payload = "$sheetName|$steps|$csv"
        cachePrefs.edit().putString(timestamp, payload).apply()
    }

    private fun uploadCachedData() {
        val webhookUrl = getSharedPreferences("NiclaPrefs", MODE_PRIVATE).getString("SERVER_URL", "") ?: return

        serviceScope.launch {
            if (!uploadMutex.tryLock()) return@launch

            try {
                val cachePrefs = getSharedPreferences("NiclaCache", MODE_PRIVATE)
                val allEntries = cachePrefs.all

                if (webhookUrl.isEmpty() || allEntries.isEmpty()) return@launch

                for ((timestamp, payloadRaw) in allEntries) {
                    val payload = payloadRaw as? String ?: continue
                    val parts = payload.split("|", limit = 3)

                    if (parts.size == 3) {
                        try {
                            val body = FormBody.Builder()
                                .add("sheetName", parts[0])
                                .add("steps", parts[1])
                                .add("logData", parts[2])
                                .build()

                            val request = Request.Builder().url(webhookUrl).post(body).build()

                            httpClient.newCall(request).execute().use { response ->
                                if (response.isSuccessful) {
                                    cachePrefs.edit().remove(timestamp).apply()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("BleService", "Network Error: ${e.message}")
                        }
                    }
                }
            } finally {
                uploadMutex.unlock()
            }
        }
    }

    private fun sendDataToGoogleNative(sheetName: String, steps: String, csv: String) {
        val webhookUrl = getSharedPreferences("NiclaPrefs", MODE_PRIVATE).getString("SERVER_URL", "") ?: return
        if (webhookUrl.isEmpty()) return

        serviceScope.launch {
            try {
                val body = FormBody.Builder()
                    .add("sheetName", sheetName)
                    .add("steps", steps)
                    .add("logData", csv)
                    .build()
                val request = Request.Builder().url(webhookUrl).post(body).build()
                httpClient.newCall(request).execute().use { }
            } catch (e: Exception) {
                Log.e("BleService", "Native Network Error: ${e.message}")
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
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}