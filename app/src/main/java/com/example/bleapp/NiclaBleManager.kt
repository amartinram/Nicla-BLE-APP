package com.example.bleapp

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import no.nordicsemi.android.ble.BleManager

class NiclaBleManager(context: Context) : BleManager(context) {

    var onDataReceived: ((String) -> Unit)? = null

    override fun getGattCallback(): BleManagerGattCallback {
        return object : BleManagerGattCallback() {
            var dataCharacteristic: BluetoothGattCharacteristic? = null

            override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
                for (service in gatt.services) {
                    for (characteristic in service.characteristics) {
                        val properties = characteristic.properties
                        if ((properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                            dataCharacteristic = characteristic
                            return true
                        }
                    }
                }
                return false
            }

            override fun initialize() {
                requestMtu(512).enqueue()

                val characteristic = dataCharacteristic ?: return

                setNotificationCallback(characteristic).with { _, data ->
                    val value = data.getStringValue(0) ?: ""
                    onDataReceived?.invoke(value)
                }
                enableNotifications(characteristic).enqueue()
            }

            override fun onServicesInvalidated() {
                dataCharacteristic = null
            }
        }
    }
}