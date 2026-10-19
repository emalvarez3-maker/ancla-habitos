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
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.ui.AnclaTheme
import org.isoron.uhabits.ancla.ui.DexterOwl
import org.isoron.uhabits.ancla.ui.OwlState
import org.isoron.uhabits.ancla.ui.combinedClickableCompat
import kotlin.math.PI
import kotlin.math.sin

/**
 * Dexter on top of whatever app is open while you talk to it with "Oye Dexter", like Siri: a card
 * at the bottom of the screen with the owl, a glow that follows your voice, what you said and
 * Dexter's answer as it streams. Tapping it ends the conversation. Needs "Display over other
 * apps"; without it nothing is shown and Dexter still answers out loud. Android never draws it
 * over the lock screen.
 */
class DexterOverlay(private val context: Context, private val onTap: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val windows = context.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null

    private val state = mutableStateOf(OwlState.IDLE)
    private val level = mutableFloatStateOf(0f)
    private val heard = mutableStateOf("")
    private val reply = mutableStateOf("")

    private val hideLater = Runnable { remove() }

    val allowed get() = Settings.canDrawOverlays(context)

    /** Shows the card (if allowed) in [owl] state; [youSaid] and [answer] replace the texts when given. */
    fun show(owl: OwlState, youSaid: String? = null, answer: String? = null) = onMain {
        main.removeCallbacks(hideLater)
        state.value = owl
        youSaid?.let { heard.value = it }
        answer?.let { reply.value = it }
        if (view == null && allowed) add()
    }

    fun level(value: Float) = onMain { level.floatValue = value }

    fun appendReply(text: String) = onMain { reply.value += text }

    /** A new conversation turn: clears the texts. */
    fun clear() = onMain {
        heard.value = ""
        reply.value = ""
    }

    /** Leaves the last answer on screen for a moment, then hides. */
    fun hideSoon(delayMs: Long = 3500) = onMain {
        state.value = OwlState.IDLE
        main.removeCallbacks(hideLater)
        main.postDelayed(hideLater, delayMs)
    }

    fun hide() = onMain {
        main.removeCallbacks(hideLater)
        remove()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun add() {
        val owner = OverlayOwner().also { this.owner = it }
        val compose = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent { AnclaTheme(dark = true) { Card() } }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Never takes the keyboard or touches outside the card: the app below keeps working.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            windowAnimations = android.R.style.Animation_Toast
        }
        runCatching { windows.addView(compose, params) }.onFailure { owner.destroy(); this.owner = null; return }
        view = compose
    }

    private fun remove() {
        view?.let { runCatching { windows.removeView(it) } }
        view = null
        owner?.destroy()
        owner = null
    }

    @Composable
    private fun Card() {
        val owl by state
        val mic by level
        val you by heard
        val answer by reply
        val glow by animateFloatAsState(
            when (owl) {
                OwlState.LISTENING -> 0.45f + 0.55f * mic
                OwlState.SPEAKING -> 0.7f
                OwlState.THINKING -> 0.5f
                else -> 0.25f
            },
            tween(160), label = "glow"
        )
        val scroll = rememberScrollState()
        LaunchedEffect(answer) { scroll.animateScrollTo(scroll.maxValue) }
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 18.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xF2141226))
                .combinedClickableCompat(Role.Button, onTap)
        ) {
            Glow(glow, Modifier.matchParentSize())
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DexterOwl(owl, Modifier.size(64.dp), mic)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val status = when (owl) {
                        OwlState.LISTENING -> stringResource(R.string.dexter_service_listening)
                        OwlState.THINKING -> stringResource(R.string.voice_pill_thinking)
                        else -> null
                    }
                    if (you.isNotBlank()) {
                        Text("“$you”", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB9B4E0), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (answer.isNotBlank()) {
                        Text(
                            answer.trim(), style = MaterialTheme.typography.bodyLarge, color = Color.White,
                            modifier = Modifier.heightIn(max = 150.dp).verticalScroll(scroll)
                        )
                    } else if (status != null) {
                        Text(status, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                    }
                }
            }
        }
    }

    /** The Siri-like edge: a band of moving colors whose brightness follows [strength]. */
    @Composable
    private fun Glow(strength: Float, modifier: Modifier) {
        val time = remember { mutableFloatStateOf(0f) }
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (true) withFrameNanos { time.floatValue = (it - start) / 1e9f }
        }
        Canvas(modifier) {
            val t = time.floatValue
            val shift = (sin(t * 2f * PI.toFloat() / 3f) + 1f) / 2f
            val colors = listOf(Color(0xFF8A7CFF), Color(0xFFFF7AC6), Color(0xFFFFC233), Color(0xFF46C793), Color(0xFF8A7CFF))
            val brush = Brush.linearGradient(
                colors.map { it.copy(alpha = strength) },
                start = androidx.compose.ui.geometry.Offset(size.width * shift, 0f),
                end = androidx.compose.ui.geometry.Offset(size.width * (shift + 1f), size.height)
            )
            val w = 3.dp.toPx() + strength * 3.dp.toPx()
            drawRoundRect(brush, cornerRadius = CornerRadius(28.dp.toPx()), style = Stroke(w))
        }
    }
}

/** The lifecycle a ComposeView needs when it lives in a window of its own, outside any activity. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    init {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
