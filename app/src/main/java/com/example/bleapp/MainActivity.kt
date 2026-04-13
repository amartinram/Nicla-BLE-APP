package com.example.bleapp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = MainViewModel(this)

        requestPermissions()

        // --- 1. THE "RUN ONCE" CHECK ---
        val prefs = getSharedPreferences("NiclaPrefs", Context.MODE_PRIVATE)
        val isFirstRun = prefs.getBoolean("IS_FIRST_RUN", true)

        if (isFirstRun) {
            runFirstTimeSetup()
            prefs.edit().putBoolean("IS_FIRST_RUN", false).apply()
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TrackerScreen(viewModel)
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun runFirstTimeSetup() {
        // 1. Standard Android Battery Exemption
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent().apply {
                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("MainActivity", "Battery exemption intent failed: ${e.message}")
            }
        }

        // 2. The Universal OEM Autostart Bypass
        requestUniversalAutoStart()
    }

    // --- 2. THE UNIVERSAL OEM BYPASS ---
    private fun requestUniversalAutoStart() {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intent = Intent()

        try {
            when (manufacturer) {
                "xiaomi", "redmi", "poco" -> {
                    intent.component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                }
                "samsung" -> {
                    intent.component = ComponentName("com.samsung.android.sm_devicesecurity", "com.samsung.android.sm.ui.appscreens.AppCategoryListActivity")
                }
                "oppo" -> {
                    intent.component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                }
                "vivo" -> {
                    intent.component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
                }
                "huawei", "honor" -> {
                    intent.component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                }
                else -> {
                    // If it's a Pixel, Motorola, Sony, etc., standard Android handles it.
                    Log.d("MainActivity", "Standard Android detected. No OEM Autostart needed.")
                    return
                }
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fails silently if the user's specific OS version moved the menu
            Log.e("MainActivity", "Failed to open OEM autostart for $manufacturer: ${e.message}")
        }
    }
}

@Composable
fun TrackerScreen(viewModel: MainViewModel) {
    val serverUrl by viewModel.serverUrl.collectAsState()
    val macAddress by viewModel.macAddress.collectAsState()
    val isTracking by viewModel.isTracking.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Nicla Tracker Setup", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 32.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { viewModel.setServerUrl(it) },
            label = { Text("Webhook URL") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isTracking
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = macAddress,
            onValueChange = { viewModel.setMacAddress(it) },
            label = { Text("MAC Address (AA:BB:CC:DD:EE:FF)") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isTracking
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (!isTracking) {
            Button(onClick = { viewModel.startTracking() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Save, Pair & Start", fontSize = 16.sp)
            }
        } else {
            Button(
                onClick = { viewModel.stopTracking() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("Unpair & Stop Tracker", fontSize = 16.sp, color = MaterialTheme.colorScheme.onError)
            }
        }
    }
}