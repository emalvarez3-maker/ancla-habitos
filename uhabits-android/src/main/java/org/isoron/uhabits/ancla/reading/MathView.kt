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
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.isoron.uhabits.ancla.ui.Ancla
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

/**
 * Markdown with formulas, typeset by KaTeX inside a WebView that has no network and no file or
 * content access: the page and KaTeX's scripts, styles and fonts are served from the app's assets
 * through [WebViewClient.shouldInterceptRequest], like the EPUB reader does. [[Links]] call [onLink].
 *
 * The WebView is as tall as its content (the page reports its height), so it sits inside a
 * scrolling Compose column. New text is pushed into the loaded page, without reloading it, so
 * formulas with live values can change many times a second without flicker.
 */
@Composable
fun MathView(markdown: String, modifier: Modifier = Modifier, exists: (String) -> Boolean = { true }, onLink: (String) -> Unit = {}) {
    val c = Ancla.colors
    var heightDp by remember { mutableIntStateOf(0) }
    val link by rememberUpdatedState(onLink)
    val html = MathMarkdown.toHtml(markdown, exists)
    val theme = listOf(c.ink, c.inkSoft, c.muted, c.lilacInk, c.track, c.lilac).joinToString(",") { it.css() }
    AndroidView(
        factory = { context -> MathWebView(context, onHeight = { heightDp = it }, onLink = { link(it) }) },
        update = { it.show(html, theme) },
        modifier = modifier.fillMaxWidth().height(heightDp.coerceAtLeast(MIN_HEIGHT).dp)
    )
}

/** A single formula (TeX, no delimiters), e.g. one whose numbers follow a simulator's sliders. */
@Composable
fun MathFormula(tex: String, modifier: Modifier = Modifier) = MathView("$$" + tex.replace("$$", "") + "$$", modifier)

private const val MIN_HEIGHT = 24
private const val HOST = "math.ancla"

private fun Color.css() = String.format("#%06X", toArgb() and 0xFFFFFF)

@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
private class MathWebView(context: Context, onHeight: (Int) -> Unit, onLink: (String) -> Unit) : WebView(context) {
    private var loaded = false
    private var pending: String? = null
    private var shown: String? = null

    /** The page's only way to talk to the app: its content height, in CSS pixels (= dp). */
    private class Bridge(private val view: WebView, private val onHeight: (Int) -> Unit) {
        @JavascriptInterface
        fun height(px: Int) {
            view.post { onHeight(px) }
        }
    }

    init {
        settings.javaScriptEnabled = true // KaTeX typesets in JavaScript
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        settings.textZoom = (resources.configuration.fontScale * 100).roundToInt()
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        addJavascriptInterface(Bridge(this, onHeight), "AnclaMath")
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = serve(view.context, request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == HOST && url.path == "/link") url.getQueryParameter("t")?.takeIf { it.isNotBlank() }?.let(onLink)
                return true // nothing ever navigates away
            }

