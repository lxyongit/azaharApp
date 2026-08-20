// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.utils.PermissionsHandler.hasWriteAccess

/**
 * A service that spawns its own thread in order to copy several binary and shader files
 * from the Citra APK to the external file system.
 */
object DirectoryInitialization {
    private const val SYS_DIR_VERSION = "sysDirectoryVersion"
    private const val AES_KEYS_ASSET = "aes_keys.txt"
    private const val AES_KEYS_FILENAME = "aes_keys.txt"
    private const val ANDROID_LOG_TAG = "AzaharInit"

    @Volatile
    private var directoryState: DirectoryInitializationState? = null
    var userPath: String? = null
    val internalUserPath: String
        get() = CitraApplication.appContext.filesDir.canonicalPath
    private val isCitraDirectoryInitializationRunning = AtomicBoolean(false)

    val context: Context get() = CitraApplication.appContext

    @JvmStatic
    fun start(): DirectoryInitializationState? {
        if (!isCitraDirectoryInitializationRunning.compareAndSet(false, true)) {
            return null
        }

        if (directoryState != DirectoryInitializationState.CITRA_DIRECTORIES_INITIALIZED) {
            directoryState = if (hasWriteAccess(context)) {
                if (setCitraUserDirectory()) {
                    CitraApplication.documentsTree.setRoot(Uri.parse(userPath))
                    ensureBundledAesKeys()
                    NativeLibrary.createLogFile()
                    NativeLibrary.logUserDirectory(userPath.toString())
                    NativeLibrary.createConfigFile()
                    GpuDriverHelper.initializeDriverParameters()
                    DirectoryInitializationState.CITRA_DIRECTORIES_INITIALIZED
                } else {
                    DirectoryInitializationState.CANT_FIND_EXTERNAL_STORAGE
                }
            } else {
                DirectoryInitializationState.EXTERNAL_STORAGE_PERMISSION_NEEDED
            }
        }
        isCitraDirectoryInitializationRunning.set(false)
        return directoryState
    }

    private fun deleteDirectoryRecursively(file: File) {
        if (file.isDirectory) {
            for (child in file.listFiles()!!) {
                deleteDirectoryRecursively(child)
            }
        }
        file.delete()
    }

    @JvmStatic
    fun areCitraDirectoriesReady(): Boolean =
        directoryState == DirectoryInitializationState.CITRA_DIRECTORIES_INITIALIZED

    fun resetCitraDirectoryState() {
        directoryState = null
        isCitraDirectoryInitializationRunning.compareAndSet(true, false)
    }

    val userDirectory: String?
        get() {
            checkNotNull(directoryState) {
                "DirectoryInitialization has to run at least once!"
            }
            check(!isCitraDirectoryInitializationRunning.get()) {
                "DirectoryInitialization has to finish running first!"
            }
            return userPath
        }

    fun setCitraUserDirectory(): Boolean {
        val dataPath = PermissionsHandler.citraDirectory
        if (dataPath.toString().isNotEmpty()) {
            userPath = dataPath.toString()
            android.util.Log.d("[Azahar Frontend]", "[DirectoryInitialization] User Dir: $userPath")
            return true
        }
        return false
    }

    /**
     * Installs the bundled AES key file on first run. A file supplied by the user always wins and
     * is never replaced.
     */
    private fun ensureBundledAesKeys() {
        val path = userPath ?: return
        try {
            if (FileUtil.isNativePath(path)) {
                val sysdataDirectory = File(path, "sysdata")
                if (!sysdataDirectory.exists() && !sysdataDirectory.mkdirs()) {
                    android.util.Log.e(ANDROID_LOG_TAG, "Failed to create sysdata directory")
                    return
                }

                val destination = File(sysdataDirectory, AES_KEYS_FILENAME)
                if (destination.exists()) {
                    android.util.Log.d(ANDROID_LOG_TAG, "Keeping existing sysdata/aes_keys.txt")
                    return
                }

                context.assets.open(AES_KEYS_ASSET).use { input ->
                    FileOutputStream(destination).use { output -> copyFile(input, output) }
                }
            } else {
                val root = DocumentFile.fromTreeUri(context, Uri.parse(path))
                if (root == null) {
                    android.util.Log.e(ANDROID_LOG_TAG, "Failed to access user directory")
                    return
                }
                val sysdataDirectory = root.findFile("sysdata") ?: root.createDirectory("sysdata")
                if (sysdataDirectory == null || !sysdataDirectory.isDirectory) {
                    android.util.Log.e(ANDROID_LOG_TAG, "Failed to create sysdata directory")
                    return
                }
                if (sysdataDirectory.findFile(AES_KEYS_FILENAME) != null) {
                    android.util.Log.d(ANDROID_LOG_TAG, "Keeping existing sysdata/aes_keys.txt")
                    return
                }

                val destination =
                    sysdataDirectory.createFile(FileUtil.TEXT_PLAIN, AES_KEYS_FILENAME) ?: run {
                        android.util.Log.e(ANDROID_LOG_TAG, "Failed to create sysdata/aes_keys.txt")
                        return
                    }
                val output = context.contentResolver.openOutputStream(destination.uri, "wt") ?: run {
                    android.util.Log.e(ANDROID_LOG_TAG, "Failed to open sysdata/aes_keys.txt")
                    return
                }
                context.assets.open(AES_KEYS_ASSET).use { input ->
                    output.use { copyFile(input, it) }
                }
            }
            android.util.Log.i(ANDROID_LOG_TAG, "Installed bundled sysdata/aes_keys.txt")
        } catch (e: Exception) {
            android.util.Log.e(ANDROID_LOG_TAG, "Failed to install bundled aes_keys.txt", e)
        }
    }

    private fun copyAsset(asset: String, output: File, overwrite: Boolean, context: Context) {
        Log.debug("[DirectoryInitialization] Copying File $asset to $output")
        try {
            if (!output.exists() || overwrite) {
                val inputStream = context.assets.open(asset)
                val outputStream = FileOutputStream(output)
                copyFile(inputStream, outputStream)
                inputStream.close()
                outputStream.close()
            }
        } catch (e: IOException) {
            Log.error("[DirectoryInitialization] Failed to copy asset file: $asset" + e.message)
        }
    }

    private fun copyAssetFolder(
        assetFolder: String,
        outputFolder: File,
        overwrite: Boolean,
        context: Context
    ) {
        Log.debug("[DirectoryInitialization] Copying Folder $assetFolder to $outputFolder")
        try {
            var createdFolder = false
            for (file in context.assets.list(assetFolder)!!) {
                if (!createdFolder) {
                    outputFolder.mkdir()
                    createdFolder = true
                }
                copyAssetFolder(
                    assetFolder + File.separator + file,
                    File(outputFolder, file),
                    overwrite,
                    context
                )
                copyAsset(
                    assetFolder + File.separator + file,
                    File(outputFolder, file),
                    overwrite,
                    context
                )
            }
        } catch (e: IOException) {
            Log.error(
                "[DirectoryInitialization] Failed to copy asset folder: $assetFolder" +
                    e.message
            )
        }
    }

    @Throws(IOException::class)
    private fun copyFile(inputStream: InputStream, outputStream: OutputStream) {
        val buffer = ByteArray(1024)
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) {
            outputStream.write(buffer, 0, read)
        }
    }

    enum class DirectoryInitializationState {
        CITRA_DIRECTORIES_INITIALIZED,
        EXTERNAL_STORAGE_PERMISSION_NEEDED,
        CANT_FIND_EXTERNAL_STORAGE
    }
}
