package com.bizarrewind.doorsentinel

import android.content.Context
import android.content.SharedPreferences

/**
 * Runtime-configurable prefs for ServerService.
 *
 * All values are read at burst-time (inside the coroutine), never in the
 * HTTP trigger handler, so latency to first-response is unaffected.
 *
 * Usage:
 *   SentinelPrefs.init(applicationContext)   // call once from Application
 *   val n = SentinelPrefs.burstCount
 */
object SentinelPrefs {

    private const val PREF_FILE = "sentinel_prefs"

    // Keys
    private const val KEY_BURST_COUNT          = "burst_count"
    private const val KEY_BURST_INTERVAL_MS    = "burst_interval_ms"
    private const val KEY_EXPOSURE_SETTLE_MS   = "exposure_settle_ms"
    private const val KEY_UPLOAD_ENABLED       = "upload_enabled"
    private const val KEY_UPLOAD_COUNT         = "upload_count"
    private const val KEY_FACE_DETECTION       = "face_detection_enabled"
    private const val KEY_TORCH_ENABLED        = "torch_enabled"
    private const val KEY_LIGHT_THRESHOLD      = "light_trigger_threshold"
    private const val KEY_SHUTTER_SPEED_NS     = "shutter_speed_ns"
    private const val KEY_UPLOAD_FILTER        = "upload_filter"   // "human" | "both"
    private const val KEY_AUDIO_BAIT_URI       = "audio_bait_uri"

    // Defaults
    const val DEFAULT_BURST_COUNT        = 6
    const val DEFAULT_BURST_INTERVAL_MS  = 600
    const val DEFAULT_EXPOSURE_SETTLE_MS = 600
    const val DEFAULT_UPLOAD_ENABLED     = true
    const val DEFAULT_UPLOAD_COUNT       = 6
    const val DEFAULT_FACE_DETECTION     = true
    const val DEFAULT_TORCH_ENABLED      = true
    const val DEFAULT_LIGHT_THRESHOLD    = 2500
    const val DEFAULT_SHUTTER_SPEED_NS   = 0L   // 0 = auto
    /** "human" = only upload when face found; "both" = upload any motion */
    const val DEFAULT_UPLOAD_FILTER      = "both"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
    }

    // ── Reads ──────────────────────────────────────────────────────────────────

    val burstCount: Int
        get() = prefs.getInt(KEY_BURST_COUNT, DEFAULT_BURST_COUNT)

    val burstIntervalMs: Long
        get() = prefs.getInt(KEY_BURST_INTERVAL_MS, DEFAULT_BURST_INTERVAL_MS).toLong()

    val exposureSettleMs: Long
        get() = prefs.getInt(KEY_EXPOSURE_SETTLE_MS, DEFAULT_EXPOSURE_SETTLE_MS).toLong()

    val uploadEnabled: Boolean
        get() = prefs.getBoolean(KEY_UPLOAD_ENABLED, DEFAULT_UPLOAD_ENABLED)

    val uploadCount: Int
        get() = prefs.getInt(KEY_UPLOAD_COUNT, DEFAULT_UPLOAD_COUNT)

    val faceDetectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_FACE_DETECTION, DEFAULT_FACE_DETECTION)

    val torchEnabled: Boolean
        get() = prefs.getBoolean(KEY_TORCH_ENABLED, DEFAULT_TORCH_ENABLED)

    val lightTriggerThreshold: Int
        get() = prefs.getInt(KEY_LIGHT_THRESHOLD, DEFAULT_LIGHT_THRESHOLD)

    /** Shutter speed in nanoseconds. 0 = auto (let camera decide). */
    val shutterSpeedNs: Long
        get() = prefs.getLong(KEY_SHUTTER_SPEED_NS, DEFAULT_SHUTTER_SPEED_NS)

    /** Upload filter: "human" = require face; "both" = any motion triggers upload */
    val uploadFilter: String
        get() = prefs.getString(KEY_UPLOAD_FILTER, DEFAULT_UPLOAD_FILTER) ?: DEFAULT_UPLOAD_FILTER

    val audioBaitUri: String?
        get() = prefs.getString(KEY_AUDIO_BAIT_URI, null)

    // ── Writes ─────────────────────────────────────────────────────────────────

    fun setBurstCount(v: Int)          = prefs.edit().putInt(KEY_BURST_COUNT, v).apply()
    fun setBurstIntervalMs(v: Int)     = prefs.edit().putInt(KEY_BURST_INTERVAL_MS, v).apply()
    fun setExposureSettleMs(v: Int)    = prefs.edit().putInt(KEY_EXPOSURE_SETTLE_MS, v).apply()
    fun setUploadEnabled(v: Boolean)   = prefs.edit().putBoolean(KEY_UPLOAD_ENABLED, v).apply()
    fun setUploadCount(v: Int)         = prefs.edit().putInt(KEY_UPLOAD_COUNT, v).apply()
    fun setFaceDetection(v: Boolean)   = prefs.edit().putBoolean(KEY_FACE_DETECTION, v).apply()
    fun setTorchEnabled(v: Boolean)    = prefs.edit().putBoolean(KEY_TORCH_ENABLED, v).apply()
    fun setLightTriggerThreshold(v: Int) = prefs.edit().putInt(KEY_LIGHT_THRESHOLD, v).apply()
    fun setShutterSpeedNs(v: Long)     = prefs.edit().putLong(KEY_SHUTTER_SPEED_NS, v).apply()
    fun setUploadFilter(v: String)     = prefs.edit().putString(KEY_UPLOAD_FILTER, v).apply()
    fun setAudioBaitUri(v: String?)    = prefs.edit().putString(KEY_AUDIO_BAIT_URI, v).apply()
}
