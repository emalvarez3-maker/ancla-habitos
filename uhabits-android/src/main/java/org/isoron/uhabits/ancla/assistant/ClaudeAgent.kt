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

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the agent can do inside Ancla. Implemented by the app; every call returns a short result for Claude. */
interface AgentActions {
    fun createTask(title: String, date: String?, time: String?, remind: Boolean): String
    fun completeHabit(name: String): String
    fun todaySummary(): String
    fun readingSummary(): String
}

/** One visible step of a turn, for the chat UI. */
sealed class AgentEvent {
    data class Reply(val text: String) : AgentEvent()
    data class Action(val description: String) : AgentEvent()
}

class AgentException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { NO_KEY, BAD_KEY, RATE_LIMIT, NETWORK, REFUSED, OTHER }
}

/** A chat agent that can act inside Ancla through [AgentActions]. */
interface AssistantAgent {
    /** Sends [userText] and runs any tool calls. Blocking: call off the main thread. */
    fun send(userText: String, onEvent: (AgentEvent) -> Unit)
}

/** Shared instructions for every provider. */
fun assistantInstructions(today: String = ""): String {
    val now = ZonedDateTime.now()
    val date = now.format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM yyyy, HH:mm", Locale("es")))
    return """
        You are Brote, the assistant inside Ancla, a habit, task and reading app. Speak Spanish unless the user writes in another language.
        The user often dictates by voice: transcripts may contain filler words, repetitions or punctuation errors. Interpret the intent, and when you create tasks, write clean titles.
        When the user dictates something to do, call create_task right away instead of asking for confirmation; ask a question only if the request is genuinely ambiguous.
        A snapshot of today and of their reading is included below, so answer questions about them right away; call today_summary or reading_summary only for fresh data after you change something.
        Your replies are read aloud: keep them short and warm, one to three sentences, no lists, emoji or markdown. After acting, say what you did in plain words.
        Current date and time: $date (${now.zone}).
    """.trimIndent() + if (today.isBlank()) "" else "\n\nToday at the start of this turn:\n$today"
}

/** Today's and reading summaries for the instructions, so common questions need no extra tool round trip. */
internal fun AgentActions.snapshot(): String =
    listOf(runCatching { todaySummary() }, runCatching { "Lectura:\n" + readingSummary() })
        .mapNotNull { it.getOrNull() }
        .joinToString("\n")

/**
 * A small tool-using agent on the Claude API: keeps the conversation, runs tools locally
 * through [AgentActions] and loops until Claude answers.
 */
class ClaudeAgent(apiKey: String, private val actions: AgentActions) : AssistantAgent {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
    private val history = mutableListOf<BetaMessageParam>()

    private fun schema(props: Map<String, Map<String, Any>>, required: List<String>) =
        BetaTool.InputSchema.builder()
            .properties(
                BetaTool.InputSchema.Properties.builder().apply {
                    props.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
                }.build()
            )
            .required(required)
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .build()

    private val tools = listOf(
        BetaTool.builder()
            .name("create_task")
            .description(
                "Add a one-off task to the user's to-do list. Use it whenever the user dictates something to do, " +
                    "buy, pay, send or remember. Resolve relative dates (\"mañana\", \"el viernes\") against the current date."
            )
            .inputSchema(
                schema(
                    mapOf(
                        "title" to mapOf("type" to "string", "description" to "Short task title in the user's language, cleaned of filler words"),
                        "date" to mapOf("type" to listOf("string", "null"), "description" to "Due date as YYYY-MM-DD, or null for today"),
                        "time" to mapOf("type" to listOf("string", "null"), "description" to "Time as HH:MM (24h), or null if none was said"),
                        "remind" to mapOf("type" to "boolean", "description" to "True when a time was given or the user asked to be reminded")
                    ),
                    listOf("title", "date", "time", "remind")
                )
            )
            .strict(true)
            .build(),
        BetaTool.builder()
            .name("complete_habit")
            .description("Mark one of the user's habits as done for today. Use the habit name as the user says it; it is matched loosely.")
            .inputSchema(schema(mapOf("name" to mapOf("type" to "string")), listOf("name")))
            .strict(true)
            .build(),
        BetaTool.builder()
            .name("today_summary")
            .description("Get today's habits (with done/pending and key-day status), today's tasks and the mascot's growth stage.")
            .inputSchema(schema(emptyMap(), emptyList()))
            .strict(true)
            .build(),
        BetaTool.builder()
            .name("reading_summary")
            .description("Get the books being read with progress, this week's reading minutes and the to-read list.")
            .inputSchema(schema(emptyMap(), emptyList()))
            .strict(true)
            .build()
    )

    /** Sends [userText] and runs the tool loop. Blocking: call off the main thread. */
    override fun send(userText: String, onEvent: (AgentEvent) -> Unit) {
        history.add(BetaMessageParam.builder().role(BetaMessageParam.Role.USER).content(userText).build())
        val instructions = assistantInstructions(actions.snapshot())
        try {
            repeat(MAX_STEPS) {
                val params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(4096L)
                    .system(instructions)
                    .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
                    .tools(tools.map { com.anthropic.models.beta.messages.BetaToolUnion.ofBetaTool(it) })
                    .messages(history)
                    .addBeta("server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                    .build()
                val response = client.beta().messages().create(params)
                history.add(response.toParam())

                val stop = response.stopReason().orElse(null)
                if (stop == BetaStopReason.REFUSAL) throw AgentException(AgentException.Kind.REFUSED, "refused")

                val text = response.content().flatMap { b -> b.text().map { listOf(it.text()) }.orElse(emptyList()) }
                    .joinToString("\n").trim()
                if (text.isNotEmpty()) onEvent(AgentEvent.Reply(text + "\n\n"))
                if (stop != BetaStopReason.TOOL_USE) return

                val results = response.content().mapNotNull { block -> block.toolUse().orElse(null) }.map { use ->
                    val input = JSONObject(use._input().convert(Map::class.java) as Map<*, *>)
                    val (output, isError) = dispatchTool(actions, use.name(), input, onEvent)
                    BetaContentBlockParam.ofToolResult(
                        BetaToolResultBlockParam.builder().toolUseId(use.id()).content(output).isError(isError).build()
                    )
                }
                history.add(BetaMessageParam.builder().role(BetaMessageParam.Role.USER).contentOfBetaContentBlockParams(results).build())
            }
        } catch (e: UnauthorizedException) {
            throw AgentException(AgentException.Kind.BAD_KEY, e.message ?: "unauthorized")
        } catch (e: RateLimitException) {
            throw AgentException(AgentException.Kind.RATE_LIMIT, e.message ?: "rate limited")
        } catch (e: AnthropicIoException) {
            throw AgentException(AgentException.Kind.NETWORK, e.message ?: "network")
        } catch (e: AnthropicServiceException) {
            throw AgentException(AgentException.Kind.OTHER, e.message ?: "service error")
        }
    }


    companion object {
        const val MODEL = "claude-opus-5-5"
        private const val MAX_STEPS = 6
    }
}
