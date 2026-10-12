// Copyright 2026 Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.utils

import android.net.Uri
import androidx.preference.PreferenceManager
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.citra.citra_emu.CitraApplication
import org.json.JSONObject

/** Runs only at the end of game launch prerequisites, never from a permission callback. */
object AutomaticGpuDriver {
    private const val CONFIG_URL = "https://mpgame.lxyong.com/api/app/config?key=gpus_default"
    private const val DRIVER_DIRECTORY = "/storage/emulated/0/rwEmulator/gpu"
    private const val COMPLETED = "automatic_gpu_driver_configured"
    private val configurationMutex = Mutex()
    private val adrenoModel = Regex(
        """\bAdreno\s*(?:\(TM\)\s*)?A?(\d{3})(?!\d)""", RegexOption.IGNORE_CASE
    )

    enum class Phase { IDLE, CHECKING, PERMISSION_REQUIRED, DOWNLOADING, INSTALLING, READY, FAILED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val percent: Int? = null,
        val error: String? = null
    )

    suspend fun configure(update: (State) -> Unit) = withContext(Dispatchers.IO) {
        // Serializes retries and separate launch fragments as well as activity recreation.
        configurationMutex.withLock {
            val context = CitraApplication.appContext
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            if (preferences.getBoolean(COMPLETED, false) ||
                !GpuDriverHelper.supportsCustomDriverLoading()
            ) {
                update(State(Phase.READY))
                return@withLock
            }

            val model = adrenoModel.find(GraphicsUtil.openGLRendererString)?.groupValues?.get(1)
            if (model == null) {
                update(State(Phase.READY))
                return@withLock
            }

            if (!PermissionsHandler.hasNativeFilesystemAccess(context)) {
                update(State(Phase.PERMISSION_REQUIRED))
                return@withLock
            }
            requireStoragePermission()
            check(DirectoryInitialization.areCitraDirectoriesReady()) {
                "User directory setup has not finished"
            }

            val key = if (model.startsWith('6') || model.startsWith('7')) "67" else "default"
            val url = fetchDriverUrl(key)
            currentCoroutineContext().ensureActive()
            val filename = Uri.parse(url.toString()).lastPathSegment
                ?.takeIf {
                    it.isNotBlank() && it != "." && it != ".." &&
                        !it.endsWith(".part", ignoreCase = true) &&
                        it.none { char -> char == '/' || char == '\\' || char.isISOControl() }
                } ?: throw IOException("The GPU driver URL has no valid filename")

            requireStoragePermission()
            val directory = File(DRIVER_DIRECTORY)
            val driver = File(directory, filename)
            // Only the exact selected package is reusable; .part files are never candidates.
            if (!GpuDriverHelper.isCompleteDriverPackage(driver)) {
                download(url, driver, update)
            }

            currentCoroutineContext().ensureActive()
            requireStoragePermission()
            update(State(Phase.INSTALLING))
            // Finish installation and persist its result together even if the view is destroyed.
            withContext(NonCancellable) {
                if (!GpuDriverHelper.setGpuDriver(driver.absolutePath)) {
                    throw IOException("The GPU driver could not be installed and selected")
                }
                requireStoragePermission()
                if (!preferences.edit().putBoolean(COMPLETED, true).commit()) {
                    throw IOException("The GPU driver configuration could not be saved")
                }
            }
            update(State(Phase.READY))
        }
    }

    private fun requireStoragePermission() {
        if (!PermissionsHandler.hasNativeFilesystemAccess(CitraApplication.appContext)) {
            throw SecurityException("All files access is required to configure the GPU driver")
        }
    }

    private fun openConnection(url: URL): HttpURLConnection {
        if (url.protocol != "https" && url.protocol != "http") {
            throw IOException("Unsupported GPU driver URL")
        }
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
            // Keep Content-Length and the bytes written in the same representation.
            setRequestProperty("Accept-Encoding", "identity")
        }
    }

    private fun fetchDriverUrl(key: String): URL {
        requireStoragePermission()
        val connection = openConnection(URL(CONFIG_URL))
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GPU configuration request failed: HTTP ${connection.responseCode}")
            }
            val response = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            if (!response.optBoolean("success") || response.optInt("code") != 200) {
                throw IOException("The GPU configuration service reported a failure")
            }
            val selected = (response.optJSONObject("data")?.opt(key) as? String)?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw IOException("No GPU driver URL is configured for key $key")
            return URL(selected).also {
                if (it.protocol != "https" && it.protocol != "http") {
                    throw IOException("Unsupported GPU driver URL")
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun download(url: URL, driver: File, update: (State) -> Unit) {
        requireStoragePermission()
        val directory = driver.parentFile!!
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("The GPU driver download directory could not be created")
        }
        val connection = openConnection(url)
        val temporary = File.createTempFile("gpu-", ".part", directory)
        try {
            update(State(Phase.DOWNLOADING))
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GPU driver download failed: HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 }
            var downloaded = 0L
            var lastPercent: Int? = null
            update(State(Phase.DOWNLOADING, if (total != null) 0 else null))
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        requireStoragePermission()
                        val count = input.read(buffer)
                        if (count == -1) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        val percent = total?.let {
                            (downloaded.toDouble() * 100 / it).toInt().coerceIn(0, 100)
                        }
                        if (percent != lastPercent) {
                            update(State(Phase.DOWNLOADING, percent))
                            lastPercent = percent
                        }
                    }
                    output.fd.sync()
                }
            }
            currentCoroutineContext().ensureActive()
            requireStoragePermission()
            if (downloaded == 0L || (total != null && downloaded != total) ||
                !GpuDriverHelper.isCompleteDriverPackage(temporary)
            ) {
                throw IOException("The downloaded GPU driver package is incomplete or invalid")
            }
            currentCoroutineContext().ensureActive()
            requireStoragePermission()
            Files.move(
                temporary.toPath(), driver.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            connection.disconnect()
            // If permission was revoked, leave only an unusable .part, never a final driver.
            if (PermissionsHandler.hasNativeFilesystemAccess(CitraApplication.appContext)) {
                temporary.delete()
            }
        }
    }
}
