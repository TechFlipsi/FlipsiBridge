package com.hermesandroid.bridge.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.ui.FlipsiColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * WELLE-13 P11a (Chat-Hintergrund): zwei Zustände, ehrlich getrennt —
 *
 * 1. Standard = sehr dunkles EIGENES Muster: Void-Navy-Basis mit einem
 *    hand-generierten, minimalen Punktraster + schrägen Ember-Ankern
 *    ([DefaultChatPattern]). „Anmutung ja, Assets nein“ (Fixplan-Grundregel):
 *    es wird KEIN fremdes Muster kopiert, das Muster ist deterministisch aus
 *    der Flipsi-Palette gerechnet.
 * 2. Nutzer-Foto: [ChatWallpaperPrefs.wallpaperPath] — W16.3 (F6) liegt die
 *    Pick-Datei als JPEG app-privat (filesDir/chat_wallpaper); das Foto
 *    füllt flächendeckend (Crop), ein dunkler Scrim (55 %) liegt darüber,
 *    damit die Bubbles lesbar bleiben (WhatsApp-Lesbarkeits-Trick, mit
 *    eigener Umsetzung).
 *
 * Leere/fehlende Kopie (oder URI-AltStand) = Eigenmuster (nie ein weißer Blitz).
 */
object ChatWallpaperPrefs {
    private const val FILE = "chat_wallpaper"
    private const val KEY_PATH = "wallpaperImagePath"
    private const val DIR_NAME = "chat_wallpaper"
    private const val TARGET_FILE = "wallpaper.jpg"

    /** Verzeichnis der Wallpaper-Kopie (app-privat). */
    private fun storeDir(context: Context): File =
        File(context.applicationContext.filesDir, DIR_NAME)

    /**
     * W16.3 (F6): Gewähltes Nutzer-Foto als APP-PRIVATE JPEG-Datei unter
     * filesDir/chat_wallpaper (leer = Eigenmuster). Nur echte Dateipfade
     * gelten — ein Alt-Stand aus der URI-Ära (content://…) fällt auf das
     * Eigenmuster zurück, statt an einem zurückgezogenen Verweis still
     * leerzulaufen (URI-Fall entfällt).
     */
    fun wallpaperPath(context: Context): String {
        val path = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_PATH, "") ?: ""
        return path.takeIf { it.startsWith("/") && File(it).exists() } ?: ""
    }

    /**
     * W16.3 (F6): Wallpaper setzen (Chatdesign-Pick) — die Galerie-Datei wird
     * sampled dekodiert und als JPEG app-privat KOPIERT (Parität
     * AgentAvatarStore.setAvatar): die Kopie überlebt Content-Uri-Revokes der
     * Galerie-Quelle; ein reiner URI-Verweis kann jederzeit weggezogen werden.
     * true = Kopie+Prefs-Eintrag erfolgreich; false = ehrlicher Fail (Zustand
     * bleibt „Eigenmuster", der Tap-Aufrufer zeigt nichts an).
     */
    fun setWallpaperImage(context: Context, uri: Uri): Boolean {
        return runCatching {
            val bitmap = decodeImageSampled(context, uri, targetMin = 1080)
                ?: throw IllegalStateException("Wallpaper unlesbar")
            val dir = storeDir(context)
            if (!dir.exists()) dir.mkdirs()
            val target = File(dir, TARGET_FILE)
            val tmp = File(dir, ".${target.name}.part")
            tmp.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            if (!tmp.renameTo(target)) {
                // Plattformbedingtes Rename-Scheitern — copy+delete-Fallback
                // (Parität zum writeAtomic-Muster des ChatHistoryStores).
                target.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                tmp.delete()
            }
            bitmap.recycle()
            target.absolutePath
        }.onSuccess { savedPath ->
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString(KEY_PATH, savedPath).apply()
        }.isSuccess
    }

    /** Wallpaper entfernen → zurück zum Eigenmuster (Kopie + Prefs-Eintrag räumen). */
    fun clearWallpaper(context: Context) {
        runCatching {
            File(storeDir(context), TARGET_FILE).delete()
        }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .remove(KEY_PATH).apply()
    }
}

/**
 * WELLE-13 P11a: Chat-Hintergrund hinter dem Verlauf — Nutzer-Foto mit
 * 55-%-Scrim, sonst das dunkle Eigenmuster. Bewusst JEDE Composition frisch
 * gelesen (kein remember): der Settings-Pick wirkt sofort beim Zurückkehren.
 */
