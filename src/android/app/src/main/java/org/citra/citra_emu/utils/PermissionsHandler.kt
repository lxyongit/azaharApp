// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import org.citra.citra_emu.CitraApplication
import java.io.File

object PermissionsHandler {
    const val CITRA_DIRECTORY = "CITRA_DIRECTORY"
    val preferences: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)

    fun hasWriteAccess(context: Context): Boolean {
        try {
            val directoryString = preferences.getString(CITRA_DIRECTORY, "").orEmpty()
            if (directoryString.isEmpty()) {
                return false
            }

            if (FileUtil.isNativePath(directoryString)) {
                if (!hasNativeFilesystemAccess(context)) {
                    return false
                }
                val directory = File(directoryString)
                return directory.exists() && directory.isDirectory
            }

            val uri = citraDirectory
            val takeFlags =
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            val root = DocumentFile.fromTreeUri(context, uri)
            if (root != null && root.exists()) {
                return true
            }

            context.contentResolver.releasePersistableUriPermission(uri, takeFlags)
        } catch (e: Exception) {
            // Do not use native library logging, as the native library may not be loaded yet
            android.util.Log.e(
                "PermissionsHandler",
                "Cannot check citra data directory permission, error: ${e.message}"
            )
        }
        return false
    }

    val citraDirectory: Uri
        get() {
            val directoryString = preferences.getString(CITRA_DIRECTORY, "")
            return Uri.parse(directoryString)
        }

    fun setCitraDirectory(uriString: String?) =
        preferences.edit().putString(CITRA_DIRECTORY, uriString).apply()

    fun compatibleSelectDirectory(activityLauncher: ActivityResultLauncher<Uri?>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activityLauncher.launch(null)
        } else {
            val initialUri = DocumentsContract.buildRootUri(
                "com.android.externalstorage.documents",
                "primary"
            )
            activityLauncher.launch(initialUri)
        }
    }

    private fun hasNativeFilesystemAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
