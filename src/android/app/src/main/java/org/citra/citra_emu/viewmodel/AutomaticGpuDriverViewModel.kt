// Copyright 2026 Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.utils.AutomaticGpuDriver
import org.citra.citra_emu.utils.AutomaticGpuDriver.Phase
import org.citra.citra_emu.utils.AutomaticGpuDriver.State
import org.citra.citra_emu.utils.Log
import org.citra.citra_emu.utils.PermissionsHandler

class AutomaticGpuDriverViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun startAfterPrerequisites() {
        if (job?.isActive == true) return
        val phase = state.value.phase
        if (phase != Phase.IDLE &&
            !(phase == Phase.PERMISSION_REQUIRED &&
                PermissionsHandler.hasNativeFilesystemAccess(CitraApplication.appContext))
        ) return

        mutableState.value = State(Phase.CHECKING)
        job = viewModelScope.launch {
            try {
                AutomaticGpuDriver.configure { mutableState.value = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.error("Automatic GPU driver configuration failed: ${error.message}")
                mutableState.value = if (error is SecurityException &&
                    !PermissionsHandler.hasNativeFilesystemAccess(CitraApplication.appContext)
                ) {
                    State(Phase.PERMISSION_REQUIRED)
                } else {
                    State(Phase.FAILED, error = error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    fun retry() {
        if (job?.isActive == true) return
        mutableState.value = State()
    }
}
