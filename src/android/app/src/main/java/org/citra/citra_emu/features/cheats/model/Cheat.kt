// Copyright 2023 Citra Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.features.cheats.model

import androidx.annotation.Keep

@Keep
class Cheat(@field:Keep private val mPointer: Long) {
    private var enabledChangedCallback: Runnable? = null
    protected external fun finalize()

    external fun getName(): String

    external fun getNotes(): String

    external fun getCode(): String

    external fun getEnabled(): Boolean

    fun setEnabled(enabled: Boolean) {
        setEnabledImpl(enabled)
        onEnabledChanged()
    }

    private external fun setEnabledImpl(enabled: Boolean)

    fun setEnabledChangedCallback(callback: Runnable) {
        enabledChangedCallback = callback
    }

    private fun onEnabledChanged() {
        enabledChangedCallback?.run()
    }

    companion object {
        /**
         * If the code is valid, returns 0. Otherwise, returns the 1-based index
         * for the line containing the error.
         */
        @JvmStatic
        external fun isValidGatewayCode(code: String): Int

        @JvmStatic
        external fun createGatewayCode(name: String, notes: String, code: String): Cheat
    }
}

/** Metadata stored as hidden note lines so the single cheat file has stable logical sections. */
object CheatMetadata {
    const val MANUAL_ORIGIN = "azaharplus:origin=manual"
    const val SERVER_ORIGIN = "azaharplus:origin=local-server"

    fun getOrigin(notes: String): String? = notes.lineSequence()
        .map(String::trim)
        .firstOrNull { it == MANUAL_ORIGIN || it == SERVER_ORIGIN }

    fun visibleNotes(notes: String): String = notes.lineSequence()
        .filter { it.trim() != MANUAL_ORIGIN && it.trim() != SERVER_ORIGIN }
        .joinToString("\n")
        .trim()

    fun withOrigin(notes: String, origin: String): String {
        val visible = visibleNotes(notes)
        return listOf(visible, origin)
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }
}
