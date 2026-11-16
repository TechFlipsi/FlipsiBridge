package com.hermesandroid.bridge.ui.chat

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.media.VoiceMemoRecorder
import com.hermesandroid.bridge.ui.FlipsiColors
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WELLE 16 / B2 (Fixliste 31 + 30b): WhatsApp-artige Sprachnachricht-Bubble.
 *
 * - [VoiceBubbleMedia]: Datei-Richtung — eigene gesendete Voice-Memos UND
 *   eingehende/vom Agenten geholte Voice-Medien (beide laufen über den
 *   ChatMedia-Kanal). WA-Layout: runder Play/Pause-Knopf, gecachete Wellenform
 *   (Amplituden aus der Audio-Datei, je Nachricht genau 1x dekodiert → [VoiceWaveStore]),
 *   Fortschritts-Fill über der Welle, Dauer 0:21-Format und verstrichene
 *   Sekunden während der Wiedergabe.
 * - [VoiceBubbleAgentTts]: Agenten-Richtung ([ChatSpeechSpeaker]-TTS) — dieselbe
 *   Bubble; das Audio wird per synthesizeToFile app-privat erzeugt und als
 *   Datei gespielt (Wellenform + Dauer sind damit REAL aus dem Audio, kein
 *   Deko-Fake). Avatar bleibt optional ([AgentAvatarStore] von B1 einhängen,
 *   null = kein Kreis, graceful).
 * - [ChatVoicePlayback]: gemeinsame Wiedergabe-Registry (WA-Parität: GENAU
 *   EINE Sprachnachricht spielt gleichzeitig, Tap verdrängt die laufende).
 * - [VoiceHoldRules]/[voiceHoldToRecord]/[VoiceHoldRecorder]/[VoiceRecordingFahne]:
 *   P30b Hold-to-Record/Release-Send — Halten startet, Loslassen sendet,
 *   Wischen-nach-Links bricht ab, unter 0,5 s wird verworfen. Wiederverwendbar
 *   für B1s runden Mikro-Knopf im Composer (API im FINALREPORT beschrieben).
 *
 * Uhrzeit: bewusst KEINE Doppel-Zeit — in der MessageBubble-Metareihe steht die
 * Zeit heute schon IN der Bubble unten-rechts (Punkt-10-Format). Für B1s
 * Nachbau als VOLLE Bubble nimmt [time] die „HH:mm“ entgegen und rendert sie
 * inline neben der Dauer.
 */

// ── Wiedergabe-Registry (WA-Parität: EINE laufende Sprachnachricht) ──────────

/**
 * Ein Spieler für alle Voice-Bubbles (Datei- UND TTS-Richtung). Die
 * Zustandsflows sind der Sync-Standard für Fortschritts-Fill und verstrichene
 * Sekunden. Beim Verdrängen (andere Nachricht angetippt) läuft laufendes
 * Vorlesen ([ChatSpeechSpeaker]) mit stoppen — es gibt nie zwei parallele
 * Audiospuren. AudioAttributes (CONTENT_TYPE_SPEECH) stellen die Wiedergabe
 * in die Sprach-Klasse des Geräts (kein Medien-Mixing-Hinweis).
 */
object ChatVoicePlayback {

    private const val KEY_MEDIA_PREFIX = "media:"
    private const val KEY_TTS_PREFIX = "tts:"

    /** Datei-Bubble: Wiedergabe-Schlüssel aus dem stabilen lokalen Dateipfad. */
    fun mediaKey(localPath: String): String = KEY_MEDIA_PREFIX + localPath

    /** TTS-Bubble-Schlüssel je Nachrichten-ID (Datei liegt unter cache/voice_tts/). */
    fun ttsKey(messageId: Long): String = KEY_TTS_PREFIX + messageId

    private val lock = Any()
    private var player: MediaPlayer? = null
    private var tickerScope: CoroutineScope? = null

    private val activeKeyFlow = MutableStateFlow<String?>(null)
    private val playingFlow = MutableStateFlow(false)
    private val positionFlow = MutableStateFlow(0L)
    private val durationFlow = MutableStateFlow(0L)

