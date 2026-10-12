// Copyright 2026 Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.features.cheats.model

import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object CheatServer {
    const val LOG_TAG = "CheatServer"
    const val ORIGIN = "azaharplus:origin=local-server"
    private const val URL_STRING = "https://mpgame.lxyong.com/api/app/get-3ds-cheat"

    data class FetchedCheat(val name: String, val code: String)

    fun gameId(titleId: Long): String = String.format(Locale.ROOT, "%016X", titleId)

    suspend fun fetch(titleId: Long, romPath: String): List<FetchedCheat> =
        withContext(Dispatchers.IO) {
            val payload = JSONObject()
                .put("gameId", gameId(titleId))
                .put("romPath", romPath.removePrefix("!"))
                .toString()
            val connection = (URL(URL_STRING).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            val started = SystemClock.elapsedRealtime()
            try {
                currentCoroutineContext().ensureActive()
                Log.i(LOG_TAG, "[CheatServer] Sending POST: url='$URL_STRING', payload=$payload")
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                Log.i(LOG_TAG, "[CheatServer] POST body sent: title_id=${gameId(titleId)}")
                val status = connection.responseCode
                val stream = if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                Log.i(
                    LOG_TAG,
                    "[CheatServer] Request finished: title_id=${gameId(titleId)}, " +
                        "http_status=$status, elapsed_ms=${SystemClock.elapsedRealtime() - started}, " +
                        "response_chars=${body.length}"
                )
                currentCoroutineContext().ensureActive()
                if (status !in 200..299) {
                    Log.w(LOG_TAG, "[CheatServer] HTTP failure: response_preview=${body.take(1024)}")
                    throw IOException("Cheat server returned HTTP $status")
                }
                val response = try {
                    JSONObject(body)
                } catch (e: JSONException) {
                    Log.w(
                        LOG_TAG,
                        "[CheatServer] Invalid JSON: response_preview=${body.take(1024)}",
                        e
                    )
                    throw e
                }
                if (!response.optBoolean("success")) {
                    Log.w(
                        LOG_TAG,
                        "[CheatServer] Server rejected request: msg='${response.optString("msg")}', " +
                            "response_preview=${body.take(1024)}"
                    )
                    throw IOException("Cheat server reported a failure")
                }
                val groups = response.optJSONArray("data")
                    ?: throw IOException("Cheat server response data is not an array")
                val fetched = mutableListOf<FetchedCheat>()
                var optionsCount = 0
                var skippedCount = 0
                for (groupIndex in 0 until groups.length()) {
                    val group = groups.optJSONObject(groupIndex) ?: continue
                    val title = group.optString("title").trim()
                    val options = group.optJSONArray("options") ?: continue
                    for (optionIndex in 0 until options.length()) {
                        ++optionsCount
                        val option = options.optJSONObject(optionIndex)
                        val code = when (val value = option?.opt("value")) {
                            is String -> value
                            is JSONArray -> {
                                val lines = (0 until value.length()).map { value.opt(it) }
                                if (lines.all { it is String }) lines.joinToString("\n") else ""
                            }
                            else -> ""
                        }
                        val description = option?.optString("description")?.trim().orEmpty()
                        val name = listOf(title, description)
                            .filter { it.isNotEmpty() }
                            .joinToString(" - ")
                        if (name.isNotEmpty() && code.isNotBlank()) {
                            fetched.add(FetchedCheat(name, code))
                        } else {
                            ++skippedCount
                        }
                    }
                }
                Log.i(
                    LOG_TAG,
                    "[CheatServer] Parsed response: title_id=${gameId(titleId)}, " +
                        "groups=${groups.length()}, options=$optionsCount, cheats=${fetched.size}, " +
                        "skipped_empty_or_invalid=$skippedCount"
                )
                fetched
            } finally {
                connection.disconnect()
            }
        }
}
