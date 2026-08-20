// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.databinding.ActivityGameInfoBinding
import org.citra.citra_emu.features.cheats.ui.CheatsActivity
import org.citra.citra_emu.fragments.CompressProgressDialogFragment
import org.citra.citra_emu.model.Game
import org.citra.citra_emu.utils.BuildUtil
import org.citra.citra_emu.utils.FileUtil
import org.citra.citra_emu.utils.GameHelper
import org.citra.citra_emu.utils.GameIconUtils
import org.citra.citra_emu.utils.ThemeUtil
import org.citra.citra_emu.viewmodel.CompressProgressDialogViewModel

/** Displays the same metadata shown by the game-list long-press dialog. */
class GameInfoActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGameInfoBinding
    private var pendingCompressionInput: String? = null
    private var shouldCompress = true

    private val compressionLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { outputUri ->
        val inputPath = pendingCompressionInput
        pendingCompressionInput = null
        if (inputPath != null && outputUri != null) {
            compressGame(inputPath, outputUri, shouldCompress)
        }
    }

    companion object {
        private const val TAG = "AzaharGameInfo"
        private const val CHEATS_TITLE_ID_ARGUMENT = "titleId"
        private const val CHEATS_ROM_PATH_ARGUMENT = "romPath"
        private const val DISABLED_BUTTON_ALPHA = 0.38f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.setTheme(this)
        super.onCreate(savedInstanceState)

        binding = ActivityGameInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val thirdPartyRomPath = intent.getStringExtra("filePath")
            ?.takeIf { it.isNotBlank() }
            ?.removePrefix("!")
        val gameUri = intent.data ?: thirdPartyRomPath?.let { Uri.fromFile(File(it)) }
        Log.i(
            TAG,
            "[APILOG][GameInfoActivity] Open request: action=${intent.action}, uri=$gameUri, " +
                "data=${intent.data}, flags=0x${intent.flags.toString(16)}, " +
                "clipUri=${intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri}, " +
                "filePath=$thirdPartyRomPath",
        )
        if (gameUri == null) {
            Log.e(TAG, "Cannot load game information: launch intent has no data URI")
            showLoadError()
            return
        }

        lifecycleScope.launch {
            val game = withContext(Dispatchers.IO) {
                if (isInstallableArchive(gameUri)) {
                    loadInstalledArchiveGame(gameUri) ?: loadGame(gameUri)
                } else {
                    loadGame(gameUri)
                }
            }

            if (game?.valid != true) {
                Log.e(TAG, "Cannot load game information: native parser returned no valid game")
                showLoadError()
                return@launch
            }
            Log.i(TAG, "Game information loaded: titleId=${String.format("%016X", game.titleId)}, title=${game.title}")
            bindGame(
                game,
                if (game.isInstalled) game.filename else getGameFilename(gameUri),
                thirdPartyRomPath ?: resolveRomPathForCheats(game, gameUri)
            )
        }
    }

    private fun isInstallableArchive(gameUri: Uri): Boolean =
        runCatching { FileUtil.getExtension(gameUri) }
            .getOrNull() in setOf("cia", "zcia")

    /** Matches MainActivity's CIA launch lookup: title ID first, then installed Game. */
    private fun loadInstalledArchiveGame(gameUri: Uri): Game? {
        val gameFd = runCatching {
            contentResolver.openFileDescriptor(gameUri, "r")?.detachFd()
        }.onFailure { error ->
            Log.e(TAG, "Unable to open CIA for title lookup: uri=$gameUri", error)
        }.getOrNull() ?: return null

        val titleId = try {
            NativeLibrary.getTitleId("fd://$gameFd")
        } finally {
            ParcelFileDescriptor.adoptFd(gameFd).close()
        }
        if (titleId == 0L) {
            Log.e(TAG, "CIA title lookup returned no title ID: uri=$gameUri")
            return null
        }
        Log.d(TAG, "CIA title lookup result: titleId=${String.format("%016X", titleId)}")

        val installedGame = NativeLibrary.getInstalledGamePaths()
            .asSequence()
            .mapNotNull { installed ->
                runCatching {
                    GameHelper.getGame(
                        Uri.parse(installed.path),
                        isInstalled = true,
                        addedToLibrary = false,
                        mediaType = installed.mediaType,
                    )
                }.onFailure { error ->
                    Log.w(TAG, "Unable to read installed game: path=${installed.path}", error)
                }.getOrNull()
            }
            .firstOrNull { it.valid && it.titleId == titleId }

        if (installedGame == null) {
            Log.e(TAG, "No installed game matches CIA titleId=${String.format("%016X", titleId)}")
        } else {
            Log.i(TAG, "Loaded installed game for CIA: title=${installedGame.title}")
        }
        return installedGame
    }

    private fun loadGame(gameUri: Uri): Game? {
        fun parse(uri: Uri): Game? = runCatching {
            GameHelper.getGame(
                uri,
                isInstalled = false,
                addedToLibrary = false,
                mediaType = Game.MediaType.GAME_CARD,
            ).also { game ->
                Log.d(
                    TAG,
                    "Native parser result: parserUri=$uri, valid=${game.valid}, " +
                        "titleId=${String.format("%016X", game.titleId)}",
                )
            }
        }.onFailure { error ->
            Log.e(TAG, "Native parser failed: parserUri=$uri", error)
        }.getOrNull()

        // Match the external-game startup path: FileProvider/SAF URIs are read
        // through a detached file descriptor, without creating a local copy.
        if (BuildUtil.isGooglePlayBuild || gameUri.toString().startsWith("!")) {
            Log.d(TAG, "Parsing URI directly: uri=$gameUri")
            return parse(gameUri)?.takeIf { it.valid }
        }

        val gameFd = runCatching {
            contentResolver.openFileDescriptor(gameUri, "r")?.detachFd()
        }.onFailure { error ->
            Log.e(TAG, "Unable to open shared game URI: uri=$gameUri", error)
        }.getOrNull() ?: run {
            Log.e(TAG, "Unable to open shared game URI: uri=$gameUri, fileDescriptor=null")
            return null
        }
        Log.d(TAG, "Opened shared game URI with file descriptor: fd=$gameFd")
        return try {
            parse(Uri.parse("fd://$gameFd"))?.takeIf { it.valid }
        } finally {
            Log.d(TAG, "Closing shared game file descriptor: fd=$gameFd")
            ParcelFileDescriptor.adoptFd(gameFd).close()
        }
    }

    private fun getGameFilename(gameUri: Uri): String =
        runCatching { FileUtil.getFilename(gameUri) }
            .getOrDefault(gameUri.lastPathSegment.orEmpty())

    private fun bindGame(game: Game, filename: String, romPath: String) {
        binding.gameInfoContent.visibility = View.VISIBLE
        binding.gameInfoError.visibility = View.GONE

        GameIconUtils.loadGameIcon(this, game, binding.gameIcon)
        binding.gameTitle.text = game.title
        binding.gameCompany.text = game.company
        binding.gameRegion.text = game.regions
        binding.gameId.text = getString(R.string.game_context_id) + " " +
            String.format("%016X", game.titleId)
        binding.gameFilename.text = getString(R.string.game_context_file) + " " + filename
        binding.gameFiletype.text = getString(R.string.game_context_type) + " " + game.fileType
        binding.gamePlaytime.text = getString(R.string.game_information_playtime, formatPlayTime(game.titleId))

        bindActions(game, romPath)
    }

    private fun bindActions(game: Game, romPath: String) {
        binding.gameInfoDelete.setOnClickListener {
            if (!game.isInstalled) {
                Toast.makeText(this, R.string.game_information_delete_unavailable, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            confirmDelete(game)
        }
        binding.gameInfoDelete.alpha = if (game.isInstalled) 1f else DISABLED_BUTTON_ALPHA

        binding.gameInfoCheats.setOnClickListener {
            startActivity(
                Intent(this, CheatsActivity::class.java)
                    .putExtra(CHEATS_TITLE_ID_ARGUMENT, game.titleId)
                    .putExtra(CHEATS_ROM_PATH_ARGUMENT, romPath)
            )
        }

        val canCompress = !game.isInstalled && !game.path.startsWith("fd://")
        binding.gameInfoCompress.text = getString(
            if (game.isCompressed) R.string.decompress else R.string.compress
        )
        binding.gameInfoCompress.alpha = if (canCompress) 1f else DISABLED_BUTTON_ALPHA
        binding.gameInfoCompress.setOnClickListener {
            if (!canCompress) {
                val message = if (game.isInstalled) {
                    R.string.compress_decompress_installed_app
                } else {
                    R.string.game_information_compress_unavailable
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            shouldCompress = !game.isCompressed
            val extension = NativeLibrary.getRecommendedExtension(game.path, shouldCompress)
            val basename = game.filename.substringBeforeLast('.')
            pendingCompressionInput = game.path
            compressionLauncher.launch("$basename.$extension")
        }

        binding.gameInfoDeleteShaderCache.setOnClickListener {
            deleteShaderCache(game.titleId)
        }
    }

    private fun resolveRomPathForCheats(game: Game, sourceUri: Uri): String {
        if (sourceUri.authority == "com.gzhuaiyun.retro.provider") {
            val externalPath = sourceUri.path?.removePrefix("/external/")
            if (externalPath != null && externalPath != sourceUri.path) {
                return "/storage/emulated/0/$externalPath"
            }
        }
        if (game.isInstalled) {
            return game.path.removePrefix("!")
        }
        if (sourceUri.scheme == null || sourceUri.scheme == "file") {
            return (sourceUri.path ?: sourceUri.toString()).removePrefix("!")
        }
        if (BuildUtil.isGooglePlayBuild) {
            return game.path.removePrefix("!")
        }
        return runCatching { NativeLibrary.getNativePath(sourceUri) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: game.path.removePrefix("!")
    }

    private fun confirmDelete(game: Game) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage(R.string.game_information_delete_confirmation)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteInstalledGame(game) }
            .show()
    }

    private fun deleteInstalledGame(game: Game) {
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.uninstalling)
            .setView(R.layout.dialog_progress_bar)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                NativeLibrary.uninstallTitle(game.titleId, game.mediaType)
            }
            progressDialog.dismiss()
            if (deleted) {
                Toast.makeText(this@GameInfoActivity, R.string.game_information_deleted, Toast.LENGTH_SHORT)
                    .show()
                finish()
            } else {
                Toast.makeText(this@GameInfoActivity, R.string.game_information_delete_failed, Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    private fun compressGame(inputPath: String, outputUri: Uri, compress: Boolean) {
        val outputPath = if (BuildUtil.isGooglePlayBuild) {
            outputUri.toString()
        } else {
            "!" + NativeLibrary.getNativePath(outputUri)
        }
        CompressProgressDialogViewModel.reset()
        val progressDialog = CompressProgressDialogFragment.newInstance(compress, outputPath)
        progressDialog.showNow(supportFragmentManager, CompressProgressDialogFragment.TAG)

        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                if (compress) {
                    NativeLibrary.compressFile(inputPath, outputPath)
                } else {
                    NativeLibrary.decompressFile(inputPath, outputPath)
                }
            }
            progressDialog.dismiss()
            MaterialAlertDialogBuilder(this@GameInfoActivity)
                .setMessage(getString(compressionResultMessage(status, compress)))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun compressionResultMessage(
        status: NativeLibrary.CompressStatus,
        compress: Boolean,
    ): Int = when (status) {
        NativeLibrary.CompressStatus.SUCCESS -> if (compress) R.string.compress_success else R.string.decompress_success
        NativeLibrary.CompressStatus.COMPRESS_UNSUPPORTED -> R.string.compress_unsupported
        NativeLibrary.CompressStatus.COMPRESS_ALREADY_COMPRESSED -> R.string.compress_already
        NativeLibrary.CompressStatus.COMPRESS_FAILED -> R.string.compress_failed
        NativeLibrary.CompressStatus.DECOMPRESS_UNSUPPORTED -> R.string.decompress_unsupported
        NativeLibrary.CompressStatus.DECOMPRESS_NOT_COMPRESSED -> R.string.decompress_not_compressed
        NativeLibrary.CompressStatus.DECOMPRESS_FAILED -> R.string.decompress_failed
        NativeLibrary.CompressStatus.INSTALLED_APPLICATION -> R.string.compress_decompress_installed_app
    }

    private fun deleteShaderCache(titleId: Long) {
        val options = arrayOf(getString(R.string.vulkan), getString(R.string.opengles))
        var selectedIndex = -1
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_cache_select_backend)
            .setSingleChoiceItems(options, -1) { _, which -> selectedIndex = which }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val toast = Toast.makeText(this, R.string.deleting_shader_cache, Toast.LENGTH_LONG)
                toast.show()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        when (selectedIndex) {
                            0 -> NativeLibrary.deleteVulkanShaderCache(titleId)
                            1 -> NativeLibrary.deleteOpenGLShaderCache(titleId)
                        }
                    }
                    toast.cancel()
                    Toast.makeText(this@GameInfoActivity, R.string.shader_cache_deleted, Toast.LENGTH_SHORT)
                        .show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            positive.isEnabled = false
            dialog.listView.setOnItemClickListener { _, _, position, _ ->
                selectedIndex = position
                positive.isEnabled = true
            }
        }
        dialog.show()
    }

    private fun formatPlayTime(titleId: Long): String {
        val playTimeSeconds = NativeLibrary.playTimeManagerGetPlayTime(titleId)
        val hours = playTimeSeconds / 3600
        val minutes = (playTimeSeconds % 3600) / 60
        val seconds = playTimeSeconds % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    private fun showLoadError() {
        binding.gameInfoContent.visibility = View.GONE
        binding.gameInfoError.visibility = View.VISIBLE
    }
}
