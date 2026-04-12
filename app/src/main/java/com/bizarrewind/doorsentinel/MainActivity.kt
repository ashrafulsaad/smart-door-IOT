package com.bizarrewind.doorsentinel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Environment
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bizarrewind.doorsentinel.ui.theme.DoorsentinelTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val statusText    = mutableStateOf("Waiting for permissions...")
    private val ipAddressText = mutableStateOf("Fetching IP...")
    private val logs          = mutableStateListOf<String>()

    // Telemetry
    private val isConnected  = mutableStateOf(false)
    private val tempText     = mutableStateOf("--")
    private val humText      = mutableStateOf("--")
    private val lightText    = mutableStateOf("--")
    private var connectionTimeoutJob: Job? = null

    // Gallery
    private val photos = mutableStateListOf<File>()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            addLog("✅ Camera permission granted")
            startServer()
        } else {
            statusText.value = "❌ CRITICAL: Permission Denied"
            addLog("❌ Cannot function without camera permission.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen BEFORE super.onCreate()
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Pre-load any existing photos from disk
        loadExistingPhotos()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startServer()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        val currentIp = getIpAddress()
        ipAddressText.value = currentIp

        setContent {
            DoorsentinelTheme {
                MainTabScreen(
                    status     = statusText.value,
                    ipAddress  = currentIp,
                    connected  = isConnected.value,
                    temp       = tempText.value,
                    humidity   = humText.value,
                    light      = lightText.value,
                    logs       = logs,
                    photos     = photos,
                    onDeletePhoto = { file ->
                        file.delete()
                        photos.remove(file)
                        addLog("🗑️ Deleted: ${file.name}")
                    },
                    onUploadPhoto = { file ->
                        ServerService.manualUploadRequest?.invoke(file)
                        addLog("☁️ Manual upload queued: ${file.name}")
                    },
                    onExportPhotos = { files ->
                        exportPhotosToDownloads(files)
                    }
                )
            }
        }
    }

    private fun loadExistingPhotos() {
        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return
        val existing = dir.listFiles { f -> f.extension.lowercase() == "jpg" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        photos.addAll(existing)
    }

    private fun startServer() {
        ServerService.onTriggerCallback = {
            addLog("🔥 ESP32 TRIGGER RECEIVED — camera activating")
            lifecycleScope.launch { statusText.value = "🔥 CAPTURING" }
        }
        ServerService.onFaceDetectedCallback = {
            addLog("👤 Face spotted by ML Kit!")
        }
        ServerService.onStopCallback = {
            addLog("🛑 Stop received — camera going to sleep")
            lifecycleScope.launch { statusText.value = "✅ Armed — idle" }
        }
        ServerService.onTelemetryReceived = { temp, hum, light ->
            tempText.value  = temp
            humText.value   = hum
            lightText.value = light

            isConnected.value = true
            connectionTimeoutJob?.cancel()
            connectionTimeoutJob = lifecycleScope.launch {
                delay(5000)
                isConnected.value = false
                addLog("⚠️ ESP32 heartbeat lost — disconnected")
            }
        }
        ServerService.onPhotoSaved = { file ->
            // Insert at top so newest photos appear first in gallery
            lifecycleScope.launch {
                if (!photos.contains(file)) photos.add(0, file)
            }
            addLog("📸 Saved: ${file.name}")
        }

        val intent = Intent(this, ServerService::class.java)
        startForegroundService(intent)
        addLog("🚀 Server started on port 8080 — awaiting ESP32")
        statusText.value = "✅ Armed — idle"
    }

    private fun getIpAddress(): String {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wifiManager.connectionInfo.ipAddress
            if (ip == 0) "192.168.43.1"
            else String.format(Locale.US, "%d.%d.%d.%d",
                ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
        } catch (e: Exception) { "Unknown" }
    }

    private fun addLog(message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        lifecycleScope.launch { logs.add(0, "[$ts] $message") }
    }

    /**
     * Copies [files] into the public Downloads/DoorSentinel folder.
     * This folder is visible over USB (MTP) and in the Files app without
     * requiring storage permission on Android 10+.
     */
    private fun exportPhotosToDownloads(files: List<File>) {
        val destDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "DoorSentinel"
        )
        if (!destDir.exists()) destDir.mkdirs()

        var copied = 0
        files.forEach { src ->
            try {
                val dest = File(destDir, src.name)
                src.copyTo(dest, overwrite = true)
                copied++
                addLog("📁 Exported: ${src.name}")
            } catch (e: Exception) {
                addLog("❌ Export failed: ${src.name} — ${e.message}")
            }
        }
        Toast.makeText(
            this,
            if (copied > 0) "✅ $copied photo${if (copied > 1) "s" else ""} exported to Downloads/DoorSentinel"
            else "❌ Export failed",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService(Intent(this, ServerService::class.java))
    }
}

// ── Tab navigation ────────────────────────────────────────────────────────────

private enum class Tab(val label: String, val icon: String) {
    MONITOR("Monitor", "📡"),
    PICTURES("Pictures", "📷"),
    SETTINGS("Settings", "⚙️")
}

private val NavBarBg   = Color(0xFF000000)
private val NavBarSel  = Color(0xFF3B82F6)
private val NavBarUnsel= Color(0xFF6B7280)

@Composable
fun MainTabScreen(
    status     : String,
    ipAddress  : String,
    connected  : Boolean,
    temp       : String,
    humidity   : String,
    light      : String,
    logs       : List<String>,
    photos     : List<File>,
    onDeletePhoto: (File) -> Unit,
    onUploadPhoto: (File) -> Unit,
    onExportPhotos: (List<File>) -> Unit
) {
    var selectedTab by remember { mutableStateOf(Tab.MONITOR) }

    Scaffold(
        containerColor = Color(0xFF000000),
        bottomBar = {
            NavigationBar(
                containerColor = NavBarBg,
                tonalElevation = 0.dp
            ) {
                Tab.values().forEach { tab ->
                    val selected = tab == selectedTab
                    NavigationBarItem(
                        selected = selected,
                        onClick  = { selectedTab = tab },
                        icon     = {
                            Text(
                                tab.icon,
                                fontSize = if (selected) 22.sp else 20.sp
                            )
                        },
                        label    = {
                            Text(
                                tab.label,
                                fontSize   = 10.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors   = NavigationBarItemDefaults.colors(
                            selectedTextColor   = NavBarSel,
                            unselectedTextColor = NavBarUnsel,
                            indicatorColor      = NavBarSel.copy(alpha = 0.15f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                Tab.MONITOR  -> DoorSentinelScreen(
                    status    = status,
                    ipAddress = ipAddress,
                    connected = connected,
                    temp      = temp,
                    humidity  = humidity,
                    light     = light,
                    logs      = logs
                )
                Tab.PICTURES -> PicturesScreen(
                    photos         = photos,
                    onDeletePhoto  = onDeletePhoto,
                    onUploadPhoto  = onUploadPhoto,
                    onExportPhotos = onExportPhotos
                )
                Tab.SETTINGS -> SettingsScreen()
            }
        }
    }
}

// ============================================================
//  Monitor screen composables (unchanged from before)
// ============================================================

@Composable
fun DoorSentinelScreen(
    status: String,
    ipAddress: String,
    connected: Boolean,
    temp: String,
    humidity: String,
    light: String,
    logs: List<String>
) {
    val darkBg      = Color(0xFF000000)
    val cardBg      = Color(0xFF080A0D)
    val accentBlue  = Color(0xFF3B82F6)
    val accentGreen = Color(0xFF90D769)
    val accentRed   = Color(0xFFFF5C5C)
    val textPrimary = Color(0xFFF2F3F5)
    val textSub     = Color(0xFF9AA1AD)

    Surface(modifier = Modifier.fillMaxSize(), color = darkBg) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))

            // ── HEADER ──────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "DOOR SENTINEL",
                        color = accentBlue, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 3.sp
                    )
                    Text(
                        "Security Monitor",
                        color = textPrimary, fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                ConnectionPill(connected = connected, accentGreen = accentGreen, accentRed = accentRed)
            }

            Spacer(Modifier.height(16.dp))

            // ── TELEMETRY ROW ────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TelemetryCard(
                    modifier    = Modifier.weight(1f), icon = "🌡️", label = "TEMP",
                    value       = if (temp == "--") "--" else "$temp°C",
                    cardBg      = cardBg, textPrimary = textPrimary, textSub = textSub
                )
                TelemetryCard(
                    modifier    = Modifier.weight(1f), icon = "💧", label = "HUMIDITY",
                    value       = if (humidity == "--") "--" else "$humidity%",
                    cardBg      = cardBg, textPrimary = textPrimary, textSub = textSub
                )
                TelemetryCard(
                    modifier    = Modifier.weight(1f), icon = "☀️", label = "LIGHT",
                    value       = light,
                    cardBg      = cardBg, textPrimary = textPrimary, textSub = textSub
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── STATUS CARD ──────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(16.dp),
                colors   = CardDefaults.cardColors(containerColor = cardBg)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("SYSTEM", color = textSub, fontSize = 10.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(status, color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = Color(0xFF252B38))
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📡", fontSize = 14.sp)
                        Spacer(Modifier.width(6.dp))
                        Text("Phone IP: ", color = textSub, fontSize = 12.sp)
                        Text(ipAddress, color = accentBlue, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "ESP32 → http://$ipAddress:8080/trigger",
                        color = textSub, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── EVENT LOG ────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("EVENT LOG", color = textSub, fontSize = 10.sp, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Text("${logs.size} events", color = textSub, fontSize = 10.sp)
            }

            Spacer(Modifier.height(6.dp))

            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape    = RoundedCornerShape(16.dp),
                colors   = CardDefaults.cardColors(containerColor = cardBg)
            ) {
                LazyColumn(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(logs) { log ->
                        Text(
                            text       = log,
                            fontSize   = 11.sp,
                            color      = if (log.contains("❌") || log.contains("⚠️")) accentRed
                                         else if (log.contains("✅")) accentGreen
                                         else textSub,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun ConnectionPill(connected: Boolean, accentGreen: Color, accentRed: Color) {
    val dotColor by animateColorAsState(
        targetValue  = if (connected) accentGreen else accentRed,
        animationSpec = tween(600), label = "connectionDot"
    )
    val bgColor by animateColorAsState(
        targetValue  = if (connected) accentGreen.copy(alpha = 0.15f) else accentRed.copy(alpha = 0.15f),
        animationSpec = tween(600), label = "connectionBg"
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text       = if (connected) "Connected" else "Disconnected",
            color      = dotColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun TelemetryCard(
    modifier    : Modifier = Modifier,
    icon        : String,
    label       : String,
    value       : String,
    cardBg      : Color,
    textPrimary : Color,
    textSub     : Color
) {
    Card(
        modifier = modifier,
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = cardBg)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(icon, fontSize = 22.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, color = textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(label, color = textSub, fontSize = 9.sp, letterSpacing = 1.sp)
        }
    }
}