@Composable
fun ChatWallpaper(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // W16.3 (F6): der Prefs-Stand ist jetzt ein APP-privater Dateipfad —
    // kein Content-URI mehr (URI-Falle: Galerie-Revokes = stilles Weiß).
    val path = ChatWallpaperPrefs.wallpaperPath(context)
    if (path.isBlank()) {
        DefaultChatPattern(modifier)
        return
    }
    // Nutzer-Foto: dekodiert auf IO (Downsampling als RAM-Schutz — derselbe
    // Sampling-Gedanke wie decodeImageThumbnail in ChatMediaWidgets).
    val bitmap by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            decodeWallpaperFile(path, targetMin = 1080)
        }
    }
    Box(modifier) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null, // rein dekorativ, Text bleibt lesbar
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Solange dekodiert wird (oder der Pfad unlesbar ist): Eigenmuster
            // als Unterlage — nie ein weißer Blitz hinter dunklen Bubbles.
            DefaultChatPattern(Modifier.fillMaxSize())
        }
        // Lesbarkeits-Scrim (55 %): dunkelt das Foto ab, damit TextPrimary-
        // Bubbles und Zeitstempel lesbar bleiben — Fixplan-Vorgabe.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f)),
        )
    }
}

/**
 * WELLE-13 P11a: dunkles Eigenmuster — Void-Basis, darüber ein Raster aus
 * kleinen Panel-Punkten (36 dp Raster, jede 2. Zeile versetzt) und schräge
 * Ember-Anker-Linien (144 dp Abstand, Alpha 3 %) — subtil genug, um Bubbles
 * zu tragen, charaktervoll genug, um „eigen“ statt leer zu sein. Rein
 * Canvas-gezeichnet (kein Asset, kein kopiertes Muster).
 */
@Composable
private fun DefaultChatPattern(modifier: Modifier = Modifier) {
    Box(modifier.background(FlipsiColors.Void)) {
        Canvas(Modifier.fillMaxSize()) {
            drawPatternDots()
            drawPatternAnchors()
        }
    }
}

/** Punktraster: 1,2-dp-Punkte in Panel-Ton, versetzte Reihen (Fischgrät-Anmutung). */
private fun DrawScope.drawPatternDots() {
    val stepPx = 36.dp.toPx()
    val radius = 1.2.dp.toPx()
    val paint = Color(0xFF1A1D23) // Panel — auf Void gerade sichtbar, nicht laut
    var row = 0
    var y = stepPx / 2
    while (y < size.height) {
        val offset = if (row % 2 == 0) 0f else stepPx / 2
        var x = stepPx / 2 + offset
        while (x < size.width) {
            drawCircle(color = paint, radius = radius, center = androidx.compose.ui.geometry.Offset(x, y))
            x += stepPx
        }
        y += stepPx
        row++
    }
}

/** Schräge Ember-Anker: dünne Diagonalen, Alpha 3 % — Wärme ohne Lärm. */
private fun DrawScope.drawPatternAnchors() {
    val gap = 144.dp.toPx()
    val stroke = 1.dp.toPx()
    val paint = FlipsiColors.Ember.copy(alpha = 0.03f)
    translate(left = 0f, top = 0f) {
        var x = -size.height
        while (x < size.width) {
            drawLine(
                color = paint,
                start = androidx.compose.ui.geometry.Offset(x, 0f),
                end = androidx.compose.ui.geometry.Offset(x + size.height, size.height),
                strokeWidth = stroke,
            )
            x += gap
        }
    }
}

/**
 * W16.3 (F6): Galerie-Pick RAM-schonend dekodieren (Bounds → Sampling bis
 * ~[targetMin] px, Parität AgentAvatarStore.decodeSampled) — für die
 * app-private JPEG-Kopie in [setWallpaperImage], nie das Original voll.
 */
private fun decodeImageSampled(context: Context, uri: Uri, targetMin: Int): Bitmap? {
    return runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetMin &&
            bounds.outHeight / (sample * 2) >= targetMin
        ) {
            sample *= 2
        }
        resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()
}

/**
 * W16.3 (F6): Wallpaper-Kopie RAM-schonend vom app-privaten Pfad dekodieren
 * (URI-Fall entfällt — Parität AgentAvatarStore.decodeAvatarFile).
 * Null = unlesbar/Datei weg → Eigenmuster.
 */
private fun decodeWallpaperFile(path: String, targetMin: Int): Bitmap? {
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
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
