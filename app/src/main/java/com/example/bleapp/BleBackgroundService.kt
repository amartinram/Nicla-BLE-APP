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
import android.content.SharedPreferences
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

    companion object {
        /**
         * Upper bound on pending uploads, roughly a month at the two sent per
         * day (the daily log and its battery reading). See [trimCache].
         */
        private const val MAX_CACHED_UPLOADS = 60

        /**
         * How long a finished step log waits for the gait log of the same day.
         *
         * The device sends the two back to back, about a second apart, so this
         * only ever runs out when there is no gait block at all -- step-only
         * firmware, or a day the device found no walking to analyse. Waiting
         * costs nothing: the upload is independent of the BLE link, which the
         * device drops as soon as it has its acknowledgements.
         */
        private const val GAIT_WAIT_MS = 10_000L

        /** Uploads the server has not yet accepted. Removed only on a 2xx. */
        private const val CACHE_PREFS = "NiclaCache"

        /**
         * The one day currently waiting for its gait log. Written before the
         * step log is acknowledged, because the acknowledgement is what lets
         * the device erase its own copy: from that moment this is the only
         * one, and it must not live in memory alone.
         */
        private const val PENDING_PREFS = "NiclaPending"
        private const val PENDING_DAY = "day"
        private const val PENDING_BATTERY = "battery"

        /** The last day received, so a resent day keeps its first timestamp. */
        private const val LAST_DAY_KEY = "LAST_DAY_KEY"
        private const val LAST_DAY_TIME = "LAST_DAY_TIME"
    }

    private lateinit var bleManager: NiclaBleManager
    private val uploadMutex = Mutex()

    /** Reassembles the dump from the notification stream. See NiclaProtocol. */
    private val assembler = NiclaProtocol.Assembler()

    // A finished step log, held just long enough for its gait log to arrive so
    // the two reach the server as one reading. Guarded because the watchdog
    // coroutine and the BLE callback both race to claim it.
    private val pendingLock = Any()
    private var pendingUpload: NiclaProtocol.Upload? = null
    private var pendingBattery = ""
    private var gaitWatchdog: Job? = null

    // Retries the cache until it is empty. See scheduleRetry().
    private val retryLock = Any()
    private var retryJob: Job? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val httpClient = OkHttpClient.Builder()
        // The TCP handshake is answered by the host's edge immediately, so
        // connecting and writing stay short.
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Reading is the one that matters. The server runs on a free plan that
        // stops after 15 minutes idle and takes ~50 s to start again, and this
        // app uploads about once a day, so it often arrives at a stopped
        // instance. At 30 s the first attempt timed out every time -- and the
        // server had usually committed the row by then, so the retry stored the
        // day a second time.
        .readTimeout(120, TimeUnit.SECONDS)
        // Overall ceiling so a request can never hang indefinitely.
        .callTimeout(180, TimeUnit.SECONDS)
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
                // A block never continues across connections; see Assembler.reset().
                assembler.reset()
                connectToNicla()
            }
        })

        registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        recoverPending()
        uploadCachedData()

        bleManager.onDataReceived = { bytes -> onNotification(bytes) }
    }

    /**
     * One BLE notification. A dump arrives as a header followed by payload
     * chunks, twice over: the step log, then the gait log for the same day.
     * The device waits on an acknowledgement after each block.
     *
     * Each block is stored on disk BEFORE it is acknowledged. The device
     * erases a day once it has its acknowledgements, so acknowledging first
     * left a window -- the ten-second wait for the gait log, then an upload
     * that can take minutes against a sleeping server -- in which the only
     * copy of the day was in this process's memory, and a process Android
     * killed in that window took the day with it. Storing is a few
     * milliseconds, well inside the device's ten-second acknowledgement
     * timeout; uploading still never gates the acknowledgement.
     */
    private fun onNotification(bytes: ByteArray) {
        for (event in assembler.accept(bytes)) {
            when (event) {
                // A new day is starting. Anything still waiting for a gait log
                // is never going to get one, so let it go now.
                is NiclaProtocol.Assembler.Event.DayStarted -> flushPending()

                is NiclaProtocol.Assembler.Event.StepsComplete -> {
                    holdForGait(event.header, event.csv, event.info)
                    bleManager.sendAck()
                }

                is NiclaProtocol.Assembler.Event.GaitComplete -> {
                    attachGait(event.header, event.csv)
                    bleManager.sendAck()
                }
            }
        }
    }

    private fun deviceId(): String {
        val rawMac = getSharedPreferences("NiclaPrefs", MODE_PRIVATE)
            .getString("PAIRED_MAC", "Unknown_Device") ?: "Unknown_Device"
        return rawMac.replace(":", "_")
    }

    /**
     * The timestamp a day is stored under.
     *
     * A device that reports how long the day waited gets the time the day
     * actually ended. And a day the device is sending for the second time --
     * it resends whenever an acknowledgement went missing -- gets the
     * timestamp it had the first time, so the server recognises it as the
     * same reading instead of storing the day twice.
     */
    private fun captureTimeFor(
        header: NiclaProtocol.StepHeader, csv: String, info: NiclaProtocol.DayInfo?,
    ): Long {
        val now = System.currentTimeMillis()
        if (info == null) return now

        val prefs = getSharedPreferences("NiclaPrefs", MODE_PRIVATE)
        val key = NiclaProtocol.dayKey(info, header, csv)
        if (prefs.getString(LAST_DAY_KEY, null) == key) {
            val first = prefs.getLong(LAST_DAY_TIME, 0L)
            if (first > 0L) return first
        }
        val time = NiclaProtocol.captureTime(now, info)
        prefs.edit { putString(LAST_DAY_KEY, key).putLong(LAST_DAY_TIME, time) }
        return time
    }

    /** Holds a finished step log just long enough for its gait log to arrive. */
    private fun holdForGait(
        header: NiclaProtocol.StepHeader, csv: String, info: NiclaProtocol.DayInfo?,
    ) {
        val day = NiclaProtocol.Upload(
            captureTime = captureTimeFor(header, csv, info),
            sheetName = deviceId(),
            steps = header.totalSteps.toString(),
            logData = csv,
        )
        val battery = header.battery.toString()
        synchronized(pendingLock) {
            pendingUpload = day
            pendingBattery = battery
            getSharedPreferences(PENDING_PREFS, MODE_PRIVATE).edit(commit = true) {
                putString(PENDING_DAY, day.encode())
                putString(PENDING_BATTERY, battery)
            }
            gaitWatchdog?.cancel()
            gaitWatchdog = serviceScope.launch {
                delay(GAIT_WAIT_MS)
                // Drop the handle before flushing, so claimPending() does not
                // cancel the very coroutine it is running on.
                synchronized(pendingLock) { gaitWatchdog = null }
                flushPending()
            }
        }
    }

    /**
     * Queues the gait log together with the step log it belongs to, so the day
     * reaches the server as a single reading rather than two half-rows.
     */
    private fun attachGait(header: NiclaProtocol.GaitHeader, csv: String) {
        val claimed = claimPending()
        if (claimed == null) {
            Log.w("BleService", "Gait block with no step log to attach it to")
            return
        }
        val (day, battery) = claimed
        enqueue(day.copy(speedLog = csv, gait = header), battery)
        uploadCachedData()
    }

    /** Takes the pending day, if it is still there. Only one caller can win. */
    private fun claimPending(): Pair<NiclaProtocol.Upload, String>? = synchronized(pendingLock) {
        val held = pendingUpload ?: return@synchronized null
        pendingUpload = null
        gaitWatchdog?.cancel()
        gaitWatchdog = null
        held to pendingBattery
    }

    /** Queues the pending day with no gait data, if one is still waiting. */
    private fun flushPending() {
        val (held, battery) = claimPending() ?: return
        enqueue(held, battery)
        uploadCachedData()
    }

    /**
     * Moves a finished day, and the battery reading that came with it, into
     * the upload cache. Everything is sent from the cache and removed from it
     * only once the server has accepted it, so there is one path to the server
     * and no moment at which a day exists only in memory.
     */
    private fun enqueue(day: NiclaProtocol.Upload, battery: String) {
        val batteryReading = NiclaProtocol.Upload(day.captureTime, day.sheetName + "_Battery",
                                                  battery, "")
        val cachePrefs = getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
        synchronized(pendingLock) {
            cachePrefs.edit(commit = true) {
                putString(cacheKey(batteryReading), batteryReading.encode())
                putString(cacheKey(day), day.encode())
            }
            // The stored pending copy has done its job -- unless a newer day
            // has already taken its place while this one was being queued.
            if (pendingUpload == null) {
                getSharedPreferences(PENDING_PREFS, MODE_PRIVATE).edit(commit = true) { clear() }
            }
        }
        trimCache(cachePrefs)
    }

    /**
     * Picks up a day that was acknowledged to the device but never queued,
     * because the process died while it was waiting for its gait log.
     */
    private fun recoverPending() {
        val pending = getSharedPreferences(PENDING_PREFS, MODE_PRIVATE)
        val day = pending.getString(PENDING_DAY, null)?.let { NiclaProtocol.decode(it) }
        if (day != null) {
            Log.w("BleService", "Recovering a day left unqueued by the previous run")
            enqueue(day, pending.getString(PENDING_BATTERY, "") ?: "")
        } else {
            pending.edit { clear() }
        }
    }

    private fun cacheKey(day: NiclaProtocol.Upload) = "${day.captureTime}_${day.sheetName}"

    private fun formBodyFor(day: NiclaProtocol.Upload): FormBody {
        val builder = FormBody.Builder()
        for ((name, value) in day.formFields()) builder.add(name, value)
        return builder.build()
    }

    /**
     * Keeps the offline cache bounded.
     *
     * A cached entry is only removed once the server accepts it, so an upload
     * the server keeps rejecting -- a wrong token in the URL, most likely --
     * would otherwise accumulate forever. That matters because
     * SharedPreferences is read into memory in full, and each daily log is
     * several KB.
     *
     * Rejected uploads are still kept rather than dropped on the spot, so the
     * data survives long enough to be recovered once the URL is corrected.
     */
    private fun trimCache(cachePrefs: SharedPreferences) {
        val keys = cachePrefs.all.keys
        if (keys.size <= MAX_CACHED_UPLOADS) return

        val excess = keys.size - MAX_CACHED_UPLOADS
        cachePrefs.edit {
            for (key in NiclaProtocol.oldestFirst(keys).take(excess)) {
                Log.w("BleService", "Cache at capacity, discarding oldest entry $key")
                remove(key)
            }
        }
    }

    /** Sends whatever is waiting, and keeps trying until none of it is. */
    private fun uploadCachedData() {
        serviceScope.launch {
            if (!drainCache()) scheduleRetry()
        }
    }

    /**
     * One pass over the cache, oldest day first.
     *
     * @return true when nothing is left waiting.
     */
    private suspend fun drainCache(): Boolean = uploadMutex.withLock {
        val webhookUrl = getSharedPreferences("NiclaPrefs", MODE_PRIVATE)
            .getString("SERVER_URL", "").orEmpty()
        // Nothing can be sent without a URL, and retrying will not change that.
        if (webhookUrl.isEmpty()) return@withLock true

        val cachePrefs = getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
        val entries = cachePrefs.all

        for (key in NiclaProtocol.oldestFirst(entries.keys)) {
            val day = (entries[key] as? String)?.let { NiclaProtocol.decode(it) }
            if (day == null) {
                Log.w("BleService", "Discarding unreadable cache entry $key")
                cachePrefs.edit { remove(key) }
                continue
            }
            try {
                val request = Request.Builder().url(webhookUrl).post(formBodyFor(day)).build()
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body.string()
                    Log.d("BleService", "Upload HTTP ${response.code} for $key body=$responseBody")
                    if (response.isSuccessful) {
                        cachePrefs.edit { remove(key) }
                    }
                }
            } catch (e: Exception) {
                // No network, or the server did not answer. The rest of the
                // cache would fail the same way, each after its own timeout.
                Log.e("BleService", "Upload failed, will retry: ${e.message}")
                return@withLock false
            }
        }
        cachePrefs.all.isEmpty()
    }

    /**
     * Retries the cache on a backoff until it is empty.
     *
     * A failed upload used to wait for the next network change or the next
     * day's dump, whichever came first. On a phone that sits on the same Wi-Fi
     * that is a full day, during which the server sees nothing from a patient
     * whose device is working, and raises a no-data alert for them.
     */
    private fun scheduleRetry() {
        synchronized(retryLock) {
            if (retryJob?.isActive == true) return
            retryJob = serviceScope.launch {
                var attempt = 0
                while (true) {
                    delay(NiclaProtocol.retryDelayMs(attempt++))
                    if (drainCache()) break
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
        // A day still waiting for its gait log is already on disk, but queue
        // it now so it goes out with the rest as soon as the service is back.
        claimPending()?.let { (day, battery) ->
            Log.w("BleService", "Service stopped mid-dump; queueing the day for later")
            enqueue(day, battery)
        }
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