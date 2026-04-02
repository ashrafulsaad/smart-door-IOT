package com.bizarrewind.doorsentinel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val statusText   = mutableStateOf("Waiting for permissions...")
    private val ipAddressText = mutableStateOf("Fetching IP...")
    private val logs         = mutableStateListOf<String>()

    // Telemetry
    private val isConnected  = mutableStateOf(false)
    private val tempText     = mutableStateOf("--")
    private val humText      = mutableStateOf("--")
    private val lightText    = mutableStateOf("--")
    private var connectionTimeoutJob: Job? = null

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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
                DoorSentinelScreen(
                    status     = statusText.value,
                    ipAddress  = currentIp,
                    connected  = isConnected.value,
                    temp       = tempText.value,
                    humidity   = humText.value,
                    light      = lightText.value,
                    logs       = logs
                )
            }
        }
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
            // Already posted to main thread inside ServerService
            tempText.value  = temp
            humText.value   = hum
            lightText.value = light

            // Mark connected and reset the 5-second timeout
            isConnected.value = true
            connectionTimeoutJob?.cancel()
            connectionTimeoutJob = lifecycleScope.launch {
                delay(5000)
                isConnected.value = false
                addLog("⚠️ ESP32 heartbeat lost — disconnected")
            }
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

    override fun onDestroy() {
        super.onDestroy()
        stopService(Intent(this, ServerService::class.java))
    }
}

// ============================================================
//  UI COMPOSABLES
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
    val darkBg     = Color(0xFF0D0F14)
    val cardBg     = Color(0xFF171B22)
    val accentBlue = Color(0xFF4A9EFF)
    val accentGreen = Color(0xFF34D399)
    val accentRed  = Color(0xFFFF5C5C)
    val textPrimary = Color(0xFFE8EAF0)
    val textSub    = Color(0xFF8B95A8)

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
                        color = accentBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp
                    )
                    Text(
                        "Security Monitor",
                        color = textPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                // Connection pill
                ConnectionPill(connected = connected, accentGreen = accentGreen, accentRed = accentRed)
            }

            Spacer(Modifier.height(16.dp))

            // ── TELEMETRY ROW ────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TelemetryCard(
                    modifier = Modifier.weight(1f),
                    icon = "🌡️",
                    label = "TEMP",
                    value = if (temp == "--") "--" else "$temp°C",
                    cardBg = cardBg,
                    textPrimary = textPrimary,
                    textSub = textSub
                )
                TelemetryCard(
                    modifier = Modifier.weight(1f),
                    icon = "💧",
                    label = "HUMIDITY",
                    value = if (humidity == "--") "--" else "$humidity%",
                    cardBg = cardBg,
                    textPrimary = textPrimary,
                    textSub = textSub
                )
                TelemetryCard(
                    modifier = Modifier.weight(1f),
                    icon = "☀️",
                    label = "LIGHT",
                    value = light,
                    cardBg = cardBg,
                    textPrimary = textPrimary,
                    textSub = textSub
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── STATUS CARD ──────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg)
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
                        color = textSub,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
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
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg)
            ) {
                LazyColumn(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(logs) { log ->
                        Text(
                            text = log,
                            fontSize = 11.sp,
                            color = if (log.contains("❌") || log.contains("⚠️")) accentRed
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
        targetValue = if (connected) accentGreen else accentRed,
        animationSpec = tween(600),
        label = "connectionDot"
    )
    val bgColor by animateColorAsState(
        targetValue = if (connected) accentGreen.copy(alpha = 0.15f) else accentRed.copy(alpha = 0.15f),
        animationSpec = tween(600),
        label = "connectionBg"
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (connected) "Connected" else "Disconnected",
            color = dotColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun TelemetryCard(
    modifier: Modifier = Modifier,
    icon: String,
    label: String,
    value: String,
    cardBg: Color,
    textPrimary: Color,
    textSub: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg)
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