package com.hermesandroid.bridge.ui.chat

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.chat.ChatHistoryStore
import com.hermesandroid.bridge.media.MediaProtocol
import com.hermesandroid.bridge.ui.FlipsiColors
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * W4 (App-Lane C) — Mediathek-Tab je Agenten-Conversation.
 *
 * Zeigt ALLE Medien des Chats mit DIESEM Agenten: Quell-Daten sind die
 * persistierten Media-Verweise aus dem ChatHistoryStore (je Agent eine
 * JSONL-Datei, Load auf IO) plus die LIVE-Bubbles der offenen Session
 * (Merge via MediathekIndex — Dubletten zählen einmal). Verbände:
 * - Abschnitte je Medientyp: Bilder/Videos als Thumbnail-Grid (adaptive
 *   Kacheln), Sprachnachrichten als Player-Zeilen, Dateien als Liste
 *   (Tap = In Downloads sichern — derselbe Weg wie in der Media-Bubble).
 * - STALE-REGEL (mediaPath kann stale sein): Kachel/Zeile rendert eine
 *   Platzhalter-Karte („Nicht mehr verfügbar“) — NIEMALS ein Crash, auch
 *   nicht bei unlesbarem Pfad (runCatching um jede File-Annäherung).
 * - Leer-Zustand: Titel + Hinweis (DE-Keys), nie eine leere weiße Fläche.
 *
 * Reduced-Pass-Faser je Anim-Stelle: KEIN Fortschritts-Animat (Voice-Zeile
 * zeigt statische Dauer, kein Ticker, kein per-Frame-Neuholen) — Neuladen
 * nur je Agent-Wechsel bzw. geändertem LIVE-Medium (LaunchedEffect-Schlüssel
 * unten); Item-Zustand (z. B. gespeichert-Flag) ist je Reload frisch.
 */
