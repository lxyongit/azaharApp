// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.utils

import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.IllegalStateException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.utils.FileUtil.inputStream

object GpuDriverHelper {
    private const val META_JSON_FILENAME = "meta.json"
    private var fileRedirectionPath: String? = null
    var driverInstallationPath: String? = null
    private var hookLibPath: String? = null

    val driverStoragePath: DocumentFile
        get() {
            // Bypass directory initialization checks
            val userPath = DirectoryInitialization.userPath
                ?: throw IllegalStateException("User directory is not initialized")
            val root = if (FileUtil.isNativePath(userPath)) {
                val rootFile = File(userPath)
                if (!rootFile.exists()) {
                    rootFile.mkdirs()
                }
                DocumentFile.fromFile(rootFile)
            } else {
                DocumentFile.fromTreeUri(
                    CitraApplication.appContext,
                    Uri.parse(userPath)
                )
            } ?: throw IllegalStateException("User directory is invalid")
            var driverDirectory = root.findFile("gpu_drivers")
            if (driverDirectory == null) {
                driverDirectory = FileUtil.createDir(root.uri.toString(), "gpu_drivers")
            }
            return driverDirectory!!
        }

    fun initializeDriverParameters(): Boolean {
        try {
            // Initialize the file redirection directory.
            fileRedirectionPath =
                DirectoryInitialization.internalUserPath + "/gpu/vk_file_redirect/"

            // Initialize the driver installation directory.
            driverInstallationPath = CitraApplication.appContext
                .filesDir.canonicalPath + "/gpu_driver/"
        } catch (e: IOException) {
            throw RuntimeException(e)
        }

        // Initialize directories.
        initializeDirectories()

        // Initialize hook libraries directory.
        hookLibPath = CitraApplication.appContext.applicationInfo.nativeLibraryDir + "/"

        // Initialize GPU driver.
        return NativeLibrary.initializeGpuDriver(
            hookLibPath,
            driverInstallationPath,
            customDriverData.libraryName,
            fileRedirectionPath
        )
    }

    fun getDrivers(): MutableList<Pair<Uri, GpuDriverMetadata>> {
        val driverZips = driverStoragePath.listFiles()
        val drivers: MutableList<Pair<Uri, GpuDriverMetadata>> =
            driverZips
                .filter { it.isFile }
                .mapNotNull {
                    val metadata = getMetadataFromZip(it.inputStream())
                    metadata.name?.let { _ -> Pair(it.uri, metadata) }
                }
                .sortedByDescending { it: Pair<Uri, GpuDriverMetadata> -> it.second.name }
                .distinct()
                .toMutableList()

        // TODO: Get system driver information
        drivers.add(0, Pair(Uri.EMPTY, GpuDriverMetadata()))
        return drivers
    }

    @Synchronized
    fun installDefaultDriver() {
        // Removing the installed driver will result in the backend using the default system driver.
        File(driverInstallationPath!!).deleteRecursively()
        initializeDriverParameters()
    }

    fun copyDriverToExternalStorage(driverUri: Uri): DocumentFile? {
        // Ensure we have directories.
        initializeDirectories()

        // Copy the zip file URI to user data
        val copiedFile =
            FileUtil.copyToExternalStorage(driverUri, driverStoragePath) ?: return null

        // Validate driver
        val metadata = getMetadataFromZip(copiedFile.inputStream())
        if (metadata.name == null) {
            Log.error("GPU driver package has invalid metadata: $driverUri")
            copiedFile.delete()
            return null
        }

        if (metadata.minApi > Build.VERSION.SDK_INT) {
            Log.error("GPU driver requires API ${metadata.minApi}, device API is ${Build.VERSION.SDK_INT}")
            copiedFile.delete()
            return null
        }
        return copiedFile
    }

    /**
     * Shared entry point for the external driver_path (setGPU) action and automatic setup.
     * A local absolute path is installed, not saved as a URL preference.
     */
    fun setGpuDriver(driverPath: String): Boolean {
        val driver = File(driverPath)
        if (!driver.isAbsolute || !PermissionsHandler.hasNativeFilesystemAccess(
                CitraApplication.appContext
            )
        ) return false
        return installCustomDriverComplete(Uri.fromFile(driver))
    }

