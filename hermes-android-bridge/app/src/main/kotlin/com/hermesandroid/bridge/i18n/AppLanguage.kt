package com.hermesandroid.bridge.i18n

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * W4 (App-Lane C) — In-App-Sprachwahl DE/EN, UNABHÄNGIG von der System-Sprache.
 *
 * Warum KEIN AppCompatDelegate.setApplicationLocales: appcompat fehlt im
 * Dep-Tree (und build.gradle ist Sperrzone) — der Verband hier wrappt rein in
 * Compose: [AppLanguageGate] ersetzt LocalContext/LocalConfiguration durch
 * einen createConfigurationContext-Wrap (minSdk 26 ≥ API 17, setLocale/setLocales
 * vorhanden). Kein Activity-Recreate: der State-Wechsel re-wrapped den Baum,
 * und alle stringResource-Auflösungen unterhalb des Gates lesen dann DIESE
 * Konfiguration (Werte je Key in res/values-de + res/values-en).
 *
 * Scope: App-UI-Strings (Compose/stringResource). Texte, die NICHT über die
 * Compose-Kette laufen (Notifications, Toasts am System vorbei, Service-Skripte),
 * folgen weiter dem System — bewusst, keine zweite Locale-Verkabelung.
 *
 * Persistenz: SharedPreferences „hermes_bridge_prefs" (dasselbe Prefs-Haus wie
 * Termux-Revival), Key „app_language“, Default „" = Systemstandard (die App-
 * Ressourcen sind deutsch → System ohne Wahl = Deutsch, System = en → EN).
 *
 * A11y/Ehrlichkeit: die Wahl-Zeile zeigt drei Optionen (Systemstandard, Deutsch,
 * English) mit RadioButton-Semantik — Zustand nie nur über Farbe.
 */
object AppLanguage {

    /** „“ = System-Sprache (Ressourcen folgen dem System; Default-Fassung = Deutsch). */
    const val SYSTEM = ""

    const val DE = "de"

    const val EN = "en"

    /** Wahl-Optionen in Anzeige-Reihenfolge (Systemstandard zuerst). */
    val OPTIONS = listOf(SYSTEM, DE, EN)

    // UI-Kette. Initial SYSTEM; init() liest die Prefs idempotent (gesammelt
    // in MainActivity vor setContent — falls ein Leser ZUVOR liest, gilt der
    // Default und init() korrigiert nach; der State-Wechsel drückt den Wrap).
    private var loaded = false
    private val mutableState = mutableStateOf(SYSTEM)

    /** Aktiver Tag (read-only für Compose; Wechsel nur über [set]). */
    val current: State<String> = mutableState

    /** Option gültig (Verteidigung gegen Prefs-Garbage). */
    fun isOption(tag: String): Boolean = tag in OPTIONS

    /**
     * Prefs lesen (idempotent). MainActivity.onCreate ruft VOR setContent —
     * davor gilt der Default SYSTEM (App-Deutsch).
     */
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        mutableState.value = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY, SYSTEM) ?: SYSTEM
        }.getOrDefault(SYSTEM).takeIf { isOption(it) } ?: SYSTEM
    }

    /**
     * Wahl setzen + persistieren. Bewusst KEIN Activity-Recreate: der
     * AppLanguageGate-Wrapper liest [current] und erzeugt den Context neu
     * (Compose re-wrapped SOFORT, ohne Screen-Flash-Recreate).
     */
    fun set(context: Context, tag: String) {
        if (!isOption(tag)) return
        if (!loaded) init(context)
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY, tag).apply()
        }
        mutableState.value = tag
    }

    /**
     * Wrap: Context mit überlagerter Konfiguration (NUR Ressourcen-Auflösung).
     * SYSTEM → unverändert; DE/EN → createConfigurationContext + Locale.setDefault
     * (richtet auch String.format-Dezimaltrenner — z. B. „Antwort nach 1,3 s“
     * — in der App-UI; Systemprozesse bleiben unberührt).
     *
     * W15 (Rückwechsel-Fix): bei SYSTEM wird Locale.setDefault auf die
     * System-Locale ZURÜCKgestellt — ein vorheriger DE/EN-Lauf hätte sonst
     * den Dezimaltrenner („%.1f“.format in ChatWidgets) weiter auf der alten
     * Wahl gelassen („Antwort nach 1.3 s“ statt „1,3 s“ am deutschen System).
     */
    fun wrap(base: Context, tag: String): Context {
        if (!isOption(tag)) return base
        if (tag == SYSTEM) {
            runCatching {
                val sysLocale = base.resources.configuration.locales[0]
                if (!sysLocale.language.isNullOrBlank()) Locale.setDefault(sysLocale)
            }
            return base
        }
        return runCatching {
            val locale = Locale.forLanguageTag(tag)
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            Locale.setDefault(locale)
            base.createConfigurationContext(config)
        }.getOrDefault(base)
    }

    /**
     * Activity-Quelle für die Gate-Unterbewohner: der wrapped Context ist
     * KEIN Activity-Context (context as? Activity → null würde alle
     * Berechtigungssprünge/Dialoge killen). Das Gate hängt die ORIGINAL
     * Activity hier dran — BridgeScreen liest LocalAppActivity statt
     * „context as? Activity“.
     */
    val LocalAppActivity = compositionLocalOf<Activity?> { null }

    private const val PREFS_NAME = "hermes_bridge_prefs"
    private const val KEY = "app_language"
}

/**
 * Compose-Wrap am Activity-Kompositions-Punkt (setContent → FlipsiTheme →
 * GATE → AppLockGate → Rest). [AppLanguage.current] als State → ein set()
 * re-wrapped den ganzen Baum OHNE Activity-Recreate. Zusätzlich wird die
 * ORIGINAL Activity über [AppLanguage.LocalAppActivity] durchgereicht.
 */
@Composable
fun AppLanguageGate(content: @Composable () -> Unit) {
    val tag = AppLanguage.current.value
    val base = LocalContext.current
    val wrapped = remember(tag, base) { AppLanguage.wrap(base, tag) }
    CompositionLocalProvider(
        LocalContext provides wrapped,
        LocalConfiguration provides wrapped.resources.configuration,
        AppLanguage.LocalAppActivity provides (base as? Activity),
        content = content,
    )
}