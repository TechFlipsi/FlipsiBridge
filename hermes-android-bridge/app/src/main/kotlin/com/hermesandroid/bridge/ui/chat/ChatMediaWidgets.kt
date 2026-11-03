package com.hermesandroid.bridge.ui.chat

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.media.MediaProtocol
import com.hermesandroid.bridge.ui.FlipsiColors
import java.io.File

/**
 * MEDIA-T1: Media-Bubble-Inhalt je Art (in der MessageBubble-Surface).
 *
 * - image: Thumbnail (downgesampelte Dekodierung, app-private Datei) +
 *   Tap → Full-Screen-Viewer (Dialog).
 * - voice: Play/Stopp-Button + Dauer (MediaPlayer, Sekunden-Zeile).
 * - file/video: Name + Größe + „In Downloads speichern“-Aktionszeile
 *   (MediaStore.Downloads, API 29+; darunter: Hinweis auf App-Ordner).
 * Klartext-Kontext: der Relay sieht nur Metadaten; die Datei liegt
 * app-privat (hinter dem AppLock-Gate).
 */
@Composable
internal fun MediaBubbleContent(
    media: ChatMediaInfo,
    fromAgent: Boolean,
    text: String,
) {
    var viewerOpen by remember { mutableStateOf(false) }
    val contentColor = if (fromAgent) FlipsiColors.TextPrimary else Color(0xFFFCE9DC)
    val subColor = if (fromAgent) FlipsiColors.TextSecondary else Color(0xFFFCE9DC).copy(alpha = 0.8f)

    Column(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        when (media.kind) {
            "image" -> {
                val thumbnail = remember(media.localPath) {
                    decodeImageThumbnail(media.localPath)
                }
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = "Foto ${media.name} — zum Vergrößern antippen",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .clickable { viewerOpen = true }
                            .semantics {
                                contentDescription =
                                    "Foto ${media.name} — zum Vergrößern antippen"
                            },
                    )
                } else {
                    MediaMetaLine(media, contentColor, subColor)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${MediaProtocol.kindGlyph(media.kind)} ${media.name} · ${MediaProtocol.formatBytes(media.size)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = subColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            "voice" -> {
                VoiceBubbleRow(media = media, contentColor = contentColor, subColor = subColor)
            }
            else -> {
                // file / video: Name + Größe prominent, Speichern-Zeile darunter.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_save),
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            media.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = contentColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${MediaProtocol.kindLabel(media.kind)} · ${MediaProtocol.formatBytes(media.size)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = subColor,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                SaveToDownloadsRow(media = media, subColor = subColor)
            }
        }
        // Text-Kontext (z. B. Beschriftung) bleibt unter dem Medium lesbar.
        if (text.isNotBlank() && !text.startsWith("[")) {
            Spacer(Modifier.height(4.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = subColor,
            )
        }
    }

    if (viewerOpen && media.kind == "image") {
        FullscreenImageViewer(path = media.localPath, name = media.name, onDismiss = { viewerOpen = false })
    }
}

/** Meta-Zeile (Fallback, wenn kein Thumbnail dekodierbar ist). */
@Composable
private fun MediaMetaLine(media: ChatMediaInfo, contentColor: Color, subColor: Color) {
    Column {
        Text(
            "${MediaProtocol.kindGlyph(media.kind)} ${MediaProtocol.kindLabel(media.kind)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
        )
        Text(
            "${media.name} · ${MediaProtocol.formatBytes(media.size)}",
            style = MaterialTheme.typography.labelMedium,
            color = subColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** MEDIA-T1: Sprachnachricht — Play/Stopp + Dauer (MediaPlayer, je Bubble 1). */
@Composable
private fun VoiceBubbleRow(media: ChatMediaInfo, contentColor: Color, subColor: Color) {
    val context = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    var durationMs by remember(media.localPath) { mutableStateOf(voiceDurationMs(media.localPath)) }
    // Player je Bubble-Instanz; beim Verlassen IMMER freigeben (Leak-Schutz).
    val player = remember(media.localPath) { runCatching { MediaPlayer() }.getOrNull() }
    DisposableEffect(media.localPath) {
        onDispose {
            runCatching {
                player?.stop()
                player?.release()
            }
            playing = false
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                val p = player ?: return@IconButton
                runCatching {
                    if (playing) {
                        p.stop()
                        p.reset()
                        p.setDataSource(media.localPath)
                        p.prepare()
                        playing = false
                    } else {
                        if (!p.isPlaying) {
                            p.setDataSource(media.localPath)
                            p.prepare()
                        }
                        p.start()
                        playing = true
                    }
                }
            },
            modifier = Modifier.size(44.dp),
        ) {
            Icon(
                painterResource(if (playing) R.drawable.ic_close else R.drawable.ic_mic),
                contentDescription = if (playing) "Wiedergabe stoppen" else "Sprachnachricht abspielen",
                tint = contentColor,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                "Sprachnachricht",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
            Text(
                if (durationMs > 0) formatVoiceDuration(durationMs) else media.name,
                style = MaterialTheme.typography.labelMedium,
                color = subColor,
            )
        }
    }
}

/** MEDIA-T1: „In Downloads speichern“-Zeile (MediaStore.Downloads, API 29+). */
@Composable
private fun SaveToDownloadsRow(media: ChatMediaInfo, subColor: Color) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(false) }
    Text(
        if (saved) "✓ Gespeichert (Downloads)"
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "⬇ In Downloads speichern"
        else "Datei im App-Ordner: ${File(media.localPath).parent}",
        style = MaterialTheme.typography.labelMedium,
        color = if (saved) FlipsiColors.SignalGreen else subColor,
        modifier = Modifier
            .clickable(enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !saved) {
                saved = saveToDownloads(context, media.name, media.mime, media.localPath)
            }
            .padding(vertical = 4.dp)
            .semantics {
                contentDescription =
                    if (saved) "In Downloads gespeichert" else "Datei in Downloads speichern"
            },
    )
}

/**
 * MEDIA-T1: Full-Screen-Bild-Viewer (Dialog, Tap außen schließt). Dekodierung
 * mit Sampling-Schätzung (RAM-Schutz: nie das Original in voller Größe halten).
 */
@Composable
internal fun FullscreenImageViewer(path: String, name: String, onDismiss: () -> Unit) {
    val bitmap = remember(path) { decodeImageThumbnail(path, targetMin = 1280) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = FlipsiColors.PanelElev,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(12.dp),
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Foto $name (Vollbild)",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 620.dp)
                            .clickable { onDismiss() },
                    )
                } else {
                    Text(
                        "Bild konnte nicht geladen werden.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FlipsiColors.TextPrimary,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss) { Text("Schließen") }
            }
        }
    }
}

/**
 * MEDIA-T1: Anhang-Auswahl (Foto/Video, Datei, Sprachnachricht) als
 * AlertDialog — eine Aktion je Zeile, Touch-Ziele groß, deutsches Label.
 */
@Composable
internal fun AttachSheet(
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onVoiceToggle: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Anhang senden") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Foto oder Video",
                    style = MaterialTheme.typography.bodyLarge,
                    color = FlipsiColors.TextPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onDismiss(); onPickImage() }
                        .padding(vertical = 12.dp),
                )
                Text(
                    "Datei",
                    style = MaterialTheme.typography.bodyLarge,
                    color = FlipsiColors.TextPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onDismiss(); onPickFile() }
                        .padding(vertical = 12.dp),
                )
                Text(
                    "Sprachnachricht aufnehmen",
                    style = MaterialTheme.typography.bodyLarge,
                    color = FlipsiColors.TextPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onDismiss(); onVoiceToggle() }
                        .padding(vertical = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        },
    )
}

