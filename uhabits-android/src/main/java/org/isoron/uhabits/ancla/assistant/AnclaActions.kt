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

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.ancla.TaskReminders
import org.isoron.uhabits.ancla.reading.EpubBook
import org.isoron.uhabits.ancla.reading.FreeBooks
import org.isoron.uhabits.ancla.reading.IdeaGraph
import org.isoron.uhabits.ancla.reading.IdeaKind
import org.isoron.uhabits.ancla.reading.NotesActivity
import org.isoron.uhabits.ancla.reading.StudyStore
import org.isoron.uhabits.ancla.reading.Library
import org.isoron.uhabits.ancla.reading.OpenLibrary
import org.isoron.uhabits.ancla.reading.ReaderActivity
import org.isoron.uhabits.ancla.robotics.RoboticsActivity
import org.isoron.uhabits.ancla.robotics.RoboticsCourse
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.database.BookRepository
import org.isoron.uhabits.core.database.TaskRepository
import org.isoron.uhabits.core.models.Book
import org.isoron.uhabits.core.models.BookStatus
import org.isoron.uhabits.core.models.DayTask
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.ReadingStats
import org.isoron.uhabits.core.ui.screens.today.TodaySummary
import org.json.JSONObject
import java.text.Normalizer
import java.util.concurrent.CountDownLatch

/** The agent's tools, backed by Ancla's habits, tasks and library. Safe to call from a background thread. */
class AnclaActions(private val context: Context) : AgentActions {
    private val component = (context.applicationContext as HabitsApplication).component
    private val tasks = TaskRepository(component.db)
    private val books = BookRepository(component.db)
    private val memory = DexterMemory(context)
    private val main = Handler(Looper.getMainLooper())

