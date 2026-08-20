// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.ui.main

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import androidx.preference.PreferenceManager
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.navigation.NavigationBarView
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.citra.citra_emu.BuildConfig
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.activities.EmulationActivity
import org.citra.citra_emu.contracts.OpenFileResultContract
import org.citra.citra_emu.databinding.ActivityMainBinding
import org.citra.citra_emu.dialogs.NetPlayDialog
import org.citra.citra_emu.features.settings.model.Settings
import org.citra.citra_emu.features.settings.model.SettingsViewModel
import org.citra.citra_emu.features.settings.ui.SettingsActivity
import org.citra.citra_emu.features.settings.utils.SettingsFile
import org.citra.citra_emu.fragments.GrantMissingFilesystemPermissionFragment
import org.citra.citra_emu.fragments.CiaInstallProgressDialogFragment
import org.citra.citra_emu.fragments.MessageDialogFragment
import org.citra.citra_emu.fragments.SelectUserDirectoryDialogFragment
import org.citra.citra_emu.fragments.UpdateUserDirectoryDialogFragment
import org.citra.citra_emu.model.Game
import org.citra.citra_emu.utils.BuildUtil
import org.citra.citra_emu.utils.CiaInstallWorker
import org.citra.citra_emu.utils.CitraDirectoryHelper
import org.citra.citra_emu.utils.CitraDirectoryUtils
import org.citra.citra_emu.utils.DirectoryInitialization
import org.citra.citra_emu.utils.FileBrowserHelper
import org.citra.citra_emu.utils.FileUtil
import org.citra.citra_emu.utils.GameHelper
import org.citra.citra_emu.utils.GpuDriverHelper
import org.citra.citra_emu.utils.InsetsHelper
import org.citra.citra_emu.utils.Log
import org.citra.citra_emu.utils.PermissionsHandler
import org.citra.citra_emu.utils.RefreshRateUtil
import org.citra.citra_emu.utils.ThemeUtil
import org.citra.citra_emu.viewmodel.GamesViewModel
import org.citra.citra_emu.viewmodel.HomeViewModel
import java.io.File
import java.util.UUID

