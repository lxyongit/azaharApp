// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.features.cheats.model

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.citra.citra_emu.NativeLibrary

class CheatsViewModel : ViewModel() {
    val selectedCheat get() = _selectedCheat.asStateFlow()
    private val _selectedCheat = MutableStateFlow<Cheat?>(null)

    val isAdding get() = _isAdding.asStateFlow()
    private val _isAdding = MutableStateFlow(false)

    val isEditing get() = _isEditing.asStateFlow()
    private val _isEditing = MutableStateFlow(false)

    /**
     * When a cheat is added, the integer stored in the returned StateFlow
     * changes to the position of that cheat, then changes back to null.
     */
    val cheatAddedEvent get() = _cheatAddedEvent.asStateFlow()
    private val _cheatAddedEvent = MutableStateFlow<Int?>(null)

    val cheatChangedEvent get() = _cheatChangedEvent.asStateFlow()
    private val _cheatChangedEvent = MutableStateFlow<Int?>(null)

    /**
     * When a cheat is deleted, the integer stored in the returned StateFlow
     * changes to the position of that cheat, then changes back to null.
     */
    val cheatDeletedEvent get() = _cheatDeletedEvent.asStateFlow()
    private val _cheatDeletedEvent = MutableStateFlow<Int?>(null)

    val openDetailsViewEvent get() = _openDetailsViewEvent.asStateFlow()
    private val _openDetailsViewEvent = MutableStateFlow(false)

    val closeDetailsViewEvent get() = _closeDetailsViewEvent.asStateFlow()
    private val _closeDetailsViewEvent = MutableStateFlow(false)

    val listViewFocusChange get() = _listViewFocusChange.asStateFlow()
    private val _listViewFocusChange = MutableStateFlow(false)

    val detailsViewFocusChange get() = _detailsViewFocusChange.asStateFlow()
    private val _detailsViewFocusChange = MutableStateFlow(false)

    val cheatsReloadedEvent get() = _cheatsReloadedEvent.asStateFlow()
    private val _cheatsReloadedEvent = MutableStateFlow(0L)

    private var titleId: Long = 0
    private var romPath = ""
    private var serverRequest: Job? = null
    lateinit var cheats: Array<Cheat>
    private var cheatsNeedSaving = false
    private var selectedCheatPosition = -1

    fun initialize(titleId_: Long, romPath_: String) {
        val sameGame = titleId == titleId_ && romPath == romPath_
        if (!sameGame) {
            cancelServerRequest()
            saveIfNeeded()
            setSelectedCheat(null, -1)
        }
        titleId = titleId_
        romPath = romPath_
        Log.i(
            CheatServer.LOG_TAG,
            "[CheatServer] Initializing: title_id=${CheatServer.gameId(titleId)}, rom_path='$romPath'"
        )
        load()
        Log.i(CheatServer.LOG_TAG, "[CheatServer] Loaded ${cheats.size} local cheats")
        if (sameGame && serverRequest?.isActive == true) {
            Log.i(CheatServer.LOG_TAG, "[CheatServer] Request already pending; skipping duplicate")
            return
        }
        if (titleId == 0L || titleId == -1L || romPath.removePrefix("!").isBlank()) {
            Log.w(
                CheatServer.LOG_TAG,
                "[CheatServer] Skipping request: invalid_game_id=${titleId == 0L || titleId == -1L}, " +
                    "missing_rom_path=${romPath.removePrefix("!").isBlank()}"
            )
            return
        }
        val requestedTitleId = titleId
        val requestedRomPath = romPath
        serverRequest = viewModelScope.launch {
            try {
                val fetched = CheatServer.fetch(requestedTitleId, requestedRomPath)
                if (titleId != requestedTitleId ||
                    (NativeLibrary.isRunning() && NativeLibrary.getRunningTitleId() != requestedTitleId)
                ) {
                    Log.w(CheatServer.LOG_TAG, "[CheatServer] Ignoring response after game changed")
                    return@launch
                }
                importServerCheats(fetched)
            } catch (e: CancellationException) {
                Log.i(
                    CheatServer.LOG_TAG,
                    "[CheatServer] Request cancelled: title_id=${CheatServer.gameId(requestedTitleId)}"
                )
                throw e
            } catch (e: Exception) {
                Log.e(
                    CheatServer.LOG_TAG,
                    "[CheatServer] Request/import failed: title_id=${CheatServer.gameId(requestedTitleId)}",
                    e
                )
            }
        }
    }