// ── Helfer (Android, nicht JVM-getestet — Logik-Deckel in MediaProtocol) ───

/** Bild dekodieren mit Sampling (RAM-Schutz; Ziel-Mindestauflösung px). */
private fun decodeImageThumbnail(path: String, targetMin: Int = 640): android.graphics.Bitmap? {
    if (path.isBlank()) return null
    val file = File(path)
    if (!file.exists()) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetMin &&
            bounds.outHeight / (sample * 2) >= targetMin
        ) {
            sample *= 2
        }
        BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()
}

/** Dauer einer Audio-Datei (MediaMetadataRetriever, 0 = unbekannt). */
private fun voiceDurationMs(path: String): Long = runCatching {
    val retriever = MediaMetadataRetriever()
    retriever.setDataSource(path)
    val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    retriever.release()
    ms
}.getOrDefault(0L)

/** mm:ss-Format für Voice-Dauern. */
internal fun formatVoiceDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return String.format(
        java.util.Locale.GERMANY, "%02d:%02d",
        totalSeconds / 60, totalSeconds % 60,
    )
}

/** Datei via MediaStore in Downloads sichern (API 29+); true = ok. */
private fun saveToDownloads(
    context: android.content.Context,
    name: String,
    mime: String,
    path: String,
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    return runCatching {
        val bytes = File(path).readBytes()
        val values = android.content.ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name.take(128))
            put(MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    }.getOrDefault(false)
}