@Composable
internal fun MediathekTabBody(
    agent: ChatAgent,
    conversation: List<ChatMessage>,
    modifier: Modifier = Modifier,
) {
    val appContext = LocalContext.current.applicationContext
    // LIVE-Verweise der offenen Session (eigene gewählte + empfangene Medien).
    // W4-Hinweis: ein eigener Upload fügt im Ack-Lauf seine media_id in die
    // Bubble ein — die geänderte LIVE-Signatur steuert den Store-Reload, der
    // dann den persistierten Eintrag holt und über die media_id dedupt.
    val liveItems = remember(conversation) {
        conversation.mapNotNull { msg ->
            msg.media?.let { media ->
                MediathekIndex.Item(
                    mediaId = media.mediaId,
                    kind = media.kind.ifBlank { "file" },
                    name = media.name,
                    mime = media.mime,
                    size = media.size,
                    time = msg.time,
                    path = "",
                    stale = false,
                    fromStore = false,
                )
            }
        }
    }
    var mediaItems by remember(agent.agentId) { mutableStateOf<List<MediathekIndex.Item>>(emptyList()) }
    // W4-FIX-JAG2 (C7): Effekt-Schlüssel = Media-SIGNATUR (Inhalt), nicht
    // Listen-Identität. `conversation` änderte JE Verlaufs-Frame (auch reine
    // Text-Bubbles) die listen-Referenz von liveItems → LaunchedEffect startete
    // je Frame NEU → withContext(IO)-Store-Reload je Verlaufs-Frame. Eine
    // Signatur über die Media-Eigenschaften (mediaId/kind/name/mime/size/Zeit)
    // feuert NUR auf echtem Media-Wechsel (Ack füttert media_id → Reload);
    // bei unveränderter Signatur bleibt der alte Merge-Stand (Inhalt gleich).
    val liveSignature = remember(liveItems) {
        liveItems.joinToString("|") {
            "${it.mediaId}\u0000${it.kind}\u0000${it.name}\u0000${it.mime}\u0000${it.size}\u0000${it.time}"
        }
    }
    LaunchedEffect(agent.agentId, liveSignature) {
        val loaded = withContext(Dispatchers.IO) {
            loadStoreItems(appContext, agent.agentId)
        }
        mediaItems = MediathekIndex.merge(loaded, liveItems)
    }
    if (mediaItems.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.mediathek_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = FlipsiColors.TextPrimary,
            )
            Text(
                stringResource(R.string.mediathek_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = FlipsiColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── Abschnitt Bilder & Videos: Thumbnail-Grid (adaptive Kacheln) ──
        // video ohne Thumbnail-Entzerrer (RAM/Reduced-Pass): Kachel mit
        // Sortier-Zeichen + Meta, Bild-Kacheln mit downgesampeltem Thumb.
        // Bewusst KEINE Grid-Keys (Positional Items): ein Key auf Nutzereintrag
        // kann bei gleichem Name/Größe/Minute kollidieren und die Liste crasht
        // — Positionen sind hier sicherer (Liste ändert sich nur ganz hinten).
        val mediaTiles = MediathekIndex.byKind(mediaItems, "image") + MediathekIndex.byKind(mediaItems, "video")
        val voices = MediathekIndex.byKind(mediaItems, "voice")
        val files = MediathekIndex.byKind(mediaItems, "file")
        if (mediaTiles.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { MediathekSectionHeader(R.string.mediathek_section_media) }
            items(mediaTiles) { tile ->
                MediaTile(tile)
            }
        }
        // ── Abschnitt Sprachnachrichten: Player-Zeilen (volle Breite) ─────
        if (voices.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { MediathekSectionHeader(R.string.mediathek_section_voices) }
            items(voices) { voice ->
                MediathekVoiceRow(voice)
            }
        }
        // ── Abschnitt Dateien: Liste (volle Breite), Tap = Downloads ──────
        if (files.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { MediathekSectionHeader(R.string.mediathek_section_files) }
            items(files) { file ->
                MediathekFileRow(file)
            }
        }
    }
}

// ── Abschnitts-Kopf ──────────────────────────────────────────────────────────

@Composable
private fun MediathekSectionHeader(titleRes: Int) {
    Text(
        stringResource(titleRes),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = FlipsiColors.TextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )
}

// ── Bild-/Video-Kachel (mit STALE-Platzhalter) ──────────────────────────────

/**
 * Kachel im Thumbnail-Grid. path = "" (stale oder nur Verweis) → Platzhalter-
 * Karte mit Sortier-Zeichen + Hinweis — NICHT antippbar (ehrlich: nichts zu
 * zeigen), kein Crash. path da → Bild mit downgesampeltem Thumb + Tap →
 * Full-Screen-Viewer (bestehender Verband aus ChatMediaWidgets).
 */
@Composable
private fun MediaTile(item: MediathekIndex.Item) {
    if (item.path.isBlank()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = FlipsiColors.PanelElev,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    MediaProtocol.kindGlyph(item.kind),
                    style = MaterialTheme.typography.headlineSmall,
                    color = FlipsiColors.TextSecondary,
                )
                Text(
                    item.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = FlipsiColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                )
                Text(
                    stringResource(if (item.stale) R.string.mediathek_stale_hint else R.string.mediathek_ref_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = FlipsiColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
        return
    }
    // Pfad da: Thumbnail dekodieren (downgesampelt — RAM-Schutz wie in der Bubble).
    val thumbnail = remember(item.path) { decodeImageThumbnail(item.path, targetMin = 320) }
    var viewerOpen by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = FlipsiColors.PanelElev,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clickable(onClickLabel = stringResource(R.string.mediathek_view_fullscreen)) {
                    if (thumbnail != null) viewerOpen = true
                },
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription =
                        stringResource(R.string.mediathek_view_fullscreen) + " — ${item.name}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .aspectRatio(1f),
                )
            } else {
                // Pfad da, aber Dekodierung schief (Kaputt-Bytes/garbage) → Platzhalter.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                ) {
                    Text(
                        MediaProtocol.kindGlyph(item.kind),
                        style = MaterialTheme.typography.headlineSmall,
                        color = FlipsiColors.TextSecondary,
                    )
                    Text(
                        item.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = FlipsiColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
    if (viewerOpen) {
        FullscreenImageViewer(path = item.path, name = item.name, onDismiss = { viewerOpen = false })
    }
}

// ── Sprachnachricht-Zeile (Player, Reduced-Pass: keine Fortschritts-Animat) ──

@Composable
private fun MediathekVoiceRow(item: MediathekIndex.Item) {
    var playing by remember(item.path) { mutableStateOf(false) }
    // Dauer statisch EINMAL je Pfad holen (kein Ticker — Reduced-Pass).
    var durationMs by remember(item.path) {
        mutableStateOf(if (item.path.isBlank()) 0L else voiceDurationMs(item.path))
    }
    val unavailable = item.path.isBlank()
    // Player je Zeile; beim Verlassen IMMER freigeben (Leak-Schutz).
    val player = remember(item.path) {
        if (unavailable) null else runCatching { MediaPlayer() }.getOrNull()
    }
    DisposableEffect(item.path) {
        onDispose {
            runCatching {
                player?.stop()
                player?.release()
            }
        }
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = FlipsiColors.PanelElev,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            IconButton(
                onClick = {
                    val p = player ?: return@IconButton
                    runCatching {
                        if (playing) {
                            p.stop()
                            p.reset()
                            playing = false
                        } else {
                            p.reset()
                            p.setDataSource(item.path)
                            p.prepare()
                            p.start()
                            playing = true
                        }
                    }.onFailure { playing = false }
                },
                enabled = !unavailable,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    painterResource(if (playing) R.drawable.ic_close else R.drawable.ic_mic),
                    contentDescription = stringResource(
                        if (playing) R.string.mediathek_stop_voice else R.string.mediathek_play_voice,
                    ) + " — ${item.name}",
                    tint = if (unavailable) FlipsiColors.TextSecondary else FlipsiColors.Ember,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = FlipsiColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = mutableListOf<String>()
                if (durationMs > 0) meta.add(formatVoiceDuration(durationMs))
                else if (!unavailable) meta.add(item.time)
                else meta.add(stringResource(if (item.stale) R.string.mediathek_stale_hint else R.string.mediathek_ref_hint))
                Text(
                    meta.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = FlipsiColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── Datei-Zeile (Tap = In Downloads sichern; STALE → Platzhalter-Karte) ─────

@Composable
private fun MediathekFileRow(item: MediathekIndex.Item) {
    val context = LocalContext.current
    val unavailable = item.path.isBlank()
    var saved by remember(item.path) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = FlipsiColors.PanelElev,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .then(
                    if (unavailable || saved) Modifier else Modifier.clickable(
                        onClickLabel = stringResource(R.string.mediathek_save_file),
                    ) { saved = saveToDownloads(context, item.name, item.mime, item.path) }
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Icon(
                painterResource(if (saved) R.drawable.ic_check else R.drawable.ic_folder),
                contentDescription = null,
                tint = if (saved) FlipsiColors.SignalGreen else FlipsiColors.TextSecondary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = FlipsiColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val metaSuffix = when {
                    saved -> stringResource(R.string.mediathek_saved_file)
                    unavailable ->
                        stringResource(if (item.stale) R.string.mediathek_stale_hint else R.string.mediathek_ref_hint)
                    else -> stringResource(R.string.mediathek_save_file)
                }
                Text(
                    "${MediaProtocol.formatBytes(item.size)} · ${item.time} · $metaSuffix",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (saved) FlipsiColors.SignalGreen else FlipsiColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── IO-Loader (Store-Verband; NIE crashen) ───────────────────────────────────

/**
 * Store-Verband: persistierte Media-Verweise JESES Agenten laden (IO — die
 * Datei wächst mit dem Verlauf; Existenz-Prüfung je mediaPath ist der
 * STALE-Check, STALE-REGEL = Platzhalter-Karte, kein Crash). Agent ohne
 * echte Relay-ID (Demo-Fallback) hat bewusst NIE Einträge im Store
 * (ChatHistoryStore.Entry-Vertrag) → leere Liste.
 */
internal fun loadStoreItems(appContext: Context, agentId: String): List<MediathekIndex.Item> {
    if (agentId.isBlank()) return emptyList()
    return runCatching {
        val root = ChatHistoryStore.root(appContext)
        MediathekIndex.fromEntries(ChatHistoryStore.load(root, agentId)) { path ->
            runCatching { File(path).isFile }.getOrDefault(false)
        }
    }.getOrDefault(emptyList())
}