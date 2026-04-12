package com.bizarrewind.doorsentinel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.storage.FirebaseStorage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ServerService : LifecycleService() {

    private var server: LocalServer? = null
    private lateinit var cameraExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraControl: CameraControl? = null
    private var imageCapture: ImageCapture? = null

    // ── ML Kit face detector ──────────────────────────────────────────────────
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
    )

    // ── Burst-capture state ───────────────────────────────────────────────────
    private var isCapturing = false
    private var burstJob: Job? = null

    // ── Camera-warm state (keeps camera bound for instant trigger) ─────────────
    private var isCameraWarm = false
    private var warmShutterNs = 0L   // shutter speed baked into the current ImageCapture

    // ── Livestream state ──────────────────────────────────────────────────────
    private var isLivestreaming = false
    private var livestreamFps = 3
    private var livestreamWidth = 320
    private var livestreamHeight = 240
    private var lastFrameTime = 0L
    private var livestreamAnalysis: ImageAnalysis? = null

    // ── Torch state (persists across camera rebind) ──────────────────────────
    private var torchRequested = false

    // ── Firebase ──────────────────────────────────────────────────────────────
    private val DB_URL = "https://doorsentinel-2-default-rtdb.asia-southeast1.firebasedatabase.app"
    private val STORAGE_URL = "gs://doorsentinel-2.firebasestorage.app"
    private val dbRef by lazy { FirebaseDatabase.getInstance(DB_URL).reference }

    // ── Latest telemetry (for snapshot in event logs) ─────────────────────────
    private var latestTemp  = "--"
    private var latestHum   = "--"
    private var latestLight = "--"

    // ── Heartbeat ─────────────────────────────────────────────────────────────
    private var heartbeatJob: Job? = null

    // ── Idle baseline (Background Subtraction) ─────────────────────────────────
    private var baselineBitmap: Bitmap? = null
    private var lastBaselineTime = 0L

    companion object {
        const val CHANNEL_ID      = "DoorSentinelChannel"
        const val NOTIFICATION_ID = 1

        const val BURST_COUNT         = 6
        const val BURST_INTERVAL_MS   = 600L
        const val EXPOSURE_SETTLE_MS  = 600L

        var onTriggerCallback: (() -> Unit)? = null
        var onStopCallback: (() -> Unit)? = null
        var onFaceDetectedCallback: (() -> Unit)? = null
        var onTelemetryReceived: ((temp: String, hum: String, light: String) -> Unit)? = null
        var onPhotoSaved: ((File) -> Unit)? = null
        var manualUploadRequest: ((File) -> Unit)? = null
        var bulkUploadRequest: (() -> Unit)? = null

        @Volatile var lastLightValue: Int = -1
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
        createNotificationChannel()

        startForeground(
            NOTIFICATION_ID,
            buildNotification("Sentinel armed — idle"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        // ── Pre-warm camera and KEEP BOUND for instant trigger response ──────
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            try {
                warmUpCamera()
                Log.d("ServerService", "📷 Camera pre-warmed and kept bound — instant trigger available")
            } catch (e: Exception) {
                Log.e("ServerService", "📷 Pre-warm bind failed: ${e.message}")
                cameraProvider?.unbindAll()
            }
        }, ContextCompat.getMainExecutor(this))

        // Wire manual-upload from UI
        manualUploadRequest = { file -> uploadToFirebase(file, burstIndex = -1) }
        bulkUploadRequest = {
            lifecycleScope.launch(Dispatchers.IO) {
                val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                val files = dir?.listFiles { f -> f.name.endsWith(".jpg") } ?: emptyArray()
                Log.d("ServerService", "📤 Bulk upload started for ${files.size} local photos")
                updateNotification("⬆️ Bulk Uploading 0/${files.size} photos")
                files.forEachIndexed { index, file ->
                    updateNotification("⬆️ Bulk Uploading ${index + 1}/${files.size} photos")
                    uploadToFirebase(file, burstIndex = 99) // 99 indicates bulk
                    delay(500) // gentle networking
                }
                updateNotification("✅ Bulk Upload Complete")
            }
        }

        server = LocalServer(
            port = 8080,
            onTrigger = {
                triggerCapture()
            },
            onStop = {
                stopCapture()
            }
        )

        try {
            server?.start()
            startUdpTelemetryListener()

            // Force Firebase to reconnect (recovers from stale/dropped connections)
            FirebaseDatabase.getInstance(DB_URL).goOnline()
            Log.d("ServerService", "🔥 Firebase goOnline() called — forcing reconnection")

            setupPhoneStatus()
            startCommandListener()
        } catch (e: Exception) {
            Log.e("ServerService", "❌ Failed to start server: ${e.message}")
        }
    }

    // ── Camera warm-up (keeps camera perpetually bound) ───────────────────────

    @OptIn(ExperimentalCamera2Interop::class)
    private fun warmUpCamera() {
        val provider = cameraProvider ?: return
        val shutterNs = SentinelPrefs.shutterSpeedNs

        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG)

        if (shutterNs > 0L) {
            Camera2Interop.Extender(captureBuilder)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        }

        imageCapture = captureBuilder.build()
        warmShutterNs = shutterNs

        provider.unbindAll()
        val cam = provider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
            imageCapture!!
        )
        cameraControl = cam.cameraControl
        isCameraWarm = true
    }

    // ── Trigger capture (ultra-fast path when camera is warm) ─────────────────

    private var mediaPlayer: android.media.MediaPlayer? = null

    private fun triggerCapture() {
        isCapturing = true
        onTriggerCallback?.invoke()
        updateNotification("🔥 TRIGGERED — burst capturing")

        // ── Audio Bait ──
        val baitUriStr = SentinelPrefs.audioBaitUri
        if (baitUriStr != null) {
            try {
                mediaPlayer?.release()
                mediaPlayer = android.media.MediaPlayer.create(this, android.net.Uri.parse(baitUriStr))
                mediaPlayer?.start()
                Log.d("ServerService", "🔊 Playing audio bait: $baitUriStr")
            } catch (e: Exception) {
                Log.e("ServerService", "🔊 Audio bait failed: ${e.message}")
            }
        }

        val threshold = SentinelPrefs.lightTriggerThreshold
        val torchAllowed = SentinelPrefs.torchEnabled
        val isDark = lastLightValue > threshold || (threshold == 0 && lastLightValue >= 0)
        val shouldTorch = torchAllowed && (lastLightValue >= 0 && isDark)

        if (shouldTorch) {
            cameraControl?.enableTorch(true)
            Log.d("ServerService", "🔦 Torch ON — light=$lastLightValue > threshold=$threshold (DARK)")
        } else {
            Log.d("ServerService", "🔦 Torch SKIP — light=$lastLightValue threshold=$threshold torchEnabled=$torchAllowed")
        }

        // Check if we need to rebuild ImageCapture (shutter speed changed)
        val currentShutterNs = SentinelPrefs.shutterSpeedNs
        val needsRebuild = !isCameraWarm || currentShutterNs != warmShutterNs

        if (needsRebuild) {
            // Slow path: rebind camera (only when settings changed)
            Log.d("ServerService", "📷 Rebuilding ImageCapture (shutter changed: $warmShutterNs → $currentShutterNs)")
            ContextCompat.getMainExecutor(this).execute {
                warmUpCamera()
                if (shouldTorch) cameraControl?.enableTorch(true)
                startBurst(shouldTorch)
            }
        } else {
            // ⚡ FAST PATH: camera already warm, capture immediately
            Log.d("ServerService", "⚡ Fast trigger — camera warm, capturing immediately")
            startBurst(shouldTorch)
        }
    }

    private fun startBurst(torchOn: Boolean) {
        burstJob = lifecycleScope.launch {
            val burstCount  = SentinelPrefs.burstCount
            val intervalMs  = SentinelPrefs.burstIntervalMs

            // Minimal delay — just enough for torch to reach full brightness
            delay(if (torchOn) 80L else 20L)

            repeat(burstCount) { index ->
                if (!isCapturing) return@launch
                Log.d("ServerService", "📸 Burst shot ${index + 1}/$burstCount")
                takeAndSavePhoto(burstIndex = index)
                if (index < burstCount - 1) delay(intervalMs)
            }
            stopCapture()
        }
    }

    private fun stopCapture() {
        isCapturing = false
        burstJob?.cancel()
        onStopCallback?.invoke()
        updateNotification("✅ Sentinel armed — idle")
        // Only turn off torch if it wasn't explicitly requested by user
        if (!torchRequested) {
            cameraControl?.enableTorch(false)
        }
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        // Camera stays bound — warm for next trigger
        Log.d("ServerService", "📷 Burst complete — camera stays warm")
    }

    // ── Photo capture ─────────────────────────────────────────────────────────

    private fun takeAndSavePhoto(burstIndex: Int) {
        val cap = imageCapture ?: run {
            Log.e("ServerService", "📸 imageCapture is null — skipping shot $burstIndex")
            return
        }

        val ts        = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(System.currentTimeMillis())
        val photoFile = File(
            getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES),
            "${ts}_burst${burstIndex}.jpg"
        )

        cap.takePicture(
            ImageCapture.OutputFileOptions.Builder(photoFile).build(),
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d("ServerService", "📸 Saved burst[$burstIndex]: ${photoFile.name}")
                    Handler(Looper.getMainLooper()).post { onPhotoSaved?.invoke(photoFile) }

                    if (!SentinelPrefs.uploadEnabled || burstIndex >= SentinelPrefs.uploadCount) return

                    // ── Gate 1 (ALWAYS): Background subtraction ──────────────────────
                    // Motion detection is the primary gate — if nothing moved vs the
                    // baseline, the scene is static background → drop the photo.
                    val bitmap = android.graphics.BitmapFactory.decodeFile(photoFile.absolutePath) ?: run {
                        Log.w("ServerService", "⚠️ Bitmap null — skipping gates, uploading anyway")
                        uploadToFirebase(photoFile, burstIndex)
                        return
                    }

                    val hasMotion = checkMotionAndQuality(bitmap)
                    if (!hasMotion) {
                        Log.d("ServerService", "😴 Static background — dropping burst[$burstIndex]")
                        return  // Nothing moved → don't upload
                    }

                    // ── Gate 2: Upload filter ────────────────────────────────────────
                    // Motion was detected. Now apply the filter setting:
                    //   "both"  → upload, something moved
                    //   "human" → only upload if a face is present
                    val filter = SentinelPrefs.uploadFilter
                    if (filter == "human" && SentinelPrefs.faceDetectionEnabled) {
                        val labelImage = InputImage.fromBitmap(bitmap, 0)
                        faceDetector.process(labelImage)
                            .addOnSuccessListener { faces ->
                                if (faces.isNotEmpty()) {
                                    Log.d("ServerService", "👤 Human filter: face found in burst[$burstIndex] — uploading")
                                    onFaceDetectedCallback?.invoke()
                                    uploadToFirebase(photoFile, burstIndex, motionDetected = true)
                                } else {
                                    Log.d("ServerService", "🚫 Human filter: motion but no face in burst[$burstIndex] — skipping")
                                }
                            }
                            .addOnFailureListener { e ->
                                Log.w("ServerService", "⚠️ Face detection failed: ${e.message} — uploading anyway")
                                uploadToFirebase(photoFile, burstIndex, motionDetected = true)
                            }
                    } else {
                        // "both" mode: upload everything that moved
                        Log.d("ServerService", "✅ Both filter: motion in burst[$burstIndex] — uploading")
                        uploadToFirebase(photoFile, burstIndex, motionDetected = true)
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e("ServerService", "📸 Capture failed burst[$burstIndex]: ${exc.message}", exc)
                }
            }
        )
    }

    // ── Motion Baseline Subtraction & Quality Check ───────────────────────────
    private fun checkMotionAndQuality(bitmap: Bitmap): Boolean {
        // Resize down to 64x64 for ultra-fast diffing and noise reduction
        val scaled = Bitmap.createScaledBitmap(bitmap, 64, 64, false)
        val pixels = IntArray(64 * 64)
        scaled.getPixels(pixels, 0, 64, 0, 0, 64, 64)

        var darkPixels = 0
        var totalLuma = 0
        val lumaArray = IntArray(64 * 64)

        for (i in pixels.indices) {
            val color = pixels[i]
            val r = (color shr 16) and 0xff
            val g = (color shr 8) and 0xff
            val b = color and 0xff
            val luma = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
            lumaArray[i] = luma
            totalLuma += luma
            if (luma < 10) darkPixels++
        }

        val avgLuma = if (pixels.isNotEmpty()) totalLuma / pixels.size else 0

        // 1. Black image drop
        if (avgLuma < 15 || darkPixels > (pixels.size * 0.90)) {
            Log.w("ServerService", "🖤 Image rejected: Too dark ($avgLuma luma, $darkPixels dark px)")
            return false // Force drop
        }

        val now = System.currentTimeMillis()
        if (baselineBitmap == null || (now - lastBaselineTime > 300_000)) { // Update baseline every 5 min
            baselineBitmap = scaled
            lastBaselineTime = now
            Log.d("ServerService", "🖼️ Created new motion baseline")
            return true // Allow first frame
        }

        val basePixels = IntArray(64 * 64)
        val baseLumaArray = IntArray(64 * 64)
        baselineBitmap!!.getPixels(basePixels, 0, 64, 0, 0, 64, 64)

        var diffPixels = 0
        for (i in pixels.indices) {
            val bColor = basePixels[i]
            val br = (bColor shr 16) and 0xff
            val bg = (bColor shr 8) and 0xff
            val bb = bColor and 0xff
            val bLuma = (br * 0.299 + bg * 0.587 + bb * 0.114).toInt()

            if (Math.abs(lumaArray[i] - bLuma) > 30) {
                diffPixels++
            }
        }

        val diffRatio = diffPixels.toFloat() / pixels.size.toFloat()
        val formattedDiff = String.format("%.1f", diffRatio * 100)

        // 2. Motion threshold drop
        if (diffRatio > 0.25f) {
            Log.d("ServerService", "⚡ Motion detected: $formattedDiff% pixels changed")
            baselineBitmap = scaled
            lastBaselineTime = now
            return true
        }

        Log.d("ServerService", "😴 No motion: $formattedDiff% pixels changed")
        return false // Below motion threshold
    }

    // ── Face detection ────────────────────────────────────────────────────────

    @kotlin.OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        if (!isCapturing) { imageProxy.close(); return }

        val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }

        val img = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        faceDetector.process(img)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val angles = faces.map { f ->
                        "yaw=%.1f pitch=%.1f".format(f.headEulerAngleY, f.headEulerAngleX)
                    }.joinToString(", ")
                    Log.d("ServerService", "👤 ${faces.size} face(s) detected [$angles]")
                    onFaceDetectedCallback?.invoke()
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    // ── Firebase upload ───────────────────────────────────────────────────────

    internal fun uploadToFirebase(photoFile: File, burstIndex: Int, motionDetected: Boolean = false) {
        Log.d("FirebaseDiag", "🔥 [1/6] Initialising FirebaseStorage — bucket: $STORAGE_URL")

        val storage: FirebaseStorage
        try {
            storage = FirebaseStorage.getInstance(STORAGE_URL)
            Log.d("FirebaseDiag", "✅ [2/6] FirebaseStorage instance obtained")
        } catch (e: Exception) {
            Log.e("FirebaseDiag", "❌ [2/6] FirebaseStorage init FAILED: ${e.message}", e)
            return
        }

        val imageRef = storage.reference.child("intruders/${photoFile.name}")
        Log.d("FirebaseDiag", "📁 [3/6] StorageReference path: ${imageRef.path}")

        if (!SentinelPrefs.faceDetectionEnabled) {
            Log.d("FirebaseDiag", "⏩ Face detection disabled — uploading without label")
            doUpload(imageRef, photoFile, burstIndex, faceDetected = false, faceCount = 0, motionDetected = motionDetected)
            return
        }

        val bitmap = android.graphics.BitmapFactory.decodeFile(photoFile.absolutePath)
        if (bitmap == null) {
            Log.w("FirebaseDiag", "⚠️ BitmapFactory returned null — uploading anyway")
            doUpload(imageRef, photoFile, burstIndex, faceDetected = false, faceCount = 0, motionDetected = motionDetected)
            return
        }

        val labelImage = InputImage.fromBitmap(bitmap, 0)
        faceDetector.process(labelImage)
            .addOnSuccessListener { faces ->
                doUpload(imageRef, photoFile, burstIndex, faceDetected = faces.isNotEmpty(), faceCount = faces.size, motionDetected = motionDetected)
            }
            .addOnFailureListener { e ->
                Log.w("FirebaseDiag", "⚠️ Face label failed (${e.message}) — uploading without label")
                doUpload(imageRef, photoFile, burstIndex, faceDetected = false, faceCount = 0, motionDetected = motionDetected)
            }
    }

    private fun doUpload(
        imageRef: com.google.firebase.storage.StorageReference,
        photoFile: File,
        burstIndex: Int,
        faceDetected: Boolean,
        faceCount: Int,
        motionDetected: Boolean
    ) {
        val label = if (burstIndex == 99) "Backlog" else "Burst[$burstIndex]"
        updateNotification("☁️ Uploading $label to server...")

        imageRef.putFile(Uri.fromFile(photoFile))
            .addOnProgressListener { snap ->
                val pct = (100.0 * snap.bytesTransferred / snap.totalByteCount).toInt()
                if (pct % 25 == 0) updateNotification("☁️ Uploading $label ($pct%)...")
                Log.d("FirebaseDiag", "   ↑ upload progress: $pct%")
            }
            .addOnSuccessListener { meta ->
                Log.d("FirebaseDiag", "✅ [5/6] putFile() SUCCESS — bytes: ${meta.bytesTransferred}")
                imageRef.downloadUrl
                    .addOnSuccessListener { uri ->
                        Log.d("FirebaseDiag", "✅ [6/6] Download URL: $uri")
                        saveToDatabase(uri.toString(), photoFile.name, burstIndex, faceDetected, faceCount, motionDetected)
                    }
                    .addOnFailureListener { e ->
                        Log.e("FirebaseDiag", "❌ [6/6] getDownloadUrl FAILED: ${e.message}", e)
                    }
            }
            .addOnFailureListener { e ->
                Log.e("FirebaseDiag", "❌ [4/6] putFile() FAILED: ${e.message}", e)
            }
    }

    private fun saveToDatabase(
        imageUrl: String,
        fileName: String,
        burstIndex: Int,
        faceDetected: Boolean,
        faceCount: Int,
        motionDetected: Boolean
    ) {
        val eventId = fileName.substringBefore("_burst")

        val logData = mapOf(
            "timestamp"      to System.currentTimeMillis(),
            "fileName"       to fileName,
            "imageUrl"       to imageUrl,
            "eventId"        to eventId,
            "burstIndex"     to burstIndex,
            "faceDetected"   to faceDetected,
            "faceCount"      to faceCount,
            "motionDetected" to motionDetected,
            "person"         to "UNKNOWN",
            "status"         to "unlabelled",
            // ── Telemetry snapshot at capture time ──
            "telemetry"    to mapOf(
                "temp"     to latestTemp,
                "humidity" to latestHum,
                "light"    to latestLight
            )
        )

        dbRef.child("logs").push().setValue(logData)
            .addOnSuccessListener { 
                Log.d("ServerService", "📝 DB log created for burst[$burstIndex]")
                updateNotification("✅ Upload complete for $fileName")
                Handler(Looper.getMainLooper()).postDelayed({
                    updateNotification("Sentinel armed — idle")
                }, 2000)
            }
            .addOnFailureListener { e -> Log.e("ServerService", "❌ DB log failed: ${e.message}") }
    }

    // ── Phone status (online/offline) — resilient .info/connected pattern ─────

    private fun setupPhoneStatus() {
        val statusRef = dbRef.child("status/phone")
        val connectedRef = FirebaseDatabase.getInstance(DB_URL).getReference(".info/connected")

        connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                if (connected) {
                    // Re-register online status AND onDisconnect on EVERY reconnect
                    statusRef.onDisconnect().setValue(mapOf("online" to false, "lastSeen" to ServerValue.TIMESTAMP))
                    statusRef.setValue(mapOf("online" to true, "lastSeen" to ServerValue.TIMESTAMP))
                    Log.d("ServerService", "📡 Firebase connected — phone status: ONLINE")
                } else {
                    Log.w("ServerService", "📡 Firebase disconnected — waiting for reconnect...")
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("ServerService", "📡 .info/connected listener cancelled: ${error.message}")
            }
        })

        // Heartbeat: update lastSeen every 30s to keep connection alive
        heartbeatJob?.cancel()
        heartbeatJob = lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                delay(30_000)
                try {
                    statusRef.child("lastSeen").setValue(ServerValue.TIMESTAMP)
                } catch (e: Exception) {
                    Log.w("ServerService", "💓 Heartbeat failed: ${e.message}")
                }
            }
        }

        Log.d("ServerService", "📡 Phone status setup with .info/connected + heartbeat")
    }

    // ── Remote command listener ───────────────────────────────────────────────

    private fun startCommandListener() {
        val commandsRef = dbRef.child("commands")

        commandsRef.orderByChild("status").equalTo("pending")
            .addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    val type = snapshot.child("type").getValue(String::class.java) ?: return
                    val key  = snapshot.key ?: return

                    // ── Skip stale commands (older than 60 seconds) ──
                    // Note: serverTimestamp() may initially be a placeholder Map before resolving
                    val cmdTimestamp = try {
                        snapshot.child("timestamp").getValue(Long::class.java) ?: 0L
                    } catch (_: Exception) { 0L }
                    val age = if (cmdTimestamp > 0) System.currentTimeMillis() - cmdTimestamp else 0L
                    if (cmdTimestamp > 0 && age > 60_000) {
                        Log.w("RemoteCmd", "⏭ Skipping stale command: $type ($key) — ${age/1000}s old")
                        snapshot.ref.child("status").setValue("expired")
                        return
                    }

                    Log.d("RemoteCmd", "📡 Command received: $type ($key)")

                    Handler(Looper.getMainLooper()).post {
                        when (type) {
                            "capture" -> triggerCapture()
                            "torch_on" -> {
                                torchRequested = true
                                if (!isCameraWarm || cameraControl == null) {
                                    ContextCompat.getMainExecutor(this@ServerService).execute {
                                        warmUpCamera()
                                        cameraControl?.enableTorch(true)
                                        Log.d("RemoteCmd", "🔦 Torch ON (after warmup)")
                                    }
                                } else {
                                    cameraControl?.enableTorch(true)
                                    Log.d("RemoteCmd", "🔦 Torch ON (remote)")
                                }
                            }
                            "torch_off" -> {
                                torchRequested = false
                                cameraControl?.enableTorch(false)
                                Log.d("RemoteCmd", "🔦 Torch OFF (remote)")
                            }
                            "stop" -> stopCapture()
                            "livestream_start" -> {
                                val fps = snapshot.child("fps").getValue(Int::class.java) ?: 3
                                val w = snapshot.child("width").getValue(Int::class.java) ?: 320
                                val h = snapshot.child("height").getValue(Int::class.java) ?: 240
                                startLivestream(fps, w, h)
                            }
                            "livestream_stop" -> stopLivestream()

                            // ── ESP32 proxy commands ─────────────────────────────────────────────
                            // Phone forwards these to the ESP32's local web server
                            // using the IP address learned from its UDP telemetry packets.
                            "esp_toggle", "esp_toggle_auto", "esp_status" -> {
                                val ip = espIp
                                if (ip == null) {
                                    Log.w("RemoteCmd", "⚠️ ESP32 IP unknown — no UDP packet received yet")
                                    snapshot.ref.child("status").setValue("error_no_esp")
                                    return@post
                                }
                                val path = when (type) {
                                    "esp_toggle"      -> "/toggle"
                                    "esp_toggle_auto" -> "/toggleAuto"
                                    else              -> "/status"
                                }
                                val method = if (type == "esp_status") "GET" else "POST"
                                proxyToEsp(ip, path, method) { response ->
                                    snapshot.ref.child("espResponse").setValue(response)
                                }
                            }
                        }
                    }

                    // Mark command as done
                    snapshot.ref.child("status").setValue("done")
                    snapshot.ref.child("processedAt").setValue(ServerValue.TIMESTAMP)
                }

                override fun onChildChanged(s: DataSnapshot, p: String?) {}
                override fun onChildRemoved(s: DataSnapshot) {}
                override fun onChildMoved(s: DataSnapshot, p: String?) {}
                override fun onCancelled(error: DatabaseError) {
                    Log.e("RemoteCmd", "❌ Command listener cancelled: ${error.message}")
                }
            })

        Log.d("ServerService", "📡 Remote command listener active on /commands")
    }

    // ── Livestream (push frames via RTDB) ─────────────────────────────────────

    @kotlin.OptIn(ExperimentalGetImage::class)
    private fun startLivestream(fps: Int, width: Int = 320, height: Int = 240) {
        if (isLivestreaming) {
            livestreamFps = fps
            Log.d("Livestream", "📹 FPS updated to $fps")
            return
        }

        isLivestreaming = true
        livestreamFps = fps
        livestreamWidth = width
        livestreamHeight = height
        lastFrameTime = 0L
        val wasTorchOn = torchRequested

        Log.d("Livestream", "📹 Setting up livestream at ${fps}fps, ${width}x${height}...")

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(Size(width, height))
            .build()

        analysis.setAnalyzer(cameraExecutor) { proxy ->
            if (!isLivestreaming) { proxy.close(); return@setAnalyzer }

            val now = System.currentTimeMillis()
            val interval = 1000L / livestreamFps
            if (now - lastFrameTime < interval) { proxy.close(); return@setAnalyzer }
            lastFrameTime = now

            try {
                val bitmap = proxy.toBitmap()
                val rotationDeg = proxy.imageInfo.rotationDegrees

                val rotated = if (rotationDeg != 0) {
                    val mx = Matrix().apply { postRotate(rotationDeg.toFloat()) }
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, mx, true).also {
                        if (it !== bitmap) bitmap.recycle()
                    }
                } else bitmap

                val small = Bitmap.createScaledBitmap(rotated, livestreamWidth, livestreamHeight, true)
                if (small !== rotated) rotated.recycle()

                val quality = if (livestreamWidth >= 640) 50 else 30
                val baos = ByteArrayOutputStream()
                small.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                small.recycle()

                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

                dbRef.child("livestream/frame").setValue(mapOf(
                    "data"      to b64,
                    "timestamp" to now,
                    "width"     to livestreamWidth,
                    "height"    to livestreamHeight
                ))
            } catch (e: Exception) {
                Log.e("Livestream", "Frame push failed: ${e.message}")
            } finally {
                proxy.close()
            }
        }

        livestreamAnalysis = analysis

        // Rebind camera with both ImageCapture and ImageAnalysis
        ContextCompat.getMainExecutor(this).execute {
            try {
                val provider = cameraProvider ?: run {
                    Log.e("Livestream", "❌ cameraProvider is null — cannot start")
                    isLivestreaming = false
                    return@execute
                }
                provider.unbindAll()

                val captureBuilder = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                imageCapture = captureBuilder.build()

                val cam = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    imageCapture!!,
                    analysis
                )
                cameraControl = cam.cameraControl
                isCameraWarm = true
                warmShutterNs = 0L

                // Restore torch state after rebind
                if (wasTorchOn) {
                    cameraControl?.enableTorch(true)
                    Log.d("Livestream", "🔦 Torch restored after rebind")
                }

                Log.d("Livestream", "📹 Livestream started at ${fps}fps ${width}x${height}")
                updateNotification("📹 Livestream active — ${fps}fps")

                dbRef.child("livestream/status").setValue(mapOf(
                    "active" to true,
                    "fps"    to fps,
                    "width"  to width,
                    "height" to height,
                    "startedAt" to ServerValue.TIMESTAMP
                ))
            } catch (e: Exception) {
                Log.e("Livestream", "❌ Dual bind failed: ${e.message} — trying analysis-only fallback")
                try {
                    val provider = cameraProvider ?: return@execute
                    provider.unbindAll()
                    val cam = provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        analysis
                    )
                    cameraControl = cam.cameraControl
                    isCameraWarm = false
                    imageCapture = null

                    if (wasTorchOn) cameraControl?.enableTorch(true)

                    Log.d("Livestream", "📹 Livestream started (analysis-only fallback) at ${fps}fps")
                    updateNotification("📹 Livestream active — ${fps}fps (limited)")

                    dbRef.child("livestream/status").setValue(mapOf(
                        "active" to true,
                        "fps"    to fps,
                        "width"  to width,
                        "height" to height,
                        "startedAt" to ServerValue.TIMESTAMP
                    ))
                } catch (e2: Exception) {
                    Log.e("Livestream", "❌ Fallback also failed: ${e2.message}", e2)
                    isLivestreaming = false
                }
            }
        }
    }

    private fun stopLivestream() {
        isLivestreaming = false
        livestreamAnalysis?.clearAnalyzer()
        val wasTorchOn = torchRequested

        // Rebind without analysis
        ContextCompat.getMainExecutor(this).execute {
            try {
                warmUpCamera()
                // Restore torch if it was on
                if (wasTorchOn) {
                    cameraControl?.enableTorch(true)
                    Log.d("Livestream", "🔦 Torch restored after stream stop")
                }
                Log.d("Livestream", "📹 Livestream stopped — camera warm")
                updateNotification("✅ Sentinel armed — idle")

                dbRef.child("livestream/status").setValue(mapOf(
                    "active" to false,
                    "stoppedAt" to ServerValue.TIMESTAMP
                ))
                dbRef.child("livestream/frame").removeValue()
            } catch (e: Exception) {
                Log.e("Livestream", "❌ Failed to stop cleanly: ${e.message}")
            }
        }
    }

    // ── ESP32 HTTP Proxy ──────────────────────────────────────────────────────
    // Fire-and-forget: sends a request to the ESP32's local web server.
    // Runs on IO thread, delivers response text via callback on main thread.
    // DOES NOT affect the IR → trigger path (separate coroutine, separate socket).

    private fun proxyToEsp(
        ip: String,
        path: String,
        method: String = "POST",
        onResult: (String) -> Unit
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                val url = java.net.URL("http://$ip$path")
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 3_000
                    readTimeout    = 3_000
                    setRequestProperty("Content-Length", "0")
                    if (method == "POST") doOutput = true
                }
                val code = conn.responseCode
                val body = try { conn.inputStream.bufferedReader().readText() } catch (_: Exception) { "" }
                conn.disconnect()
                Log.d("ESPProxy", "✅ $method $path → $code")
                body.take(500).ifBlank { "OK $code" }
            } catch (e: Exception) {
                Log.w("ESPProxy", "⚠️ Proxy failed: ${e.message}")
                "error: ${e.message}"
            }
            Handler(Looper.getMainLooper()).post { onResult(result) }
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Door Sentinel", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Door Sentinel")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ── UDP telemetry listener ────────────────────────────────────────────────

    private var isListeningUDP = false
    private var udpSocket: DatagramSocket? = null
    @Volatile private var espIp: String? = null   // learned from UDP source address

    private fun startUdpTelemetryListener() {
        isListeningUDP = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                udpSocket = DatagramSocket(8081)
                val buffer = ByteArray(1024)
                Log.d("Telemetry", "👂 UDP listening on port 8081")

                while (isListeningUDP) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)
                    val message = String(packet.data, 0, packet.length).trim()

                    // Learn ESP32's IP from each incoming UDP packet
                    val srcIp = packet.address?.hostAddress
                    if (srcIp != null && srcIp != espIp) {
                        espIp = srcIp
                        Log.d("Telemetry", "🌐 ESP32 IP learned: $srcIp")
                    }

                    // Format: T:24.5|H:60.0|L:2500
                    val parts = message.split("|")
                    if (parts.size == 3) {
                        val temp  = parts[0].substringAfter("T:")
                        val hum   = parts[1].substringAfter("H:")
                        val light = parts[2].substringAfter("L:")

                        // Store latest for event snapshots
                        latestTemp  = temp
                        latestHum   = hum
                        latestLight = light

                        lastLightValue = light.toIntOrNull() ?: lastLightValue

                        Handler(Looper.getMainLooper()).post {
                            onTelemetryReceived?.invoke(temp, hum, light)
                        }

                        // ── Push telemetry to RTDB for website ──
                        dbRef.child("telemetry/latest").setValue(mapOf(
                            "temp"      to temp,
                            "humidity"  to hum,
                            "light"     to light,
                            "timestamp" to ServerValue.TIMESTAMP
                        ))
                    }
                }
            } catch (e: Exception) {
                Log.e("Telemetry", "❌ UDP listener stopped: ${e.message}")
            }
        }
    }

    // ── Lifecycle cleanup ─────────────────────────────────────────────────────

    override fun onDestroy() {
        server?.stop()
        burstJob?.cancel()
        heartbeatJob?.cancel()
        isListeningUDP = false
        isLivestreaming = false
        udpSocket?.close()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        manualUploadRequest = null
        // Mark phone as offline
        try { dbRef.child("status/phone").setValue(mapOf("online" to false, "lastSeen" to ServerValue.TIMESTAMP)) } catch (_: Exception) {}
        super.onDestroy()
    }
}
