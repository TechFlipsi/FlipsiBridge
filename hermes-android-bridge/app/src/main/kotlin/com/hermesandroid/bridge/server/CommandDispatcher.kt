@file:Suppress("unused")

package com.hermesandroid.bridge.server

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import com.google.gson.JsonObject
import com.hermesandroid.bridge.BridgeApplication
import com.hermesandroid.bridge.audio.MicrophoneRecorderService
import com.hermesandroid.bridge.audio.MicrophoneRecordingFiles
import com.hermesandroid.bridge.audio.MicrophoneRecordingState
import com.hermesandroid.bridge.event.EventStore
import com.hermesandroid.bridge.executor.ActionExecutor
import com.hermesandroid.bridge.executor.ScreenReader
import com.hermesandroid.bridge.files.DeviceFiles
import com.hermesandroid.bridge.files.UpdateInstaller
import com.hermesandroid.bridge.media.ScreenRecorder
import com.hermesandroid.bridge.model.DeviceCapabilities
import com.hermesandroid.bridge.model.ScreenNode
import com.hermesandroid.bridge.notification.NotificationStore
import com.hermesandroid.bridge.security.CapabilityGate
import com.hermesandroid.bridge.power.BatteryMonitor
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import com.hermesandroid.bridge.service.BridgeNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single source of truth for command handling, shared by both transports:
 *  - [RelayClient]  — outbound WebSocket relay (the path Hermes drives the device through)
 *  - [configureRouting] in BridgeRouter.kt — the local ktor HTTP server on :8765
 *
 * Both transports parse their request into (method, path, params, body) and call [dispatch],
 * so endpoint contracts, device-capability gating, and behaviour live in exactly one place.
 *
 * @param authenticated whether the caller is authenticated. The relay connection is
 *   authenticated at connect time (Bearer token in the WS handshake), so it passes `true`; the HTTP
 *   server computes it from the request's Bearer token. Only `/ping` reports it back.
 */
object CommandDispatcher {

    /**
     * Where the battery read gets its Context.
     *
     * A seam rather than a direct `BridgeApplication.instance` read. That
     * property is published from `Application.onCreate`, which Robolectric does
     * not run, and it cannot be assigned from a test because its setter is
     * private — so without this the endpoint is untestable rather than merely
     * awkward to test. Tests point it at `RuntimeEnvironment.getApplication()`;
     * nothing else replaces it.
     */
    internal var batteryContext: () -> Context = { BridgeApplication.instance }

    suspend fun dispatch(
        method: String,
        path: String,
        params: JsonObject,
        body: JsonObject,
        authenticated: Boolean
    ): Pair<Any, Int> {
        // Capability gating BEFORE any execution (last line of defense).
        CapabilityGate.checkEndpoint(method, path)?.let { msg ->
            return Pair(mapOf("error" to msg), 403)
        }
        return when {
            method == "GET" && path == "/ping" -> {
                val serviceRunning = BridgeAccessibilityService.instance != null
                mapOf(
                    "status" to "ok",
                    "accessibilityService" to serviceRunning,
                    "authenticated" to authenticated
                    // Version omitted: /ping is unauthenticated and version info
                    // helps attackers fingerprint the deployment and target known
                    // vulnerabilities in specific versions.
                ) to 200
            }

            method == "GET" && path == "/screen" -> {
                val bounds = params.get("bounds")?.asString == "true"
                val systemUi = params.get("system_ui")?.asString == "true"
                val tree = withContext(Dispatchers.Main) {
                    ScreenReader.readCurrentScreen(bounds, systemUi)
                }
                mapOf("tree" to tree, "count" to countAllNodes(tree)) to 200
            }

            method == "POST" && path == "/tap" -> {
                val x = body.get("x")?.asInt
                val y = body.get("y")?.asInt
                val nodeId = body.get("nodeId")?.asString
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.tap(x, y, nodeId)
                }
                result to 200
            }

            method == "POST" && path == "/tap_text" -> {
                val text = body.get("text")?.asString ?: ""
                val exact = body.get("exact")?.asBoolean ?: false
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.tapText(text, exact)
                }
                result to 200
            }

