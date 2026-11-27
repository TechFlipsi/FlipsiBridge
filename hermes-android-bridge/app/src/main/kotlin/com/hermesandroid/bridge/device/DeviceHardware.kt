package com.hermesandroid.bridge.device

import android.bluetooth.BluetoothManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager

/**
 * DeviceHardware - hardware controls + status (v0.10.0).
 * Kamera-Foto ohne Preview-UI via CameraManager (ein Frame, sofort speichern),
 * Taschenlampe, WLAN/BT/Netz-Status, Lautst_rken, Wecker-Intent.
 */
object DeviceHardware {

    data class PhotoResult(val ok: Boolean, val path: String, val error: String?)

    /**
     * JÄGER-J2 (P1, JAEGE-RFULL-20261102): Dateiname-Sanitizing für /photo.
     * `File(dir, "../../Download/x.jpg")` löste auf und schrieb ÜBERALL unter
     * /storage/emulated/0 — die Route ist nur hinter der Capability `device`
     * (Taschenlampe/Wecker) gegated, NICHT hinter files_write. Vertrag: nur
     * [A-Za-z0-9._-] (≤128 Zeichen), kein Pfad-Separator, kein "..", kein
     * reiner Verweis wie "." — Muster wie MicrophoneRecordingFiles.safeName.
     * null = Name wurde verworfen (Aufrufer meldet Fehler statt zu schreiben).
     */
    private val safePhotoName = Regex("[A-Za-z0-9._-]{1,128}")

    /** JÄGER-J2: prüft UND begrenzt den Namen auf reine Dateinamen. */
    internal fun safePhotoFileName(rawName: String): String? {
        val name = rawName.trim()
        if (name.isEmpty()) return null
        // PC-GATE-FUND (Fix-Welle 2, vorbelastet aus J2/8b5a54f — der Test lief
        // nie auf einer JVM): „.“ passierte Regex + contains(„..“) + File-Check
        // (File(".").name == „.“) und wäre als Dateiname durchgegangen.
        if (name == "." || name == "..") return null
        if (!safePhotoName.matches(name)) return null
        if (name.contains("..")) return null
        if (java.io.File(name).name != name) return null
        return name
    }

