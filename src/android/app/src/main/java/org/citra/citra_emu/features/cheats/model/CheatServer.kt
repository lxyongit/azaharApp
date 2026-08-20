// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version.

package org.citra.citra_emu.features.cheats.model

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.citra.citra_emu.utils.Log

data class FetchedCheat(
    val name: String,
    val code: String,
)

private const val CHEAT_SERVER_URL = "https://mpgame.lxyong.com/api/app/get-3ds-cheat"
private const val API_LOG_PREFIX = "[APILOG][CheatServer]"

@Serializable
private data class CheatServerResponse(
    val data: List<CheatServerGroup> = emptyList(),
    val success: Boolean = false,
    val msg: String = "",
)

@Serializable
private data class CheatServerGroup(
    val title: String = "",
    val options: List<CheatServerOption> = emptyList(),
)

@Serializable
private data class CheatServerOption(
    val description: String = "",
    val value: JsonElement,
)

object CheatServer {
    private val json = Json { ignoreUnknownKeys = true }

    fun fetch(titleId: Long, romPath: String): List<FetchedCheat> {
        val actualRomPath = romPath.removePrefix("!")
        val connection = (URL(CHEAT_SERVER_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        try {
            val gameId = String.format(Locale.ROOT, "%016X", titleId)
            val request = buildJsonObject {
                put("gameId", gameId)
                put("romPath", actualRomPath)
            }.toString()
            Log.info("$API_LOG_PREFIX request url=$CHEAT_SERVER_URL body=$request")
            connection.outputStream.use { it.write(request.toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            val response = (if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.use { it.readText() }.orEmpty()
            Log.info("$API_LOG_PREFIX response http=$responseCode body=$response")

            if (responseCode !in 200..299) {
                throw IOException("HTTP $responseCode")
            }

            val result = try {
                json.decodeFromString<CheatServerResponse>(response)
            } catch (exception: Exception) {
                throw IOException("Invalid cheat server response", exception)
            }
            if (!result.success) {
                Log.warning("$API_LOG_PREFIX server returned success=false msg=${result.msg}")
                throw IOException(result.msg.ifBlank { "The cheat server returned no cheats" })
            }

            val fetched = result.data.flatMap { group ->
                Log.info(
                    "$API_LOG_PREFIX group title=${group.title} options=${group.options.size}"
                )
                group.options.mapNotNull { option ->
                    val code = option.value.toCode()
                    if (code == null || code.isBlank()) {
                        Log.warning(
                            "$API_LOG_PREFIX skipped option description=${option.description} " +
                                "value=${option.value}"
                        )
                        return@mapNotNull null
                    }
                    val name = listOf(group.title.trim(), option.description.trim())
                        .filter { it.isNotEmpty() }
                        .joinToString(" - ")
                    if (name.isEmpty()) {
                        Log.warning("$API_LOG_PREFIX skipped option with empty name")
                        null
                    } else {
                        FetchedCheat(name, code)
                    }
                }
            }
            Log.info("$API_LOG_PREFIX parsed groups=${result.data.size} cheats=${fetched.size}")
            return fetched
        } catch (exception: Exception) {
            Log.error(
                "$API_LOG_PREFIX exception=${exception::class.simpleName} message=${exception.message}"
            )
            throw exception
        } finally {
            connection.disconnect()
        }
    }

    private fun JsonElement.toCode(): String? = when (this) {
        is JsonPrimitive -> contentOrNull
        is JsonArray -> map { element ->
            (element as? JsonPrimitive)?.contentOrNull
        }.takeIf { values -> values.all { it != null } }?.filterNotNull()?.joinToString("\n")
        else -> null
    }
}
