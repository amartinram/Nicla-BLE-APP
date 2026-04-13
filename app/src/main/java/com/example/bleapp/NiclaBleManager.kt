package com.example.bleapp

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import no.nordicsemi.android.ble.BleManager
import java.util.UUID

class NiclaBleManager(context: Context) : BleManager(context) {

    private val SERVICE_UUID = UUID.fromString("00001814-0000-1000-8000-00805f9b34fb")
    private val CHAR_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private var dataCharacteristic: BluetoothGattCharacteristic? = null

    var onDataReceived: ((ByteArray) -> Unit)? = null

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(SERVICE_UUID)
        if (service != null) {
            dataCharacteristic = service.getCharacteristic(CHAR_UUID)
        }
        return dataCharacteristic != null
    }

    override fun initialize() {
        requestMtu(512).enqueue()
        val characteristic = dataCharacteristic ?: return

        setNotificationCallback(characteristic).with { _, data ->
            data.value?.let { onDataReceived?.invoke(it) }
        }
        enableNotifications(characteristic).enqueue()
    }

    override fun onServicesInvalidated() {
        dataCharacteristic = null
    }
}