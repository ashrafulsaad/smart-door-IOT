package com.bizarrewind.doorsentinel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Colours ──────────────────────────────────────────────────────────────────────────────
private val SetDarkBg      = Color(0xFF000000)
private val SetCardBg      = Color(0xFF080A0D)
private val SetAccentBlue  = Color(0xFF3B82F6)
private val SetAccentGreen = Color(0xFF90D769)
private val SetTextPrimary = Color(0xFFF2F3F5)
private val SetTextSub     = Color(0xFF9AA1AD)
private val SetDivider     = Color(0xFF1A1D25)

// ── Shutter speed options ─────────────────────────────────────────────────────
private data class ShutterOption(val label: String, val ns: Long)

private val SHUTTER_OPTIONS = listOf(
    ShutterOption("Auto",    0L),
    ShutterOption("1/4000", 250_000L),
    ShutterOption("1/2000", 500_000L),
    ShutterOption("1/1000", 1_000_000L),
    ShutterOption("1/500",  2_000_000L),
    ShutterOption("1/250",  4_000_000L),
    ShutterOption("1/125",  8_000_000L),
    ShutterOption("1/60",   16_666_667L),
    ShutterOption("1/30",   33_333_333L)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    // ── Local state mirrors SentinelPrefs so sliders/toggles are reactive ─────
    var burstCount          by remember { mutableIntStateOf(SentinelPrefs.burstCount) }
    var burstIntervalMs     by remember { mutableIntStateOf(SentinelPrefs.burstIntervalMs.toInt()) }
    var exposureSettleMs    by remember { mutableIntStateOf(SentinelPrefs.exposureSettleMs.toInt()) }
    var uploadEnabled       by remember { mutableStateOf(SentinelPrefs.uploadEnabled) }
    var uploadCount         by remember { mutableIntStateOf(SentinelPrefs.uploadCount) }
    var faceDetection       by remember { mutableStateOf(SentinelPrefs.faceDetectionEnabled) }
    var torchEnabled        by remember { mutableStateOf(SentinelPrefs.torchEnabled) }
    var lightThreshold      by remember { mutableIntStateOf(SentinelPrefs.lightTriggerThreshold) }
    var uploadFilter        by remember { mutableStateOf(SentinelPrefs.uploadFilter) } // "human" | "both"
    var audioBaitUri        by remember { mutableStateOf(SentinelPrefs.audioBaitUri) }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            SentinelPrefs.setAudioBaitUri(uri.toString())
            audioBaitUri = uri.toString()
        }
    }

    // Shutter speed state
    val savedNs             = SentinelPrefs.shutterSpeedNs
    var selectedShutter     by remember { mutableStateOf(SHUTTER_OPTIONS.find { it.ns == savedNs } ?: SHUTTER_OPTIONS[0]) }
    var shutterDropdownOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SetDarkBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        // ── Title ──────────────────────────────────────────────────────────────
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(
                    "DOOR SENTINEL",
                    color = SetAccentBlue, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 3.sp
                )
                Text(
                    "Settings",
                    color = SetTextPrimary, fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Section: Capture ──────────────────────────────────────────────────
        SectionLabel("📷  CAPTURE")
        SettingsCard {

            IntSliderRow(
                label    = "Burst count",
                sub      = "Photos taken per trigger event",
                value    = burstCount,
                range    = 1..20,
                unit     = "shots",
                onValue  = { burstCount = it; SentinelPrefs.setBurstCount(it) }
            )
            Divider()
            IntSliderRow(
                label    = "Burst interval",
                sub      = "Time between shots in a burst",
                value    = burstIntervalMs,
                range    = 100..3000,
                step     = 50,
                unit     = "ms",
                onValue  = { burstIntervalMs = it; SentinelPrefs.setBurstIntervalMs(it) }
            )
            Divider()
            IntSliderRow(
                label    = "Exposure settle",
                sub      = "Wait after camera wakes before first shot",
                value    = exposureSettleMs,
                range    = 0..2000,
                step     = 50,
                unit     = "ms",
                onValue  = { exposureSettleMs = it; SentinelPrefs.setExposureSettleMs(it) }
            )
            Divider()

            // Shutter speed dropdown
            Column(modifier = Modifier.padding(vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Shutter speed", color = SetTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (selectedShutter.ns == 0L) "Auto — let camera decide" else "Fixed ${selectedShutter.label}s",
                            color = SetTextSub,  fontSize = 11.sp, lineHeight = 16.sp
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    ExposedDropdownMenuBox(
                        expanded         = shutterDropdownOpen,
                        onExpandedChange = { shutterDropdownOpen = it }
                    ) {
                        Surface(
                            modifier = Modifier
                                .menuAnchor()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { shutterDropdownOpen = true },
                            shape = RoundedCornerShape(8.dp),
                            color = SetAccentBlue.copy(alpha = 0.18f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    selectedShutter.label,
                                    color      = SetAccentBlue,
                                    fontSize   = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("▾", color = SetAccentBlue, fontSize = 10.sp)
                            }
                        }
                        ExposedDropdownMenu(
                            expanded         = shutterDropdownOpen,
                            onDismissRequest = { shutterDropdownOpen = false },
                            containerColor   = SetCardBg
                        ) {
                            SHUTTER_OPTIONS.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (option.ns == 0L) "Auto" else "${option.label}s",
                                            color      = if (option == selectedShutter) SetAccentBlue else SetTextPrimary,
                                            fontWeight = if (option == selectedShutter) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        selectedShutter = option
                                        SentinelPrefs.setShutterSpeedNs(option.ns)
                                        shutterDropdownOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Section: Audio Bait ───────────────────────────────────────────────────
        SectionLabel("🔊  AUDIO BAIT")
        SettingsCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .clickable { audioPicker.launch("audio/*") },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Select trigger sound", color = SetTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (audioBaitUri != null) "Sound selected (will play on trigger)" else "No sound selected",
                        color = SetTextSub, fontSize = 11.sp, lineHeight = 16.sp
                    )
                }
                if (audioBaitUri != null) {
                    TextButton(onClick = { 
                        SentinelPrefs.setAudioBaitUri(null)
                        audioBaitUri = null 
                    }) {
                        Text("Clear", color = SetAccentBlue, fontSize = 14.sp)
                    }
                } else {
                    Text("Select", color = SetAccentBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Section: Upload ───────────────────────────────────────────────────
        SectionLabel("☁️  UPLOAD")
        SettingsCard {

            ToggleRow(
                label   = "Auto-upload",
                sub     = "Automatically send captured photos to Firebase Storage",
                checked = uploadEnabled,
                onToggle = { uploadEnabled = it; SentinelPrefs.setUploadEnabled(it) }
            )

            if (uploadEnabled) {
                Divider()
                IntSliderRow(
                    label   = "Max uploads per burst",
                    sub     = "Only the first N photos of each burst are uploaded",
                    value   = uploadCount.coerceAtMost(burstCount),
                    range   = 1..burstCount.coerceAtLeast(1),
                    unit    = "photos",
                    onValue = { uploadCount = it; SentinelPrefs.setUploadCount(it) }
                )
            }

            if (uploadEnabled) {
                Divider()
                // ── Upload filter: Human / Both segmented control ──────────────────
                Column(modifier = Modifier.padding(vertical = 10.dp)) {
                    Text("Upload filter", color = SetTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (uploadFilter == "human") "Only upload when a face is detected (saves storage)" else "Upload everything that triggered motion",
                        color = SetTextSub, fontSize = 11.sp, lineHeight = 16.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SetCardBg.copy(alpha = 0.6f)),
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        listOf("human" to "👤 Human", "both" to "⚡️ Both").forEach { (key, label) ->
                            val selected = uploadFilter == key
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        uploadFilter = key
                                        SentinelPrefs.setUploadFilter(key)
                                    },
                                color = if (selected) SetAccentBlue.copy(alpha = 0.2f) else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    label,
                                    color      = if (selected) SetAccentBlue else SetTextSub,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize   = 13.sp,
                                    modifier   = Modifier.padding(vertical = 10.dp).fillMaxWidth(),
                                    textAlign  = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }
                Divider()
                TextButton(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    onClick = { ServerService.bulkUploadRequest?.invoke() }
                ) {
                    Text("Bulk Upload Backlog Photos", color = SetAccentBlue, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Section: AI & Hardware ────────────────────────────────────────────
        SectionLabel("🤖  AI & HARDWARE")
        SettingsCard {

            ToggleRow(
                label    = "Face detection",
                sub      = "Run ML Kit on each photo to label faces in Firebase. " +
                           "Disable to speed up upload start.",
                checked  = faceDetection,
                onToggle = { faceDetection = it; SentinelPrefs.setFaceDetection(it) }
            )
            Divider()
            ToggleRow(
                label    = "Torch in dark mode",
                sub      = "Flash/torch fires when light reading is ABOVE the threshold (higher = darker room)",
                checked  = torchEnabled,
                onToggle = { torchEnabled = it; SentinelPrefs.setTorchEnabled(it) }
            )
            if (torchEnabled) {
                Divider()
                IntSliderRow(
                    label   = "Light trigger threshold",
                    sub     = "Torch fires when ESP32 light sensor reads above this value. Higher value = darker room.",
                    value   = lightThreshold,
                    range   = 0..5000,
                    step    = 100,
                    unit    = "lux",
                    onValue = { lightThreshold = it; SentinelPrefs.setLightTriggerThreshold(it) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Info card ─────────────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(14.dp),
            colors   = CardDefaults.cardColors(containerColor = SetAccentBlue.copy(alpha = 0.08f))
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text("ℹ️", fontSize = 16.sp, modifier = Modifier.padding(end = 10.dp, top = 2.dp))
                Text(
                    "Settings take effect on the next trigger — the camera response to an " +
                    "ESP32 /trigger request is never delayed by these values.\n\n" +
                    "Torch fires based on the last light reading received via UDP telemetry, " +
                    "compared against the threshold above.",
                    color    = SetTextSub,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(Modifier.height(80.dp))
    }
}

// ── Reusable composables ──────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text       = text,
        color      = SetTextSub,
        fontSize   = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        modifier   = Modifier.padding(start = 4.dp, bottom = 6.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = SetCardBg)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            content  = content
        )
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(
        modifier  = Modifier.padding(vertical = 4.dp),
        color     = SetDivider,
        thickness = 0.5.dp
    )
}

@Composable
private fun IntSliderRow(
    label   : String,
    sub     : String,
    value   : Int,
    range   : IntRange,
    unit    : String,
    step    : Int = 1,
    onValue : (Int) -> Unit
) {
    val steps = if (step > 1) (range.last - range.first) / step - 1 else 0

    Column(modifier = Modifier.padding(vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = SetTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(sub,   color = SetTextSub,     fontSize = 11.sp, lineHeight = 16.sp)
            }
            Spacer(Modifier.width(12.dp))
            Surface(
                shape  = RoundedCornerShape(8.dp),
                color  = SetAccentBlue.copy(alpha = 0.18f)
            ) {
                Text(
                    "$value $unit",
                    color      = SetAccentBlue,
                    fontSize   = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier   = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Slider(
            value         = value.toFloat(),
            onValueChange = { onValue(snapToStep(it.toInt(), range.first, step)) },
            valueRange    = range.first.toFloat()..range.last.toFloat(),
            steps         = steps.coerceAtLeast(0),
            colors        = SliderDefaults.colors(
                thumbColor        = SetAccentBlue,
                activeTrackColor  = SetAccentBlue,
                inactiveTrackColor = SetAccentBlue.copy(alpha = 0.2f)
            )
        )
    }
}

@Composable
private fun ToggleRow(
    label   : String,
    sub     : String,
    checked : Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = SetTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(sub,   color = SetTextSub,     fontSize = 11.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked         = checked,
            onCheckedChange = onToggle,
            colors          = SwitchDefaults.colors(
                checkedThumbColor      = Color.White,
                checkedTrackColor      = SetAccentGreen,
                uncheckedThumbColor    = Color.White,
                uncheckedTrackColor    = SetTextSub.copy(alpha = 0.35f)
            )
        )
    }
}

private fun snapToStep(raw: Int, min: Int, step: Int): Int {
    if (step <= 1) return raw
    val snapped = min + ((raw - min + step / 2) / step) * step
    return snapped
}
