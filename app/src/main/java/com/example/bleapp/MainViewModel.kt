package com.example.bleapp

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@SuppressLint("MissingPermission")
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("NiclaPrefs", Context.MODE_PRIVATE)

    private val _serverUrl = MutableStateFlow(prefs.getString("WEBHOOK_URL", "") ?: "")
    val serverUrl = _serverUrl.asStateFlow()

    fun saveUrl(newUrl: String) {
        prefs.edit().putString("WEBHOOK_URL", newUrl.trim()).apply()
        _serverUrl.value = newUrl.trim()
    }

    private val _pairedDeviceAddress = MutableStateFlow(prefs.getString("PAIRED_MAC", "") ?: "")
    val pairedDeviceAddress = _pairedDeviceAddress.asStateFlow()

    private val _pairedDeviceName = MutableStateFlow(prefs.getString("PAIRED_NAME", "Unknown") ?: "Unknown")
    val pairedDeviceName = _pairedDeviceName.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<android.bluetooth.BluetoothDevice>>(emptyList())
    val scannedDevices = _scannedDevices.asStateFlow()

    private val _scanStatus = MutableStateFlow("Idle")
    val scanStatus = _scanStatus.asStateFlow()

    fun startScan() {
        _scanStatus.value = "Scanning..."
        _scannedDevices.value = emptyList()

        val bluetoothManager = getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = bluetoothManager.adapter.bluetoothLeScanner

        scanner.startScan(object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val currentList = _scannedDevices.value
                if (currentList.none { it.address == device.address }) {
                    _scannedDevices.value = currentList + device
                }
            }
        })
    }

    fun pairAndConnect(device: android.bluetooth.BluetoothDevice) {
        val context = getApplication<Application>().applicationContext

        prefs.edit()
            .putString("PAIRED_MAC", device.address)
            .putString("PAIRED_NAME", device.name ?: "Nicla")
            .apply()

        _pairedDeviceAddress.value = device.address
        _pairedDeviceName.value = device.name ?: "Nicla"

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter.bluetoothLeScanner.stopScan(object : ScanCallback() {})
        _scanStatus.value = "Paired! Starting background service..."

        //Start Background Service
        val serviceIntent = Intent(context, BleBackgroundService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    fun unpair() {
        prefs.edit().remove("PAIRED_MAC").remove("PAIRED_NAME").apply()
        _pairedDeviceAddress.value = ""
        _pairedDeviceName.value = "Unknown"
        _scanStatus.value = "Unpaired."

        val context = getApplication<Application>().applicationContext
        context.stopService(Intent(context, BleBackgroundService::class.java))
    }
}