            override fun onPageFinished(view: WebView, url: String) {
                loaded = true
                pending?.let { view.evaluateJavascript(it, null) }
                pending = null
            }
        }
        loadUrl("https://$HOST/index.html")
    }

    fun show(html: String, theme: String) {
        val key = theme + "\u0000" + html
        if (key == shown) return
        shown = key
        val js = "ancla.show(${JSONObject.quote(html)}, ${JSONObject.quote(theme)})"
        if (loaded) evaluateJavascript(js, null) else pending = js
    }

    private companion object {
        fun empty(code: Int) = WebResourceResponse("text/plain", "utf-8", code, if (code == 404) "Not found" else "OK", null, ByteArrayInputStream(ByteArray(0)))

        fun serve(context: Context, url: Uri): WebResourceResponse {
            if (url.scheme != "https" || url.host != HOST) return empty(404)
            val path = url.path.orEmpty()
            if (path == "/index.html") return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(PAGE.toByteArray()))
            if (!MathMarkdown.isServableAsset(path)) return empty(404)
            val bytes = runCatching { context.assets.open(path.removePrefix("/")).use { it.readBytes() } }.getOrNull() ?: return empty(404)
            val mime = when (path.substringAfterLast('.')) {
                "js" -> "application/javascript"
                "css" -> "text/css"
                else -> "font/woff2"
            }
            return WebResourceResponse(mime, "utf-8", ByteArrayInputStream(bytes))
        }

        /** The page: KaTeX, the note styles (colors come from the app with each update) and a tiny API. */
        val PAGE = """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <link rel="stylesheet" href="katex/katex.min.css">
            <style>
            html, body { margin: 0; padding: 0; background: transparent; }
            body { font-family: sans-serif; font-size: 16px; line-height: 1.55; color: var(--ink); -webkit-text-size-adjust: none; overflow-wrap: break-word; }
            #c { padding: 1px 0; }
            h1 { font-size: 1.45em; margin: .45em 0 .2em; line-height: 1.25; }
            h2 { font-size: 1.25em; margin: .45em 0 .2em; line-height: 1.25; }
            h3 { font-size: 1.1em; margin: .4em 0 .15em; }
            p { margin: .2em 0; }
            .gap { height: .45em; }
            blockquote { margin: .3em 0; padding-left: 10px; border-left: 3px solid var(--accent); color: var(--soft); font-style: italic; }
            ul, ol { margin: .2em 0; padding-left: 1.4em; }
            li { margin: .15em 0; }
            li.task { list-style: none; margin-left: -1.2em; }
            li.done { color: var(--muted); text-decoration: line-through; }
            a.link { color: var(--link); font-weight: 600; text-decoration: none; }
            a.new { color: var(--muted); text-decoration: underline; }
            code { font-family: monospace; background: var(--track); padding: 0 .25em; border-radius: 4px; }
            pre { background: var(--track); padding: .6em .8em; border-radius: 10px; overflow-x: auto; margin: .35em 0; }
            pre code { padding: 0; background: none; font-size: .88em; line-height: 1.45; }
            .display, .katex-display { overflow-x: auto; overflow-y: hidden; }
            .katex-display { margin: .35em 0; padding: .1em 0; }
            .katex { font-size: 1.12em; }
            </style>
            <script src="katex/katex.min.js"></script>
            <script src="katex/contrib/auto-render.min.js"></script>
            </head><body><div id="c"></div>
            <script>
            var ancla = {
              show: function (html, theme) {
                var t = theme.split(','), s = document.documentElement.style;
                ['--ink', '--soft', '--muted', '--link', '--track', '--accent'].forEach(function (k, i) { s.setProperty(k, t[i]); });
                var c = document.getElementById('c');
                c.innerHTML = html;
                renderMathInElement(c, {
                  delimiters: [{ left: '\\[', right: '\\]', display: true }, { left: '\\(', right: '\\)', display: false }],
                  throwOnError: false
                });
                ancla.fit();
                ancla.report();
              },
              // A formula wider than the screen (a matrix, a long derivation) shrinks to fit, down to
              // 65 % of the normal size; past that it scrolls sideways as before.
              fit: function () {
                document.querySelectorAll('#c .katex-display').forEach(function (el) {
                  var k = el.querySelector('.katex');
                  if (!k) return;
                  var size = 1.12;
                  k.style.fontSize = '';
                  for (var i = 0; i < 6 && el.scrollWidth > el.clientWidth + 1 && size > 0.73; i++) {
                    size = Math.max(0.73, size * (el.clientWidth - 2) / el.scrollWidth);
                    k.style.fontSize = size + 'em';
                  }
                });
              },
              report: function () {
                AnclaMath.height(Math.ceil(document.getElementById('c').getBoundingClientRect().height));
              }
            };
            new ResizeObserver(ancla.report).observe(document.getElementById('c'));
            // KaTeX's fonts load only when a formula first needs them, after the layout, and change every width.
            if (document.fonts) {
              document.fonts.ready.then(ancla.report);
              document.fonts.addEventListener('loadingdone', function () { ancla.fit(); ancla.report(); });
            }
            </script></body></html>
        """.trimIndent()
    }
}
