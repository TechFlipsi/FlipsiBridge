package com.hermesandroid.bridge.client

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Health-Connect-Brücke (v0.11.0, Sir-Auftrag "App bekommt Zugriff auf die
 * Gesundheitsdaten über Health Connect").
 *
 * Bewusst NUR Lesen und nur AGGREGATE über ein Fenster — keine Rohdaten-
 * Punkte-Streams (PII-Minimierung: einzelne Messungen/Schlafsegmente mit
 * Zeitstempel bleiben am Gerät, wir liefern Summen/Extremwerte über einen
 * Zeitraum).
 *
 * Berechtigungen werden NICHT still angefragt: Sir aktiviert in der App-UI
 * die Capability "health" → der Compose-Consent-Dialog fragt die hier
 * aufgelisteten READ-Permissions ab. Ohne Berechtigung liefert /health_data
 * eine deutliche, harmlose "Berechtigungen fehlen"-Antwort (keine 500er).
 */
object HealthDataBridge {

    private const val TAG = "HealthBridge"
    private val ISO_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())

    /** F6: Datum-only-Former für die Gewicht-Angabe (kein Wiege-Uhrzeitpunkt). */
    private val DATE_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

    /** READ-Permission-Set — EINMAL verankert, UI und Endpoint greifen darauf zu. */
    val readPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
    )

    /**
     * Einstieg der Route POST /health_data (params: days=1..30, default 1).
     * Antwort ist IMMER ein ordentliches Map-Ergebnis — Fehler als
     * "ok": false + "error" Text, kein 500er-Basar.
     */
    suspend fun handleRequest(params: JsonObject): Map<String, Any?> {
        val days = coerceDays(params)
        val context = bridgeContext() ?: return notAvailable("Interner Kontext fehlt")

        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return notAvailable(
                "Health Connect auf dem Gerät nicht verfügbar/aktiv (Einstellungen → Health Connect)."
            )
        }

        val client = try {
            HealthConnectClient.getOrCreate(context)
        } catch (e: Exception) {
            Log.w(TAG, "HealthConnectClient nicht erstellbar: ${e.message}")
            return notAvailable("Health Connect kann nicht geöffnet werden: ${e.message?.take(120)}")
        }

        val granted = try {
            client.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            Log.w(TAG, "Permission-Abfrage fehlgeschlagen: ${e.message}")
            return notAvailable("Berechtigungen nicht ablesbar: ${e.message?.take(120)}")
        }
        val missingPermissions = readPermissions.filter { it !in granted }
        if (missingPermissions.isNotEmpty()) {
            val metricNames = missingPermissions.map { permissionNameToMetric(it) }
            return mapOf(
                "ok" to false,
                "available" to true,
                "error" to "Health-Connect-Berechtigungen fehlen: ${metricNames.joinToString(", ")}",
                "hint" to "In der FlipsiBridge-App: Capability „health\" einschalten → Berechtigungen erteilen.",
                "granted_permissions" to granteDescription(granted),
                "missing_permissions" to metricNames,
            )
        }

        val to = Instant.now()
        val from = to.minusSeconds(days.toLong() * 24L * 60L * 60L)
        val range = TimeRangeFilter.between(from, to)

        return try {
            val result = client.aggregate(
                androidx.health.connect.client.request.AggregateRequest(
                    metrics = setOf(
                        StepsRecord.COUNT_TOTAL,
                        DistanceRecord.DISTANCE_TOTAL,
                        TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                        HeartRateRecord.BPM_AVG,
                        HeartRateRecord.BPM_MIN,
                        HeartRateRecord.BPM_MAX,
                        SleepSessionRecord.SLEEP_DURATION_TOTAL,
                    ),
                    timeRangeFilter = range,
                )
            )
            val response = mutableMapOf<String, Any?>(
                "ok" to true,
                "range" to mapOf(
                    "from" to ISO_FORMATTER.format(from),
                    "to" to ISO_FORMATTER.format(to),
                    "days" to days,
                ),
            )
            // Typsicher über den get-Operator: AggregationResult[metric:T] = T?
            result[StepsRecord.COUNT_TOTAL]?.let { steps ->
                response["steps"] = steps
            }
            result[DistanceRecord.DISTANCE_TOTAL]?.let { meters ->
                response["distance_meters"] = meters.inMeters
            }
            result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.let { kilocalories ->
                response["calories_kcal"] = kilocalories.inKilocalories
            }
            result[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.let { schlafdauer ->
                response["sleep_minutes"] = schlafdauer.toMinutes()
            }
            result[HeartRateRecord.BPM_AVG]?.let { bpmAvg ->
                val hr = mutableMapOf<String, Any?>("avg" to bpmAvg)
                result[HeartRateRecord.BPM_MIN]?.let { hr["min"] = it }
                result[HeartRateRecord.BPM_MAX]?.let { hr["max"] = it }
                response["heart_rate"] = hr
            }
            fillLatestWeight(client, response)
            response
        } catch (e: Exception) {
            Log.w(TAG, "Aggregate-Fehler: ${e.message}")
            mapOf(
                "ok" to false,
                "error" to "Health-Aggregation fehlgeschlagen: ${e.message?.take(200)}",
            )
        }
    }

    /** Latest Weight-Eintrag (nur Wert + Datum, keine weiteren Metadaten).
     *  Schnüffler-F6: NUR DATUM (yyyy-MM-dd) — der exakte Wiege-Zeitpunkt wäre
     *  verhaltensreveilierend; die UI-Minimierungs-Zusage hält. */
    private suspend fun fillLatestWeight(client: HealthConnectClient, response: MutableMap<String, Any?>) {
        try {
            val request = ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = TimeRangeFilter.between(
                    Instant.now().minusSeconds(90L * 24L * 60L * 60L),
                    Instant.now(),
                ),
            )
            val records = client.readRecords(request).records
            val latest = records.maxByOrNull { it.time }
            if (latest != null) {
                response["latest_weight_kg"] = latest.weight.inKilograms
                // Nur Datum — Uhrzeit weg (Minimierungs-Versprechen).
                response["latest_weight_at"] = DATE_FORMATTER.format(latest.time)
            }
        } catch (e: Exception) {
            // Optionaler Zusatz: weglassen statt Fehler — Kernantwort bleibt brauchbar.
            Log.w(TAG, "Gewicht nicht lesbar: ${e.message}")
        }
    }

    private fun coerceDays(params: JsonObject): Int {
        val raw = try {
            params.get("days")?.asInt
        } catch (_: Exception) {
            null
        }
        return when {
            raw == null -> 1
            raw < 1 -> 1
            raw > 30 -> 30
            else -> raw
        }
    }

    /** Kurze Metric-Bezeichnung aus dem Health-Permission-String (PII-frei). */
    private fun permissionNameToMetric(permission: String): String {
        return when {
            permission.contains("StepsRecord") -> "Schritte"
            permission.contains("DistanceRecord") -> "Distanz"
            permission.contains("CaloriesBurned") -> "Kalorien"
            permission.contains("HeartRate") -> "Herzfrequenz"
            permission.contains("Sleep") -> "Schlaf"
            permission.contains("Oxygen") -> "Sauerstoffsättigung"
            permission.contains("Weight") -> "Gewicht"
            else -> "weitere Datenklasse"
        }
    }

    /** UI-Anzeige: Lesbarer Metric-Name für eine Health-Permission (PII-frei). */
    fun metricLabel(permission: String): String = permissionNameToMetric(permission)

    private fun granteDescription(granted: Set<String>): List<String> {
        return granted.map { permissionNameToMetric(it) }.sorted()
    }

    private fun notAvailable(message: String): Map<String, Any?> {
        return mapOf("ok" to false, "available" to false, "error" to message)
    }

    private fun bridgeContext(): Context? {
        return try {
            com.hermesandroid.bridge.BridgeApplication.instance.applicationContext
        } catch (_: Exception) {
            null
        }
    }
}