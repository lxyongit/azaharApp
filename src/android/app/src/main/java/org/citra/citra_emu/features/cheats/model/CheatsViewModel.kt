// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.features.cheats.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.citra.citra_emu.utils.Log
import java.util.concurrent.ConcurrentHashMap

class CheatsViewModel : ViewModel() {
    sealed interface FetchState {
        data object Idle : FetchState
        data object Loading : FetchState
        data class Success(
            val cheats: List<FetchedCheat>,
            val loadedFromServer: Boolean
        ) : FetchState
        data class Error(val message: String) : FetchState
    }

    val fetchState get() = _fetchState.asStateFlow()
    private val _fetchState = MutableStateFlow<FetchState>(FetchState.Idle)

    val cheatsReloadedEvent get() = _cheatsReloadedEvent.asSharedFlow()
    private val _cheatsReloadedEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

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

    private var titleId: Long = 0
    private var romPath: String = ""
    private var fetchStarted = false
    lateinit var cheats: Array<Cheat>
    private var cheatsNeedSaving = false
    private var selectedCheatPosition = -1

    private data class FetchKey(val titleId: Long, val romPath: String)

    companion object {
        // Keeps separate cheat pages in the same app process from requesting the same game again.
        private val fetchedCheatsCache = ConcurrentHashMap<FetchKey, List<FetchedCheat>>()
    }

    fun initialize(titleId_: Long, romPath_: String) {
        val gameChanged = titleId != titleId_ || romPath != romPath_
        titleId = titleId_
        romPath = romPath_
        Log.info("[APILOG][CheatsViewModel] initialize titleId=$titleId romPath=$romPath")
        load()
        if (gameChanged || !fetchStarted) {
            fetchStarted = true
            fetchCheats()
        }
    }

    fun fetchCheats() {
        if (_fetchState.value is FetchState.Loading) return
        if (titleId <= 0L || romPath.isBlank()) {
            Log.error(
                "[APILOG][CheatsViewModel] fetch skipped invalid titleId=$titleId romPath=$romPath"
            )
            _fetchState.value = FetchState.Error("The game ID or ROM path is unavailable")
            return
        }

        fetchedCheatsCache[FetchKey(titleId, romPath)]?.let { cachedCheats ->
            Log.info(
                "[APILOG][CheatsViewModel] fetch cache hit titleId=$titleId " +
                    "romPath=$romPath count=${cachedCheats.size}"
            )
            // The file already contains the server section. A cache hit must not rebuild it,
            // otherwise enabled states and manual changes would be replaced unnecessarily.
            _fetchState.value = FetchState.Success(cachedCheats, loadedFromServer = false)
            return
        }

        _fetchState.value = FetchState.Loading
        viewModelScope.launch {
            _fetchState.value = try {
                val fetched = withContext(Dispatchers.IO) {
                    CheatServer.fetch(titleId, romPath)
                }
                fetchedCheatsCache[FetchKey(titleId, romPath)] = fetched
                Log.info("[APILOG][CheatsViewModel] fetch completed count=${fetched.size}")
                FetchState.Success(fetched, loadedFromServer = true)
            } catch (exception: Exception) {
                Log.error(
                    "[APILOG][CheatsViewModel] fetch failed " +
                        "${exception::class.simpleName}: ${exception.message}"
                )
                FetchState.Error(exception.message ?: "Unable to fetch cheats")
            }
        }
    }

    fun clearFetchState() {
        _fetchState.value = FetchState.Idle
    }

    fun importFetchedCheats(fetchedCheats: List<FetchedCheat>) {
        val currentCheats = cheats.toList()
        val fetchedKeys = fetchedCheats.map { cheatKey(it.name, it.code) }.toSet()
        val serverEnabledStates = currentCheats
            .filter { cheat ->
                val origin = CheatMetadata.getOrigin(cheat.getNotes())
                origin == CheatMetadata.SERVER_ORIGIN ||
                    (origin == null && cheatKey(cheat.getName(), cheat.getCode()) in fetchedKeys)
            }
            .associate { cheatKey(it.getName(), it.getCode()) to it.getEnabled() }
        val manualCheats = currentCheats.filter {
            CheatMetadata.getOrigin(it.getNotes()) == CheatMetadata.MANUAL_ORIGIN
        }
        val oldServerCheats = currentCheats.filter { cheat ->
            val origin = CheatMetadata.getOrigin(cheat.getNotes())
            origin == CheatMetadata.SERVER_ORIGIN ||
                (origin == null && cheatKey(cheat.getName(), cheat.getCode()) in fetchedKeys)
        }
        val publicCheats = currentCheats.filter { cheat ->
            cheat !in manualCheats && cheat !in oldServerCheats
        }
        val serverCheats = fetchedCheats.map { fetched ->
            val serverCheat = Cheat.createGatewayCode(
                fetched.name,
                CheatMetadata.SERVER_ORIGIN,
                fetched.code
            )
            serverEnabledStates[cheatKey(fetched.name, fetched.code)]?.let { enabled ->
                serverCheat.setEnabled(enabled)
            }
            serverCheat
        }
        Log.info(
            "[APILOG][CheatsViewModel] import received=${fetchedCheats.size} " +
                "manual=${manualCheats.size} removedServer=${oldServerCheats.size} " +
                "public=${publicCheats.size}"
        )

        // Keep the file in three logical sections: manual, local server, public.
        val orderedCheats = manualCheats + serverCheats + publicCheats
        currentCheats.indices.reversed().forEach { index -> CheatEngine.removeCheat(index) }
        orderedCheats.forEach { CheatEngine.addCheat(it) }
        load()
        cheatsNeedSaving = true
        _cheatsReloadedEvent.tryEmit(Unit)
    }

    private fun cheatKey(name: String, code: String): Pair<String, String> =
        name.trim() to code.trim()

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
        val manualCheat = cheat?.let {
            Cheat.createGatewayCode(
                it.getName(),
                CheatMetadata.withOrigin(it.getNotes(), CheatMetadata.MANUAL_ORIGIN),
                it.getCode()
            )
        }
        CheatEngine.prependCheat(manualCheat)
        cheatsNeedSaving = true
        load()
        notifyCheatAdded(0)
        setSelectedCheat(cheats[0], 0)
    }

    /**
     * Notifies that an edit has been made to the contents of the cheat at the given position.
     */
    private fun notifyCheatUpdated(position: Int) {
        _cheatChangedEvent.value = position
        _cheatChangedEvent.value = null
    }

    fun updateSelectedCheat(newCheat: Cheat?) {
        val manualCheat = newCheat?.let {
            Cheat.createGatewayCode(
                it.getName(),
                CheatMetadata.withOrigin(it.getNotes(), CheatMetadata.MANUAL_ORIGIN),
                it.getCode()
            )
        }
        CheatEngine.updateCheat(selectedCheatPosition, manualCheat)
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
