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

package org.isoron.uhabits.ancla.reading

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import org.isoron.platform.time.getToday
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.assistant.GeminiVoice
import org.isoron.uhabits.ancla.assistant.SecureKeys
import org.isoron.uhabits.ancla.assistant.Speaker
import org.isoron.uhabits.ancla.ui.AnclaTheme
import org.isoron.uhabits.core.models.Book
import org.isoron.uhabits.core.models.BookStatus
import org.isoron.uhabits.core.models.ReadingSession
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.roundToInt

enum class ReaderTheme(val bg: String, val fg: String, val link: String) {
    LIGHT("#FBFAF6", "#211F33", "#2E7D5B"),
    SEPIA("#F3E9D7", "#3B2F22", "#8A5300"),
    DARK("#15131F", "#E6E3F2", "#7FD6AE")
}

/** Reads an imported EPUB, remembers the position and logs reading time automatically. */
class ReaderActivity : AppCompatActivity() {

    private lateinit var library: Library
    private lateinit var epub: EpubBook
    private lateinit var book: Book
    private lateinit var webView: WebView
    private val prefs by lazy { getSharedPreferences("ancla_reader", Context.MODE_PRIVATE) }

    private val chapter = mutableIntStateOf(0)
    private val fraction = mutableDoubleStateOf(0.0)
    private val barsVisible = mutableStateOf(true)
    private val fontSize = mutableIntStateOf(19)
    private val theme = mutableStateOf(ReaderTheme.LIGHT)
    private val askFinish = mutableStateOf(false)

    // Narration: the chapter's paragraphs from the current position, spoken a few at a time.
    private val narrating = mutableStateOf(false)
    private var speaker: Speaker? = null
    private var blocks: List<Pair<Int, String>> = emptyList()
    private var blockAt = 0
    private var narrateOnLoad = false

    // Reading time: counted in ticks while the reader is visible and was touched recently.
    private val handler = Handler(Looper.getMainLooper())
    private var activeMillis = 0L
    private var lastInteraction = 0L
    private var startPage = 0
    private var restoreFraction = 0.0