    /** Nachricht, die gerade (oder pausiert) spielt — null = Ruhe. */
    val activeKey: StateFlow<String?> get() = activeKeyFlow
    /** true = spielt; false = pausiert (bei aktivem Schlüssel) oder Ruhe. */
    val isPlaying: StateFlow<Boolean> get() = playingFlow
    /** Verstrichene Millisekunden der aktiven Nachricht (Ticker ~100 ms). */
    val positionMs: StateFlow<Long> get() = positionFlow
    /** Gesamtdauer der aktiven Nachricht (nach prepare bekannt). */
    val durationMs: StateFlow<Long> get() = durationFlow

    /**
     * Toggle-Fassade der Bubble: läuft [key] → Pause/Resume (Fortschritt
     * bleibt, kein Neu-Prepare — WA-Muster); läuft eine ANDERE → die stoppen
     * und [path] starten (eine Nachricht verdrängt die andere).
     */
    fun toggle(key: String, path: String) {
        // Vorlesen verdrängen VOR der Sperre (keine überkreuzten Objekt-Sperren).
        ChatSpeechSpeaker.stopAll()
        synchronized(lock) {
            val mp = player
            if (mp != null && activeKeyFlow.value == key) {
                if (mp.isPlaying) {
                    runCatching { mp.pause() }
                    playingFlow.value = false
                } else {
                    val resumed = runCatching { mp.start() }.isSuccess
                    playingFlow.value = resumed
                    if (resumed) startTicker()
                }
                return
            }
            releaseLocked()
            val prepared = MediaPlayer()
            try {
                prepared.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                prepared.setDataSource(path)
                prepared.prepare()
                durationFlow.value = prepared.duration.toLong().coerceAtLeast(0L)
                positionFlow.value = 0L
                prepared.setOnCompletionListener {
                    // Ende: komplett räumen (WA springt nach dem Ende auf Ruhe).
                    synchronized(lock) { releaseLocked() }
                }
                prepared.start()
                player = prepared
                activeKeyFlow.value = key
                playingFlow.value = true
                startTicker()
            } catch (e: Exception) {
                runCatching { prepared.release() }
                // Ehrlich räumen: nie ein Phantom-Play-Zustand bei kaputter Datei.
                activeKeyFlow.value = null
                playingFlow.value = false
                positionFlow.value = 0L
                durationFlow.value = 0L
            }
        }
    }

    /** Alles stoppen (Conversation-Verlassen/Aufräumen) — Zustand auf Ruhe. */
    fun stop() {
        synchronized(lock) { releaseLocked() }
    }

    private fun releaseLocked() {
        runCatching {
            player?.setOnCompletionListener(null)
            player?.release()
        }
        player = null
        activeKeyFlow.value = null
        playingFlow.value = false
        positionFlow.value = 0L
        durationFlow.value = 0L
        tickerScope?.coroutineContext?.cancelChildren()
    }

    private fun startTicker() {
        val worker = tickerScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main).also { tickerScope = it }
        worker.launch {
            while (isActive) {
                delay(100L)
                val mp = synchronized(lock) { player } ?: return@launch
                if (!mp.isPlaying) return@launch
                positionFlow.value = runCatching { mp.currentPosition.toLong() }.getOrDefault(0L)
            }
        }
    }
}

// ── Wellenform: Amplituden dekodiert + gecachet (In-Memory-LRU + app-cache) ───

/**
 * Welle je Sprachnachricht — „NUR 1x gecached": Cache-Schlüssel ist der
 * DATEI-FINGERPRINT (SHA-1 über Pfad + Größe), nicht die Laufzeit-msgId (der
 * Disk-Restore verlagert msgIds je Session; der Fingerprint bleibt stabil und
 * erfüllt dieselbe Garantie: jede Datei wird je Schlüssel genau 1x dekodiert —
 * im RAM (LRU, 48 Einträge) UND auf Platte unter cache/voice_waves/
 * (app-privat — das Galerie-Verbot bleibt unberührt)).
 *
 * [peaks] ist blockierend: nur vom IO-Thread rufen (die Bubble macht das per
 * produceState/LaunchedEffect + withContext(Dispatchers.IO)).
 */
object VoiceWaveStore {

    private const val PEAK_TARGET = 28
    /** RAM-Deckel: die letzten 48 Wellenformen bleiben im Prozess. */
    private const val MEMORY_CAP = 48

