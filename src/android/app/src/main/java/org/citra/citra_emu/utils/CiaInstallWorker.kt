// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.util.UUID
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.NativeLibrary.InstallStatus
import org.citra.citra_emu.R
import org.citra.citra_emu.utils.FileUtil.getFilename

class CiaInstallWorker(val context: Context, params: WorkerParameters) : Worker(context, params) {
    companion object {
        const val PROGRESS_CURRENT_FILE_NAME = "CIA_PROGRESS_CURRENT_FILE_NAME"
        const val PROGRESS_CURRENT_FILE_INDEX = "CIA_PROGRESS_CURRENT_FILE_INDEX"
        const val PROGRESS_TOTAL_FILES = "CIA_PROGRESS_TOTAL_FILES"
        const val PROGRESS_MAX = "CIA_PROGRESS_MAX"
        const val PROGRESS_VALUE = "CIA_PROGRESS_VALUE"
    }

    private val groupKeyCiaInstallStatus = "org.citra.citra_emu.CIA_INSTALL_STATUS"
    private var lastNotifiedTime: Long = 0
    private val summaryNotificationId = 0xC1A0000
    private val progressNotificationId = summaryNotificationId + 1
    private var statusNotificationId = summaryNotificationId + 2
    private var currentFilename = ""
    private var currentFileIndex = 0
    private var totalFiles = 0

    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val installProgressBuilder = NotificationCompat.Builder(
        context,
        context.getString(R.string.cia_install_notification_channel_id)
    )
        .setContentTitle(context.getString(R.string.install_cia_title))
        .setSmallIcon(R.drawable.ic_stat_notification_logo)
    private val installStatusBuilder = NotificationCompat.Builder(
        context,
        context.getString(R.string.cia_install_notification_channel_id)
    )
        .setContentTitle(context.getString(R.string.install_cia_title))
        .setSmallIcon(R.drawable.ic_stat_notification_logo)
        .setGroup(groupKeyCiaInstallStatus)
    private val summaryNotification = NotificationCompat.Builder(
        context,
        context.getString(R.string.cia_install_notification_channel_id)
    )
        .setContentTitle(context.getString(R.string.install_cia_title))
        .setSmallIcon(R.drawable.ic_stat_notification_logo)
        .setGroup(groupKeyCiaInstallStatus)
        .setGroupSummary(true)
        .build()

    private fun notifyInstallStatus(filename: String, status: InstallStatus) {
        when (status) {
            InstallStatus.Success -> {
                installStatusBuilder.setContentTitle(
                    context.getString(R.string.cia_install_notification_success_title)
                )
                installStatusBuilder.setContentText(
                    context.getString(R.string.cia_install_success, filename)
                )
            }

            InstallStatus.ErrorAborted -> {
                installStatusBuilder.setContentTitle(
                    context.getString(R.string.cia_install_notification_error_title)
                )
                installStatusBuilder.setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(context.getString(R.string.cia_install_error_aborted, filename))
                )
            }

            InstallStatus.ErrorInvalid -> {
                installStatusBuilder.setContentTitle(
                    context.getString(R.string.cia_install_notification_error_title)
                )
                installStatusBuilder.setContentText(
                    context.getString(R.string.cia_install_error_invalid, filename)
                )
            }

            InstallStatus.ErrorEncrypted -> {
                installStatusBuilder.setContentTitle(
                    context.getString(R.string.cia_install_notification_error_title)
                )
                installStatusBuilder.setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(context.getString(R.string.cia_install_error_encrypted, filename))
                )
            }