class MainActivity :
    AppCompatActivity(),
    ThemeProvider {
    private lateinit var binding: ActivityMainBinding

    private val homeViewModel: HomeViewModel by viewModels()
    private val gamesViewModel: GamesViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private var hasForwardedPendingExternalGame = false
    private var hasHandledPendingGpuDriverManager = false
    private var hasHandledPendingSettings = false
    private var hasHandledPendingExternalGameDeletion = false

    override var themeId: Int = 0

    companion object {
        const val KEY_SETUP_CURRENT_PAGE = "SetupCurrentPage"
        private const val INSTALL_CIA_WORK_NAME = "installCiaWork"
        private const val ACTION_OPEN_SETTINGS =
            "com.gzhuaiyun.retro.action.OPEN_AZAHAR_SETTINGS"
        private const val ACTION_OPEN_GPU_DRIVER_MANAGER =
            "com.gzhuaiyun.retro.action.OPEN_AZAHAR_GPU_DRIVER_MANAGER"
        private const val ACTION_DELETE_EXTERNAL_GAME =
            "com.gzhuaiyun.retro.action.DELETE_AZAHAR_GAME"
        private const val EXTRA_MENU_TAG = "menu_tag"
        private const val EXTRA_GAME_ID = "game_id"
        private const val EXTRA_DRIVER_PATH = "driver_path"
        private const val EXTRA_DLC_URIS = "com.gzhuaiyun.retro.extra.AZAHAR_DLC_URIS"
        private const val EXTRA_EXTERNAL_LAUNCH_DATA = "externalLaunchData"
        private const val EXTRA_DELETE_COMPLETED =
            "com.gzhuaiyun.retro.extra.AZAHAR_DELETE_COMPLETED"
    }

    fun shouldFinishAfterDriverManagerBack(): Boolean {
        return intent?.action == ACTION_OPEN_GPU_DRIVER_MANAGER
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        RefreshRateUtil.enforceRefreshRate(this)

        val splashScreen = installSplashScreen()
        CitraDirectoryUtils.attemptAutomaticUpdateDirectory()
        splashScreen.setKeepOnScreenCondition {
            !DirectoryInitialization.areCitraDirectoriesReady() &&
                PermissionsHandler.hasWriteAccess(this) &&
                !CitraDirectoryUtils.needToUpdateManually()
        }

        if (PermissionsHandler.hasWriteAccess(applicationContext) &&
            DirectoryInitialization.areCitraDirectoriesReady() &&
            !CitraDirectoryUtils.needToUpdateManually()
        ) {
            settingsViewModel.settings.loadSettings()
        }

        ThemeUtil.themeChangeListener(this)
        ThemeUtil.setTheme(this)
        super.onCreate(savedInstanceState)
        NativeLibrary.initMultiplayer()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        window.statusBarColor =
            ContextCompat.getColor(applicationContext, android.R.color.transparent)
        window.navigationBarColor =
            ContextCompat.getColor(applicationContext, android.R.color.transparent)

        binding.statusBarShade.setBackgroundColor(
            ThemeUtil.getColorWithOpacity(
                MaterialColors.getColor(
                    binding.root,
                    com.google.android.material.R.attr.colorSurface
                ),
                ThemeUtil.SYSTEM_BAR_ALPHA
            )
        )
        if (InsetsHelper.getSystemGestureType(applicationContext) !=
            InsetsHelper.GESTURE_NAVIGATION
        ) {
            binding.navigationBarShade.setBackgroundColor(
                ThemeUtil.getColorWithOpacity(
                    MaterialColors.getColor(
                        binding.root,
                        com.google.android.material.R.attr.colorSurface
                    ),
                    ThemeUtil.SYSTEM_BAR_ALPHA
                )
            )
        }

        var applicationsClickTimestamp = TimeSource.Monotonic.markNow()

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
        setUpNavigation(savedInstanceState, navHostFragment.navController)
        (binding.navigationView as NavigationBarView).setOnItemReselectedListener {
            when (it.itemId) {
                R.id.gamesFragment -> {
                    if (applicationsClickTimestamp.elapsedNow() < 300.milliseconds) {
                        Toast.makeText(this, BuildConfig.VERSION_NAME, Toast.LENGTH_LONG)
                            .show()
                    }
                    applicationsClickTimestamp = TimeSource.Monotonic.markNow()

                    gamesViewModel.setShouldScrollToTop(true)
                }

                R.id.searchFragment -> gamesViewModel.setSearchFocused(true)

                R.id.homeSettingsFragment -> SettingsActivity.launch(
                    this,
                    SettingsFile.FILE_NAME_CONFIG,
                    ""
                )
            }
        }

        // Prevents navigation from being drawn for a short time on recreation if set to hidden
        if (!homeViewModel.navigationVisible.value.first) {
            binding.navigationView.visibility = View.INVISIBLE
            binding.statusBarShade.visibility = View.INVISIBLE
        }

        lifecycleScope.apply {
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    homeViewModel.navigationVisible.collect {
                        showNavigation(it.first, it.second)
                    }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    homeViewModel.statusBarShadeVisible.collect {
                        showStatusBarShade(it)
                    }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    homeViewModel.isPickingUserDir.collect { checkUserPermissions() }
                }
            }
        }

        setInsets()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Save the user's current game state.
        outState.putInt(KEY_SETUP_CURRENT_PAGE, homeViewModel.setupCurrentPage)

        // Always call the superclass so it can save the view hierarchy state.
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        checkUserPermissions()
        if (isPendingExternalGameDeletion()) {
            maybeDeletePendingExternalGame()
        } else {
            maybeLaunchPendingExternalGame()
            maybeOpenPendingSettings()
            maybeOpenPendingGpuDriverManager()
        }

        ThemeUtil.setCorrectTheme(this)
        super.onResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        hasForwardedPendingExternalGame = false
        hasHandledPendingSettings = false
        hasHandledPendingGpuDriverManager = false
        hasHandledPendingExternalGameDeletion = false
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    fun displayMultiplayerDialog() {
        val dialog = NetPlayDialog(this)
        dialog.show()
    }

    override fun setTheme(resId: Int) {
        super.setTheme(resId)
        themeId = resId
    }

    private fun checkUserPermissions() {
        val firstTimeSetup = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .getBoolean(Settings.PREF_FIRST_APP_LAUNCH, true)

        if (firstTimeSetup) {
            return
        }

        if (!BuildUtil.isGooglePlayBuild) {
            fun requestMissingFilesystemPermission() =
                GrantMissingFilesystemPermissionFragment.newInstance()
                    .show(supportFragmentManager, GrantMissingFilesystemPermissionFragment.TAG)

            if (supportFragmentManager.findFragmentByTag(
                    GrantMissingFilesystemPermissionFragment.TAG
                ) ==
                null
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!Environment.isExternalStorageManager()) {
                        requestMissingFilesystemPermission()
                    }
                } else {
                    if (ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestMissingFilesystemPermission()
                    }
                }
            }
        }

        if (homeViewModel.isPickingUserDir.value) {
            return
        }

        if (!PermissionsHandler.hasWriteAccess(this)) {
            SelectUserDirectoryDialogFragment.newInstance(this)
                .show(supportFragmentManager, SelectUserDirectoryDialogFragment.TAG)
            return
        } else if (CitraDirectoryUtils.needToUpdateManually()) {
            UpdateUserDirectoryDialogFragment.newInstance(this)
                .show(supportFragmentManager, UpdateUserDirectoryDialogFragment.TAG)
            return
        }

        if (!BuildUtil.isGooglePlayBuild) {
            if (supportFragmentManager.findFragmentByTag(SelectUserDirectoryDialogFragment.TAG) ==
                null
            ) {
                if (NativeLibrary.getUserDirectory() == "") {
                    SelectUserDirectoryDialogFragment.newInstance(this)
                        .show(supportFragmentManager, SelectUserDirectoryDialogFragment.TAG)
                }
            }
        }
    }

    fun finishSetup(navController: NavController) {
        navController.navigate(R.id.action_firstTimeSetupFragment_to_gamesFragment)
        (binding.navigationView as NavigationBarView).setupWithNavController(navController)
        binding.root.post {
            if (isPendingExternalGameDeletion()) {
                maybeDeletePendingExternalGame()
            } else {
                maybeLaunchPendingExternalGame()
                maybeOpenPendingSettings()
                maybeOpenPendingGpuDriverManager()
            }
        }
    }

    private fun setUpNavigation(savedInstanceState: Bundle?, navController: NavController) {
        val firstTimeSetup = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .getBoolean(Settings.PREF_FIRST_APP_LAUNCH, true)

        if (firstTimeSetup && !homeViewModel.navigatedToSetup) {
            homeViewModel.setupCurrentPage = savedInstanceState?.getInt(KEY_SETUP_CURRENT_PAGE) ?: 0
            navController.navigate(R.id.firstTimeSetupFragment)
            homeViewModel.navigatedToSetup = true
        } else {
            (binding.navigationView as NavigationBarView).setupWithNavController(navController)
        }
    }

    private fun isPendingExternalGameDeletion(): Boolean =
        intent?.action == ACTION_DELETE_EXTERNAL_GAME

    private fun maybeDeletePendingExternalGame() {
        if (hasHandledPendingExternalGameDeletion) {
            return
        }

        val gameUri = intent?.data
        if (gameUri == null || !PermissionsHandler.hasWriteAccess(applicationContext) ||
            !DirectoryInitialization.areCitraDirectoriesReady()) {
            finishExternalGameDeletion(false)
            return
        }

        hasHandledPendingExternalGameDeletion = true
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                runCatching {
                    val sourceGame = getExternalIntentGame(gameUri) ?: return@runCatching false
                    if (!sourceGame.valid || sourceGame.titleId == 0L) {
                        return@runCatching false
                    }

                    val installedGame = NativeLibrary.getInstalledGamePaths()
                        .asSequence()
                        .map { installed ->
                            GameHelper.getGame(
                                Uri.parse(installed.path),
                                isInstalled = true,
                                addedToLibrary = false,
                                mediaType = installed.mediaType,
                            )
                        }
                        .firstOrNull {
                            it.titleId == sourceGame.titleId
                        }

                    installedGame == null || NativeLibrary.uninstallTitle(
                        installedGame.titleId,
                        installedGame.mediaType,
                    )
                }.getOrDefault(false)
            }
            finishExternalGameDeletion(deleted)
        }
    }

    /**
     * Mirrors EmulationFragment's external launch handling. Retro supplies a
     * FileProvider URI, which has no native path but can be read via an fd.
     */
    private fun getExternalIntentGame(gameUri: Uri): Game? {
        fun parse(uri: Uri): Game = GameHelper.getGame(
            uri,
            isInstalled = false,
            addedToLibrary = false,
            mediaType = Game.MediaType.GAME_CARD,
        )

        if (BuildUtil.isGooglePlayBuild || gameUri.toString().startsWith("!")) {
            return parse(gameUri)
        }

        val gameFd = contentResolver.openFileDescriptor(gameUri, "r")?.detachFd()
            ?: return null
        return try {
            parse(Uri.parse("fd://$gameFd"))
        } finally {
            ParcelFileDescriptor.adoptFd(gameFd).close()
        }
    }

    private fun finishExternalGameDeletion(deleted: Boolean) {
        setResult(
            if (deleted) RESULT_OK else RESULT_CANCELED,
            Intent().putExtra(EXTRA_DELETE_COMPLETED, deleted),
        )
        finish()
    }

    private fun maybeLaunchPendingExternalGame() {
        if (hasForwardedPendingExternalGame) {
            return
        }

        val sourceIntent = intent
        val intentExtras = sourceIntent?.extras?.keySet()?.joinToString { key ->
            "$key=${sourceIntent.extras?.get(key)}"
        }.orEmpty()
        Log.info(
            "[APILOG][MainActivity] external launch data=${sourceIntent?.data} " +
                "dataString=${sourceIntent?.dataString} action=${sourceIntent?.action} " +
                "type=${sourceIntent?.type} flags=0x${sourceIntent?.flags?.toString(16)} " +
                "clipData=${sourceIntent?.clipData} extras={$intentExtras}"
        )
        val gameUri = sourceIntent?.data
            ?: sourceIntent?.getStringExtra("filePath")?.let { Uri.fromFile(File(it)) }
            ?: return
        if (sourceIntent.action != Intent.ACTION_VIEW) {
            return
        }

        val firstTimeSetup = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .getBoolean(Settings.PREF_FIRST_APP_LAUNCH, true)
        if (firstTimeSetup || CitraDirectoryUtils.needToUpdateManually()) {
            return
        }

        if (PermissionsHandler.hasWriteAccess(applicationContext) &&
            !DirectoryInitialization.areCitraDirectoriesReady()
        ) {
            DirectoryInitialization.start()
        }

        if (!PermissionsHandler.hasWriteAccess(applicationContext) ||
            !DirectoryInitialization.areCitraDirectoriesReady()
        ) {
            return
        }

        if (isInstallableArchive(gameUri)) {
            maybeInstallAndLaunchPendingArchive(gameUri, sourceIntent)
            return
        }

        val launchIntent = Intent(this, EmulationActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = gameUri
            clipData =
                sourceIntent.clipData ?: ClipData.newUri(contentResolver, "azahar-game", gameUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (sourceIntent.getBooleanExtra("launched_from_shortcut", false)) {
                putExtra("launched_from_shortcut", true)
            }
            sourceIntent.getStringExtra("filePath")?.let { putExtra("filePath", it) }
            sourceIntent.data?.let { putExtra(EXTRA_EXTERNAL_LAUNCH_DATA, it.toString()) }
        }

        hasForwardedPendingExternalGame = true
        startActivity(launchIntent)
        finish()
    }

    private fun isInstallableArchive(gameUri: Uri): Boolean {
        return when (runCatching { FileUtil.getExtension(gameUri) }.getOrNull()) {
            "cia", "zcia" -> true
            else -> false
        }
    }

    private fun maybeInstallAndLaunchPendingArchive(gameUri: Uri, sourceIntent: Intent) {
        val titleId = resolveExternalArchiveTitleId(gameUri)
        val installedGame = titleId?.let(::findInstalledGameByTitleId)
        if (installedGame != null) {
            launchInstalledGame(installedGame, sourceIntent)
            return
        }

        val installedGameKeysBefore = NativeLibrary.getInstalledGamePaths()
            .map { installedGameKey(it.path, it.mediaType) }
            .toSet()
        val installArchives = collectPendingInstallArchives(sourceIntent, gameUri)
        val workRequest = createCiaInstallWorkRequest(installArchives)
        hasForwardedPendingExternalGame = true

        WorkManager.getInstance(applicationContext)
            .getWorkInfoByIdLiveData(workRequest.id)
            .observe(this) { workInfo ->
                if (workInfo == null || !workInfo.state.isFinished) {
                    return@observe
                }

                if (workInfo.state == WorkInfo.State.SUCCEEDED) {
                    val gameToLaunch =
                        titleId?.let(::findInstalledGameByTitleId)
                            ?: findNewlyInstalledGame(installedGameKeysBefore)
                    if (gameToLaunch != null) {
                        launchInstalledGame(gameToLaunch, sourceIntent)
                    } else {
                        showExternalArchiveInstallFailed(gameUri)
                    }
                } else {
                    showExternalArchiveInstallFailed(gameUri)
                }
            }

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            INSTALL_CIA_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            workRequest
        )
        showCiaInstallProgressDialog(workRequest.id)
    }

    private fun collectPendingInstallArchives(sourceIntent: Intent, primaryGameUri: Uri): Array<String> {
        val archives = linkedSetOf<String>()
        if (isInstallableArchive(primaryGameUri)) {
            archives.add(primaryGameUri.toString())
        }

        sourceIntent.getStringArrayListExtra(EXTRA_DLC_URIS)
            ?.asSequence()
            ?.filter { it.isNotBlank() }
            ?.mapNotNull { value -> runCatching { Uri.parse(value) }.getOrNull() }
            ?.filter(::isInstallableArchive)
            ?.map(Uri::toString)
            ?.forEach(archives::add)

        val clipData = sourceIntent.clipData
        if (clipData != null) {
            for (index in 0 until clipData.itemCount) {
                clipData.getItemAt(index).uri
                    ?.takeIf(::isInstallableArchive)
                    ?.toString()
                    ?.let(archives::add)
            }
        }

        if (archives.isEmpty()) {
            archives.add(primaryGameUri.toString())
        }
        return archives.toTypedArray()
    }

    private fun resolveExternalArchiveTitleId(gameUri: Uri): Long? {
        val preparedArchive = prepareExternalArchiveForLookup(gameUri) ?: return null
        return try {
            NativeLibrary.getTitleId(preparedArchive.path)
                .takeIf { it != 0L }
        } finally {
            preparedArchive.cleanup()
        }
    }

    private fun findInstalledGameByTitleId(titleId: Long): Game? {
        return loadInstalledGames().firstOrNull { it.titleId == titleId }
    }

    private fun findNewlyInstalledGame(existingKeys: Set<String>): Game? {
        return loadInstalledGames().firstOrNull {
            installedGameKey(it.path, it.mediaType) !in existingKeys
        }
    }

    private fun loadInstalledGames(): List<Game> {
        return NativeLibrary.getInstalledGamePaths().mapNotNull { installedGame ->
            runCatching {
                GameHelper.getGame(
                    Uri.parse(installedGame.path),
                    isInstalled = true,
                    addedToLibrary = false,
                    mediaType = installedGame.mediaType
                )
            }.getOrNull()
        }
    }

    private fun installedGameKey(path: String, mediaType: Game.MediaType): String {
        return "$path|${mediaType.value}"
    }

    private fun launchInstalledGame(game: Game, sourceIntent: Intent) {
        val launchIntent = runCatching {
            game.launchIntent.apply {
                if (sourceIntent.getBooleanExtra("launched_from_shortcut", false)) {
                    putExtra("launched_from_shortcut", true)
                }
                sourceIntent.getStringExtra("filePath")?.let { putExtra("filePath", it) }
                sourceIntent.data?.let { putExtra(EXTRA_EXTERNAL_LAUNCH_DATA, it.toString()) }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }.getOrElse {
            showExternalArchiveInstallFailed(sourceIntent.data ?: Uri.parse(game.path))
            return
        }

        startActivity(launchIntent)
        finish()
    }

    private fun showExternalArchiveInstallFailed(gameUri: Uri) {
        val filename = runCatching { FileUtil.getFilename(gameUri) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: (gameUri.lastPathSegment ?: getString(R.string.install_cia_title))

        if (supportFragmentManager.findFragmentByTag(MessageDialogFragment.TAG) != null) {
            return
        }

        MessageDialogFragment.newInstance(
            R.string.cia_install_notification_error_title,
            getString(R.string.cia_install_error_unknown, filename)
        ).show(supportFragmentManager, MessageDialogFragment.TAG)
    }

    private fun prepareExternalArchiveForLookup(gameUri: Uri): PreparedArchivePath? {
        if (BuildUtil.isGooglePlayBuild) {
            return PreparedArchivePath(gameUri.toString())
        }

        val uriString = gameUri.toString()
        if (FileUtil.isNativePath(uriString)) {
            return PreparedArchivePath("!$uriString")
        }

        if (gameUri.scheme == "file") {
            val absolutePath = gameUri.path ?: return null
            return PreparedArchivePath("!$absolutePath")
        }

        val nativePath = runCatching { NativeLibrary.getNativePath(gameUri) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        if (nativePath != null) {
            return PreparedArchivePath("!$nativePath")
        }

        val filename = runCatching { FileUtil.getFilename(gameUri) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "${UUID.randomUUID()}.cia"
        val tempDir = File(cacheDir, "external-cia-lookups")
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            return null
        }

        val tempFile = File(tempDir, "${UUID.randomUUID()}-$filename")
        val copied = FileUtil.copyUriToInternalStorage(gameUri, tempDir.absolutePath, tempFile.name)
        if (!copied) {
            tempFile.delete()
            return null
        }

        return PreparedArchivePath("!${tempFile.absolutePath}") {
            tempFile.delete()
        }
    }

    private data class PreparedArchivePath(
        val path: String,
        val cleanupAction: (() -> Unit)? = null
    ) {
        fun cleanup() {
            cleanupAction?.invoke()
        }
    }

    private fun createCiaInstallWorkRequest(selectedFiles: Array<String>): OneTimeWorkRequest {
        return OneTimeWorkRequest.Builder(CiaInstallWorker::class.java)
            .setInputData(
                Data.Builder().putStringArray("CIA_FILES", selectedFiles)
                    .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
    }

    private fun showCiaInstallProgressDialog(workId: java.util.UUID) {
        if (supportFragmentManager.findFragmentByTag(CiaInstallProgressDialogFragment.TAG) != null) {
            return
        }

        CiaInstallProgressDialogFragment.newInstance(workId)
            .show(supportFragmentManager, CiaInstallProgressDialogFragment.TAG)
    }

    private fun maybeOpenPendingGpuDriverManager() {
        if (hasHandledPendingGpuDriverManager) {
            return
        }

        val sourceIntent = intent ?: return
        if (sourceIntent.action != ACTION_OPEN_GPU_DRIVER_MANAGER) {
            return
        }

        val firstTimeSetup = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .getBoolean(Settings.PREF_FIRST_APP_LAUNCH, true)
        if (firstTimeSetup || CitraDirectoryUtils.needToUpdateManually()) {
            return
        }

        if (PermissionsHandler.hasWriteAccess(applicationContext) &&
            !DirectoryInitialization.areCitraDirectoriesReady()
        ) {
            DirectoryInitialization.start()
        }

        if (!PermissionsHandler.hasWriteAccess(applicationContext) ||
            !DirectoryInitialization.areCitraDirectoriesReady()
        ) {
            return
        }

        val driverUri = sourceIntent.data
            ?: sourceIntent.getStringExtra(EXTRA_DRIVER_PATH)
                ?.takeIf { it.isNotBlank() }
                ?.let { Uri.fromFile(File(it)) }

        if (driverUri != null && GpuDriverHelper.supportsCustomDriverLoading()) {
            val installed = runCatching {
                GpuDriverHelper.installCustomDriverComplete(driverUri)
            }.getOrElse {
                Toast.makeText(
                    this,
                    getString(R.string.select_gpu_driver_error),
                    Toast.LENGTH_LONG
                ).show()
                false
            }

            if (!installed) {
                Toast.makeText(
                    this,
                    getString(R.string.select_gpu_driver_error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as? NavHostFragment
                ?: return
        val navController = navHostFragment.navController
        if (navController.currentDestination?.id != R.id.driverManagerFragment) {
            navController.navigate(R.id.driverManagerFragment)
        }

        hasHandledPendingGpuDriverManager = true
    }

    private fun maybeOpenPendingSettings() {
        if (hasHandledPendingSettings) {
            return
        }

        val sourceIntent = intent ?: return
        if (sourceIntent.action != ACTION_OPEN_SETTINGS) {
            return
        }

        val firstTimeSetup = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .getBoolean(Settings.PREF_FIRST_APP_LAUNCH, true)
        if (firstTimeSetup || CitraDirectoryUtils.needToUpdateManually()) {
            return
        }

        if (PermissionsHandler.hasWriteAccess(applicationContext) &&
            !DirectoryInitialization.areCitraDirectoriesReady()
        ) {
            DirectoryInitialization.start()
        }

        if (!PermissionsHandler.hasWriteAccess(applicationContext) ||
            !DirectoryInitialization.areCitraDirectoriesReady()
        ) {
            return
        }

        val menuTag = sourceIntent.getStringExtra(EXTRA_MENU_TAG) ?: SettingsFile.FILE_NAME_CONFIG
        val gameId = sourceIntent.getStringExtra(EXTRA_GAME_ID) ?: ""
        hasHandledPendingSettings = true
        SettingsActivity.launch(this, menuTag, gameId)
        finish()
    }

    private fun showNavigation(visible: Boolean, animated: Boolean) {
        if (!animated) {
            if (visible) {
                binding.navigationView.visibility = View.VISIBLE
            } else {
                binding.navigationView.visibility = View.INVISIBLE
            }
            return
        }

        val smallLayout = resources.getBoolean(R.bool.small_layout)
        binding.navigationView.animate().apply {
            if (visible) {
                binding.navigationView.visibility = View.VISIBLE
                duration = 300
                interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

                if (smallLayout) {
                    binding.navigationView.translationY =
                        binding.navigationView.height.toFloat() * 2
                    translationY(0f)
                } else {
                    if (ViewCompat.getLayoutDirection(binding.navigationView) ==
                        ViewCompat.LAYOUT_DIRECTION_LTR
                    ) {
                        binding.navigationView.translationX =
                            binding.navigationView.width.toFloat() * -2
                        translationX(0f)
                    } else {
                        binding.navigationView.translationX =
                            binding.navigationView.width.toFloat() * 2
                        translationX(0f)
                    }
                }
            } else {
                duration = 300
                interpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

                if (smallLayout) {
                    translationY(binding.navigationView.height.toFloat() * 2)
                } else {
                    if (ViewCompat.getLayoutDirection(binding.navigationView) ==
                        ViewCompat.LAYOUT_DIRECTION_LTR
                    ) {
                        translationX(binding.navigationView.width.toFloat() * -2)
                    } else {
                        translationX(binding.navigationView.width.toFloat() * 2)
                    }
                }
            }
        }.withEndAction {
            if (!visible) {
                binding.navigationView.visibility = View.INVISIBLE
            }
        }.start()
    }

    private fun showStatusBarShade(visible: Boolean) {
        binding.statusBarShade.animate().apply {
            if (visible) {
                binding.statusBarShade.visibility = View.VISIBLE
                binding.statusBarShade.translationY = binding.statusBarShade.height.toFloat() * -2
                duration = 300
                translationY(0f)
                interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
            } else {
                duration = 300
                translationY(binding.navigationView.height.toFloat() * -2)
                interpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
            }
        }.withEndAction {
            if (!visible) {
                binding.statusBarShade.visibility = View.INVISIBLE
            }
        }.start()
    }

    private fun setInsets() = ViewCompat.setOnApplyWindowInsetsListener(
        binding.root
    ) { _: View, windowInsets: WindowInsetsCompat ->
        val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        val mlpStatusShade = binding.statusBarShade.layoutParams as MarginLayoutParams
        mlpStatusShade.height = insets.top
        binding.statusBarShade.layoutParams = mlpStatusShade

        // The only situation where we care to have a nav bar shade is when it's at the bottom
        // of the screen where scrolling list elements can go behind it.
        val mlpNavShade = binding.navigationBarShade.layoutParams as MarginLayoutParams
        mlpNavShade.height = insets.bottom
        binding.navigationBarShade.layoutParams = mlpNavShade

        windowInsets
    }

    private fun createOpenCitraDirectoryLauncher(
        permissionsLost: Boolean
    ): ActivityResultLauncher<Uri?> {
        return registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { result: Uri? ->
            if (result == null) {
                return@registerForActivityResult
            }

            if (!BuildUtil.isGooglePlayBuild) {
                if (NativeLibrary.getNativePath(result) == "") {
                    SelectUserDirectoryDialogFragment.newInstance(
                        this,
                        R.string.invalid_selection,
                        R.string.invalid_user_directory
                    ).show(supportFragmentManager, SelectUserDirectoryDialogFragment.TAG)
                    return@registerForActivityResult
                }
            }

            CitraDirectoryHelper(this@MainActivity, permissionsLost)
                .showCitraDirectoryDialog(result, buttonState = {})
        }
    }

    val openCitraDirectory = createOpenCitraDirectoryLauncher(permissionsLost = false)
    val openCitraDirectoryLostPermission = createOpenCitraDirectoryLauncher(permissionsLost = true)

    val ciaFileInstaller = registerForActivityResult(
        OpenFileResultContract()
    ) { result: Intent? ->
        if (result == null) {
            return@registerForActivityResult
        }

        val selectedFiles =
            FileBrowserHelper.getSelectedFiles(result, applicationContext, listOf("cia", "zcia"))
        if (selectedFiles == null) {
            Toast.makeText(applicationContext, R.string.cia_file_not_found, Toast.LENGTH_LONG)
                .show()
            return@registerForActivityResult
        }

        val workManager = WorkManager.getInstance(applicationContext)
        workManager.enqueueUniqueWork(
            INSTALL_CIA_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            createCiaInstallWorkRequest(selectedFiles)
        )
    }

    val setupOpenCitraDirectory = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { result: Uri? ->
        homeViewModel.selectedCitraDirectory = result
    }

    val setupGetGamesDirectory = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { result: Uri? ->
        homeViewModel.selectedGamesDirectory = result
    }
}
