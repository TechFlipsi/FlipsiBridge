package com.hermesandroid.bridge.media

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import java.io.File

object ScreenRecorder {
    private const val TAG = "ScreenRecorder"
    private const val NO_PERMISSION_MESSAGE =
        "No MediaProjection. Grant Screen Recording in the app before each capture on Android 16."
    private const val MAX_DURATION_MS = 30_000L

    // JÄGER-J4 (P1, JAEGE-RFULL-20261102): Der Relay capped Phone-WS-Textframes
    // bei 2 MB (max_msg_size, android_relay.py) — das /screen_record-Result lief
    // als EIN Base64-JSON-Frame, ab ~6 s Aufnahme (2 Mbit/s ≈ 1,4 MB Base64)
    // riss die Verbindung (1009) inkl. Reconnect-Sturm. Budget: Video-Bytes B
    // werden Base64 ≈ 4/3·B + JSON-Hülle; 1,3 MB Video ≈ 1,73 MB Base64 + Hülle
    // < 2 MB mit Luft. Bitrate dynamisch aufs Budget geklemmt (30 s → ~347
    // kbit/s, Default 5 s → 2 Mbit/s) + harte Größenkontrolle NACH der
    // Aufnahme: Überschreitung (Encoder-Overshoot) = ehrliches Fehler-Result
    // statt Connection-Kill. Bewusste App-Lane-Alternative zum Stream-
    // Protokoll (wie /file): bräuchte Relay-+Adapter-Seite, Out-of-Scope.
    private const val MAX_VIDEO_BYTES = 1_300_000L
    private const val MIN_VIDEO_BITRATE = 300_000
    private const val MAX_VIDEO_BITRATE = 2_000_000

    @Volatile private var projectionResultCode: Int? = null
    @Volatile private var projectionData: Intent? = null
    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    // v0.11.0 (Review): Single-Flight-Guard — parallele /screen_record-Aufrufe
    // stapelten sonst MediaRecorder-Instanzen.
    // K4-Fix (Kobold): AtomicBoolean-CAS statt Check-then-Act — der Alte
    // `if (recordingBusy) return` … `recordingBusy = true` war nicht atomisch;
    // zwei parallele WS-Commands konnten beide passieren.
    private val recordingBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    // v0.12.0 (Akku P2-3): lazy statt ab Singleton-Init — der Thread lebt nur
    // während echter Aufnahme-Wartroutinen (release() räumt ihn wieder ab).
    @Volatile private var handlerThread: HandlerThread? = null
    private val handler: Handler
        get() {
            val existing = handlerThread
            if (existing != null) return Handler(existing.looper)
            synchronized(this) {
                val again = handlerThread ?: HandlerThread("ScreenRecorder").apply { start() }
                handlerThread = again
                return Handler(again.looper)
            }
        }

    fun hasPermission(): Boolean = projectionData != null

    fun setProjectionPermission(resultCode: Int, data: Intent) {
        projectionResultCode = resultCode
        projectionData = Intent(data)
    }

    private fun clearProjectionPermission() {
        projectionResultCode = null
        projectionData = null
    }

