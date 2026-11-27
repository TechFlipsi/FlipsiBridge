package com.hermesandroid.bridge.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * W16 (Fixliste 32a): Profilbilder je AGENT — dynamisch, KEINE hardcoded
 * Agenten-Liste (jeder Nutzer hat N Agenten, mehr/weniger als die Drill-Beispiele).
 *
 * Registry keyed auf der ECHTEN Relay-agent_id:
 *  - Bild gesetzt: app-privat unter filesDir/avatars/ (die Bytes liegen als
 *    JPEG-Kopie in der eigenen Sandbox — der Content-URI der Galerie-Quelle
 *    kann von Android zurückgezogen werden, die App-Kopie nicht).
 *  - KEIN Bild: WA-Standard-Muster — deterministische Farbe aus hash(agentId)
 *    ([FALLBACK_COLORS]) + Initialbuchstabe; funktioniert für jeden neuen
 *    Agenten automatisch.
 *    Demo-Einträge (blank agentId) falten auf den "?"-Key und bleiben stabil
 *    gefärbt — nie ein Crash, nie eine weiße Fläche.
 *
 * [revision] zählt jede Bild-Änderung hoch: die Composables lesen sie im
 * Composition-Scope, eine Set/Clear-Änderung löst die Neudekodierung aus
 * (Invalidate-Gedanke wie der produceState-Key am ChatWallpaper). Speicher
 * app-privat, KEINE Relay-Änderung nötig (Fixplan-Vorgabe).
 */
object AgentAvatarStore {

    private const val DIRECTORY = "avatars"

    /** Invalide-Zähler der Bild-Registry (Set/Clear → Neudekodierung der Views). */
    private val revisionState = mutableIntStateOf(0)

    /** Aktuelle Revision — im Composition-Scope lesen (Subscribe). */
    val revision: Int get() = revisionState.intValue

    private fun bumpRevision() {
        revisionState.intValue += 1
    }

    /**
     * Fallback-Palette (WA-Muster): deterministische Farbe je Agent —
     * Töne auf der dunklen Grundwelt lesbar, Initial = heller Text.
     */
    private val FALLBACK_COLORS = listOf(
        Color(0xFF5B8DEF), // blau
        Color(0xFF7C6FF0), // violett
        Color(0xFF2FB380), // grün
        Color(0xFFE08750), // orange
        Color(0xFFD96B7C), // beere
        Color(0xFF5FB3C5), // türkis
        Color(0xFFB08A5B), // braun
        Color(0xFF8FA9B8), // grau-blau
    )

    /** Initial auf der Fallback-Fläche (Void — 4,89–7,93:1 auf allen 8 Palett-Farben). */
    val INITIAL_TEXT = Color(0xFF0E1013)

    /**
     * Deterministische Fallback-Farbe: SHA-256(agentId) → Palette-Index
     * (16-Bit-Mix aus zwei Bytes gegen den Ein-Byte-Bias des Modulo).
     */
    fun fallbackColor(agentId: String): Color {
        val key = agentId.ifBlank { "?" }
        val bytes = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val mixed = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
        return FALLBACK_COLORS[mixed % FALLBACK_COLORS.size]
    }

    /** Verzeichnis der Profilbilder (app-privat). */
    fun avatarDir(context: Context): File =
        File(context.applicationContext.filesDir, DIRECTORY)

    /** Aktives Profilbild des Agenten — null = Fallback (Farbe + Initial). */
    fun avatarFile(context: Context, agentId: String): File? {
        if (agentId.isBlank()) return null
        val file = File(avatarDir(context), fileName(agentId))
        return if (file.exists()) file else null
    }

    /** true = Agent hat ein eigenes Profilbild (steuert die Entfernen-Option im Sheet). */
    fun hasAvatar(context: Context, agentId: String): Boolean =
        avatarFile(context, agentId) != null

