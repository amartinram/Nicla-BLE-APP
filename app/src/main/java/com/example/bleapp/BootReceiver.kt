package com.example.bleapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED) {

            Log.d("BootReceiver", "Boot broadcast received! Action: $action")

            // CRITICAL: We must use Device Protected Storage to read the MAC address
            // if the user hasn't typed their PIN code yet!
            val deviceContext = ContextCompat.createDeviceProtectedStorageContext(context) ?: context
            val prefs = deviceContext.getSharedPreferences("NiclaPrefs", Context.MODE_PRIVATE)
            val savedMac = prefs.getString("PAIRED_MAC", null)

            if (savedMac != null && savedMac.length == 17) {
                Log.d("BootReceiver", "Nicla MAC found: $savedMac. Starting service...")
                try {
                    val serviceIntent = Intent(context, BleBackgroundService::class.java)
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Android blocked the service start: ${e.message}")
                }
            } else {
                Log.d("BootReceiver", "No Nicla paired. Staying asleep.")
            }
        }
    }
}