    private val lock = Any()
    private val memory = LinkedHashMap<String, FloatArray>(16, 1f, true)

    /** Peaks je Datei (RAM → Platte → Dekodierung, in dieser Ordnung). */
    fun peaks(context: Context, path: String): FloatArray? {
        val fileKey = fileKey(path) ?: return null
        synchronized(lock) { memory[fileKey]?.let { return it } }
        val dir = File(context.cacheDir, "voice_waves")
        val disk = runCatching {
            File(dir, "$fileKey.wave").takeIf { it.exists() && it.length() > 0 }?.let { parseWaveFile(it) }
        }.getOrNull()
        if (disk != null) {
            rememberInMemory(fileKey, disk)
            return disk
        }
        val decoded = decodeVoicePeaks(path, PEAK_TARGET) ?: return null
        rememberInMemory(fileKey, decoded)
        runCatching {
            dir.mkdirs()
            File(dir, "$fileKey.wave")
                .writeText(decoded.joinToString(",") { ((it * 1000).toInt()).toString() })
        }
        return decoded
    }

    /** Stabiler Cache-Schlüssel: SHA-1 über Pfad + Dateigröße (null = unbrauchbar). */
    internal fun fileKey(path: String): String? = runCatching {
        val file = File(path)
        if (path.isBlank() || !file.exists() || file.length() == 0L) return null
        MessageDigest.getInstance("SHA-1")
            .digest(("voice|$path|${file.length()}").toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }.getOrNull()

    /** Datei-Format: Komma-getrennte Amplituden 0..1000, exakt PEAK_TARGET Stück. */
    private fun parseWaveFile(file: File): FloatArray? = runCatching {
        val arr = file.readText().trim()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull()?.let { v -> v / 1000f } }
            .toFloatArray()
        if (arr.size == PEAK_TARGET) arr else null
    }.getOrNull()

    private fun rememberInMemory(key: String, peaks: FloatArray) {
        synchronized(lock) {
            memory[key] = peaks
            while (memory.size > MEMORY_CAP) {
                val iterator = memory.entries.iterator()
                if (iterator.hasNext()) { iterator.next(); iterator.remove() } else break
            }
        }
    }
}

/**
 * AAC/M4A (Voice-Memo, geholte Medien) UND WAV (TTS-Synthese) → PCM →
 * Amplituden-Peaks. MediaExtractor + MediaCodec, Kanal-gemittelt, je Block der
 * Maximalwert, dann Resample auf [target] Buckets und Normierung aufs Maximum
 * (ruhige Stellen bekommen eine Sichtbarkeits-Bodengrenze). null = Format
 * nicht lesbar → die Bubble zeigt den ehrlichen Platzhalter.
 */