    private val tick = object : Runnable {
        override fun run() {
            if (SystemClock.elapsedRealtime() - lastInteraction < IDLE_LIMIT_MS) activeMillis += TICK_MS
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        library = Library(this)
        val loaded = library.repo.findById(intent.getLongExtra(EXTRA_BOOK_ID, -1))
        if (loaded == null || !loaded.isEbook || !library.fileOf(loaded).exists()) {
            Toast.makeText(this, R.string.reader_missing, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        book = loaded
        epub = try {
            EpubBook(library.fileOf(book))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.reader_broken, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        fontSize.intValue = prefs.getInt("fontSize", 19)
        theme.value = ReaderTheme.entries.getOrElse(prefs.getInt("theme", 0)) { ReaderTheme.LIGHT }
        applySystemBars()
        val parts = book.location.split(':')
        chapter.intValue = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, epub.chapters.size - 1) ?: 0
        restoreFraction = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        fraction.doubleValue = restoreFraction
        startPage = book.currentPage

        webView = createWebView()
        if (intent.getBooleanExtra(EXTRA_NARRATE, false)) {
            narrating.value = true
            narrateOnLoad = true
            narrationStarted()
        }
        loadChapter(chapter.intValue, restoreFraction)

        setContent {
            AnclaTheme {
                ReaderScreen(
                    title = book.title,
                    webView = webView,
                    chapter = chapter.intValue,
                    chapterCount = epub.chapters.size,
                    progress = epub.progressAt(chapter.intValue, fraction.doubleValue),
                    barsVisible = barsVisible.value,
                    theme = theme.value,
                    askFinish = askFinish.value,
                    onBack = { finish() },
                    onPrev = { goTo(chapter.intValue - 1, 1.0) },
                    onNext = { goTo(chapter.intValue + 1, 0.0) },
                    onSmaller = { setFont(fontSize.intValue - 1) },
                    onBigger = { setFont(fontSize.intValue + 1) },
                    onTheme = { cycleTheme() },
                    narrating = narrating.value,
                    onNarrate = { if (narrating.value) stopNarration() else startNarration() },
                    onFinish = { stars -> finishBook(stars) },
                    onDismissFinish = { askFinish.value = false }
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        isVerticalScrollBarEnabled = false
        addJavascriptInterface(Bridge(), "Ancla")
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host != HOST) return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                val path = EpubBook.resolve("", url.path ?: "")
                val bytes = epub.read(path) ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, ByteArrayInputStream(ByteArray(0)))
                val mime = mimeOf(path)
                val body = if (mime.contains("html")) inject(bytes.toString(Charsets.UTF_8)).toByteArray() else bytes
                return WebResourceResponse(mime, "utf-8", ByteArrayInputStream(body))
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host != HOST) return true
                val target = EpubBook.resolve("", url.path ?: "")
                val index = epub.chapters.indexOf(target)
                if (index >= 0) goTo(index, 0.0)
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                val at = restoreFraction
                view.postDelayed({ view.evaluateJavascript("window.anclaRestore && window.anclaRestore($at)", null) }, 120)
                if (narrateOnLoad) {
                    narrateOnLoad = false
                    // Opened by Dexter: give its spoken reply a moment before the story starts.
                    view.postDelayed({ if (narrating.value) collectAndSpeak() }, if (blocks.isEmpty()) 1800 else 500)
                }
            }
        }
    }

    private fun css(): String {
        val t = theme.value
        return """
            html, body { background: ${t.bg} !important; color: ${t.fg} !important; }
            body { font-family: Georgia, 'Noto Serif', serif !important; font-size: ${fontSize.intValue}px !important;
                   line-height: 1.65 !important; margin: 0 auto !important; max-width: 38em;
                   padding: 72px 22px 140px !important; word-wrap: break-word; -webkit-text-size-adjust: 100%; }
            p, li, div, span { font-size: inherit !important; line-height: inherit !important; color: inherit !important; }
            p, li { text-align: start !important; -webkit-hyphens: auto; hyphens: auto; }
            a { color: ${t.link} !important; }
            img, svg, video { max-width: 100% !important; height: auto !important; }
            h1, h2, h3 { line-height: 1.25 !important; }
            pre, table { max-width: 100%; overflow-x: auto; }
            .ancla-now { background: ${t.link}22 !important; border-radius: 6px; box-shadow: 0 0 0 6px ${t.link}22; }
        """.trimIndent()
    }

    private fun inject(html: String): String {
        val head = """
            <meta name="viewport" content="width=device-width, initial-scale=1" />
            <style id="ancla-style">/*<![CDATA[*/${css()}/*]]>*/</style>
            <script>//<![CDATA[
              (function(){
                var last = 0, quiet = 0;
                function frac(){ var h = document.documentElement.scrollHeight - window.innerHeight; return h > 0 ? window.scrollY / h : 1; }
                window.addEventListener('scroll', function(){ var n = Date.now(); if (n < quiet) return; if (n - last > 400) { last = n; Ancla.onScroll(frac(), true); } }, {passive:true});
                document.addEventListener('click', function(e){ if (!e.target.closest('a')) Ancla.onTap(); });
                window.anclaRestore = function(f){ quiet = Date.now() + 600; var h = document.documentElement.scrollHeight - window.innerHeight; window.scrollTo(0, Math.max(0, h * f)); Ancla.onScroll(frac(), false); };
                window.anclaStyle = function(css){ document.getElementById('ancla-style').textContent = css; };
                window.anclaBlocks = function(){
                  var els = document.body.querySelectorAll('p,h1,h2,h3,h4,h5,h6,li,blockquote,dt,dd'), out = [];
                  for (var i = 0; i < els.length; i++) {
                    var e = els[i];
                    if (e.querySelector('p,li')) continue;
                    var t = (e.innerText || '').replace(/\s+/g, ' ').trim();
                    if (!t) continue;
                    e.setAttribute('data-an', i);
                    if (e.getBoundingClientRect().bottom < 60) continue;
                    out.push([i, t]);
                  }
                  if (!out.length) (document.body.innerText || '').split(/\n+/).forEach(function(t){ t = t.trim(); if (t) out.push([-1, t]); });
                  return out;
                };
                window.anclaMark = function(i){
                  var o = document.querySelector('.ancla-now'); if (o) o.classList.remove('ancla-now');
                  var e = i >= 0 && document.querySelector('[data-an="' + i + '"]'); if (!e) return;
                  e.classList.add('ancla-now'); quiet = Date.now() + 900;
                  e.scrollIntoView({block: 'center', behavior: 'smooth'});
                  setTimeout(function(){ Ancla.onScroll(frac(), false); }, 950);
                };
              })();
            //]]></script>
        """.trimIndent()
        val i = html.indexOf("</head>", ignoreCase = true)
        return if (i >= 0) html.substring(0, i) + head + html.substring(i) else head + html
    }

    private fun mimeOf(path: String) = when (path.substringAfterLast('.').lowercase()) {
        "xhtml", "xht" -> "application/xhtml+xml"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        "ttf", "otf" -> "font/ttf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    private fun loadChapter(index: Int, restore: Double) {
        restoreFraction = restore
        webView.loadUrl("https://$HOST/${epub.chapters[index]}")
    }

    private fun goTo(index: Int, at: Double) {
        if (index < 0) return
        if (index >= epub.chapters.size) {
            askFinish.value = true
            return
        }
        interacted()
        if (narrating.value) {
            speaker?.stop()
            narrateOnLoad = true
        }
        chapter.intValue = index
        fraction.doubleValue = at
        loadChapter(index, at)
        savePosition()
    }

    // --- Narration ---

    private fun narrationStarted() {
        if (speaker == null) {
            val keys = SecureKeys(this)
            speaker = Speaker(this, { keys.cloudVoice }, { keys.phoneVoice }) {}.also {
                it.style = GeminiVoice.NARRATOR_STYLE
                it.rate = 1f
            }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun startNarration() {
        narrating.value = true
        narrationStarted()
        collectAndSpeak()
    }

    private fun stopNarration() {
        narrating.value = false
        narrateOnLoad = false
        speaker?.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        webView.evaluateJavascript("window.anclaMark && window.anclaMark(-1)", null)
    }

    /** Reads the paragraphs from the current position of the page and starts speaking them. */
    private fun collectAndSpeak() {
        webView.evaluateJavascript("window.anclaBlocks ? window.anclaBlocks() : []") { result ->
            val arr = runCatching { JSONArray(result) }.getOrNull()
            blocks = (0 until (arr?.length() ?: 0)).map { i -> arr!!.getJSONArray(i).let { it.getInt(0) to it.getString(1) } }
            blockAt = 0
            speakNext()
        }
    }

    private fun speakNext() {
        if (!narrating.value) return
        if (blockAt >= blocks.size) {
            if (chapter.intValue + 1 >= epub.chapters.size) {
                stopNarration()
                askFinish.value = true
            } else {
                goTo(chapter.intValue + 1, 0.0)
            }
            return
        }
        // A few paragraphs at a time: fewer voice requests, and natural pauses between them.
        val first = blocks[blockAt].first
        val chunk = StringBuilder()
        while (blockAt < blocks.size && (chunk.isEmpty() || chunk.length + blocks[blockAt].second.length < NARRATION_CHARS)) {
            chunk.append(blocks[blockAt].second).append('\n')
            blockAt++
        }
        webView.evaluateJavascript("window.anclaMark && window.anclaMark($first)", null)
        interacted() // listening counts as reading time
        speaker?.apply {
            begin { handler.post { speakNext() } }
            append(chunk.toString())
            finish()
        }
    }

    private fun applySystemBars() {
        val light = theme.value != ReaderTheme.DARK
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun setFont(size: Int) {
        fontSize.intValue = size.coerceIn(14, 30)
        prefs.edit().putInt("fontSize", fontSize.intValue).apply()
        restyle()
    }

    private fun cycleTheme() {
        theme.value = ReaderTheme.entries[(theme.value.ordinal + 1) % ReaderTheme.entries.size]
        applySystemBars()
        prefs.edit().putInt("theme", theme.value.ordinal).apply()
        restyle()
    }

    private fun restyle() {
        val keep = fraction.doubleValue
        val js = org.json.JSONObject.quote(css())
        webView.evaluateJavascript("window.anclaStyle && window.anclaStyle($js); window.anclaRestore && window.anclaRestore($keep);", null)
    }

    private fun interacted() {
        lastInteraction = SystemClock.elapsedRealtime()
    }

    private fun currentPage() = (book.pages * epub.progressAt(chapter.intValue, fraction.doubleValue)).roundToInt()

    private fun savePosition() {
        val page = currentPage()
        book = book.copy(
            location = "${chapter.intValue}:${"%.4f".format(java.util.Locale.ROOT, fraction.doubleValue)}",
            currentPage = page,
            status = if (book.status == BookStatus.WANT) BookStatus.READING else book.status
        )
        library.repo.update(book)
    }

    private fun logSession() {
        val minutes = (activeMillis / 60_000.0).roundToInt()
        if (minutes >= 1) {
            library.repo.insertSession(
                ReadingSession(bookId = book.id!!, day = getToday(), minutes = minutes, pages = (currentPage() - startPage).coerceAtLeast(0))
            )
            activeMillis = 0
            startPage = currentPage()
        }
    }

    private fun finishBook(stars: Int) {
        askFinish.value = false
        book = book.copy(status = BookStatus.FINISHED, rating = stars, finishedDay = getToday(), currentPage = book.pages)
        library.repo.update(book)
        logSession()
        Toast.makeText(this, R.string.reader_finished_toast, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onResume() {
        super.onResume()
        interacted()
        handler.postDelayed(tick, TICK_MS)
    }

    override fun onPause() {
        if (narrating.value) stopNarration()
        handler.removeCallbacks(tick)
        if (::book.isInitialized) {
            savePosition()
            logSession()
        }
        super.onPause()
    }

    override fun onDestroy() {
        speaker?.shutdown()
        if (::epub.isInitialized) epub.close()
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onScroll(f: Double, user: Boolean) {
            handler.post {
                fraction.doubleValue = f.coerceIn(0.0, 1.0)
                if (user) {
                    interacted()
                    if (f > 0.02) barsVisible.value = false
                }
            }
        }

        @JavascriptInterface
        fun onTap() {
            handler.post {
                interacted()
                barsVisible.value = !barsVisible.value
            }
        }
    }

    companion object {
        const val EXTRA_BOOK_ID = "bookId"
        const val EXTRA_NARRATE = "narrate"
        private const val NARRATION_CHARS = 600
        private const val HOST = "book.ancla"
        private const val TICK_MS = 15_000L
        private const val IDLE_LIMIT_MS = 120_000L

        fun intent(context: Context, bookId: Long, narrate: Boolean = false) =
            Intent(context, ReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId).putExtra(EXTRA_NARRATE, narrate)
    }
}