    /**
     * Profilbild übernehmen: gewählte Galerie-URI sampled dekodieren und als
     * JPEG app-privat kopieren. true = erfolgreich (inkl. Revision-Bump).
     * KEIN persistenter Content-Uri-Verweis — die Kopie überlebt Revokes.
     */
    fun setAvatar(context: Context, agentId: String, uri: Uri): Boolean {
        if (agentId.isBlank()) return false
        val ok = runCatching {
            val bitmap = decodeSampled(context, uri, targetMin = 512)
                ?: throw IllegalStateException("Bild unlesbar")
            val dir = avatarDir(context)
            if (!dir.exists()) dir.mkdirs()
            val target = File(dir, fileName(agentId))
            val tmp = File(dir, ".${target.name}.part")
            tmp.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 86, out)
            }
            if (!tmp.renameTo(target)) {
                // Plattformbedingtes Rename-Scheitern — copy+delete-Fallback
                // (Parität zum writeAtomic-Muster des ChatHistoryStores).
                target.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 86, out)
                }
                tmp.delete()
            }
            bitmap.recycle()
        }.isSuccess
        if (ok) bumpRevision()
        return ok
    }

    /** Profilbild entfernen → Fallback (Farbe + Initial) tritt sofort ein. */
    fun clearAvatar(context: Context, agentId: String) {
        if (agentId.isBlank()) return
        var changed = false
        runCatching {
            val file = File(avatarDir(context), fileName(agentId))
            if (file.exists() && file.delete()) changed = true
        }
        // Legacy-Zwilling ohne Hash-Suffix ebenfalls räumen (sonst lebt das alte
        // Bild über den sanitize-Namen wieder auf).
        runCatching {
            val safe = agentId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48).ifEmpty { "agent" }
            val legacy = File(avatarDir(context), "$safe.jpg")
            if (legacy.exists() && legacy.delete()) changed = true
        }
        if (changed) bumpRevision()
    }

    /** Dateiname je Agent: sanitize + SHA-256-Suffix (Parität ChatHistoryStore.fileFor). */
    private fun fileName(agentId: String): String {
        val safe = agentId.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(48).ifEmpty { "agent" }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(agentId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
        return "${safe}_${hash.take(12)}.jpg"
    }

    /**
     * Galerie-Bild RAM-schonend dekodieren (Bounds → Sampling bis ~targetMin px,
     * Parität decodeWallpaperSampled) — nie das Original in voller Auflösung.
     */
    private fun decodeSampled(context: Context, uri: Uri, targetMin: Int): Bitmap? =
        runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetMin &&
                bounds.outHeight / (sample * 2) >= targetMin
            ) {
                sample *= 2
            }
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }
        }.getOrNull()

    /** RAM-Deckel des geladenen Avatars: 256-px-Sampling vor dem UI-Draw. */
    internal fun decodeAvatarFile(file: File): Bitmap? =
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 256 &&
                bounds.outHeight / (sample * 2) >= 256
            ) {
                sample *= 2
            }
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull()
}

/**
 * W16 (Fixliste 32a): dynamisches Profilbild je Agent — gesetzt = Foto
 * (app-privat-Kopie), sonst deterministische Farbe + Initial (WA-Muster).
 * Funktioniert für JEDE agent-id ohne Konfiguration; Größe frei wählbar
 * (Chat-Header klein, Agenten-Sheet groß, Agenten-Zeile mittel). Der Avatar
 * zeigt im Chat-Header und wird von B6 (Bubble-Avatar, P31) über denselben
 * Store gelesen. TalkBack: Beschreibung über [description] (null = dekorativ,
 * Bedeutung trägt die Zeile daneben).
 */
@Composable
fun AgentAvatar(
    agentId: String,
    name: String,
    diameter: Dp,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val context = LocalContext.current
    // Revision im Composition-Scope lesen → Set/Clear invalidiert die View.
    val rev = AgentAvatarStore.revision
    val file = remember(agentId, rev) { AgentAvatarStore.avatarFile(context, agentId) }
    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .background(AgentAvatarStore.fallbackColor(agentId))
            .let { base ->
                // TalkBack: Beschreibung vom Aufrufer (null = dekorativ).
                if (description != null) {
                    // Bewusst `description` benannt — im semantics-Lambda darf
                    // der Name NICHT mit der Semantics-Extension kollidieren.
                    base.semantics { contentDescription = description }
                } else base
            },
        contentAlignment = Alignment.Center,
    ) {
        if (file != null) {
            val bitmap by produceState<Bitmap?>(initialValue = null, file.absolutePath, rev) {
                value = withContext(Dispatchers.IO) {
                    AgentAvatarStore.decodeAvatarFile(file)
                }
            }
            val bmp: Bitmap? = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    // Bedeutung trägt der Box-Wrap (siehe [description]); hier dekorativ.
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Während der Dekodierung (oder unlesbare Datei): Fallback sichtbar.
                InitialText(name, diameter)
            }
        } else {
            InitialText(name, diameter)
        }
    }
}

/** Initial der Fallback-Anzeige — der Textstil skaliert je Avatar-Durchmesser. */
@Composable
private fun InitialText(name: String, diameter: Dp) {
    val initial = name.trim().take(1).uppercase().ifBlank { "?" }
    Text(
        initial,
        style = if (diameter >= 72.dp) MaterialTheme.typography.headlineSmall
        else MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = AgentAvatarStore.INITIAL_TEXT,
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}
