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
import no.nordicsemi.android.ble.observer.ConnectionObserver
import android.bluetooth.BluetoothDevice
import android.app.AlarmManager
import android.app.PendingIntent
import android.os.SystemClock
import androidx.core.content.edit

class BleBackgroundService : Service() {

    private lateinit var bleManager: NiclaBleManager
    private val uploadMutex = Mutex()
    private val dataBuffer = mutableListOf<Int>()
    private var expectedBytes = 0
    private var receivedBytes = 0
    private var currentTotalSteps = 0L

    private var currentBatteryLevel = 0

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
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

        bleManager.setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {}
            override fun onDeviceConnected(device: BluetoothDevice) {}
            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                Log.e("BLE", "Failed to connect. Reason: $reason")

                serviceScope.launch {
                    kotlinx.coroutines.delay(5000)
                    connectToNicla()
                }
            }
            override fun onDeviceReady(device: BluetoothDevice) {}
            override fun onDeviceDisconnecting(device: BluetoothDevice) {}
            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                connectToNicla()
            }
        })

        registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        uploadCachedData()

        bleManager.onDataReceived = { bytes ->
            if (bytes.size >= 9 && bytes[0] == 0xAA.toByte() && bytes[1] == 0xBB.toByte()) {
                expectedBytes = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
                currentTotalSteps = ((bytes[4].toLong() and 0xFF) shl 24) or
                        ((bytes[5].toLong() and 0xFF) shl 16) or
                        ((bytes[6].toLong() and 0xFF) shl 8) or
                        (bytes[7].toLong() and 0xFF)

                currentBatteryLevel = bytes[8].toInt() and 0xFF

                dataBuffer.clear()
                receivedBytes = 0

                for (i in 9 until bytes.size) {
                    dataBuffer.add(bytes[i].toInt() and 0xFF)
                    receivedBytes++
                }
            } else {
                for (byte in bytes) {
                    dataBuffer.add(byte.toInt() and 0xFF)
                    receivedBytes++
                }
            }

            if (expectedBytes > 0 && receivedBytes >= expectedBytes) {

                val captureTime = System.currentTimeMillis()

                val decodedBuckets = ArrayList<String>(dataBuffer.size)
                for (stepCount in dataBuffer) {
                    decodedBuckets.add(stepCount.toString())
                }

                val csv = decodedBuckets.joinToString(",")
                val steps = currentTotalSteps.toString()
                val battery = currentBatteryLevel.toString()

                val rawMac = getSharedPreferences("NiclaPrefs", MODE_PRIVATE)
                    .getString("PAIRED_MAC", "Unknown_Device") ?: "Unknown_Device"
                val deviceId = rawMac.replace(":", "_")

                // ACK first so the device can power its radio down within its ACK timeout;
                // the upload must not gate the ACK.
                bleManager.sendAck()

                expectedBytes = 0
                receivedBytes = 0
                dataBuffer.clear()

                serviceScope.launch {
                    uploadCachedData()
                    sendOrCache("${deviceId}_Battery", battery, "", captureTime)
                    kotlinx.coroutines.delay(1500)
                    sendOrCache(deviceId, steps, csv, captureTime)
                }
            }
        }
    }

    private fun sendOrCache(sheetName: String, steps: String, csv: String, captureTime: Long) {
        val webhookUrl = getSharedPreferences("NiclaPrefs", MODE_PRIVATE).getString("SERVER_URL", "") ?: return
        if (webhookUrl.isEmpty()) return

        try {
            val body = FormBody.Builder()
                .add("sheetName", sheetName)
                .add("steps", steps)
                .add("logData", csv)
                .add("captureTime", captureTime.toString())
                .build()

            val request = Request.Builder().url(webhookUrl).post(body).build()

            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body.string()
                Log.d("BleService", "Direct send HTTP ${response.code} body=$responseBody")

                if (!response.isSuccessful) {
                    Log.w("BleService", "Server rejected, caching payload...")
                    saveToLocalCache(sheetName, steps, csv, captureTime)
                }
            }
        } catch (e: Exception) {
            Log.e("BleService", "Direct send failed, caching: ${e.message}")
            saveToLocalCache(sheetName, steps, csv, captureTime)
        }
    }

    private fun saveToLocalCache(sheetName: String, steps: String, csv: String, captureTime: Long) {
        val cachePrefs = getSharedPreferences("NiclaCache", MODE_PRIVATE)
        val key = "${captureTime}_$sheetName"
        val payload = "$captureTime|$sheetName|$steps|$csv"
        cachePrefs.edit { putString(key, payload) }
    }

    private fun uploadCachedData() {
        val webhookUrl = getSharedPreferences("NiclaPrefs", MODE_PRIVATE).getString("SERVER_URL", "") ?: return

        serviceScope.launch {
            uploadMutex.withLock {
                val cachePrefs = getSharedPreferences("NiclaCache", MODE_PRIVATE)
                val allEntries = cachePrefs.all

                if (webhookUrl.isEmpty() || allEntries.isEmpty()) return@launch

                for ((key, payloadRaw) in allEntries) {
                    val payload = payloadRaw as? String ?: continue
                    val parts = payload.split("|", limit = 4)

                    if (parts.size == 4) {
                        try {
                            val body = FormBody.Builder()
                                .add("captureTime", parts[0])
                                .add("sheetName", parts[1])
                                .add("steps", parts[2])
                                .add("logData", parts[3])
                                .build()

                            val request = Request.Builder().url(webhookUrl).post(body).build()

                            httpClient.newCall(request).execute().use { response ->
                                val responseBody = response.body.string()
                                Log.d("BleService", "Cache drain HTTP ${response.code} for $key body=$responseBody")

                                if (response.isSuccessful) {
                                    cachePrefs.edit { remove(key) }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("BleService", "Cache Network Error: ${e.message}")
                        }
                    } else {
                        cachePrefs.edit { remove(key) }
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
            .setContentTitle("Device Sync")
            .setContentText("Running in background")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
        connectToNicla()

        return START_STICKY
    }

    private fun connectToNicla() {
        if (bleManager.isConnected) return

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
        val channel = NotificationChannel(
            "NICLA_CHANNEL",
            "System Sync",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Maintains silent background connection."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        unregisterReceiver(bluetoothStateReceiver)
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartServiceIntent = Intent(applicationContext, BleBackgroundService::class.java).also {
            it.setPackage(packageName)
        }

        val restartServicePendingIntent: PendingIntent = PendingIntent.getService(
            this, 1, restartServiceIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmService: AlarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        alarmService.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 1000,
            restartServicePendingIntent
        )

        super.onTaskRemoved(rootIntent)
    }
}