    /** Nimmt ein JPEG auf (r_ckseitige Kamera, maximal verf_ugbare Aufl_sung ohne Preview). */
    fun capturePhoto(context: Context, fileName: String): Pair<String, String?> {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val backId = cm.cameraIdList.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull() ?: return Pair("", "Keine Kamera gefunden")
            val chars = cm.getCameraCharacteristics(backId)
            val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val sizeMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes: Array<android.util.Size>? = sizeMap?.getOutputSizes(android.graphics.ImageFormat.JPEG)
            val jpegStream: android.util.Size = sizes?.maxByOrNull { it.width * it.height }
                ?: return Pair("", "Keine JPEG-Groesse verfuegbar")
            val dir = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_PICTURES),
                "Agent",
            )
            if (!dir.exists()) dir.mkdirs()
            // JÄGER-J2 (P1): Pfad-Traversal — der filename kam unvalidiert
            // vom Relay und schrieb mit "../.." ÜBERALL unter /storage.
            // Sanitizing + Canonical-Confinement: nur noch Namen OHNE Pfad-
            // Bestandteile durch, und das Ergebnis muss canonical IM
            // Foto-Verzeichnis liegen (letzter Riegel gegen Symlink-/Mount-
            // Tricks).
            val requestedName = fileName.trim()
            val safeName = if (requestedName.isBlank()) {
                "agent_foto_${System.currentTimeMillis()}.jpg"
            } else {
                safePhotoFileName(requestedName)
                    ?: return Pair("", "Ungültiger Dateiname: nur Buchstaben, Zahlen, . _ - erlaubt (keine Pfade)")
            }
            val out = java.io.File(dir, safeName)
            if (out.canonicalFile.parentFile != dir.canonicalFile) {
                return Pair("", "Dateiname verlässt das Foto-Verzeichnis")
            }
            val latch = java.util.concurrent.CountDownLatch(1)
            var failure: String? = null
            val handlerThread = android.os.HandlerThread("BridgeCamera").apply { start() }
            val handler = android.os.Handler(handlerThread.looper)
            // v0.11.0 (Review R9): Referenzen für den Timeout-Pfad — vorher
            // blieb bei Timeout/Disconnect die KAMERA vom Prozess-Ende an
            // besetzt ("andere App nutzt die Kamera").
            // K3-Fix (Kobold): AtomicReference statt Captured-Var — der
            // Handler-Thread schreibt, der Aufrufer-Thread liest (Timeout);
            // ohne happens-before sah der Aufrufer stale null → Kamera blieb
            // besetzt. AtomicReference beides weg.
            val openedCamera = java.util.concurrent.atomic.AtomicReference<android.hardware.camera2.CameraDevice?>()
            val openedReader = java.util.concurrent.atomic.AtomicReference<android.media.ImageReader?>()

            val listener = object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
                    openedCamera.set(camera)
                    try {
                        val reader = android.media.ImageReader.newInstance(
                            jpegStream.width, jpegStream.height, android.graphics.ImageFormat.JPEG, 1)
                        openedReader.set(reader)
                        val surface = reader.surface
                        val builder = camera.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE)
                        builder.addTarget(surface)
                        // KEIN JPEG_ORIENTATION-Override: Sensor-Default lassen (Empirie v0.10.6).
                        camera.createCaptureSession(listOf(surface),
                            object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                                    try {
                                        session.capture(builder.build(),
                                            object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                                                override fun onCaptureCompleted(
                                                    session: android.hardware.camera2.CameraCaptureSession,
                                                    request: android.hardware.camera2.CaptureRequest,
                                                    result: android.hardware.camera2.TotalCaptureResult,
                                                ) {
                                                    var img = reader.acquireLatestImage()
                                                    var tries = 0
                                                    while (img == null && tries < 20) {
                                                        Thread.sleep(50)
                                                        img = reader.acquireLatestImage()
                                                        tries++
                                                    }
                                                    if (img == null) { failure = "Kein Bild geliefert"; latch.countDown(); return }
                                                    val buffer = img.planes[0].buffer
                                                    val bytes = ByteArray(buffer.remaining())
                                                    buffer.get(bytes)
                                                    img.close()
                                                    // Empirie 21.09., Foto 5 (roh): Rohbild ist 90 Grad gegen den
                                                    // Uhrzeigersinn gedreht -> deterministisch +90 (CW) drehen,
                                                    // OHNE EXIF-Tag (der war der Störfaktor in allen Vorversionen).
                                                    // J9 (JÄGER-RFULL, Fix-Welle 2): decodeByteArray am VOLL-
                                                    // aufgelösten JPEG (bis 50 MP ≈ 200 MB Bitmap) + createBitmap-
                                                    // Kopie = OOM-Risiko. Erst Bounds dekodieren, inSampleSize bis
                                                    // ~4 MP wählen, dann dekodieren + drehen (Anzeige-Qualität
                                                    // reicht; das Original-File bekommt der Aufrufer ohnehin nie).
                                                    val rotated = try {
                                                        val bounds = android.graphics.BitmapFactory.Options().apply {
                                                            inJustDecodeBounds = true
                                                        }
                                                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                                                        var sample = 1
                                                        val targetPixels = 4_000_000
                                                        while ((bounds.outWidth / sample).toLong() *
                                                            (bounds.outHeight / sample) > targetPixels
                                                        ) {
                                                            sample *= 2
                                                        }
                                                        val opts = android.graphics.BitmapFactory.Options().apply {
                                                            inSampleSize = sample
                                                        }
                                                        val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                                                            ?: error("Bitmap-Dekodierung fehlgeschlagen")
                                                        val m = android.graphics.Matrix()
                                                        m.postRotate(90f)
                                                        val bmp = android.graphics.Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
                                                        if (bmp !== src) src.recycle()
                                                        java.io.ByteArrayOutputStream().use { bo ->
                                                            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, bo)
                                                            bmp.recycle()
                                                            bo.toByteArray()
                                                        }
                                                    } catch (_: Exception) { bytes }
                                                    java.io.FileOutputStream(out).use { it.write(rotated) }
                                                    reader.close()
                                                    camera.close()
                                                    latch.countDown()
                                                }
                                            }, handler)
                                    } catch (e: Exception) {
                                        failure = "Capture fehlgeschlagen: ${e.javaClass.simpleName}"
                                        camera.close(); latch.countDown()
                                    }
                                }
                                override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                                    failure = "Session-Config fehlgeschlagen"
                                    camera.close(); latch.countDown()
                                }
                            }, handler)
                            } catch (e: Exception) {
                            failure = "CaptureRequest fehlgeschlagen: ${e.javaClass.simpleName}"
                        camera.close(); latch.countDown()
                    }
                }
                override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) {
                    // v0.11.0 (Review R9): hier freigeben + Latch auslösen —
                    // vorher hängt der Aufrufer die vollen 20 s und die Kamera
                    // bleibt besetzt.
                    failure = "Kamera wurde getrennt vor Aufnahme"
                    openedReader.get()?.close()
                    camera.close()
                    latch.countDown()
                }
                override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) {
                    failure = "Kamera-Fehler (Code $error) - evtl. läuft eine andere Kamera-App"
                    camera.close(); latch.countDown()
                }
            }
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                handlerThread.quitSafely()
                return Pair("", "Kamera-Berechtigung fehlt (CAMERA) - bitte in der App freigeben")
            }
            try {
                cm.openCamera(backId, listener, handler)
            } catch (e: Exception) {
                handlerThread.quitSafely()
                return Pair("", "Kamera-Oeffnung fehlgeschlagen: ${e.javaClass.simpleName}")
            }
            val done = latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
            handlerThread.quitSafely()
            if (!done) {
                // v0.11.0 (Review R9): Timeout-Pfad räumt Kamera + Reader auf.
                failure = "Timeout beim Foto (20 s)"
                runCatching { openedReader.get()?.close() }
                runCatching { openedCamera.get()?.close() }
            }
            if (failure != null) Pair("", failure!!)
            else if (out.exists() && out.length() > 0) Pair(out.absolutePath, null)
            else Pair("", "Kein Bild erzeugt")
        } catch (e: Exception) {
            Pair("", "Foto fehlgeschlagen: ${e.javaClass.simpleName}: ${e.message ?: ""}")
        }
    }

    /** Taschenlampe an/aus. */
    fun setTorch(context: Context, on: Boolean): Pair<String, String?> {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val torchId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return Pair("", "Kein Blitz gefunden")
        return try { cm.setTorchMode(torchId, on); Pair(if (on) "Taschenlampe AN" else "Taschenlampe AUS", null) }
        catch (e: Exception) { Pair("", "Taschenlampe fehlgeschlagen: ${e.javaClass.simpleName}") }
    }

    /** WLAN/Bluetooth/Mobilfunk/Flugmodus/VPN-Kurzstatus. */
    fun networkStatus(context: Context): Map<String, Any?> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val bt = context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val cm2 = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val active = cm2.activeNetwork
        val caps = active?.let { cm2.getNetworkCapabilities(it) }
        val linkProps = active?.let { cm2.getLinkProperties(it) }
        return mapOf(
            "wifiEnabled" to wifi.isWifiEnabled,
            "wifiSsid" to (wifi.connectionInfo?.ssid?.removePrefix("\"")?.removeSuffix("\"")?.takeIf { it != "<unknown ssid>" }),
            "wifiIp" to (wifi.connectionInfo?.ipAddress?.let {
                String.format("%d.%d.%d.%d", it and 0xff, it shr 8 and 0xff, it shr 16 and 0xff, it shr 24 and 0xff)
            }),
            "bluetoothOn" to (bt.adapter?.isEnabled == true),
            "hasInternet" to (caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true),
            "metered" to (caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false),
            "transportWifi" to (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true),
            "transportCellular" to (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true),
            // v0.12.0 (Akku P2-6): Akku-Freistellung überwachbar — ist sie weg
            // (HyperOS/Android kann sie zurücksetzen), stirbt das WS-Netz in
            // Deep-Doze und die Brücke flackert. Early-Warning-Hebel!
            "batteryUnrestricted" to pm.isIgnoringBatteryOptimizations(context.packageName),
            )
    }

    /** Lautst_rken abfragen oder setzen (media/ring/alarm). */
    fun volume(context: Context, stream: String, set: Int?): Map<String, Any?> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val s = when (stream) {
            "media" -> AudioManager.STREAM_MUSIC
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
        val max = am.getStreamMaxVolume(s)
        val cur = am.getStreamVolume(s)
        if (set != null) {
            am.setStreamVolume(s, set.coerceIn(0, max), 0)
        }
        return mapOf(
            "stream" to stream, "current" to (if (set != null) set.coerceIn(0, max) else cur),
            "max" to max, "set" to (set != null),
        )
    }

    /** Wecker stellen (system Clock-App, Standard-Intent - keine Spezialrechte). */
    fun setAlarm(context: Context, hour: Int, minute: Int, label: String): Pair<String, String?> {
        return try {
            // v0.11.0 (Review): unvalidierte Werte → die Uhr-App verweigert
            // still oder stürzt — hier bündig einklemmen.
            val h = hour.coerceIn(0, 23)
            val m = minute.coerceIn(0, 59)
            val i = android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(android.provider.AlarmClock.EXTRA_HOUR, h)
                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, m)
                if (label.isNotBlank()) putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            }
            context.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            Pair("Wecker $h:${m.toString().padStart(2,'0')} gestellt (${label.ifBlank { "ohne Namen" }})", null)
        } catch (e: Exception) {
            Pair("", "Wecker fehlgeschlagen: ${e.javaClass.simpleName}")
        }
    }

    /** Timer stellen (system Clock-App). */
    fun setTimer(context: Context, seconds: Int, label: String): Pair<String, String?> {
        return try {
            val i = android.content.Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
                if (label.isNotBlank()) putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            }
            context.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            Pair("Timer ${seconds}s gestellt", null)
        } catch (e: Exception) {
            Pair("", "Timer fehlgeschlagen: ${e.javaClass.simpleName}")
        }
    }
}
