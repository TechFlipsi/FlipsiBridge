package com.hermesandroid.bridge.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MEDIA-T1: Sprachnachricht-Recorder (AAC im M4A-Container — kompaktes
 * Format, vom Agenten-Adapter als kind=voice verstanden).
 *
 * Bewusst NICHT der Accessibility-MicrophoneRecorderService (der ist das
 * Command-Steuerungs-Mikrofon 24-kHz-PCM-WAV fürs Relay-Streaming): Chat-
 * Voice-Memos brauchen eine eigene, kurze Aufnahme mit fertiger Datei.
 * Eine Aufnahme je Instanz; [stop] liefert die Datei (oder null bei
 * Mindestlänge-Verstoß — unter [MIN_DURATION_MS] wird verworfen).
 */
class VoiceMemoRecorder(
    private val context: Context,
    private val minDurationMs: Long = MIN_DURATION_MS,
) {

    companion object {
        /** Mindestlänge einer Voice-Mail (kürzer = Verwurf, kein 0-s-Versand). */
        const val MIN_DURATION_MS = 800L
        /** Härte Deckel (keine Endlos-Diktate — Relay-Media-Deckel gilt zusätzlich). */
        const val MAX_DURATION_MS = 10 * 60_000L
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMs: Long = 0

    /** Läuft gerade eine Aufnahme? */
    val isRecording: Boolean get() = recorder != null

    /**
     * Aufnahme starten. Wirft [IllegalStateException], wenn schon läuft.
     * Output: cacheDir/voice_memos (app-privat, nach dem Versand nutzlos).
     */
    fun start() {
        check(recorder == null) { "Aufnahme läuft bereits" }
        val dir = File(context.cacheDir, "voice_memos").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.GERMANY).format(Date())
        val file = File(dir, "voice-$stamp.m4a")
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioEncodingBitRate(96_000)
            mediaRecorder.setAudioSamplingRate(44_100)
            mediaRecorder.setOutputFile(file.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
        } catch (e: Exception) {
            runCatching { mediaRecorder.release() }
            file.delete()
            throw e
        }
        recorder = mediaRecorder
        outputFile = file
        startedAtMs = System.currentTimeMillis()
    }

    /**
     * Aufnahme beenden. Rückgabe = fertige AAC-Datei (null = zu kurz oder
     * kein Recorder aktiv). Der Recorder wird IMMER freigegeben (finally),
     * egal wie das Stoppen ausgeht.
     */
    fun stop(): File? {
        val mediaRecorder = recorder ?: return null
        val file = outputFile
        val elapsed = System.currentTimeMillis() - startedAtMs
        var failed = false
        try {
            mediaRecorder.stop()
        } catch (e: Exception) {
            failed = true  // stop() vor start() oder 0 Bytes → Datei unbrauchbar
        } finally {
            runCatching { mediaRecorder.release() }
            recorder = null
            outputFile = null
        }
        if (failed || file == null || !file.exists() || elapsed < minDurationMs) {
            file?.delete()
            return null
        }
        return file
    }

    /** Aufnahme abbrechen (Verwerfen, z. B. beim Bildschirm-Wechsel). */
    fun cancel() {
        val mediaRecorder = recorder ?: return
        runCatching { mediaRecorder.stop() }
        runCatching { mediaRecorder.release() }
        recorder = null
        outputFile?.delete()
        outputFile = null
    }
}