package com.example.myapplication

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    /** Bumped on every resume, so the checklist re-reads settings the person may have just changed. */
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)



        // Start the foreground service
        startCallMonitorService()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CallLoggerScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }


    private fun startCallMonitorService() {
        val serviceIntent = Intent(this, CallMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    @Composable
    fun CallLoggerScreen() {
        var hasPermissions by remember { mutableStateOf(checkPermissions()) }

        // Permission launcher
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.values.all { it }
            hasPermissions = allGranted

            if (allGranted) {
                Toast.makeText(this, "Permissions granted!", Toast.LENGTH_SHORT).show()
                CallSyncWorker.startFromNow(this)
                // The setting that most often keeps Android from killing the app; ask straight away.
                requestBatteryOptimizationExemption()
            } else {
                Toast.makeText(this, "Permissions denied", Toast.LENGTH_SHORT).show()
            }
        }

        // Re-read on every resume: the person may have just changed one of these in Settings.
        val tick = resumeTick
        val batteryOk = remember(tick) { isIgnoringBatteryOptimizations() }
        val lastSentAt = remember(tick) { CallSyncWorker.lastSentAt(this) }
        LaunchedEffect(tick) {
            hasPermissions = checkPermissions()
            if (hasPermissions) CallSyncWorker.startFromNow(this@MainActivity)
        }

        val infiniteTransition = rememberInfiniteTransition()

        val scale by infiniteTransition.animateFloat(
            initialValue = 0.95f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200),
                repeatMode = RepeatMode.Reverse
            )
        )

        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.7f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200),
                repeatMode = RepeatMode.Reverse
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "ZILLOUT",
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier
                    .padding(bottom = 32.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
            )

            if (hasPermissions ) {
                SetupChecklist(
                    running = CallMonitorService.isRunning,
                    batteryOk = batteryOk,
                    lastSentAt = lastSentAt
                )
            } else {
                Button(
                    onClick = {
                        val permissions = mutableListOf(
                            Manifest.permission.READ_PHONE_STATE,
                            Manifest.permission.READ_CALL_LOG
                        )

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                        }

                        permissionLauncher.launch(permissions.toTypedArray())
                    }
                ) {
                    Text(
                        text = "Grant Permissions",
                        fontSize = 16.sp
                    )
                }
            }
        }
    }

    /**
     * What keeps the app alive, and what is still missing. Android and phone makers stop apps that
     * look idle; once the app is stopped no call reaches Zillout until someone opens it again.
     */
    @Composable
    private fun SetupChecklist(running: Boolean, batteryOk: Boolean, lastSentAt: Long?) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = if (running && batteryOk) "Ready to Use" else "Finish setup",
                fontSize = 24.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            StatusLine(ok = running, text = if (running) "Monitoring calls" else "Not monitoring calls")
            if (!running) {
                Button(onClick = { startCallMonitorService() }) { Text("Start monitoring") }
            }
            Text(
                text = lastSentAt?.let { "Last call sent ${formatTime(it)}" } ?: "No calls sent yet",
                fontSize = 14.sp
            )

            StatusLine(
                ok = batteryOk,
                text = if (batteryOk) "Battery: unrestricted" else "Battery saver can stop this app"
            )
            if (!batteryOk) {
                Button(onClick = { requestBatteryOptimizationExemption() }) {
                    Text("Allow running in background")
                }
            }

            if (autoStartIntents().isNotEmpty()) {
                Text(
                    text = "This phone can stop IVR unless auto-start / background running is turned on for it.",
                    fontSize = 14.sp
                )
                Button(onClick = { openAutoStartSettings() }) { Text("Open phone settings") }
            }

            Text(
                text = "Also lock IVR in recent apps: open recent apps, press and hold IVR, tap the lock.",
                fontSize = 14.sp
            )
        }
    }

    @Composable
    private fun StatusLine(ok: Boolean, text: String) {
        Text(
            text = (if (ok) "✓  " else "⚠  ") + text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH).format(Date(millis))

    private fun checkPermissions(): Boolean {
        val phoneState = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
        val callLog = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)

        return phoneState == PackageManager.PERMISSION_GRANTED &&
                callLog == PackageManager.PERMISSION_GRANTED
    }

    private fun checkCallScreeningRole(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            return roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        }
        return true
    }

    private fun requestCallScreeningRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
            startActivity(intent)
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    // Add this helper function in MainActivity
    fun requestBatteryOptimizationExemption() {
        if (isIgnoringBatteryOptimizations()) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Some phones hide the one-tap dialog; send them to the full list instead.
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /**
     * The auto-start / background-running screen on phone brands known to stop apps without it.
     * Several candidates per brand because the screen moves between firmware versions. Empty on
     * brands that don't need it.
     */
    private fun autoStartIntents(): List<Intent> {
        val components = when (Build.MANUFACTURER.lowercase(Locale.ROOT)) {
            "xiaomi", "redmi", "poco" -> listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            "oppo", "realme", "oneplus" -> listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
                "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
                "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
            )
            "vivo", "iqoo" -> listOf(
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
            )
            "huawei", "honor" -> listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity"
            )
            // No auto-start switch, but unused apps are put to sleep: Battery > Never sleeping apps.
            "samsung" -> listOf(
                "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity"
            )
            else -> emptyList()
        }
        return components.map { (pkg, cls) -> Intent().setComponent(ComponentName(pkg, cls)) }
    }

    private fun openAutoStartSettings() {
        for (intent in autoStartIntents()) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                // Not on this firmware (or not exported); try the next one.
            }
        }
        // None matched: the app's own settings page, where battery and background options live.
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
}
