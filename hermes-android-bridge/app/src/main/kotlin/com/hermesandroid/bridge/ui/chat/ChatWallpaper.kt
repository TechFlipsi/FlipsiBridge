package com.hermesandroid.bridge.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

/**
 * WELLE-13 P11a (Chat-Hintergrund): zwei Zustände, ehrlich getrennt —
 *
 * 1. Standard = sehr dunkles EIGENES Muster: Void-Navy-Basis mit einem
 *    hand-generierten, minimalen Punktraster + schrägen Ember-Ankern
 *    ([DefaultChatPattern]). „Anmutung ja, Assets nein“ (Fixplan-Grundregel):
 *    es wird KEIN fremdes Muster kopiert, das Muster ist deterministisch aus
 *    der Flipsi-Palette gerechnet.
 * 2. Nutzer-Foto: [ChatWallpaperPrefs.wallpaperUri] (ContentResolver-Pick,
 *    Settings-Seite setzt ihn) — das Foto füllt flächendeckend (Crop), ein
 *    dunkler Scrim (55 %) liegt darüber, damit die Bubbles lesbar bleiben
 *    (WhatsApp-Lesbarkeits-Trick, mit eigener Umsetzung).
 *
 * Leere/fehlende URI = Eigenmuster (nie ein weißer Blitz).
 */
object ChatWallpaperPrefs {
    private const val FILE = "chat_wallpaper"
    private const val KEY_URI = "wallpaperUri"

    /** Gewähltes Nutzer-Foto als content-URI (leer = Eigenmuster). */
    fun wallpaperUri(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_URI, "") ?: ""

    /**
     * Wallpaper setzen (Settings-Pick); null/leer räumt den Schlüssel →
     * zurück zum Eigenmuster. Speichern als URI-String, KEIN Kopieren der
     * Datei — das Foto bleibt in Sirs Gallery-Ordner, die App hält nur den
     * Verweis (ContentResolver-Persistenz übernimmt der Pick-Aufrufer).
     */
    fun setWallpaperUri(context: Context, uri: String?) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            if (uri.isNullOrBlank()) remove(KEY_URI) else putString(KEY_URI, uri)
        }.apply()
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
    val uri = ChatWallpaperPrefs.wallpaperUri(context)
    if (uri.isBlank()) {
        DefaultChatPattern(modifier)
        return
    }
    // Nutzer-Foto: dekodiert auf IO (Downsampling als RAM-Schutz — derselbe
    // Sampling-Gedanke wie decodeImageThumbnail in ChatMediaWidgets).
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            decodeWallpaperSampled(context, uri, targetMin = 1080)
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
 * Wallpaper-Foto RAM-schonend dekodieren: erst Bounds, dann Sampling-Faktor
 * bis ~[targetMin] px — nie das Original in voller Auflösung im Speicher.
 * Null = unlesbar (ContentResolver-Weg bereits weggezogen etc.) → Eigenmuster.
 */
private fun decodeWallpaperSampled(context: Context, uri: String, targetMin: Int): Bitmap? {
    return runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(android.net.Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetMin &&
            bounds.outHeight / (sample * 2) >= targetMin
        ) {
            sample *= 2
        }
        resolver.openInputStream(android.net.Uri.parse(uri))?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()
}