internal fun decodeVoicePeaks(path: String, target: Int): FloatArray? = runCatching {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
        extractor.setDataSource(path)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(i)
            val mime = candidate.getString(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith("audio/")) {
                trackIndex = i
                format = candidate
                break
            }
        }
        val trackFormat = format ?: return null
        val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: return null
        extractor.selectTrack(trackIndex)
        codec = MediaCodec.createDecoderByType(mime)
        codec.configure(trackFormat, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        val chunkMax = ArrayList<Float>()
        val chunkFrames = 2048
        var channels = 1
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIdx)
                    if (inputBuffer == null) { inputDone = true } else {
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0 || extractor.sampleTime > 10L * 60 * 1_000_000) {
                            // Ende/streamend ODER Deckel (Recorder: max 10 min).
                            // µs-Korrektheit: sampleTime ist MIKROsekunden — das
                            // alte 10 * 60_000L war 0,6 s (ms/µs-Verwechslung)
                            // und hätte JEDE Wellenform nach 0,6 s abgehackt.
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
            when {
                outIdx >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outIdx)
                    if (outputBuffer != null && info.size > 0) {
                        // Android-PCM = Little-Endian; Kanäle zu einem Peak je
                        // Frame gemittelt (|Wert| / 32768 = normierte Amplitude).
                        outputBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        val shorts = outputBuffer.asShortBuffer()
                        val frames = (info.size / 2) / channels
                        var i = 0
                        while (i < frames) {
                            var peak = 0f
                            val end = minOf(i + chunkFrames, frames)
                            while (i < end) {
                                var sum = 0f
                                for (c in 0 until channels) {
                                    val raw = shorts.get(i * channels + c).toInt()
                                    val magnitude = if (raw < 0) -raw else raw
                                    sum += magnitude.toFloat()
                                }
                                val amp = (sum / channels) / 32768f
                                if (amp > peak) peak = amp
                                i++
                            }
                            chunkMax.add(peak)
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    channels = runCatching {
                        codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }.getOrDefault(1).coerceAtLeast(1)
                }
                else -> { /* TRY_AGAIN_LATER / CONFIG_CHANGED: weiterlaufen. */ }
            }
        }
        val blocks = chunkMax.size
        if (blocks == 0) return null
        // Resample: je Bucket das Block-Maximum, danach aufs Gesamtmaximum normieren.
        val peaks = FloatArray(target) { b ->
            val from = b * blocks / target
            val to = ((b + 1) * blocks / target).coerceAtLeast(from + 1).coerceAtMost(blocks)
            var m = 0f
            for (i in from until to) if (chunkMax[i] > m) m = chunkMax[i]
            m
        }
        val maxPeak = peaks.max()
        if (maxPeak <= 0.002f) return null
        FloatArray(target) { b ->
            ((peaks[b] / maxPeak).coerceAtLeast(0.10f) * 0.9f + 0.1f).coerceIn(0.12f, 1f)
        }
    } finally {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor.release() }
    }
}.getOrNull()

// ── WA-Bubble-Bauteile ───────────────────────────────────────────────────────

