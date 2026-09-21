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
                "Jarvis",
            )
            if (!dir.exists()) dir.mkdirs()
            val out = java.io.File(dir, fileName.ifBlank { "jarvis_foto_${System.currentTimeMillis()}.jpg" })
            val latch = java.util.concurrent.CountDownLatch(1)
            var failure: String? = null

            val listener = object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
                    try {
                        val reader = android.media.ImageReader.newInstance(
                            jpegStream.width, jpegStream.height, android.graphics.ImageFormat.JPEG, 1)
                        val surface = reader.surface
                        val builder = camera.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE)
                        builder.addTarget(surface)
                        // Sensor-Drehung korrekt ins JPEG schreiben
                        builder.set(android.hardware.camera2.CaptureRequest.JPEG_ORIENTATION, orientation)
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
                                                    val img = reader.acquireLatestImage()
                                                    if (img == null) { failure = "Kein Bild geliefert"; latch.countDown(); return }
                                                    val buffer = img.planes[0].buffer
                                                    val bytes = ByteArray(buffer.remaining())
                                                    buffer.get(bytes)
                                                    img.close()
                                                    java.io.FileOutputStream(out).use { it.write(bytes) }
                                                    reader.close()
                                                    camera.close()
                                                    latch.countDown()
                                                }
                                            }, null)
                                    } catch (e: Exception) {
                                        failure = "Capture fehlgeschlagen: ${e.javaClass.simpleName}"
                                        camera.close(); latch.countDown()
                                    }
                                }
                                override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                                    failure = "Session-Config fehlgeschlagen"
                                    camera.close(); latch.countDown()
                                }
                            }, null)
                    } catch (e: Exception) {
                        failure = "CaptureRequest fehlgeschlagen: ${e.javaClass.simpleName}"
                        camera.close(); latch.countDown()
                    }
                }
                override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) { camera.close() }
                override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) {
                    failure = "Kamera-Fehler (Code $error) - evtl. läuft eine andere Kamera-App"
                    camera.close(); latch.countDown()
                }
            }
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return Pair("", "Kamera-Berechtigung fehlt (CAMERA) - bitte in der App freigeben")
            }
            cm.openCamera(backId, listener, null)
            latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
            if (failure != null) Pair("", failure!!)
            else if (out.exists() && out.length() > 0) Pair(out.absolutePath, null)
            else Pair("", "Timeout beim Foto")
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
            val i = android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                if (label.isNotBlank()) putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            }
            context.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            Pair("Wecker $hour:${minute.toString().padStart(2,'0')} gestellt (${label.ifBlank { "ohne Name" }})", null)
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