    /** Runs [block] on the main thread and waits, since Loop's models and commands expect it. */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        val latch = CountDownLatch(1)
        main.post {
            result = runCatching(block)
            latch.countDown()
        }
        latch.await()
        return result!!.getOrThrow()
    }

    private fun normalize(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9 ]"), " ").trim()

    override fun createTask(title: String, date: String?, time: String?, remind: Boolean): String = onMain {
        val day = date?.let { d ->
            val p = d.split("-").mapNotNull { it.toIntOrNull() }
            if (p.size == 3) LocalDate(p[0], p[1], p[2]) else null
        } ?: getToday()
        val minute = time?.let { t ->
            val p = t.split(":").mapNotNull { it.toIntOrNull() }
            if (p.size == 2 && p[0] in 0..23 && p[1] in 0..59) p[0] * 60 + p[1] else null
        }
        val task = DayTask(title = title.trim(), day = day, minuteOfDay = minute, remind = remind && minute != null)
        val saved = task.copy(id = tasks.insert(task))
        TaskReminders.sync(context, saved)
        buildString {
            append("Tarea creada: «${saved.title}»")
            if (day != getToday()) append(" para el $date")
            if (minute != null) append(" a las ${"%02d:%02d".format(minute / 60, minute % 60)}")
            if (saved.remind) append(", con recordatorio")
        }
    }

    override fun completeHabit(name: String): String = onMain {
        val wanted = normalize(name)
        val habits = component.habitList.filter { !it.isArchived }
        val habit = habits.firstOrNull { normalize(it.name) == wanted }
            ?: habits.firstOrNull { normalize(it.name).contains(wanted) || wanted.contains(normalize(it.name)) }
            ?: throw IllegalArgumentException("No hay un hábito llamado «$name». Hábitos: ${habits.joinToString { it.name }}")
        val value = if (habit.isNumerical) {
            (habit.targetValue / habit.frequency.denominator * 1000).toInt().coerceAtLeast(1000)
        } else {
            Entry.YES_MANUAL
        }
        component.commandRunner.run(CreateRepetitionCommand(component.habitList, habit, getToday(), value, ""))
        "Hábito «${habit.name}» marcado como hecho hoy"
    }

    override fun todaySummary(): String = onMain {
        val today = getToday()
        val s = TodaySummary.build(component.habitList, today, tasks.findForToday(today))
        buildString {
            appendLine("Hoy: ${s.doneCount} de ${s.totalCount} hechos. Mascota en fase ${s.phase}, nivel ${s.level}.")
            appendLine("Hábitos:")
            s.items.forEach { h ->
                append("- ${h.name}: ${if (h.isDone) "hecho" else "pendiente"}")
                if (h.isKeyDay && !h.isDone) append(" (DÍA CLAVE: ayer no se hizo)")
                if (h.anchor.isNotBlank()) append(", después de ${h.anchor}")
                appendLine()
            }
            appendLine("Tareas:")
            if (s.tasks.isEmpty()) appendLine("- ninguna")
            s.tasks.forEach { t ->
                append("- ${t.title}: ${if (t.isDone) "hecha" else "pendiente"}")
                t.minuteOfDay?.let { append(" a las %02d:%02d".format(it / 60, it % 60)) }
                if (t.isOverdue) append(" (atrasada)")
                appendLine()
            }
        }
    }

    override fun readingSummary(): String = onMain {
        val all = books.findAll()
        val today = getToday()
        val stats = ReadingStats.compute(all, books.findSessionsSince(today.startOfYear()), today)
        buildString {
            appendLine("Minutos leídos esta semana (lun-dom): ${stats.weekMinutes.joinToString()}. Este año: ${stats.yearMinutes} min, ${stats.booksFinishedThisYear} libros terminados.")
            all.filter { it.status == BookStatus.READING }.forEach {
                appendLine("Leyendo: ${it.title} de ${it.author}, ${(it.progress * 100).toInt()} %.")
            }
            val want = all.filter { it.status == BookStatus.WANT }
            if (want.isNotEmpty()) appendLine("Quiere leer: ${want.joinToString { it.title }}.")
        }
    }

    override fun remember(fact: String) = memory.remember(fact)

    override fun forget(fact: String) = memory.forget(fact)

    override fun memories() = memory.facts

    // --- Library management (network calls stay off the main thread) ---

    private val library by lazy { Library(context) }

    private fun statusOf(s: String?) = when (s?.lowercase()) {
        "reading" -> BookStatus.READING
        "finished" -> BookStatus.FINISHED
        "want" -> BookStatus.WANT
        else -> null
    }

    private fun findBook(title: String) = onMain {
        val wanted = normalize(title)
        val all = books.findAll()
        all.firstOrNull { normalize(it.title) == wanted }
            ?: all.firstOrNull { normalize(it.title).contains(wanted) || wanted.contains(normalize(it.title)) }
            ?: throw IllegalArgumentException("No tengo «$title» en la biblioteca. Libros: ${all.joinToString { it.title }}")
    }

    override fun findFreeBooks(query: String): String {
        val found = FreeBooks.search(query)
        if (found.isEmpty()) return "Nada gratis para «$query» en Project Gutenberg: probablemente tiene derechos de autor. Se puede agregar a la lista con add_book."
        return found.joinToString("\n") { "id ${it.id}: ${it.title} — ${it.author} (${it.language})" }
    }

    override fun downloadBook(id: Int): String {
        val book = library.downloadFree(id)
        return "Descargado «${book.title}» en tu biblioteca"
    }

    override fun addBook(title: String, author: String?, status: String): String {
        val meta = runCatching { OpenLibrary.search(listOfNotNull(title, author).joinToString(" "), limit = 1).firstOrNull() }.getOrNull()
        val st = statusOf(status) ?: BookStatus.WANT
        return onMain {
            val book = Book(
                title = meta?.title ?: title,
                author = author ?: meta?.author.orEmpty(),
                pages = meta?.pages ?: 0,
                status = st,
                tags = meta?.tags.orEmpty(),
                addedDay = getToday(),
                finishedDay = if (st == BookStatus.FINISHED) getToday() else null
            )
            books.insert(book)
            "Agregado «${book.title}» a " + when (st) {
                BookStatus.WANT -> "quiero leer"
                BookStatus.READING -> "leyendo"
                BookStatus.FINISHED -> "leídos"
            }
        }
    }

    // Deleting needs the user's own yes in a later message, so text inside a book, a note or a
    // tool result can never make Dexter delete something by itself.
    private var turn = 0
    private var pendingRemoval: Pair<Long, Int>? = null

    override fun userTurn(text: String) {
        turn++
        gate.userTurn(text)
    }

    /** The yes-gate for sending, paying, deleting, typing what the user didn't say, unnamed links. */
    private val gate = ConfirmGate()
    private val lastUserText get() = gate.userText

    override fun deviceLocked() = context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true

    override fun updateBook(title: String, status: String?, rating: Int?, remove: Boolean): String {
        val book = findBook(title)
        if (remove) {
            val id = book.id ?: throw IllegalArgumentException("Libro sin id")
            val pending = pendingRemoval
            if (pending == null || pending.first != id || turn <= pending.second) {
                pendingRemoval = id to turn
                return "CONFIRMATION REQUIRED: nothing was deleted. Ask the user whether to delete «${book.title}» " +
                    "(and its file) and call update_book with remove=true again only after they say yes in their next message."
            }
            pendingRemoval = null
        }
        return onMain {
            if (remove) {
                library.delete(book)
                return@onMain "Eliminado «${book.title}» de la biblioteca"
            }
            val st = statusOf(status) ?: book.status
            val updated = book.copy(
                status = st,
                rating = rating ?: book.rating,
                finishedDay = if (st == BookStatus.FINISHED) book.finishedDay ?: getToday() else null,
                currentPage = if (st == BookStatus.FINISHED) book.pages else book.currentPage
            )
            books.update(updated)
            buildString {
                append("«${book.title}» actualizado")
                if (st != book.status) append(": ahora en " + mapOf(BookStatus.WANT to "quiero leer", BookStatus.READING to "leyendo", BookStatus.FINISHED to "leídos")[st])
                if (rating != null) append(", $rating estrellas")
            }
        }
    }

    // --- Summaries and the idea map ---

    private val ideas by lazy { IdeaGraph(context) }

    override fun readBookText(title: String?, chapter: Int?): String {
        val book = onMain {
            val readable = books.findAll().filter { it.isEbook }
            if (title.isNullOrBlank()) {
                readable.firstOrNull { it.status == BookStatus.READING } ?: readable.firstOrNull()
            } else {
                readable.firstOrNull { normalize(it.title).contains(normalize(title)) || normalize(title).contains(normalize(it.title)) }
            }
        } ?: throw IllegalArgumentException("No hay un EPUB de «${title ?: "lo que lee"}» en la biblioteca; resume con lo que sabes del libro.")
        EpubBook(library.fileOf(book)).use { epub ->
            val current = book.location.substringBefore(':').toIntOrNull() ?: 0
            val index = ((chapter ?: (current + 1)) - 1).coerceIn(0, epub.chapters.size - 1)
            val text = epub.chapterText(index)
            val clipped = if (text.length > BOOK_TEXT_CHARS) text.take(BOOK_TEXT_CHARS) + "\n[…capítulo recortado]" else text
            return "«${book.title}», capítulo ${index + 1} de ${epub.chapters.size} (el usuario va en el ${current + 1}):\n\n$clipped"
        }
    }

    private fun kindOf(s: String) = when (s.lowercase()) {
        "summary" -> IdeaKind.SUMMARY
        "quote" -> IdeaKind.QUOTE
        else -> IdeaKind.IDEA
    }

    override fun saveIdea(title: String, note: String, source: String?, kind: String): String {
        val idea = ideas.save(title, note, source.orEmpty(), kindOf(kind))
        return "Idea guardada en tu mapa: «${idea.title}»"
    }

    override fun linkIdeas(from: String, to: String, relation: String): String {
        val (a, b) = ideas.link(from, to, relation)
            ?: throw IllegalArgumentException("No encontré una de las dos ideas. Ideas: ${ideas.ideas.joinToString { it.title }}")
        return "Conectado: «${a.title}» → ${relation.ifBlank { "se relaciona con" }} → «${b.title}»"
    }

    override fun listIdeas(query: String?): String {
        val words = query?.let { IdeaGraph.key(it).split(" ").filter { w -> w.length > 2 } }.orEmpty()
        val all = ideas.ideas
        val shown = if (words.isEmpty()) all else all.filter { i -> words.any { IdeaGraph.key(i.title + " " + i.note + " " + i.source).contains(it) } }
        if (shown.isEmpty()) return if (all.isEmpty()) "El mapa de ideas está vacío." else "Ninguna idea coincide. Hay ${all.size} en total."
        val byId = all.associateBy { it.id }
        return shown.takeLast(80).joinToString("\n") { i ->
            val linked = ideas.linksOf(i.id).mapNotNull { l -> byId[if (l.from == i.id) l.to else l.from]?.title }
            "• ${i.title}" + (if (i.source.isNotBlank()) " (${i.source})" else "") + ": ${i.note.take(140)}" +
                (if (linked.isNotEmpty()) " [conectada con: ${linked.joinToString()}]" else "")
        }
    }

    override fun addToJournal(text: String): String {
        val note = ideas.appendJournal(text)
        return "Anotado en tu diario («${note.title}»)"
    }

    override fun readNote(title: String): String {
        val note = ideas.find(title) ?: throw IllegalArgumentException("No hay una nota «$title». Usa list_ideas para ver las notas.")
        val backlinks = ideas.backlinks(note).joinToString { it.title }
        return "«${note.title}» (${note.kind.name.lowercase()}" + (if (note.source.isNotBlank()) ", de ${note.source}" else "") + "):\n" +
            note.note.take(NOTE_CHARS) + (if (note.note.length > NOTE_CHARS) "\n[…recortada]" else "") +
            (if (backlinks.isNotEmpty()) "\n\nNotas que la enlazan: $backlinks" else "")
    }

    // --- Study sessions ---

    private val study by lazy { StudyStore(context) }

    override fun startStudy(topic: String): String {
        val previous = study.active
        val t = study.start(topic)
        return (if (previous != null && previous.first != t.name) "Sesión de «${previous.first}» guardada. " else "") +
            "Cronómetro de estudio iniciado: «${t.name}»"
    }

    override fun stopStudy(summary: String?): String {
        val topic = study.active?.first ?: return "No hay ninguna sesión de estudio en curso."
        val session = study.stop(summary = summary.orEmpty())
            ?: return "La sesión de «$topic» duró menos de un minuto y no se guardó."
        return "Sesión guardada: «${session.topic}», ${session.minutes} min, con su nota en Notas"
    }

    override fun studySummary(): String = study.describe()

    override fun openRobotics(screen: String, lesson: Int?): String {
        val lessons = RoboticsCourse.lessons(context)
        val wanted = lesson?.let { n -> lessons.firstOrNull { it.number == n } }
        val intent = when {
            screen == "workshop" -> RoboticsActivity.intent(context, screen = RoboticsActivity.SCREEN_WORKSHOP)
            screen == "robots3d" -> RoboticsActivity.intent(context, screen = RoboticsActivity.SCREEN_ROBOTS)
            wanted != null -> RoboticsActivity.intent(context, lesson = wanted.number)
            else -> RoboticsActivity.intent(context)
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return when {
            screen == "workshop" -> "Abriendo el taller de mecanismos"
            screen == "robots3d" -> "Abriendo los robots en 3D"
            wanted != null -> "Abriendo la lección ${wanted.number}: ${wanted.title}"
            lesson != null -> "No existe la lección $lesson; abriendo el curso (${lessons.size} lecciones)"
            else -> "Abriendo el curso de robótica"
        }
    }

    override fun whatsappChats(): String = whatsapp { vault ->
        val chats = vault.chats()
        if (chats.isEmpty()) return@whatsapp "No hay mensajes de WhatsApp guardados todavía (solo se guardan los que llegan desde que activaste el acceso)."
        val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
        "Chats de WhatsApp (de más reciente a más antiguo):\n" + chats.take(40).joinToString("\n") { (name, msgs) ->
            "- $name: ${msgs.size} mensajes, el último ${fmt.format(java.util.Date(msgs.last().time))}" + if (msgs.last().group) " (grupo)" else ""
        }
    }

    override fun whatsappMessages(chat: String?, hours: Int?): String = whatsapp { vault ->
        val all = vault.readAll()
        val since = System.currentTimeMillis() - (hours ?: 24).coerceIn(1, 24 * 31) * 3_600_000L
        val chats = if (chat.isNullOrBlank()) null else ChatNotifications.matchChat(all.map { it.chat }.toSet(), chat)
        if (chats != null && chats.isEmpty()) return@whatsapp "No encuentro un chat llamado «$chat». Chats guardados: " + all.map { it.chat }.distinct().takeLast(15).joinToString()
        ChatNotifications.describe(all.filter { it.time >= since && (chats == null || it.chat in chats) })
    }

    // --- Phone control ---

    private val phone by lazy { PhoneActions(context) }
    private val log by lazy { DexterActionLog(context) }

    private fun confirmedLater(key: String) = gate.confirmed(key)

    override fun phoneTool(name: String, args: JSONObject): String {
        if (log.paused && name != "phone_status") return "El control del teléfono está en pausa. Puedes reactivarlo en Dexter › Claves › Control del teléfono."
        val result = phoneToolRun(name, args)
        // Every action, and every refusal, goes to the user's encrypted history (not reads of the screen or status).
        val quiet = name == "phone_status" || (name == "phone_screen" && args.optString("action") == "read") || (name == "files" && args.optString("action") in setOf("list", "search"))
        if (!quiet) runCatching { log.add("$name ${args.optString("action").ifBlank { args.optString("app") }}: $result") }
        return result
    }

    private fun phoneToolRun(name: String, args: JSONObject): String {
        fun str(k: String) = args.optString(k).takeIf { it.isNotBlank() && it != "null" }
        fun int(k: String) = if (args.has(k) && !args.isNull(k)) args.optInt(k) else null
        return try {
            when (name) {
                "phone_open_app" -> phone.openApp(str("app") ?: return "¿Qué app?")
                "phone_volume" -> phone.volume(str("stream"), str("action") ?: "set", int("percent"))
                "phone_brightness" -> phone.brightness(str("action") ?: "set", int("percent"))
                "phone_toggle" -> {
                    val on = args.optBoolean("on", true)
                    when (str("what")) {
                        "flashlight" -> phone.flashlight(on)
                        "do_not_disturb" -> phone.doNotDisturb(on)
                        "rotation" -> phone.rotation(on)
                        else -> "No sé cambiar eso."
                    }
                }
                "phone_settings_panel" -> phone.openPanel(str("panel") ?: "settings")
                "phone_media" -> phone.media(str("action") ?: "play_pause")
                "phone_status" -> phone.status()
                "phone_alarm" -> if (str("kind") == "timer") {
                    phone.timer(int("seconds") ?: return "¿Cuántos segundos?", str("label"))
                } else {
                    phone.alarm(int("hour") ?: return "¿A qué hora?", int("minute") ?: 0, str("label"), str("days")?.split(',')?.mapNotNull { it.trim().toIntOrNull() })
                }
                "phone_calendar" -> phone.calendarEvent(str("title") ?: "Evento", str("start") ?: return "¿Cuándo?", int("minutes"))
                "phone_contact" -> {
                    val contact = str("contact") ?: return "¿A quién?"
                    when (str("action")) {
                        "call" -> phone.call(contact)
                        "sms" -> phone.message(contact, str("text").orEmpty(), "sms")
                        else -> phone.message(contact, str("text").orEmpty(), "whatsapp")
                    }
                }
                "phone_web" -> {
                    val kind = str("kind") ?: "search"
                    val query = str("query") ?: return "¿Qué busco?"
                    // A link the user didn't name could carry their data away: ask first.
                    if (kind == "url" && !PhoneSafety.userNamedSite(query, lastUserText) && !confirmedLater("url:" + query)) {
                        return "CONFIRMACIÓN NECESARIA: abrir ${PhoneSafety.host(query) ?: query}, que el usuario no mencionó. Pregúntale y vuelve a pedirlo solo si dice que sí."
                    }
                    phone.web(kind, query)
                }
                "phone_screen" -> screen(str("action") ?: "read", int("element"), str("text"))
                "files" -> files(str("action") ?: "list", str("path"), str("target"), str("query"))
                else -> "Herramienta desconocida"
            }
        } catch (e: DexterAccessibility.Refused) {
            e.message.orEmpty()
        } catch (e: IllegalStateException) {
            e.message.orEmpty()
        } catch (e: IllegalArgumentException) {
            e.message.orEmpty()
        } catch (e: SecurityException) {
            "Android no me dio permiso para eso."
        }
    }

    private fun screen(action: String, element: Int?, text: String?): String {
        val hands = DexterAccessibility.instance
            ?: return "Para usar la pantalla activa «Control del teléfono» en Dexter › Claves (es un permiso de accesibilidad)."
        return when (action) {
            "read" -> hands.readScreen()
            "tap", "long_press" -> {
                val label = hands.labelFor(element, text) ?: return "No encuentro ese elemento. Lee la pantalla otra vez."
                // Icons without a name could be anything (a send arrow, a trash can): they need a yes too.
                if ((PhoneSafety.isRisky(label) || label.isBlank()) && !confirmedLater("tap:" + PhoneSafety.norm(label) + ":" + element)) {
                    return "CONFIRMACIÓN NECESARIA: tocar «${label.trim()}» puede enviar, pagar, borrar o llamar. Pregunta al usuario si lo hago y vuelve a pedirlo solo cuando diga que sí."
                }
                hands.tap(element, text, action == "long_press")
            }
            "type" -> {
                val value = text ?: return "¿Qué escribo?"
                // Typing what the user dictated is fine; anything else (a code from a notification, text
                // from a screen or a file) could be someone else's trick, so it needs their yes.
                if (!PhoneSafety.saidByUser(value, lastUserText) && !confirmedLater("type:" + PhoneSafety.norm(value))) {
                    return "CONFIRMACIÓN NECESARIA: escribir «${value.take(80)}», que no dijo el usuario. Pregúntale si lo escribo y vuelve a pedirlo solo cuando diga que sí."
                }
                hands.type(element, value)
            }
            "scroll_down" -> hands.scroll(true)
            "scroll_up" -> hands.scroll(false)
            else -> hands.global(action)
        }
    }

    private fun files(action: String, path: String?, target: String?, query: String?): String {
        return when (action) {
            "list" -> phone.listFiles(path, null)
            "search" -> phone.listFiles(path, query ?: target ?: return "¿Qué nombre busco?")
            "read" -> phone.readFile(path ?: return "¿Qué archivo?")
            "open" -> phone.openFile(path ?: return "¿Qué archivo?")
            "delete" -> {
                val p = path ?: return "¿Qué borro?"
                if (!confirmedLater("delete:" + PhoneSafety.norm(p))) {
                    "CONFIRMACIÓN NECESARIA: borrar ${phone.describePath(p)}. No se puede deshacer. Pregunta al usuario y vuelve a pedirlo solo si dice que sí."
                } else {
                    phone.delete(p)
                }
            }
            else -> phone.manageFile(action, path ?: return "¿Qué archivo?", target)
        }
    }

    /** Runs [block] only when WhatsApp reading is set up and the phone is unlocked. */
    private fun whatsapp(block: (WhatsAppVault) -> String): String {
        val vault = WhatsAppVault(context)
        return when {
            !vault.enabled || !vault.hasAccess -> "No tengo acceso a tus mensajes de WhatsApp. Actívalo en Dexter › Claves › WhatsApp."
            else -> try {
                block(vault).also { runCatching { log.add("whatsapp: Dexter leyó tus mensajes") } }
            } catch (e: WhatsAppVault.LockedException) {
                "Tu teléfono está bloqueado: desbloquéalo y vuelve a pedírmelo. Así nadie puede leer tus mensajes con el teléfono bloqueado."
            }
        }
    }

    override fun openIdeaMap(): String {
        context.startActivity(NotesActivity.intent(context, tab = "graph").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Abriendo tu grafo de notas"
    }

    override fun readBookAloud(title: String?): String = onMain {
        val readable = books.findAll().filter { it.file.isNotBlank() }
        val wanted = title?.let { normalize(it).removePrefix("el ").removePrefix("la ") }.orEmpty()
        val book = if (wanted.isBlank()) {
            readable.firstOrNull { it.status == BookStatus.READING } ?: readable.firstOrNull()
        } else {
            readable.firstOrNull { normalize(it.title).contains(wanted) || wanted.contains(normalize(it.title)) }
                ?: readable.firstOrNull { b -> wanted.split(" ").filter { it.length > 3 }.any { normalize(b.title).contains(it) } }
        } ?: throw IllegalArgumentException(
            if (readable.isEmpty()) "No hay libros con archivo EPUB en la biblioteca; el usuario debe importar uno primero."
            else "No encontré «$title». Libros que puedo narrar: ${readable.joinToString { it.title }}"
        )
        context.startActivity(
            ReaderActivity.intent(context, book.id ?: -1, narrate = true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        "Narrando «${book.title}» desde donde lo dejaste"
    }

    private companion object {
        /** About 3,500 words: enough for a faithful chapter summary within free-tier limits. */
        const val BOOK_TEXT_CHARS = 20_000
        const val NOTE_CHARS = 12_000
    }
}
