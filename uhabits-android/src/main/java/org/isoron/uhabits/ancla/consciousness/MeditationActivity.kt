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

package org.isoron.uhabits.ancla.consciousness

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.assistant.AssistantActivity
import org.isoron.uhabits.ancla.assistant.DexterService
import org.isoron.uhabits.ancla.assistant.GeminiVoice
import org.isoron.uhabits.ancla.assistant.SecureKeys
import org.isoron.uhabits.ancla.assistant.Speaker
import org.isoron.uhabits.ancla.ui.Ancla
import org.isoron.uhabits.ancla.ui.AnclaTheme
import org.isoron.uhabits.ancla.ui.DexterOwl
import org.isoron.uhabits.ancla.ui.OwlState
import org.isoron.uhabits.ancla.ui.combinedClickableCompat
import org.isoron.uhabits.ancla.ui.enableDexterEdgeToEdge
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * A guided practice in Dexter's calm voice: each line, then a measured silence, with a soft bell
 * at the start and the end. "Oye Dexter" rests meanwhile, so the guide's own voice can't wake it.
 * Opened from Consciencia or by Dexter's guided_practice tool.
 */
class MeditationActivity : AppCompatActivity() {
    private enum class Phase { CHOOSE, RUNNING, DONE }

    private val main = Handler(Looper.getMainLooper())
    private val phase = mutableStateOf(Phase.CHOOSE)
    private val kind = mutableStateOf(Meditation.BREATHING)
    private val minutes = mutableIntStateOf(5)
    private val line = mutableStateOf("")
    private val paused = mutableStateOf(false)
    private val speaking = mutableStateOf(false)
    private val left = mutableIntStateOf(0)