            method == "POST" && path == "/type" -> {
                val text = body.get("text")?.asString ?: ""
                val clearFirst = body.get("clearFirst")?.asBoolean ?: false
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.typeText(text, clearFirst)
                }
                result to 200
            }

            method == "POST" && path == "/swipe" -> {
                val direction = body.get("direction")?.asString ?: ""
                val distance = body.get("distance")?.asString ?: "medium"
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.swipe(direction, distance)
                }
                result to 200
            }

            method == "POST" && path == "/open_app" -> {
                val pkg = body.get("package")?.asString
                    ?: return mapOf("error" to "Missing package") to 400
                val result = ActionExecutor.openApp(pkg)
                result to 200
            }

            method == "POST" && path == "/press_key" -> {
                val key = body.get("key")?.asString ?: ""
                val result = ActionExecutor.pressKey(key)
                result to 200
            }

            method == "GET" && path == "/screenshot" -> {
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.takeScreenshot()
                }
                result to 200
            }

            method == "POST" && path == "/scroll" -> {
                val direction = body.get("direction")?.asString ?: ""
                val nodeId = body.get("nodeId")?.asString
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.scroll(direction, nodeId)
                }
                result to 200
            }

            method == "POST" && path == "/wait" -> {
                val text = body.get("text")?.asString
                val className = body.get("className")?.asString
                val timeoutMs = body.get("timeoutMs")?.asInt ?: 5000
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.waitForElement(text, className, timeoutMs)
                }
                result to 200
            }

            method == "GET" && path == "/apps" -> {
                val apps = ActionExecutor.getInstalledApps()
                mapOf("apps" to apps, "count" to apps.size) to 200
            }

            method == "GET" && path == "/current_app" -> {
                val result = withContext(Dispatchers.Main) {
                    val service = BridgeAccessibilityService.instance
                    val windows = service?.windows ?: emptyList()
                    val roots = windows.mapNotNull { it.root }
                    val firstRoot = roots.firstOrNull()
                    val pkg = firstRoot?.packageName?.toString() ?: "unknown"
                    val cls = firstRoot?.className?.toString() ?: "unknown"
                    roots.forEach { it.recycle() }
                    windows.forEach { it.recycle() }
                    mapOf("package" to pkg, "className" to cls)
                }
                result to 200
            }

            method == "GET" && path == "/clipboard" -> {
                val result = ActionExecutor.clipboardRead()
                result to 200
            }

            method == "POST" && path == "/clipboard" -> {
                val text = body.get("text")?.asString ?: ""
                val result = ActionExecutor.clipboardWrite(text)
                result to 200
            }

            method == "GET" && path == "/notifications" -> {
                val limit = params.get("limit")?.asString?.toIntOrNull() ?: 50
                val since = params.get("since")?.asString?.toLongOrNull() ?: 0L
                // Dismissed notifications are retained on-device but only
                // served on explicit opt-in — cleared notifications can hold
                // PII the user has already dealt with (#100 follow-up).
                val includeRemoved = params.get("include_removed")?.asString
                    ?.equals("true", ignoreCase = true) == true
                val entries = if (since > 0) {
                    NotificationStore.getSince(since, limit, includeRemoved)
                } else {
                    NotificationStore.getAll(limit, includeRemoved)
                }
                val mapped = entries.map { NotificationStore.toMap(it) }
                val listenerRunning = BridgeNotificationListener.instance != null
                mapOf(
                    "notifications" to mapped,
                    "count" to mapped.size,
                    "listenerActive" to listenerRunning
                ) to 200
            }

            method == "POST" && path == "/long_press" -> {
                val x = body.get("x")?.asInt
                val y = body.get("y")?.asInt
                val nodeId = body.get("nodeId")?.asString
                val duration = body.get("duration")?.asLong ?: 500L
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.longPress(x, y, nodeId, duration)
                }
                result to 200
            }

            method == "POST" && path == "/drag" -> {
                val startX = body.get("startX")?.asInt ?: 0
                val startY = body.get("startY")?.asInt ?: 0
                val endX = body.get("endX")?.asInt ?: 0
                val endY = body.get("endY")?.asInt ?: 0
                val duration = body.get("duration")?.asLong ?: 500L
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.drag(startX, startY, endX, endY, duration)
                }
                result to 200
            }

            method == "POST" && path == "/describe_node" -> {
                val nodeId = body.get("nodeId")?.asString ?: ""
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.describeNode(nodeId)
                }
                result to 200
            }

            method == "POST" && path == "/find_nodes" -> {
                val text = body.get("text")?.asString
                val className = body.get("className")?.asString
                val clickable = body.get("clickable")?.asBoolean
                val limit = body.get("limit")?.asInt ?: 20
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.findNodes(text, className, clickable, limit)
                }
                result to 200
            }

            method == "POST" && path == "/diff_screen" -> {
                val previousHash = body.get("previousHash")?.asString ?: ""
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.diffScreen(previousHash)
                }
                result to 200
            }

            method == "POST" && path == "/pinch" -> {
                val x = body.get("x")?.asInt ?: 0
                val y = body.get("y")?.asInt ?: 0
                val scale = body.get("scale")?.asFloat ?: 1.5f
                val duration = body.get("duration")?.asLong ?: 300L
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.pinch(x, y, scale, duration)
                }
                result to 200
            }

            method == "GET" && path == "/screen_hash" -> {
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.screenHash()
                }
                result to 200
            }

            method == "GET" && path == "/location" -> {
                val result = ActionExecutor.location()
                result to 200
            }

            method == "GET" && path == "/battery" -> {
                // Both values come from BatteryManager — percentage via
                // getIntProperty (API 21), charge state via isCharging (API 23)
                // — against a minSdk of 26, so there is no version gate and no
                // dependency on the accessibility service. Reading the level
                // out of the status-bar node instead would be language- and
                // OEM-fragile; see BatteryMonitor.
                //
                // No auth check here on purpose. The ktor interceptor in
                // BridgeServer requires a valid Bearer token for every path
                // except /ping, and the relay authenticates at connect time
                // before passing `true`. A second gate in the handler would be
                // unreachable and would only imply the first one is optional.
                //
                // The shape is fixed — both keys, always present. Nullable
                // fields are not an option even though BridgeServer configures
                // serializeNulls(): RelayClient serialises results with a
                // default Gson(), which drops nulls, so a nullable key would
                // appear over HTTP and vanish over the relay.
                val app = batteryContext()
                val percentage = BatteryMonitor.percentage(app)
                val charging = BatteryMonitor.charging(app)
                if (percentage == null || charging == null) {
                    return mapOf(
                        "error" to "Battery state unavailable on this device",
                    ) to 503
                }
                mapOf(
                    "batteryPercentage" to percentage,
                    "charging" to charging,
                ) to 200
            }

            method == "POST" && path == "/send_sms" -> {
                if (!DeviceCapabilities.hasTelephony) {
                    return mapOf("success" to false, "error" to "SMS not available on this device") to 200
                }
                val to = body.get("to")?.asString ?: ""
                val smsBody = body.get("body")?.asString ?: ""
                val result = ActionExecutor.sendSms(to, smsBody)
                result to 200
            }

            method == "POST" && path == "/call" -> {
                if (!DeviceCapabilities.hasTelephony) {
                    return mapOf("success" to false, "error" to "Phone calls not available on this device") to 200
                }
                val number = body.get("number")?.asString ?: ""
                val result = ActionExecutor.makeCall(number)
                result to 200
            }

            method == "POST" && path == "/media" -> {
                val action = body.get("action")?.asString ?: ""
                val result = ActionExecutor.mediaControl(action)
                result to 200
            }

            method == "GET" && path == "/events" -> {
                val limit = params.get("limit")?.asString?.toIntOrNull() ?: 50
                val since = params.get("since")?.asString?.toLongOrNull() ?: 0L
                val entries = if (since > 0) {
                    EventStore.getSince(since, limit)
                } else {
                    EventStore.getAll(limit)
                }
                val mapped = entries.map { EventStore.toMap(it) }
                mapOf("events" to mapped, "count" to mapped.size, "streaming" to EventStore.streamingEnabled) to 200
            }

            method == "POST" && path == "/events/stream" -> {
                val enabled = body.get("enabled")?.asBoolean ?: false
                EventStore.setStreaming(enabled)
                // Hochfrequenz-Events (Tastendruck etc.) nur bei aktivem Stream
                EventStore.highVolumeEvents = enabled
                mapOf("success" to true, "streaming" to enabled) to 200
            }

            method == "GET" && path == "/contacts" -> {
                if (!DeviceCapabilities.hasTelephony) {
                    return mapOf("success" to false, "error" to "Contacts not available on this device") to 200
                }
                val query = params.get("query")?.asString ?: ""
                val limit = params.get("limit")?.asString?.toIntOrNull() ?: 20
                val result = withContext(Dispatchers.IO) {
                    ActionExecutor.searchContacts(query, limit)
                }
                result to 200
            }

            method == "POST" && path == "/intent" -> {
                val action = body.get("action")?.asString ?: ""
                val dataUri = body.get("dataUri")?.asString
                val extrasObj = body.get("extras")?.asJsonObject
                val extras = extrasObj?.let { obj ->
                    val map = mutableMapOf<String, String>()
                    obj.entrySet().forEach { (k, v) -> map[k] = v.asString }
                    map
                }
                val packageOverride = body.get("packageOverride")?.asString
                val result = ActionExecutor.sendIntent(action, dataUri, extras, packageOverride)
                result to 200
            }

            method == "POST" && path == "/broadcast" -> {
                val action = body.get("action")?.asString ?: ""
                val extrasObj = body.get("extras")?.asJsonObject
                val extras = extrasObj?.let { obj ->
                    val map = mutableMapOf<String, String>()
                    obj.entrySet().forEach { (k, v) -> map[k] = v.asString }
                    map
                }
                val result = ActionExecutor.sendBroadcast(action, extras)
                result to 200
            }

            method == "POST" && path == "/speak" -> {
                val text = body.get("text")?.asString ?: ""
                val queue = body.get("queue")?.asInt ?: 1
                val result = ActionExecutor.speak(text, queue)
                result to 200
            }

            method == "POST" && path == "/stop_speaking" -> {
                val result = ActionExecutor.stopSpeaking()
                result to 200
            }

            method == "POST" && path == "/mic_start" -> {
                val durationValue = body.get("duration")
                val durationSec = when {
                    durationValue == null -> 0
                    !durationValue.isJsonPrimitive || !durationValue.asJsonPrimitive.isNumber -> {
                        return mapOf("error" to "duration must be an integer number of seconds") to 400
                    }
                    else -> durationValue.asJsonPrimitive.asString.toIntOrNull()
                        ?: return mapOf("error" to "duration must be an integer number of seconds") to 400
                }
                val app = BridgeApplication.instance
                if (durationSec !in 0..MicrophoneRecorderService.MAX_DURATION_SECONDS) {
                    return mapOf(
                        "error" to "duration must be between 0 and ${MicrophoneRecorderService.MAX_DURATION_SECONDS} seconds"
                    ) to 400
                }
                if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    return mapOf("error" to "Microphone permission is not granted") to 403
                }
                if (!MicrophoneRecordingState.tryReserveStart()) {
                    return mapOf("error" to "A microphone recording is already active") to 409
                }

                runCatching {
                    MicrophoneRecorderService.start(app, durationSec)
                    mapOf(
                        "status" to "starting",
                        "duration" to durationSec,
                    ) to 202
                }.getOrElse { error ->
                    MicrophoneRecordingState.markError(
                        "Could not start microphone service (${error.javaClass.simpleName})",
                    )
                    mapOf(
                        "error" to "Could not start microphone service (${error.javaClass.simpleName})"
                    ) to 500
                }
            }

            method == "POST" && path == "/mic_stop" -> {
                val snapshot = MicrophoneRecordingState.snapshot()
                if (!snapshot.isActive) {
                    return mapOf("status" to "idle") to 200
                }
                runCatching {
                    MicrophoneRecorderService.stop(BridgeApplication.instance)
                    mapOf("status" to "stopping") to 202
                }.getOrElse { error ->
                    mapOf(
                        "error" to "Could not stop microphone service (${error.javaClass.simpleName})"
                    ) to 500
                }
            }

            method == "GET" && path == "/mic_status" -> {
                val app = BridgeApplication.instance
                val snapshot = MicrophoneRecordingState.snapshot()
                val files = MicrophoneRecordingFiles.listCompleted(app)
                val latest = files.firstOrNull()
                mapOf(
                    "phase" to snapshot.phase.name.lowercase(),
                    "recording" to snapshot.isActive,
                    "count" to files.size,
                    "retentionLimit" to MicrophoneRecordingFiles.MAX_COMPLETED_RECORDINGS,
                    "latest" to latest?.name,
                    "latestSize" to latest?.length(),
                    "bytesWritten" to snapshot.bytesWritten,
                    "startedAt" to snapshot.startedAtMs,
                    "error" to snapshot.error,
                ) to 200
            }

            method == "POST" && path == "/screen_record" -> {
                val durationMs = body.get("durationMs")?.asLong?.coerceAtMost(30_000L) ?: 5000L
                val result = withContext(Dispatchers.IO) {
                    ScreenRecorder.record(durationMs)
                }
                result to 200
            }

            method == "GET" && path == "/widgets" -> {
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.readWidgets()
                }
                result to 200
            }

            method == "GET" && path == "/files" -> {
                val rel = params.get("path")?.asString ?: ""
                val showHidden = params.get("hidden")?.asString == "true"
                val (entries, error) = DeviceFiles.list(rel, showHidden)
                if (error != null) return mapOf("error" to error) to 400
                mapOf(
                    "path" to rel,
                    "count" to entries!!.size,
                    "entries" to entries.map { e ->
                        mapOf(
                            "name" to e.name,
                            "path" to e.path,
                            "isDir" to e.isDir,
                            "size" to e.sizeBytes,
                            "modifiedMs" to e.modifiedMs,
                        )
                    },
                ) to 200
            }

            method == "GET" && path == "/files_search" -> {
                val query = params.get("query")?.asString ?: ""
                val maxFiles = (params.get("limit")?.asString?.toIntOrNull() ?: 200).coerceIn(1, 1000)
                val (entries, error) = DeviceFiles.search(query, maxFiles)
                if (error != null) return mapOf("error" to error) to 400
                mapOf(
                    "query" to query,
                    "count" to entries!!.size,
                    "entries" to entries.map { e ->
                        mapOf(
                            "name" to e.name,
                            "path" to e.path,
                            "size" to e.sizeBytes,
                            "modifiedMs" to e.modifiedMs,
                        )
                    },
                ) to 200
            }

            method == "POST" && path == "/files_push" -> {
                val rel = body.get("path")?.asString
                val b64 = body.get("data")?.asString
                val overwrite = body.get("overwrite")?.asBoolean ?: false
                if (rel.isNullOrBlank() || b64.isNullOrBlank()) {
                    return mapOf("error" to "path und data (base64) sind Pflicht") to 400
                }
                val bytes = try {
                    android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                } catch (e: IllegalArgumentException) {
                    return mapOf("error" to "data ist kein gültiges Base64") to 400
                }
                val result = withContext(Dispatchers.IO) {
                    DeviceFiles.writeBytes(rel, bytes, overwrite)
                }
                if (result.second != null) return mapOf("error" to result.second) to 400
                mapOf("written" to true, "path" to result.first, "bytes" to bytes.size) to 200
            }

            method == "POST" && path == "/files_delete" -> {
                val rel = body.get("path")?.asString
                val pathsArr = body.get("paths")?.asJsonArray
                if (pathsArr != null && pathsArr.size() > 0) {
                    val list = pathsArr.mapNotNull { (it as? com.google.gson.JsonPrimitive)?.takeIf { p -> p.isString }?.asString }.take(200)
                    val results = withContext(Dispatchers.IO) { DeviceFiles.deleteMany(list) }
                    mapOf(
                        "requested" to list.size,
                        "results" to results,
                        "okCount" to results.count { it["ok"] == "true" },
                        "failCount" to results.count { it["ok"] == "false" },
                    ) to 200
                } else {
                    if (rel.isNullOrBlank()) return mapOf("error" to "path oder paths ist Pflicht") to 400
                    val result = withContext(Dispatchers.IO) { DeviceFiles.deleteFile(rel) }
                    if (result.second != null) return mapOf("error" to result.second) to 400
                    mapOf("deleted" to true, "message" to result.first) to 200
                }
            }

            method == "GET" && path == "/files_permission" -> {
                val app = BridgeApplication.instance
                val granted = android.os.Environment.isExternalStorageManager()
                mapOf("allFilesAccess" to granted) to 200
            }

            method == "POST" && path == "/files_permission" -> {
                val app = BridgeApplication.instance
                if (android.os.Environment.isExternalStorageManager()) {
                    mapOf("allFilesAccess" to true, "message" to "All-files access already granted") to 200
                } else {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.parse("package:" + app.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    app.startActivity(intent)
                    mapOf("allFilesAccess" to false, "message" to "Settings opened - please grant 'All files access' for the app") to 200
                }
            }

            method == "GET" && path == "/files_count" -> {
                val ext = params.get("ext")?.asString ?: "pdf"
                val counts = DeviceFiles.countByExtension(ext)
                val total = counts.values.sum()
                mapOf("ext" to ext, "total" to total, "byRoot" to counts) to 200
            }

            method == "POST" && path == "/photo" -> {
                val fileName = body.get("filename")?.asString ?: ""
                val result = withContext(Dispatchers.IO) {
                    com.hermesandroid.bridge.device.DeviceHardware.capturePhoto(BridgeApplication.instance, fileName)
                }
                if (result.second != null) return mapOf("error" to result.second) to 400
                mapOf("path" to result.first, "saved" to "Pictures/Bridge") to 200
            }

            method == "POST" && path == "/torch" -> {
                val on = body.get("on")?.asBoolean ?: true
                val result = com.hermesandroid.bridge.device.DeviceHardware.setTorch(BridgeApplication.instance, on)
                if (result.second != null) return mapOf("error" to result.second) to 400
                mapOf("message" to result.first) to 200
            }

            method == "GET" && path == "/network" -> {
                com.hermesandroid.bridge.device.DeviceHardware.networkStatus(BridgeApplication.instance) to 200
            }

            method == "POST" && path == "/volume" -> {
                val stream = body.get("stream")?.asString ?: "media"
                val set = body.get("set")?.asInt
                com.hermesandroid.bridge.device.DeviceHardware.volume(BridgeApplication.instance, stream, set) to 200
            }

            method == "POST" && path == "/alarm" -> {
                val hour = body.get("hour")?.asInt ?: return mapOf("error" to "hour fehlt") to 400
                val minute = body.get("minute")?.asInt ?: 0
                val label = body.get("label")?.asString ?: ""
                val result = com.hermesandroid.bridge.device.DeviceHardware.setAlarm(BridgeApplication.instance, hour, minute, label)
                if (result.second != null) return mapOf("error" to result.second) to 400
                mapOf("message" to result.first) to 200
            }

            method == "POST" && path == "/timer" -> {
                val seconds = body.get("seconds")?.asInt ?: return mapOf("error" to "seconds fehlt") to 400
                val label = body.get("label")?.asString ?: ""
                val result = com.hermesandroid.bridge.device.DeviceHardware.setTimer(BridgeApplication.instance, seconds, label)
                if (result.second != null) return mapOf("error" to result.second) to 400
                mapOf("message" to result.first) to 200
            }

            method == "POST" && path == "/notify_reply" -> {
                val text = body.get("text")?.asString ?: return mapOf("error" to "text fehlt") to 400
                val key = body.get("key")?.asString ?: ""
                val pkg = body.get("package")?.asString ?: ""
                val err = when {
                    key.isNotBlank() -> com.hermesandroid.bridge.service.NotificationReplier.reply(key, text)
                    pkg.isNotBlank() -> com.hermesandroid.bridge.service.NotificationReplier.replyLatestForPackage(pkg, text)
                    else -> "key oder package ist Pflicht"
                }
                if (err != null) return mapOf("error" to err) to 400
                mapOf("replied" to true) to 200
            }

            method == "POST" && path == "/apk_install" -> {
                val url = body.get("url")?.asString
                val sha = body.get("sha256")?.asString
                if (url.isNullOrBlank() || sha.isNullOrBlank()) {
                    return mapOf("error" to "url und sha256 sind Pflicht") to 400
                }
                val result = withContext(Dispatchers.IO) {
                    UpdateInstaller.start(BridgeApplication.instance, url, sha)
                }
                mapOf(
                    "ok" to result.ok,
                    "message" to result.message,
                    "bytes" to result.bytesDownloaded,
                    "sha256" to result.sha256,
                ) to result.status
            }

            else -> {
                mapOf("error" to "Unknown command: $method $path") to 404
            }
        }
    }

    private fun countAllNodes(nodes: List<ScreenNode>): Int {
        var count = 0
        for (node in nodes) {
            count += 1 + countAllNodes(node.children)
        }
        return count
    }
}
