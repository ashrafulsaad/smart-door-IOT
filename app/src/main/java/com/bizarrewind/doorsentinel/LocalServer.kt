package com.bizarrewind.doorsentinel

import android.os.Handler
import android.os.Looper
import android.util.Log
import fi.iki.elonen.NanoHTTPD

class LocalServer(
    port: Int,
    private val onTrigger: (Boolean) -> Unit,
    private val onStop: () -> Unit
) : NanoHTTPD(port) {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d("LocalServer", "✅ Request received: $uri")

        return when (uri.lowercase(java.util.Locale.getDefault()).trimEnd('/')) {
            "/trigger" -> {
                val isDark = session.parameters["dark"]?.firstOrNull()?.toBoolean() ?: true
                mainHandler.post { onTrigger(isDark) }
                Log.d("LocalServer", "🔥 Trigger dispatched to main thread (dark=$isDark)")
                newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK: trigger")
            }
            "/stop" -> {
                mainHandler.post { onStop() }
                Log.d("LocalServer", "🛑 Stop dispatched to main thread")
                newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK: stop")
            }
            else -> {
                Log.w("LocalServer", "⚠ Unknown path: $uri")
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
        }
    }
}