/** mm:ss im WA-Schema („0:21“ — Minuten OHNE führende Null); 0/unklar = „--:--“. */
internal fun formatVoiceBubbleDuration(ms: Long): String {
    if (ms <= 0L) return "--:--"
    val seconds = ms / 1000
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

private const val WAVE_BARS_FALLBACK = 28

// Eigene Bubble = EmberDim-Hintergrund → Text der hellen Warm-Variante
// (gleiche Sprach-Metrik wie der bestehende Voice-Datei-Text).
private const val WAVE_OWN_TEXT: Long = 0xFFFCE9DC

/**
 * Wellenform mit Fortschritts-Fill (Fixliste 31, Anforderung (5)): gespielte
 * Balken = [playedColor], Rest = [restColor]; nur die AKTIVE Nachricht zeigt
 * einen Fortschritt (WA-Parität). Dekodiert nicht = gleichförmige ruhige
 * Balken als ehrlicher Platzhalter (kein Datenanspruch).
 */
@Composable
internal fun VoiceWaveBars(
    peaks: FloatArray?,
    active: Boolean,
    progress: Float,
    playedColor: Color,
    restColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val list = peaks
        if (list == null || list.isEmpty()) {
            // Platzhalter (Dekodierung fehl): gleichförmige ruhige Balken ohne Anspruch.
            val gap = 2.dp.toPx()
            val barW = (size.width - gap * (WAVE_BARS_FALLBACK - 1)) / WAVE_BARS_FALLBACK
            repeat(WAVE_BARS_FALLBACK) { i ->
                drawRoundRect(
                    color = restColor,
                    topLeft = Offset(i * (barW + gap), size.height * 0.4f),
                    size = Size(barW, size.height * 0.2f),
                    cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                )
            }
            return@Canvas
        }
        val gap = 2.dp.toPx()
        val barW = (size.width - gap * (list.size - 1)) / list.size
        val playedBars = if (active) (progress * list.size).toInt().coerceIn(0, list.size) else 0
        repeat(list.size) { i ->
            val amp = list[i].coerceIn(0.10f, 1f)
            val h = (size.height * (0.25f + 0.75f * amp)).coerceIn(4f, size.height)
            drawRoundRect(
                color = if (i < playedBars) playedColor else restColor,
                topLeft = Offset(i * (barW + gap), (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

/**
 * Runder Play/Pause-Knopf (WA-Muster, Ember-Akzent). Das Pause-Glyph ist
 * selbst gezeichnet (zwei Balken) — material-icons-core liefert kein Pause-
 * Symbol und neue Drawables bleiben bewusst vermieden.
 */
@Composable
internal fun VoicePlayCircle(
    playing: Boolean,
    enabled: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
) {
    val playA11y = stringResource(R.string.media_voice_play_a11y)
    val pauseA11y = stringResource(R.string.media_voice_pause_a11y)
    val busyA11y = stringResource(R.string.chat_tts_voice_prepare_a11y)
    val glyphColor = FlipsiColors.OnEmber
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(FlipsiColors.Ember)
            .then(if (enabled) Modifier.clickable(onClick = onToggle) else Modifier)
            .semantics {
                contentDescription = when {
                    busy -> busyA11y
                    playing -> pauseA11y
                    else -> playA11y
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            busy -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = glyphColor,
            )
            playing -> PauseGlyph(tint = glyphColor, barWidth = 5.dp, totalHeight = 14.dp)
            else -> Icon(
                painterResource(R.drawable.ic_play),
                contentDescription = null,
                tint = glyphColor,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Pause-Symbol: zwei abgerundete Balken (Canvas, kein neues Drawable). */
@Composable
internal fun PauseGlyph(
    tint: Color,
    barWidth: Dp,
    totalHeight: Dp,
) {
    Canvas(modifier = Modifier.size(barWidth * 2 + 4.dp, totalHeight)) {
        val bw = barWidth.toPx()
        val gap = 4.dp.toPx()
        val h = totalHeight.toPx()
        drawRoundRect(
            color = tint,
            topLeft = Offset(0f, 0f),
            size = Size(bw, h),
            cornerRadius = CornerRadius(bw / 2f, bw / 2f),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(bw + gap, 0f),
            size = Size(bw, h),
            cornerRadius = CornerRadius(bw / 2f, bw / 2f),
        )
    }
}

/**
 * Gemeinsame Zeile: [Avatar?] [Play-Knopf] [Welle 160 dp] [Dauer] [Zeit?].
 * WELLE: fixe Balkenbreite = die Bubble wrapt den Inhalt (kein Punkt-16b-
 * „riesige Bubble“); WA-typisch ist die feste Wellenbreite je Bubble.
 */
@Composable
private fun VoiceBubbleCoreRow(
    peaks: FloatArray?,
    active: Boolean,
    progress: Float,
    durationLabel: String,
    playing: Boolean,
    busy: Boolean,
    enabled: Boolean,
    reasonHint: String?,
    time: String?,
    fromAgent: Boolean,
    avatar: (@Composable () -> Unit)?,
    onToggle: () -> Unit,
) {
    val contentColor = if (fromAgent) FlipsiColors.TextPrimary else Color(WAVE_OWN_TEXT)
    val playedColor = if (fromAgent) FlipsiColors.Ember else contentColor
    val restColor = if (fromAgent) Color(0xFF363D47) else contentColor.copy(alpha = 0.38f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        avatar?.let {
            it()
            Spacer(Modifier.width(8.dp))
        }
        VoicePlayCircle(
            playing = playing,
            enabled = enabled,
            busy = busy,
            onToggle = onToggle,
        )
        Spacer(Modifier.width(10.dp))
        VoiceWaveBars(
            peaks = peaks,
            active = active,
            progress = progress,
            playedColor = playedColor,
            restColor = restColor,
            modifier = Modifier.size(width = 160.dp, height = 30.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            durationLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            softWrap = false,
        )
        if (time != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                time,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.7f),
                softWrap = false,
            )
        }
    }
    reasonHint?.let {
        Spacer(Modifier.height(4.dp))
        Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = FlipsiColors.SignalAmber,
        )
    }
}

/**
 * W16 B2 (Fixliste 31, Datei-Richtung — gilt für BEIDE: eigene gesendete
 * Sprachnachrichten UND eingehende Agenten-Voice-Medien, dieselbe
 * Bubble-Silhouette): WA-Bubble aus einer app-privaten Audio-Datei.
 * Wellenform = [VoiceWaveStore] (1x dekodiert, gecachet),
 * Fortschritt/Verstrichene-Sekunden = [ChatVoicePlayback],
 * Dauer = [startDurationMs] (Metadaten) oder live aus der Wiedergabe.
 * Zeit: [time] NUR setzen, wenn die Bubble OHNE die MessageBubble-Metareihe
 * gebaut wird (doppelte Zeit vermeiden).
 */
@Composable
fun VoiceBubbleMedia(
    playbackKey: String,
    audioPath: String,
    startDurationMs: Long,
    fromAgent: Boolean,
    time: String? = null,
    avatar: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val activeKey by ChatVoicePlayback.activeKey.collectAsState()
    val isPlaying by ChatVoicePlayback.isPlaying.collectAsState()
    val positionMs by ChatVoicePlayback.positionMs.collectAsState()
    val playbackDuration by ChatVoicePlayback.durationMs.collectAsState()

    val active = activeKey == playbackKey
    val playing = active && isPlaying
    val durationKnown = if (active) playbackDuration else startDurationMs
    val position = if (active) positionMs else 0L
    val progress = if (durationKnown > 0) {
        (position.toFloat() / durationKnown).coerceIn(0f, 1f)
    } else 0f

    val peaks by produceState<FloatArray?>(null, audioPath) {
        value = withContext(Dispatchers.IO) { VoiceWaveStore.peaks(context, audioPath) }
    }

    VoiceBubbleCoreRow(
        peaks = peaks,
        active = active,
        progress = progress,
        durationLabel = formatVoiceBubbleDuration(if (playing) position else durationKnown),
        playing = playing,
        busy = false,
        enabled = true,
        reasonHint = null,
        time = time,
        fromAgent = fromAgent,
        avatar = avatar,
        onToggle = { ChatVoicePlayback.toggle(playbackKey, audioPath) },
    )
}

/**
 * W16 B2 (Fixliste 31, Agenten-Richtung — [ChatSpeechSpeaker]-TTS): die
 * Agenten-Antwort als WA-Sprachnachricht-Bubble. Beim ersten Play wird die
 * Antwort app-privat synthetisiert (synthesizeToFile, Cache je msgId unter
 * cache/voice_tts/) und SOFORT gespielt; danach läuft alles über die Datei
 * (echte Welle + echte Dauer aus [VoiceWaveStore]/Metadaten). Vorlesen und
 * Bubble-Wiedergabe: die Bubble-Play-Verdränger stoppt laufendes Vorlesen.
 * B1-Verband (P30/P32): in der Agenten-Bubble anstelle des Speaking-Chips —
 * [avatar] optional (AgentAvatarStore), [time] nur bei vollem Bubble-Überbau.
 */
@Composable
fun VoiceBubbleAgentTts(
    messageId: Long,
    text: String,
    time: String? = null,
    avatar: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val key = ChatVoicePlayback.ttsKey(messageId)
    val activeKey by ChatVoicePlayback.activeKey.collectAsState()
    val isPlaying by ChatVoicePlayback.isPlaying.collectAsState()
    val positionMs by ChatVoicePlayback.positionMs.collectAsState()
    val playbackDuration by ChatVoicePlayback.durationMs.collectAsState()
    val speechState by ChatSpeechSpeaker.state.collectAsState()

    var ttsPath by remember(messageId) { mutableStateOf<String?>(null) }
    var preparing by remember(messageId) { mutableStateOf(false) }
    var fileDuration by remember(messageId) { mutableStateOf(0L) }

    // Dauer aus der erzeugten Datei (Metadaten) — einmal je Pfad.
    LaunchedEffect(ttsPath) {
        val path = ttsPath ?: return@LaunchedEffect
        if (fileDuration == 0L) {
            fileDuration = withContext(Dispatchers.IO) { voiceDurationMs(path) }
        }
    }
    val peaks by produceState<FloatArray?>(null, ttsPath) {
        value = when (val path = ttsPath) {
            null -> null
            else -> withContext(Dispatchers.IO) { VoiceWaveStore.peaks(context, path) }
        }
    }

    val failed = (speechState as? ChatSpeechState.Failed)?.reason
    val active = activeKey == key
    val playing = active && isPlaying
    val durationKnown = if (active) playbackDuration else fileDuration
    val position = if (active) positionMs else 0L
    val progress = if (durationKnown > 0) {
        (position.toFloat() / durationKnown).coerceIn(0f, 1f)
    } else 0f

    VoiceBubbleCoreRow(
        peaks = peaks,
        active = active,
        progress = progress,
        durationLabel = if (preparing) {
            stringResource(R.string.chat_tts_voice_prepare)
        } else {
            formatVoiceBubbleDuration(if (playing) position else durationKnown)
        },
        playing = playing,
        busy = preparing,
        enabled = !preparing && failed == null,
        reasonHint = failed,
        time = time,
        fromAgent = true,
        avatar = avatar,
        onToggle = {
            val current = ttsPath
            if (current != null) {
                ChatVoicePlayback.toggle(key, current)
            } else if (!preparing && failed == null) {
                preparing = true
                ChatSpeechSpeaker.synthesizeMessageFile(context, messageId, text) { produced ->
                    preparing = false
                    if (produced != null) {
                        ttsPath = produced
                        ChatVoicePlayback.toggle(key, produced)
                    }
                }
            }
        },
    )
}

// ── P30b: Hold-to-Record / Release-Send (Runde-B1-Mikro-Knopf-Extension) ─────

/** P30b-Regeln (Fixliste 30b): Schwellwerte der Hold-to-Record-Geste. */
object VoiceHoldRules {
    /** Haltezeit darunter = verwerfen (Fixliste 30b: „<0,5 s verwerfen"). */
    const val MIN_HOLD_MS = 500L
    /** Wisch-Abbrechen: so weit (dp) muss der Finger nach LINKS fahren. */
    const val SWIPE_CANCEL_DP = 110f
}

/** Ergebnis der Hold-to-Record-Geste am Release — verbindet mit [VoiceHoldRecorder.finish]. */
enum class VoiceHoldOutcome {
    /** Release ≥ 0,5 s ohne Wisch-Abbriss → „halt → loslassen = senden". */
    SEND,
    /** Zu kurz (< [VoiceHoldRules.MIN_HOLD_MS]) → verwerfen (kein Send-Fehler-Banner). */
    TOO_SHORT,
    /** Nach links gewischt → Recorder cancel() (Aufnahme fällt weg). */
    SWIPE_CANCELLED,
    /** Start verweigert (Berechtigung/Recorder-Fail) — Release-Aktion entfällt. */
    START_DENIED,
}

/**
 * P30b (Fixliste 30b): Hold-to-Record an B1s runden Mikro-Knopf.
 * DRÜCKEN → [onStart] (Recorder.start(); false = Start verweigert — z. B.
 * Mikro-Berechtigung fehlt → der Host zeigt seinen Fehlerpfad, die Release-
 * Aktion bleibt aus). LOSLASSEN → [onEnd] mit [VoiceHoldOutcome] + Haltezeit:
 * SEND → Recorder stop() + senden; TOO_SHORT/SWIPE_CANCELLED → cancel()
 * (verwerfen). Wischen nach LINKS über [VoiceHoldRules.SWIPE_CANCEL_DP] =
 * Abbriss-Kennung an [onSwipeCancelState] (WA-Fahne zeigt den Abbruch-Modus).
 *
 * Geste-Regeln: während der Aufnahme wird NACH-Links überwacht (einmal
 * ausgelöst bleibt ausgelöst — kein Aufheben des Abbruchs); die Haltezeit
 * misst Druck→Release (die Fahne tickt selbst, kein Ticker im Geste-Loop).
 */
fun Modifier.voiceHoldToRecord(
    onStart: () -> Boolean,
    onEnd: (VoiceHoldOutcome, holdMs: Long) -> Unit,
    onSwipeCancelState: ((cancelling: Boolean) -> Unit)? = null,
    enabled: Boolean = true,
): Modifier = composed {
    val startRef = rememberUpdatedState(onStart)
    val endRef = rememberUpdatedState(onEnd)
    val swipeRef = rememberUpdatedState(onSwipeCancelState)
    val density = LocalDensity.current
    val cancelSlopPx = with(density) { VoiceHoldRules.SWIPE_CANCEL_DP.dp.toPx() }
    pointerInput(enabled, cancelSlopPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val downAt = down.uptimeMillis
            val started = startRef.value()
            var cancelling = false
            var holdMs = 0L
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                if (!change.pressed) {
                    holdMs = (change.uptimeMillis - downAt).coerceAtLeast(0L)
                    break
                }
                if (started && !cancelling &&
                    change.position.x < down.position.x - cancelSlopPx
                ) {
                    cancelling = true
                    swipeRef.value?.invoke(true)
                }
            }
            val outcome = when {
                !started -> VoiceHoldOutcome.START_DENIED
                cancelling -> VoiceHoldOutcome.SWIPE_CANCELLED
                holdMs < VoiceHoldRules.MIN_HOLD_MS -> VoiceHoldOutcome.TOO_SHORT
                else -> VoiceHoldOutcome.SEND
            }
            endRef.value(outcome, holdMs)
        }
    }
}

/**
 * P30b: Recorder-Fassade für den Hold-Flow — konstruiert den [VoiceMemoRecorder]
 * mit der WA-Schwelle (0,5 s), damit die GESTE-Verwerfen-Kante und die
 * RECORDER-Kante an derselben Zahl liegen. START_DENIED/Tauschfehlerpfade
 * behandelt der Host (Berechtigungs-Launcher + Send-Fehler-Banner).
 */
class VoiceHoldRecorder(context: Context) {

    private val recorder = VoiceMemoRecorder(context, VoiceHoldRules.MIN_HOLD_MS)

    /** Läuft gerade eine Aufnahme? */
    val isRecording: Boolean get() = recorder.isRecording

    /**
     * DRÜCKEN: Aufnahme starten. false = Start verweigert (z. B. Mikro-
     * Berechtigung fehlt — der Host-Fehlerpfad ist dann an der Reihe).
     */
    fun start(): Boolean = runCatching { recorder.start() }.map { true }.getOrDefault(false)

    /**
     * LOSLASSEN: SEND → stop() + fertige AAC-Datei (null = Recorder-Kante,
     * Datei unlesbar — Host zeigt ehrlichen Send-Fehler); TOO_SHORT/
     * SWIPE_CANCELLED/START_DENIED → verwerfen (cancel, KEIN Send).
     */
    fun finish(outcome: VoiceHoldOutcome): File? = when (outcome) {
        VoiceHoldOutcome.SEND -> recorder.stop()
        else -> { recorder.cancel(); null }
    }

    /** Harter Abbruch (Conversation verlassen, Screen-Dispose — kein Send). */
    fun cancelNow() {
        recorder.cancel()
    }
}

/**
 * P30b: Aufnahme-Fahne (WA-Muster) über der Composer-Zeile: roter Punkt +
 * Laufende mm:ss + „‹ Wischen zum Abbrechen"; beim Wisch-Abbruch färbt sie
 * rot und der X-Knopf bricht hart ab ([onCancel] → cancel()). A11y über den
 * Zeilen-description-Verband.
 */
@Composable
fun VoiceRecordingFahne(
    recording: Boolean,
    cancelling: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!recording) return
    // Zeit je Fahnen-Lauf (recording-An = Start): tickt selbst, kein Geste-Ticker.
    var elapsedMs by remember(recording) { mutableStateOf(0L) }
    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        val startedAt = System.currentTimeMillis()
        while (true) {
            elapsedMs = System.currentTimeMillis() - startedAt
            delay(500L)
        }
    }
    val cancelA11y = stringResource(R.string.chat_voice_recording_cancel_a11y)
    val holdA11y = stringResource(
        R.string.chat_voice_recording_holding_a11y,
        formatVoiceBubbleDuration(elapsedMs),
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (cancelling) FlipsiColors.SignalRed.copy(alpha = 0.18f)
                else FlipsiColors.PanelElev,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics { contentDescription = holdA11y },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(FlipsiColors.SignalRed),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            formatVoiceBubbleDuration(elapsedMs),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = FlipsiColors.TextPrimary,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (cancelling) stringResource(R.string.chat_voice_recording_cancel_active)
            else stringResource(R.string.chat_voice_recording_swipe_hint),
            style = MaterialTheme.typography.labelSmall,
            color = if (cancelling) FlipsiColors.SignalRedText else FlipsiColors.TextSecondary,
            modifier = Modifier.padding(start = 8.dp, end = 2.dp),
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onCancel) {
            Icon(
                Icons.Filled.Close,
                contentDescription = cancelA11y,
                tint = FlipsiColors.TextPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
