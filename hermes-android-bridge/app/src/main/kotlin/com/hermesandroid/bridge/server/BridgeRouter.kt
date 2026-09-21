package com.hermesandroid.bridge.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hermesandroid.bridge.BridgeApplication
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.audio.MicrophoneRecordingFiles
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Local HTTP server routing. This is a thin transport adapter: it parses each request
 * into (method, path, params, body) and hands it to [CommandDispatcher], which holds the
 * single source of truth for command behaviour shared with the WebSocket relay path.
 *
 * Auth is enforced by the interceptor in BridgeServer.kt; here we only compute the
 * authenticated flag so `/ping` can report it accurately.
 */
fun Application.configureRouting() {
    routing {
        get("/mic_file") {
            val requestedName = call.request.queryParameters["name"]
            val file = MicrophoneRecordingFiles.resolve(
                BridgeApplication.instance,
                requestedName,
            )
            if (file == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Recording not found"))
                return@get
            }

            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header(HttpHeaders.ContentType, "audio/wav")
            call.response.header(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"${file.name}\"",
            )
            call.respondFile(file)
        }

        get("/file") {
            // Capability-Gate manuell (Route liefert Binärdaten, kein Dispatcher-JSON)
            com.hermesandroid.bridge.security.CapabilityGate.checkEndpoint("GET", "/file")?.let { msg ->
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to msg))
                return@get
            }
            val rel = call.request.queryParameters["path"] ?: ""
            val resolved = com.hermesandroid.bridge.files.DeviceFiles.resolveForRead(rel)
            if (resolved == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Datei nicht lesbar: $rel"))
                return@get
            }
            val (file, fileName) = resolved
            val mime = when (fileName.substringAfterLast('.', "").lowercase()) {
                "pdf" -> "application/pdf"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "txt", "log", "csv" -> "text/plain"
                "zip", "apk" -> "application/zip"
                "mp3" -> "audio/mpeg"
                "mp4", "mov", "mkv", "webm" -> "video/mp4"
                "wav" -> "audio/wav"
                else -> "application/octet-stream"
            }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header(HttpHeaders.ContentType, mime)
            call.response.header(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"${fileName}\"",
            )
            call.respondFile(file)
        }

        route("{path...}") {
            handle {
                val method = call.request.httpMethod.value.uppercase()
                val segments = call.parameters.getAll("path") ?: emptyList()
                val path = "/" + segments.joinToString("/")

                val params = JsonObject().apply {
                    call.request.queryParameters.names().forEach { name ->
                        call.request.queryParameters[name]?.let { addProperty(name, it) }
                    }
                }

                val body = try {
                    val text = call.receiveText()
                    if (text.isBlank()) JsonObject() else JsonParser.parseString(text).asJsonObject
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON body: ${e.message}"))
                    return@handle
                }

                val authenticated = PairingManager.validateToken(
                    call.request.header(HttpHeaders.Authorization)
                )

                val (result, status) = CommandDispatcher.dispatch(method, path, params, body, authenticated)
                call.respond(HttpStatusCode.fromValue(status), result)
            }
        }
    }
}
