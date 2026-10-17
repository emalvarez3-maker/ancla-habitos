/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.ancla.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Runs one of Ancla's tools for any provider; returns the result text and whether it failed. */
internal fun dispatchTool(actions: AgentActions, name: String, input: JSONObject, onEvent: (AgentEvent) -> Unit): Pair<String, Boolean> = try {
    fun opt(key: String) = input.optString(key).takeIf { it.isNotBlank() && it != "null" }
    val out = when (name) {
        "create_task" -> actions.createTask(input.getString("title"), opt("date"), opt("time"), input.optBoolean("remind", false))
            .also { onEvent(AgentEvent.Action(it)) }
        "complete_habit" -> actions.completeHabit(input.getString("name")).also { onEvent(AgentEvent.Action(it)) }
        "today_summary" -> actions.todaySummary()
        "reading_summary" -> actions.readingSummary()
        else -> return "Unknown tool $name" to true
    }
    out to false
} catch (e: Exception) {
    (e.message ?: "Tool failed") to true
}

/**
 * The same assistant on Google's Gemini API (generateContent with function calling).
 * Model turns are sent back exactly as received so thought signatures stay intact.
 */
class GeminiAgent(private val apiKey: String, private val actions: AgentActions) : AssistantAgent {
    private val history = JSONArray()

    private fun prop(type: String, description: String? = null, nullable: Boolean = false) = JSONObject().apply {
        put("type", type)
        if (description != null) put("description", description)
        if (nullable) put("nullable", true)
    }

    private fun function(name: String, description: String, properties: JSONObject, required: List<String>) = JSONObject().apply {
        put("name", name)
        put("description", description)
        put(
            "parameters",
            JSONObject().apply {
                put("type", "object")
                put("properties", properties)
                if (required.isNotEmpty()) put("required", JSONArray(required))
            }
        )
    }

    private val tools = JSONArray().put(
        JSONObject().put(
            "functionDeclarations",
            JSONArray()
                .put(
                    function(
                        "create_task",
                        "Add a one-off task to the user's to-do list. Use it whenever the user dictates something to do, buy, pay, send or remember. Resolve relative dates against the current date.",
                        JSONObject()
                            .put("title", prop("string", "Short task title in the user's language, cleaned of filler words"))
                            .put("date", prop("string", "Due date as YYYY-MM-DD, or null for today", nullable = true))
                            .put("time", prop("string", "Time as HH:MM (24h), or null if none was said", nullable = true))
                            .put("remind", prop("boolean", "True when a time was given or the user asked to be reminded")),
                        listOf("title", "remind")
                    )
                )
                .put(
                    function(
                        "complete_habit",
                        "Mark one of the user's habits as done for today. Use the habit name as the user says it; it is matched loosely.",
                        JSONObject().put("name", prop("string")),
                        listOf("name")
                    )
                )
                .put(function("today_summary", "Get today's habits (done/pending, key days), today's tasks and the mascot's growth stage.", JSONObject(), emptyList()))
                .put(function("reading_summary", "Get the books being read with progress, this week's reading minutes and the to-read list.", JSONObject(), emptyList()))
        )
    )

    override fun send(userText: String, onEvent: (AgentEvent) -> Unit) {
        history.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userText))))
        repeat(MAX_STEPS) {
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", assistantInstructions()))))
                .put("contents", history)
                .put("tools", tools)
            val response = post(body)
            val candidate = response.optJSONArray("candidates")?.optJSONObject(0)
                ?: throw AgentException(AgentException.Kind.REFUSED, "no candidates")
            val content = candidate.optJSONObject("content") ?: JSONObject().put("role", "model").put("parts", JSONArray())
            if (!content.has("role")) content.put("role", "model")
            history.put(content)

            val parts = content.optJSONArray("parts") ?: JSONArray()
            val text = (0 until parts.length()).mapNotNull { i ->
                val p = parts.getJSONObject(i)
                if (p.optBoolean("thought", false)) null else p.optString("text").takeIf { it.isNotBlank() }
            }.joinToString("\n").trim()
            if (text.isNotEmpty()) onEvent(AgentEvent.Reply(text))

            val calls = (0 until parts.length()).mapNotNull { parts.getJSONObject(it).optJSONObject("functionCall") }
            if (calls.isEmpty()) {
                if (candidate.optString("finishReason") == "SAFETY" && text.isEmpty()) throw AgentException(AgentException.Kind.REFUSED, "safety")
                return
            }
            val responses = JSONArray()
            calls.forEach { call ->
                val name = call.getString("name")
                val (output, isError) = dispatchTool(actions, name, call.optJSONObject("args") ?: JSONObject(), onEvent)
                val fr = JSONObject().put("name", name).put("response", JSONObject().put(if (isError) "error" else "result", output))
                if (call.has("id")) fr.put("id", call.getString("id"))
                responses.put(JSONObject().put("functionResponse", fr))
            }
            history.put(JSONObject().put("role", "user").put("parts", responses))
        }
    }

    private fun post(body: JSONObject): JSONObject {
        val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 60000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            when {
                code in 200..299 -> return JSONObject(text)
                code == 400 && text.contains("API_KEY_INVALID") -> throw AgentException(AgentException.Kind.BAD_KEY, "invalid key")
                code == 401 || code == 403 -> throw AgentException(AgentException.Kind.BAD_KEY, "HTTP $code")
                code == 429 -> throw AgentException(AgentException.Kind.RATE_LIMIT, "HTTP 429")
                else -> throw AgentException(AgentException.Kind.OTHER, "HTTP $code: ${text.take(300)}")
            }
        } catch (e: IOException) {
            throw AgentException(AgentException.Kind.NETWORK, e.message ?: "network")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val MODEL = "gemini-3.8-flash"
        private const val MAX_STEPS = 6
    }
}