    private var cues: List<Cue> = emptyList()
    private var index = 0
    private var sittingSince = 0L
    private var satMs = 0L
    private lateinit var speaker: Speaker
    private val next = Runnable { play(index + 1) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableDexterEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val keys = SecureKeys(this)
        speaker = Speaker(this, { keys.cloudVoice }, { keys.phoneVoice }) { speaking.value = it }.apply {
            style = GeminiVoice.GUIDE_STYLE
            rate = GUIDE_RATE
        }
        kind.value = Meditation.of(intent.getStringExtra(EXTRA_KIND))
        minutes.intValue = intent.getIntExtra(EXTRA_MINUTES, 5).coerceIn(Meditation.MIN_MINUTES, Meditation.MAX_MINUTES)
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_START, false)) start()
        setContent { AnclaTheme { Screen() } }
    }

    override fun onResume() {
        super.onResume()
        DexterService.instance?.pause()
    }

    override fun onStop() {
        // Leaving the screen pauses the practice, and Dexter listens again.
        if (phase.value == Phase.RUNNING && !paused.value) pausePlayback()
        DexterService.instance?.resume()
        super.onStop()
    }

    override fun onDestroy() {
        if (phase.value == Phase.RUNNING) logSit()
        main.removeCallbacksAndMessages(null)
        speaker.shutdown()
        super.onDestroy()
    }

    private fun start() {
        cues = kind.value.steps(minutes.intValue)
        index = 0
        satMs = 0
        sittingSince = System.currentTimeMillis()
        paused.value = false
        phase.value = Phase.RUNNING
        line.value = ""
        left.intValue = remainingMinutes(0)
        Bell.ring()
        main.postDelayed({ play(0) }, BELL_LEAD_MS)
    }

    private fun play(i: Int) {
        if (phase.value != Phase.RUNNING || paused.value) return
        if (i >= cues.size) return complete()
        index = i
        val cue = cues[i]
        line.value = cue.text
        left.intValue = remainingMinutes(i)
        speaker.begin { main.postDelayed(next, cue.pauseSeconds * 1000L) }
        speaker.append(cue.text + " ")
        speaker.finish()
    }

    private fun remainingMinutes(from: Int) =
        ((cues.drop(from).sumOf { it.pauseSeconds + Meditation.speakSeconds(it.text) } + 30) / 60).coerceAtLeast(1)

    private fun pausePlayback() {
        paused.value = true
        main.removeCallbacks(next)
        speaker.stop()
        satMs += System.currentTimeMillis() - sittingSince
    }

    private fun resumePlayback() {
        paused.value = false
        sittingSince = System.currentTimeMillis()
        play(index) // the line that was cut is said again
    }

    private fun complete() {
        Bell.ring()
        logSit()
        phase.value = Phase.DONE
    }

    private fun stopEarly() {
        main.removeCallbacks(next)
        speaker.stop()
        logSit()
        phase.value = Phase.DONE
    }

    private fun logSit() {
        if (!paused.value) satMs += System.currentTimeMillis() - sittingSince
        sittingSince = System.currentTimeMillis()
        val sat = ((satMs + 30_000) / 60_000).toInt()
        satMs = 0
        val name = kind.value.title
        Thread { runCatching { ConsciousnessStore(applicationContext).addSit(name, sat) } }.start()
    }

    @Composable
    private fun Screen() {
        val c = Ancla.colors
        Column(Modifier.fillMaxSize().background(c.ground).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹", style = MaterialTheme.typography.headlineMedium, color = c.ink,
                    modifier = Modifier.combinedClickableCompat(Role.Button, { finish() }).padding(horizontal = 12.dp, vertical = 2.dp)
                )
                Column {
                    Text(stringResource(R.string.med_caption), style = MaterialTheme.typography.labelSmall, color = c.muted)
                    Text(stringResource(R.string.med_title), style = MaterialTheme.typography.titleLarge, color = c.ink)
                }
            }
            when (phase.value) {
                Phase.CHOOSE -> Choose()
                Phase.RUNNING -> Running()
                Phase.DONE -> Done()
            }
        }
    }

    @Composable
    private fun Choose() {
        val c = Ancla.colors
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.med_pick), style = MaterialTheme.typography.labelLarge, color = c.lilacInk)
            Meditation.entries.forEach { m ->
                val on = kind.value == m
                Column(
                    Modifier.fillMaxWidth().clipped(18).background(if (on) c.lilac.copy(alpha = 0.22f) else c.card)
                        .combinedClickableCompat(Role.RadioButton, { kind.value = m }).padding(14.dp)
                ) {
                    Text(m.title, style = MaterialTheme.typography.titleMedium, color = if (on) c.ink else c.inkSoft)
                    Text(m.purpose, style = MaterialTheme.typography.labelMedium, color = c.muted)
                }
            }
            Text(stringResource(R.string.med_minutes), style = MaterialTheme.typography.labelLarge, color = c.lilacInk, modifier = Modifier.padding(top = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 5, 10, 15, 20).forEach { n ->
                    val on = minutes.intValue == n
                    Text(
                        "$n", style = MaterialTheme.typography.titleMedium, color = if (on) c.onInk else c.ink, textAlign = TextAlign.Center,
                        modifier = Modifier.size(52.dp).clipped(26).background(if (on) c.amber else c.card)
                            .combinedClickableCompat(Role.RadioButton, { minutes.intValue = n }).padding(top = 13.dp)
                    )
                }
            }
            Text(
                stringResource(R.string.med_start), style = MaterialTheme.typography.titleMedium, color = c.onInk, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clipped(28).background(c.amber)
                    .combinedClickableCompat(Role.Button, { start() }).padding(vertical = 16.dp)
            )
        }
    }

    @Composable
    private fun Running() {
        val c = Ancla.colors
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(kind.value.title, style = MaterialTheme.typography.titleMedium, color = c.lilacInk)
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                BreathingHalo(kind.value == Meditation.BREATHING, paused.value)
                DexterOwl(if (paused.value) OwlState.SLEEPING else if (speaking.value) OwlState.SPEAKING else OwlState.IDLE, Modifier.size(120.dp))
            }
            Text(
                line.value, style = MaterialTheme.typography.titleLarge, color = c.ink, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )
            Text(stringResource(R.string.med_left, left.intValue), style = MaterialTheme.typography.labelMedium, color = c.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(if (paused.value) R.string.med_resume else R.string.med_pause), style = MaterialTheme.typography.labelLarge, color = c.onInk,
                    modifier = Modifier.clipped(24).background(c.amber)
                        .combinedClickableCompat(Role.Button, { if (paused.value) resumePlayback() else pausePlayback() }).padding(horizontal = 24.dp, vertical = 12.dp)
                )
                Text(
                    stringResource(R.string.med_stop), style = MaterialTheme.typography.labelLarge, color = c.ink,
                    modifier = Modifier.clipped(24).background(c.card).combinedClickableCompat(Role.Button, { stopEarly() }).padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
        }
    }

    @Composable
    private fun Done() {
        val c = Ancla.colors
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
        ) {
            DexterOwl(OwlState.IDLE, Modifier.size(120.dp))
            Text(stringResource(R.string.med_done), style = MaterialTheme.typography.headlineSmall, color = c.ink, textAlign = TextAlign.Center)
            Text(stringResource(R.string.med_done_sub), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.med_tell), style = MaterialTheme.typography.labelLarge, color = c.onInk,
                modifier = Modifier.clipped(24).background(c.amber).combinedClickableCompat(Role.Button, {
                    startActivity(AssistantActivity.intent(this@MeditationActivity))
                    finish()
                }).padding(horizontal = 24.dp, vertical = 12.dp)
            )
            Text(
                stringResource(R.string.med_close), style = MaterialTheme.typography.labelLarge, color = c.ink,
                modifier = Modifier.clipped(24).background(c.card).combinedClickableCompat(Role.Button, { finish() }).padding(horizontal = 24.dp, vertical = 12.dp)
            )
        }
    }

    /** A violet glow that swells with the breath: in 4 s, out 6 s (a slow pulse for the other practices). */
    @Composable
    private fun BreathingHalo(breathing: Boolean, still: Boolean) {
        val c = Ancla.colors
        val t = rememberInfiniteTransition(label = "halo")
        val grow by t.animateFloat(
            0.55f, 1f,
            infiniteRepeatable(if (breathing) tween(5000, easing = FastOutSlowInEasing) else tween(4000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breath"
        )
        val k = if (still) 0.6f else grow
        Canvas(Modifier.size(280.dp)) {
            val r = size.minDimension / 2 * k
            drawCircle(Brush.radialGradient(listOf(c.lilac.copy(alpha = 0.45f), c.lilac.copy(alpha = 0.08f), Color.Transparent), center, r), r, center)
            drawCircle(c.amber.copy(alpha = 0.35f), r * 0.98f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        }
    }

    private fun Modifier.clipped(radius: Int) = clip(RoundedCornerShape(radius.dp))

    /** A soft singing-bowl bell, made here so no sound file ships with the app. */
    private object Bell {
        fun ring() = Thread {
            runCatching {
                val rate = 22050
                val samples = ShortArray((rate * 3.2).toInt()) { i ->
                    val s = i.toDouble() / rate
                    val env = (1 - exp(-s * 60)) * exp(-s * 1.4)
                    val v = 0.6 * sin(2 * PI * 432 * s) + 0.25 * sin(2 * PI * 432 * 2.76 * s) * exp(-s * 2) + 0.15 * sin(2 * PI * 432 * 5.4 * s) * exp(-s * 4)
                    (v * env * 0.35 * Short.MAX_VALUE).toInt().toShort()
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(samples.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(samples, 0, samples.size)
                track.play()
                Thread.sleep(3400)
                track.release()
            }
        }.start()
    }

    companion object {
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_MINUTES = "minutes"
        private const val EXTRA_START = "start"
        private const val BELL_LEAD_MS = 2500L
        /** Slower than Dexter's brisk everyday pace. */
        private const val GUIDE_RATE = 0.9f

        /** [kind] and [minutes] given: the practice starts at once (Dexter asked for it). */
        fun intent(context: Context, kind: Meditation? = null, minutes: Int? = null): Intent =
            Intent(context, MeditationActivity::class.java).apply {
                if (kind != null) {
                    putExtra(EXTRA_KIND, kind.name)
                    putExtra(EXTRA_MINUTES, minutes ?: 5)
                    putExtra(EXTRA_START, true)
                }
            }
    }
}