    /** Checks ZIP structure and entry lengths without computing a checksum. */
    fun isCompleteDriverPackage(driver: File): Boolean {
        return try {
            if (!driver.isFile || driver.length() == 0L) {
                false
            } else {
                ZipFile(driver).use { zip ->
                    val metaEntry = zip.getEntry(META_JSON_FILENAME) ?: return false
                    val metadata = zip.getInputStream(metaEntry).use {
                        GpuDriverMetadata(it, metaEntry.size)
                    }
                    val libraryName = metadata.libraryName?.takeIf { it.isNotBlank() }
                        ?: return false
                    if (metadata.name.isNullOrBlank() ||
                        zip.getEntry(libraryName)?.isDirectory != false
                    ) return false

                    // A ZIP central directory alone does not guarantee all data is present.
                    val buffer = ByteArray(64 * 1024)
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        var size = 0L
                        zip.getInputStream(entry).use { input ->
                            while (true) {
                                val count = input.read(buffer)
                                if (count == -1) break
                                size += count
                            }
                        }
                        if (size != entry.size || (entry.name == libraryName && size == 0L)) {
                            return false
                        }
                    }
                    true
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Copies driver zip into user data directory so that it can be exported along with
     * other user data and also unzipped into the installation directory
     */
    @Synchronized
    fun installCustomDriverComplete(driverUri: Uri): Boolean {
        val copiedFile = copyDriverToExternalStorage(driverUri) ?: run {
            Log.error("GPU driver package could not be copied or validated: $driverUri")
            return false
        }
        val installed = installCustomDriverPartial(copiedFile.uri)
        if (!installed) copiedFile.delete()
        return installed
    }

    /**
     * Unzips driver into private installation directory
     */
    @Synchronized
    fun installCustomDriverPartial(driver: Uri): Boolean {
        initializeDirectories()
        val parent = CitraApplication.appContext.filesDir
        val staging = Files.createTempDirectory(parent.toPath(), "gpu-driver-").toFile()
        val packageFile = File(staging, "package.zip")
        val extracted = File(staging, "installed")
        val installed = File(driverInstallationPath!!)
        val backup = File(staging, "previous")
        var replaced = false
        var keepBackup = false
        try {
            driver.inputStream().use { input ->
                packageFile.outputStream().use { input.copyTo(it) }
            }
            if (!isCompleteDriverPackage(packageFile)) {
                throw IOException("GPU driver ZIP is incomplete or has invalid metadata/library entries")
            }
            extracted.mkdirs()
            FileUtil.unzipToInternalStorage(
                BufferedInputStream(packageFile.inputStream()), extracted
            )
            val metadata = GpuDriverMetadata(File(extracted, META_JSON_FILENAME))
            val libraryName = metadata.libraryName?.takeIf { it.isNotBlank() }
                ?: throw IOException("GPU driver metadata is missing libraryName")
            val library = File(extracted, libraryName)
            if (metadata.name.isNullOrBlank() || metadata.minApi > Build.VERSION.SDK_INT ||
                !library.canonicalPath.startsWith(extracted.canonicalPath + File.separator) ||
                !library.isFile || library.length() == 0L
            ) throw IOException("GPU driver metadata, library path, or Android API requirement is invalid")

            // Select only a fully extracted package, keeping the previous selection on failure.
            if (!installed.renameTo(backup)) {
                throw IOException("Could not back up the current GPU driver before installation")
            }
            replaced = true
            if (!extracted.renameTo(installed)) throw IOException("Could not select GPU driver")
            if (!initializeDriverParameters()) {
                throw IOException("The selected GPU driver could not be loaded")
            }
            return true
        } catch (e: Exception) {
            Log.error("GPU driver installation failed: ${e.message}")
            if (replaced) {
                installed.deleteRecursively()
                if (backup.renameTo(installed)) {
                    initializeDriverParameters()
                } else {
                    keepBackup = true
                    Log.error("Could not restore previous GPU driver at ${backup.absolutePath}")
                }
            }
            return false
        } finally {
            if (!keepBackup) staging.deleteRecursively()
        }
    }

    /**
     * Takes in a zip file and reads the meta.json file for presentation to the UI
     *
     * @param driver Zip containing driver and meta.json file
     * @return A non-null [GpuDriverMetadata] instance that may have null members
     */
    fun getMetadataFromZip(driver: InputStream): GpuDriverMetadata {
        try {
            ZipInputStream(driver).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.lowercase().contains(".json")) {
                        val size = if (entry.size == -1L) 0L else entry.size
                        return GpuDriverMetadata(zis, size)
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (_: ZipException) {
        }
        return GpuDriverMetadata()
    }

    external fun supportsCustomDriverLoading(): Boolean

    // Parse the custom driver metadata to retrieve the name.
    val customDriverData: GpuDriverMetadata
        get() = GpuDriverMetadata(File(driverInstallationPath + META_JSON_FILENAME))

    fun initializeDirectories() {
        // Ensure the file redirection directory exists.
        val fileRedirectionDir = File(fileRedirectionPath!!)
        if (!fileRedirectionDir.exists()) {
            fileRedirectionDir.mkdirs()
        }
        // Ensure the driver installation directory exists.
        val driverInstallationDir = File(driverInstallationPath!!)
        if (!driverInstallationDir.exists()) {
            driverInstallationDir.mkdirs()
        }
        // Ensure the driver storage directory exists
        if (!driverStoragePath.exists()) {
            throw IllegalStateException("Driver storage directory couldn't be created!")
        }
    }
}