    /**
     * Record the screen for [durationMs] milliseconds.
     * CRITICAL: Entire recording runs on the HandlerThread via handler.post().
     * MediaRecorder.start()/stop() and Thread.sleep() MUST be on the same thread
     * that created the VirtualDisplay callback handler — NOT on Dispatchers.IO.
     */
    fun record(durationMs: Long = 5000): Map<String, Any?> {
        // v0.11.0 (Review): negative Dauer → IllegalArgumentException in
        // latch.await + MediaRecorder — bündig nach unten geklemmt.
        val safeDuration = durationMs.coerceIn(200L, MAX_DURATION_MS)
        // K4 (Kobold): atomischer Single-Flight-Einstieg (CAS).
        if (!recordingBusy.compareAndSet(false, true)) {
            return mapOf("success" to false, "message" to "Recording läuft bereits")
        }
        val service = BridgeAccessibilityService.instance
            ?: return mapOf("success" to false, "message" to "Accessibility service not running").also {
                recordingBusy.set(false)
            }
        val resultCode = projectionResultCode
            ?: return mapOf("success" to false, "message" to NO_PERMISSION_MESSAGE).also {
                recordingBusy.set(false)
            }
        val resultData = projectionData
            ?: return mapOf("success" to false, "message" to NO_PERMISSION_MESSAGE).also {
                recordingBusy.set(false)
            }

        val latch = java.util.concurrent.CountDownLatch(1)
        val resultHolder = arrayOf<Map<String, Any?>?>(null)

        handler.post {
            var outputFile: File? = null
            var projection: MediaProjection? = null
            var projectionCallback: MediaProjection.Callback? = null
            try {
                service.startForeground(includeMediaProjection = true)
                val mpm = service.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = mpm.getMediaProjection(resultCode, resultData)
                    ?: throw IllegalStateException("MediaProjection permission token was not accepted")
                projectionCallback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        cleanupRecording()
                    }
                }
                projection.registerCallback(projectionCallback, handler)

                outputFile = File(service.cacheDir, "screen_record_${System.currentTimeMillis()}.mp4")
                val metrics = service.resources.displayMetrics
                val width = metrics.widthPixels
                val height = metrics.heightPixels
                val density = metrics.densityDpi

                val mr = MediaRecorder(service).apply {
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setOutputFile(outputFile.absolutePath)
                    setVideoSize(width, height)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    // JÄGER-J4: Bitrate aufs Frame-Budget geklemmt —
                    // bytes ≈ bitrate · dauer / 8 bleibt unter der 2-MB-
                    // Framegrenze des Relays, für JEDE erlaubte Dauer.
                    setVideoEncodingBitRate(frameBudgetBitRate(safeDuration))
                    setVideoFrameRate(30)
                    prepare()
                }
                recorder = mr

                val vd = projection.createVirtualDisplay(
                    "ScreenRecorder", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mr.surface, null, handler
                )
                // v0.11.0 (Kotlin-2.0-Compile + Return-Regel im post-Lambda):
                // VirtualDisplay ist nullable — hier als Fehler abbrechen; das
                // vorhandene catch macht cleanup + Fehlantwort.
                if (vd == null) {
                    throw IllegalStateException("VirtualDisplay nicht erstellbar (projection null?)")
                }
                virtualDisplay = vd

                mr.start()

                // Safe to block here — we're on the dedicated HandlerThread
                Thread.sleep(safeDuration)

                mr.stop()
                mr.release()
                vd.release()
                recorder = null
                virtualDisplay = null

                val bytes = outputFile.readBytes()
                outputFile.delete()

                if (bytes.size > MAX_VIDEO_BYTES) {
                    // JÄGER-J4-Härtung: Encoder-Overshoot — NIEMALS als
                    // >2-MB-Einzelframe senden (riss sonst die Phone-WS mit
                    // 1009): ehrliches Fehler-Result, Verbindung bleibt.
                    resultHolder[0] = mapOf(
                        "success" to false,
                        "message" to "Video zu groß für den Relay-Frame " +
                            "(${bytes.size / 1024} KiB nach ${safeDuration}ms) — kürzere Dauer wählen",
                    )
                } else {
                    val base64Video = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    resultHolder[0] = mapOf(
                        "success" to true,
                        "message" to "Recorded ${safeDuration}ms",
                        "data" to mapOf(
                            "video" to base64Video,
                            "width" to width,
                            "height" to height,
                            "durationMs" to safeDuration,
                            "sizeBytes" to bytes.size,
                            "mimeType" to "video/mp4",
                        ),
                    )
                }
            } catch (e: Exception) {
                cleanupRecording()
                outputFile?.delete()
                if (e is SecurityException || e is IllegalStateException) {
                    clearProjectionPermission()
                }
                resultHolder[0] = mapOf("success" to false, "message" to "Recording failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                // K4: Recording-Ende → Projection ist weg → der FGS darf den
                // mediaProjection-Typ NICHT behalten (Android-14-Prüfpflicht;
                // früher blieb der Typ stehen = System-Check-Kill-Risiko).
                runCatching { BridgeAccessibilityService.instance?.startForeground(includeMediaProjection = false) }
                    .onFailure { Log.w(TAG, "FGS-Typ-Rückfall fehlgeschlagen: ${it.message}") }
                recordingBusy.set(false)
                try {
                    if (projectionCallback != null) projection?.unregisterCallback(projectionCallback)
                } catch (_: Exception) {}
                try { projection?.stop() } catch (_: Exception) {}
                latch.countDown()
            }
        }

        // Wait for the handler thread to finish (with generous timeout)
        latch.await(safeDuration + 10000, java.util.concurrent.TimeUnit.MILLISECONDS)
        recordingBusy.set(false) // Timeout-Pfad: hier zusätzlich räumen
        return resultHolder[0] ?: mapOf("success" to false, "message" to "Recording timed out")
    }

    private fun cleanupRecording() {
        try { recorder?.release() } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        recorder = null
        virtualDisplay = null
    }

    /**
     * JÄGER-J4: maximale H264-Bitrate, damit das Base64-JSON-Result der
     * Aufnahme sicher UNTER der 2-MB-Framegrenze des Relays bleibt —
     * bytes ≈ bitrate · dauer / 8 ≤ MAX_VIDEO_BYTES.
     */
    internal fun frameBudgetBitRate(durationMs: Long): Int =
        (MAX_VIDEO_BYTES * 8L / (durationMs / 1000L).coerceAtLeast(1L))
            .toInt().coerceIn(MIN_VIDEO_BITRATE, MAX_VIDEO_BITRATE)

    fun release() {
        cleanupRecording()
        handlerThread?.quitSafely()
        handlerThread = null
    }
}
