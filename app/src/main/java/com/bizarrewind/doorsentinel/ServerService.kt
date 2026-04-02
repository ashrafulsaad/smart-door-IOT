package com.bizarrewind.doorsentinel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.storage.FirebaseStorage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
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
    // ACCURATE mode for better labeling of angled / looking-down faces.
    // FAST was used before but misses partial faces which are exactly what
    // you need in a training set.
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

    // How many photos per trigger and the gap between them (ms).
    // At 600 ms intervals over 3 seconds you get 5-6 shots covering
    // approach angle, straight-on, and departure — much richer dataset.
    companion object {
        const val CHANNEL_ID           = "DoorSentinelChannel"
        const val NOTIFICATION_ID      = 1
        const val BURST_COUNT          = 6       // photos per trigger event
        const val BURST_INTERVAL_MS    = 600L    // ms between shots
        const val EXPOSURE_SETTLE_MS   = 600L    // ms before first shot

        var onTriggerCallback: (() -> Unit)? = null
        var onStopCallback: (() -> Unit)? = null
        var onFaceDetectedCallback: (() -> Unit)? = null
        var onTelemetryReceived: ((temp: String, hum: String, light: String) -> Unit)? = null
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

        // Pre-warm the CameraProvider so the sensor can stay OFF until needed
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            cameraProvider?.unbindAll()
            Log.d("ServerService", "📷 CameraProvider ready — sensor is OFF (idle)")
        }, ContextCompat.getMainExecutor(this))

        server = LocalServer(
            port = 8080,
            onTrigger = { isDark ->
                isCapturing = true
                onTriggerCallback?.invoke()
                updateNotification("🔥 TRIGGERED — burst capturing")
                bindCameraAndBurstCapture(isDark)
            },
            onStop = {
                stopCapture()
            }
        )

        try {
            server?.start()
            startUdpTelemetryListener()
        } catch (e: Exception) {
            Log.e("ServerService", "❌ Failed to start server: ${e.message}")
        }
    }

    // ── Camera bind & burst ───────────────────────────────────────────────────

    /**
     * Binds CameraX use-cases on-demand (sensor OFF when idle).
     * Waits EXPOSURE_SETTLE_MS, then fires BURST_COUNT shots spaced
     * BURST_INTERVAL_MS apart.  Every frame is uploaded regardless of
     * whether a face was detected — you need the full variety for training.
     * Face detection is used only to add metadata labels in Firebase.
     */
    private fun bindCameraAndBurstCapture(isDark: Boolean) {
        val provider = cameraProvider ?: run {
            Log.e("ServerService", "❌ CameraProvider not ready yet — aborting capture")
            return
        }

        ContextCompat.getMainExecutor(this).execute {
            try {
                // Face-detection analyzer — runs while isCapturing, labels each upload
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor) { proxy -> processImageProxy(proxy) } }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    imageAnalyzer,
                    imageCapture
                )
                cameraControl = camera.cameraControl
                cameraControl?.enableTorch(isDark)

                // Fire the burst after the sensor has settled
                burstJob = lifecycleScope.launch {
                    delay(EXPOSURE_SETTLE_MS)
                    repeat(BURST_COUNT) { index ->
                        if (!isCapturing) return@launch   // /stop arrived mid-burst
                        Log.d("ServerService", "📸 Burst shot ${index + 1}/$BURST_COUNT")
                        takeAndSavePhoto(burstIndex = index)
                        delay(BURST_INTERVAL_MS)
                    }
                    // Auto-stop after the burst is finished so the sensor goes back to sleep.
                    // The ESP32 can still send /stop early to interrupt.
                    stopCapture()
                }

            } catch (e: Exception) {
                Log.e("ServerService", "📷 Camera bind failed: ${e.message}", e)
            }
        }
    }

    private fun stopCapture() {
        isCapturing = false
        burstJob?.cancel()
        onStopCallback?.invoke()
        updateNotification("✅ Sentinel armed — idle")
        cameraControl?.enableTorch(false)
        ContextCompat.getMainExecutor(this).execute {
            cameraProvider?.unbindAll()
            Log.d("ServerService", "📷 Camera unbound — sensor is OFF (idle)")
        }
    }

    // ── Photo capture ─────────────────────────────────────────────────────────

    /**
     * burstIndex is embedded in the filename so Firebase keeps all shots from
     * one event grouped together (same timestamp prefix, different index suffix).
     */
    private fun takeAndSavePhoto(burstIndex: Int) {
        val cap = imageCapture ?: run {
            Log.e("ServerService", "📸 imageCapture is null — skipping shot $burstIndex")
            return
        }

        val ts       = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(System.currentTimeMillis())
        val photoFile = File(
            getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES),
            "${ts}_burst${burstIndex}.jpg"
        )

        cap.takePicture(
            ImageCapture.OutputFileOptions.Builder(photoFile).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d("ServerService", "📸 Saved burst[$burstIndex]: ${photoFile.name}")
                    uploadToFirebase(photoFile, burstIndex)
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e("ServerService", "📸 Capture failed burst[$burstIndex]: ${exc.message}", exc)
                }
            }
        )
    }

    // ── Face detection (labels metadata, does NOT gate upload) ───────────────

    /**
     * Runs on every preview frame while isCapturing == true.
     *
     * IMPORTANT change from the original:
     *   Before → face detection gated the upload (only uploaded when a face was found)
     *   Now    → face detection only adds a "faceDetected" label in Firebase metadata
     *
     * Why: for training you need ALL frames — people entering sideways, looking
     * down at their phone, partial occlusion, etc. Filtering to clean frontal
     * faces would give you a biased, low-diversity dataset that will fail in
     * real conditions.
     */
    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        if (!isCapturing) { imageProxy.close(); return }

        val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }

        val img = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        faceDetector.process(img)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val angles = faces.map { f ->
                        "yaw=%.1f pitch=%.1f".format(
                            f.headEulerAngleY,   // left/right turn
                            f.headEulerAngleX    // up/down tilt
                        )
                    }.joinToString(", ")
                    Log.d("ServerService", "👤 ${faces.size} face(s) detected [$angles]")
                    onFaceDetectedCallback?.invoke()
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    // ── Firebase upload ───────────────────────────────────────────────────────

    /**
     * Uploads every burst photo. faceDetected metadata is set via a second
     * ML Kit pass on the saved JPEG before upload, so the Realtime Database
     * log records whether that specific frame contained a face — useful for
     * annotating your training set automatically.
     */
    private fun uploadToFirebase(photoFile: File, burstIndex: Int) {
        val storage  = FirebaseStorage.getInstance("gs://doorsentinel04.firebasestorage.app")
        val imageRef = storage.reference.child("intruders/${photoFile.name}")

        Log.d("ServerService", "☁️ Uploading: intruders/${photoFile.name}")

        // Run a quick face-detection pass on the saved JPEG so we can record
        // per-photo face labels in the DB — good free annotation for training.
        val bitmap = android.graphics.BitmapFactory.decodeFile(photoFile.absolutePath)
        if (bitmap == null) {
            doUpload(imageRef, photoFile, burstIndex, faceDetected = false, faceCount = 0)
            return
        }

        val labelImage = InputImage.fromBitmap(bitmap, 0)
        faceDetector.process(labelImage)
            .addOnSuccessListener { faces ->
                doUpload(imageRef, photoFile, burstIndex, faceDetected = faces.isNotEmpty(), faceCount = faces.size)
            }
            .addOnFailureListener {
                doUpload(imageRef, photoFile, burstIndex, faceDetected = false, faceCount = 0)
            }
    }

    private fun doUpload(
        imageRef: com.google.firebase.storage.StorageReference,
        photoFile: File,
        burstIndex: Int,
        faceDetected: Boolean,
        faceCount: Int
    ) {
        imageRef.putFile(Uri.fromFile(photoFile))
            .addOnSuccessListener {
                Log.d("ServerService", "✅ Upload OK — fetching URL")
                imageRef.downloadUrl.addOnSuccessListener { uri ->
                    saveToDatabase(uri.toString(), photoFile.name, burstIndex, faceDetected, faceCount)
                }
            }
            .addOnFailureListener { e ->
                Log.e("ServerService", "❌ Firebase upload failed: ${e.message}")
            }
    }

    private fun saveToDatabase(
        imageUrl: String,
        fileName: String,
        burstIndex: Int,
        faceDetected: Boolean,
        faceCount: Int
    ) {
        val db = FirebaseDatabase
            .getInstance("https://doorsentinel04-default-rtdb.asia-southeast1.firebasedatabase.app")
            .reference

        // eventId groups all burst shots from one trigger together.
        // Split on "_burst" so "2025-06-01_12-00-00_burst3.jpg" → "2025-06-01_12-00-00"
        val eventId = fileName.substringBefore("_burst")

        val logData = mapOf(
            "timestamp"    to System.currentTimeMillis(),
            "fileName"     to fileName,
            "imageUrl"     to imageUrl,
            "eventId"      to eventId,   // groups burst shots in DB queries
            "burstIndex"   to burstIndex,
            "faceDetected" to faceDetected,
            "faceCount"    to faceCount,
            "person"       to "UNKNOWN",
            "status"       to "unlabelled"   // updated by your labelling tool
        )

        db.child("logs").push().setValue(logData)
            .addOnSuccessListener { Log.d("ServerService", "📝 DB log created for burst[$burstIndex]") }
            .addOnFailureListener { e -> Log.e("ServerService", "❌ DB log failed: ${e.message}") }
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

    // ── UDP telemetry listener ─────────────────────────────────────────────────

    private var isListeningUDP = false
    private var udpSocket: DatagramSocket? = null

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
                    Log.d("Telemetry", "📊 $message")

                    // Format: T:24.5|H:60.0|L:2500
                    val parts = message.split("|")
                    if (parts.size == 3) {
                        val temp  = parts[0].substringAfter("T:")
                        val hum   = parts[1].substringAfter("H:")
                        val light = parts[2].substringAfter("L:")
                        Handler(Looper.getMainLooper()).post {
                            onTelemetryReceived?.invoke(temp, hum, light)
                        }
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
        isListeningUDP = false
        udpSocket?.close()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