    fun cancelServerRequest() {
        if (serverRequest?.isActive == true) {
            Log.i(CheatServer.LOG_TAG, "[CheatServer] Cancelling pending request on page/game change")
        }
        serverRequest?.cancel()
        serverRequest = null
    }

    private fun importServerCheats(fetched: List<CheatServer.FetchedCheat>) {
        val currentCheats = CheatEngine.getCheats()
        val keys = currentCheats.map { it.getName().trim() to it.getCode().trim() }.toMutableSet()
        var added = 0
        var duplicates = 0
        // Append missing entries so an in-progress edit and existing enabled states remain valid.
        for (cheat in fetched) {
            if (!keys.add(cheat.name.trim() to cheat.code.trim())) {
                ++duplicates
                continue
            }
            CheatEngine.addCheat(Cheat.createGatewayCode(cheat.name, CheatServer.ORIGIN, cheat.code))
            ++added
        }
        if (added > 0) {
            load()
            cheatsNeedSaving = true
            saveIfNeeded()
            _cheatsReloadedEvent.value += 1
        }
        Log.i(
            CheatServer.LOG_TAG,
            "[CheatServer] Import complete: title_id=${CheatServer.gameId(titleId)}, " +
                "previous=${currentCheats.size}, added=$added, duplicates=$duplicates, total=${cheats.size}"
        )
    }

    private fun load() {
        CheatEngine.loadCheatFile(titleId)
        cheats = CheatEngine.getCheats()
        for (i in cheats.indices) {
            cheats[i].setEnabledChangedCallback {
                cheatsNeedSaving = true
                notifyCheatUpdated(i)
            }
        }
    }

    fun saveIfNeeded() {
        if (cheatsNeedSaving) {
            Log.i(
                CheatServer.LOG_TAG,
                "[CheatServer] Saving cheats: title_id=${CheatServer.gameId(titleId)}"
            )
            CheatEngine.saveCheatFile(titleId)
            cheatsNeedSaving = false
        }
    }

    fun setSelectedCheat(cheat: Cheat?, position: Int) {
        if (isEditing.value) {
            setIsEditing(false)
        }
        _selectedCheat.value = cheat
        selectedCheatPosition = position
    }

    fun setIsEditing(value: Boolean) {
        _isEditing.value = value
        if (isAdding.value && !value) {
            _isAdding.value = false
            setSelectedCheat(null, -1)
        }
    }

    private fun notifyCheatAdded(position: Int) {
        _cheatAddedEvent.value = position
        _cheatAddedEvent.value = null
    }

    fun startAddingCheat() {
        _selectedCheat.value = null
        selectedCheatPosition = -1
        _isAdding.value = true
        _isEditing.value = true
    }

    fun finishAddingCheat(cheat: Cheat?) {
        check(isAdding.value)
        _isAdding.value = false
        _isEditing.value = false
        val position = cheats.size
        CheatEngine.addCheat(cheat)
        cheatsNeedSaving = true
        load()
        notifyCheatAdded(position)
        setSelectedCheat(cheats[position], position)
    }

    /**
     * Notifies that an edit has been made to the contents of the cheat at the given position.
     */
    private fun notifyCheatUpdated(position: Int) {
        _cheatChangedEvent.value = position
        _cheatChangedEvent.value = null
    }

    fun updateSelectedCheat(newCheat: Cheat?) {
        CheatEngine.updateCheat(selectedCheatPosition, newCheat)
        cheatsNeedSaving = true
        load()
        notifyCheatUpdated(selectedCheatPosition)
        setSelectedCheat(cheats[selectedCheatPosition], selectedCheatPosition)
    }

    /**
     * Notifies that the cheat at the given position has been deleted.
     */
    private fun notifyCheatDeleted(position: Int) {
        _cheatDeletedEvent.value = position
        _cheatDeletedEvent.value = null
    }

    fun deleteSelectedCheat() {
        val position = selectedCheatPosition
        setSelectedCheat(null, -1)
        CheatEngine.removeCheat(position)
        cheatsNeedSaving = true
        load()
        notifyCheatDeleted(position)
    }

    fun openDetailsView() {
        _openDetailsViewEvent.value = true
        _openDetailsViewEvent.value = false
    }

    fun closeDetailsView() {
        _closeDetailsViewEvent.value = true
        _closeDetailsViewEvent.value = false
    }

    fun onListViewFocusChanged(changed: Boolean) {
        _listViewFocusChange.value = changed
        _listViewFocusChange.value = false
    }

    fun onDetailsViewFocusChanged(changed: Boolean) {
        _detailsViewFocusChange.value = changed
        _detailsViewFocusChange.value = false
    }
}