            InstallStatus.ErrorFailedToOpenFile, InstallStatus.ErrorFileNotFound -> {
                installStatusBuilder.setContentTitle(
                    context.getString(R.string.cia_install_notification_error_title)
                )
                installStatusBuilder.setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(context.getString(R.string.cia_install_error_unknown, filename))
                )
            }

            else -> {
                installStatusBuilder.setContentTitle(
                    context.getString(R.string.cia_install_notification_error_title)
                )
                installStatusBuilder.setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(context.getString(R.string.cia_install_error_unknown, filename))
                )
            }
        }

        // Even if newer versions of Android don't show the group summary text that you design,
        // you always need to manually set a summary to enable grouped notifications.
        notificationManager.notify(summaryNotificationId, summaryNotification)
        notificationManager.notify(statusNotificationId++, installStatusBuilder.build())
    }

    override fun doWork(): Result {
        val selectedFiles = inputData.getStringArray("CIA_FILES")!!
        totalFiles = selectedFiles.size
        val toastText: CharSequence = context.resources.getQuantityString(
            R.plurals.cia_install_toast,
            selectedFiles.size,
            selectedFiles.size
        )
        context.mainExecutor.execute {
            Toast.makeText(context, toastText, Toast.LENGTH_LONG).show()
        }

        // Issue the initial notification with zero progress
        installProgressBuilder.setOngoing(true)
        updateProgressData(0, 0)
        setProgressCallback(100, 0)
        selectedFiles.forEachIndexed { i, file ->
            val filename = getFilename(file.toUri())
            currentFilename = filename
            currentFileIndex = i + 1
            installProgressBuilder.setContentText(
                context.getString(
                    R.string.cia_install_notification_installing,
                    filename,
                    i + 1,
                    selectedFiles.size
                )
            )
            updateProgressData(100, 0)
            val preparedInstallFile = prepareInstallFile(file)
            val res = installCIA(preparedInstallFile.path)
            updateProgressData(100, 100)
            preparedInstallFile.cleanup()
            notifyInstallStatus(filename, res)
        }
        notificationManager.cancel(progressNotificationId)
        return Result.success()
    }

    fun setProgressCallback(max: Int, progress: Int) {
        updateProgressData(max, progress)

        val currentTime = System.currentTimeMillis()
        // Android applies a rate limit when updating a notification.
        // If you post updates to a single notification too frequently,
        // such as many in less than one second, the system might drop updates.
        // TODO: consider moving to C++ side
        if (currentTime - lastNotifiedTime < 500 /* ms */) {
            return
        }
        lastNotifiedTime = currentTime
        installProgressBuilder.setProgress(max, progress, false)
        notificationManager.notify(progressNotificationId, installProgressBuilder.build())
    }

    private fun updateProgressData(max: Int, progress: Int) {
        setProgressAsync(
            Data.Builder()
                .putString(PROGRESS_CURRENT_FILE_NAME, currentFilename)
                .putInt(PROGRESS_CURRENT_FILE_INDEX, currentFileIndex)
                .putInt(PROGRESS_TOTAL_FILES, totalFiles)
                .putInt(PROGRESS_MAX, max)
                .putInt(PROGRESS_VALUE, progress)
                .build()
        )
    }

    override fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(progressNotificationId, installProgressBuilder.build())

    private fun prepareInstallFile(file: String): PreparedInstallFile {
        if (BuildUtil.isGooglePlayBuild) {
            return PreparedInstallFile(file)
        }

        val fileUri = file.toUri()
        val nativePath = runCatching { NativeLibrary.getNativePath(fileUri) }.getOrNull()
        if (!nativePath.isNullOrBlank()) {
            return PreparedInstallFile("!$nativePath")
        }

        val tempFile = copyUriToCache(fileUri)
        return PreparedInstallFile("!${tempFile.absolutePath}") {
            tempFile.delete()
        }
    }

    private fun copyUriToCache(fileUri: Uri): File {
        val filename = getFilename(fileUri).takeIf { it.isNotBlank() }
            ?: "${UUID.randomUUID()}.cia"
        val tempDir = File(context.cacheDir, "external-cia-installs")
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            error("Failed to create temporary CIA install directory")
        }

        val tempFile = File(tempDir, "${UUID.randomUUID()}-$filename")
        val copied = FileUtil.copyUriToInternalStorage(fileUri, tempDir.absolutePath, tempFile.name)
        if (!copied) {
            error("Failed to copy CIA from external Uri to cache")
        }
        return tempFile
    }

    private data class PreparedInstallFile(
        val path: String,
        val cleanupAction: (() -> Unit)? = null
    ) {
        fun cleanup() {
            cleanupAction?.invoke()
        }
    }

    private external fun installCIA(path: String): InstallStatus
}
