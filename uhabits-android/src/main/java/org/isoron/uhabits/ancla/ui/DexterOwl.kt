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

package org.isoron.uhabits.ancla.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** What Dexter is doing, for its face. SLEEPING is quiet mode. */
enum class OwlState { IDLE, LISTENING, THINKING, SPEAKING, SLEEPING }

/** Dexter's colors, the same as the launcher icon. */
private object Owl {
    val body = Color(0xFFB9773F)
    val light = Color(0xFFCF8E52)
    val shade = Color(0xFF8E5728)
    val tuft = Color(0xFF9C612F)
    val disc = Color(0xFFF4E2BF)
    val belly = Color(0xFFEBD3A6)
    val lid = Color(0xFFA86A34)
    val iris = Color(0xFFFFC233)
    val pupil = Color(0xFF1A1433)
    val brow = Color(0xFF3A2412)
    val beak = Color(0xFFF2A93B)
    val beakShade = Color(0xFFD98A1E)
    val sleep = Color(0xFF8A84B8)
}

/** One pose of the face; poses are blended by how much each state is showing. */
private class Pose(
    val lid: Float = 0.38f, // eyelid coverage: skeptical, half-lidded
    val perk: Float = 0f,
    val px: Float = 0f,
    val py: Float = 0f,
    val dilate: Float = 0f,
    val wide: Float = 0f,
    val browL: Float = 0f,
    val browR: Float = 0f,
    val anger: Float = 1f,
    val wingR: Float = 0f,
    val tilt: Float = 0f,
    val droop: Float = 0f,
    val lean: Float = 0f
)

private val POSES = mapOf(
    OwlState.IDLE to Pose(),
    OwlState.LISTENING to Pose(lid = 0.12f, perk = 1f, dilate = 1f, wide = 1f, anger = 0.3f, lean = 1f),
    OwlState.THINKING to Pose(lid = 0.3f, px = 0.6f, py = -0.6f, browR = 1f, anger = 0.5f, wingR = -0.5f, tilt = -6f),
    OwlState.SPEAKING to Pose(lid = 0.28f, browL = 0.2f, browR = 0.2f, anger = 0.8f),
    OwlState.SLEEPING to Pose(lid = 1f, anger = 0f, droop = 1f, tilt = 8f)
)

/**
 * Dexter, a grumpy owl: skeptical half-lidded eyes and a permanent frown, but on the user's side.
 * It breathes, blinks and tilts its head now and then; it perks up while listening (with a ring
 * that follows the mic [level]), looks away while thinking, talks with its beak and dozes in
 * quiet mode. Drawn on a Canvas in a 100×100 box scaled to the smaller side, background left clear.
 */
@Composable
fun DexterOwl(state: OwlState, modifier: Modifier = Modifier, level: Float = 0f) {
    val weights = OwlState.entries.associateWith { s ->
        animateFloatAsState(if (s == state) 1f else 0f, tween(380), label = "owl-$s")
    }
    val ring by animateFloatAsState(if (state == OwlState.LISTENING) 0.35f + 0.65f * level.coerceIn(0f, 1f) else 0f, tween(140), label = "owl-ring")
    val speech by rememberUpdatedState(level)

    // One clock for every motion; only the Canvas reads it, so frames redraw without recomposing.
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time.floatValue = (it - start) / 1e9f }
    }
    val events = remember { OwlEvents() }

    Canvas(modifier.semantics { contentDescription = "Dexter" }) {
        val t = time.floatValue
        events.update(t)
        val w = OwlState.entries.associateWith { weights.getValue(it).value }
        fun blend(f: (Pose) -> Float) = w.entries.sumOf { (s, k) -> (f(POSES.getValue(s)) * k).toDouble() }.toFloat()
        val idle = w.getValue(OwlState.IDLE)
        val listen = w.getValue(OwlState.LISTENING)
        val think = w.getValue(OwlState.THINKING)
        val speak = w.getValue(OwlState.SPEAKING)
        val sleep = w.getValue(OwlState.SLEEPING)
        val awake = 1f - sleep

        val p = Face(
            breath = sin(t * 2f * PI.toFloat() / (3.4f + sleep * 1.6f)),
            blink = events.blink(t) * awake,
            lid = blend { it.lid },
            perk = blend { it.perk },
            px = blend { it.px } + think * 0.22f * sin(t * 1.3f),
            py = blend { it.py } + think * 0.15f * sin(t * 1.1f + 1f),
            dilate = blend { it.dilate },
            wide = blend { it.wide },
            browL = blend { it.browL } + speak * 0.35f * max(0f, sin(t * 3.1f)) + idle * events.twitch(t),
            browR = blend { it.browR } + speak * 0.3f * max(0f, sin(t * 2.3f + 1f)),
            anger = blend { it.anger },
            beak = speak * (0.25f + 0.75f * max(abs(sin(t * 13f)) * 0.7f, speech.coerceIn(0f, 1f))),
            wingR = blend { it.wingR } + think * 0.16f * abs(sin(t * 6f)),
            tilt = blend { it.tilt } + idle * 6f * events.tilt(t),
            droop = blend { it.droop },
            lean = blend { it.lean },
            sleep = sleep,
            z = (t % 2.4f) / 2.4f,
            ring = ring
        )
        val side = min(size.width, size.height)
        translate((size.width - side) / 2f, (size.height - side) / 2f) {
            scale(side / 100f, side / 100f, pivot = Offset.Zero) { drawOwl(p) }
        }
    }
}

/** Random blinks, head tilts and brow twitches, each at its own unhurried pace. */
private class OwlEvents {
    private val random = Random(System.nanoTime())
    private var blinkAt = 2f
    private var blinkStart = -10f
    private var tiltAt = 6f
    private var tiltStart = -10f
    private var twitchAt = 4f
    private var twitchStart = -10f

    fun update(t: Float) {
        if (t >= blinkAt) {
            blinkStart = t
            // Now and then a double blink, like a real owl.
            blinkAt = t + if (random.nextFloat() < 0.2f) 0.35f else 3f + random.nextFloat() * 3f
        }
        if (t >= tiltAt) {
            tiltStart = t
            tiltAt = t + 7f + random.nextFloat() * 6f
        }
        if (t >= twitchAt) {
            twitchStart = t
            twitchAt = t + 5f + random.nextFloat() * 5f
        }
    }

    /** 0 open → 1 closed → 0, over 0.16 s. */
    fun blink(t: Float): Float {
        val x = (t - blinkStart) / 0.16f
        return if (x in 0f..1f) 1f - abs(2f * x - 1f) else 0f
    }

    /** A slow, suspicious head tilt that comes back: −1..1. */
    fun tilt(t: Float): Float {
        val x = (t - tiltStart) / 1.6f
        return if (x in 0f..1f) sin(PI.toFloat() * x) * (if (tiltStart.toInt() % 2 == 0) 1f else -1f) else 0f
    }

    /** One brow goes up for a moment: "really?". */
    fun twitch(t: Float): Float {
        val x = (t - twitchStart) / 0.7f
        return if (x in 0f..1f) 0.8f * sin(PI.toFloat() * x) else 0f
    }
}

private class Face(
    val breath: Float, val blink: Float, val lid: Float, val perk: Float, val px: Float, val py: Float,
    val dilate: Float, val wide: Float, val browL: Float, val browR: Float, val anger: Float, val beak: Float,
    val wingR: Float, val tilt: Float, val droop: Float, val lean: Float, val sleep: Float, val z: Float, val ring: Float
)

private fun DrawScope.oval(color: Color, cx: Float, cy: Float, rx: Float, ry: Float) =
    drawOval(color, Offset(cx - rx, cy - ry), Size(2 * rx, 2 * ry))

private fun DrawScope.drawOwl(p: Face) {
    if (p.ring > 0.01f) {
        drawCircle(Owl.iris.copy(alpha = 0.45f * p.ring), 46f + p.ring * 3f, Offset(50f, 55f), style = Stroke(2f))
    }
    // Feet.
    oval(Owl.beak, 42f, 93f, 5f, 2.6f)
    oval(Owl.beak, 58f, 93f, 5f, 2.6f)

    // Body, breathing from the feet.
    val breath = 1f + p.breath * 0.02f
    scale(breath, breath, pivot = Offset(50f, 94f)) {
        for (s in listOf(-1f, 1f)) {
            val lift = if (s > 0) p.wingR else 0f
            rotate((s * (0.25f + lift)) * 180f / PI.toFloat(), pivot = Offset(50f + s * 23f, 62f)) {
                oval(Owl.shade, 50f + s * 23f, 71f, 7f, 16f)
            }
        }
        drawOval(Brush.verticalGradient(listOf(Owl.light, Owl.body), 46f, 94f), Offset(26f, 47f), Size(48f, 46f))
        oval(Owl.belly, 50f, 75f, 14.5f, 15.5f)
        for ((x, y) in listOf(45f to 70f, 55f to 70f, 50f to 76f, 44f to 81f, 56f to 81f)) {
            val chevron = Path().apply {
                moveTo(x - 2.5f, y)
                quadraticTo(x, y + 2.5f, x + 2.5f, y)
            }
            drawPath(chevron, Owl.lid, style = Stroke(1.4f, cap = StrokeCap.Round))
        }
    }

    // Head: tilts, droops when asleep, leans in when listening.
    rotate(p.tilt, pivot = Offset(50f, 62f + p.droop * 4f)) {
        translate(0f, p.droop * 4f - p.lean * 1.5f) { drawHead(p) }
    }

    if (p.sleep > 0.05f) {
        val a = p.sleep
        drawZ(78f, 22f - p.z * 6f, 7f, Owl.sleep.copy(alpha = a * (1f - p.z * 0.6f)))
        drawZ(87f, 13f - p.z * 4f, 4.5f, Owl.sleep.copy(alpha = a * (1f - p.z * 0.6f)))
    }
}

private fun DrawScope.drawHead(p: Face) {
    val perk = p.perk * 5f
    for (s in listOf(-1f, 1f)) {
        val tuft = Path().apply {
            moveTo(50f + s * 21f, 30f)
            quadraticTo(50f + s * 28f, 14f - perk, 50f + s * 26f, 8f - perk)
            quadraticTo(50f + s * 16f, 14f, 50f + s * 8f, 20f)
            close()
        }
        drawPath(tuft, Owl.tuft)
    }
    drawOval(Brush.verticalGradient(listOf(Owl.light, Owl.body), 15f, 70f), Offset(22f, 16f), Size(56f, 52f))
    // Facial disc around both eyes.
    drawCircle(Owl.disc, 13f, Offset(38.5f, 44f))
    drawCircle(Owl.disc, 13f, Offset(61.5f, 44f))

    for (s in listOf(-1f, 1f)) {
        val ex = 50f + s * 11.5f
        val ey = 44f
        val r = 9f * (1f + p.wide * 0.08f)
        val eye = Path().apply { addOval(Rect(ex - r, ey - r, ex + r, ey + r)) }
        clipPath(eye) {
            drawRect(Owl.iris, Offset(ex - r, ey - r), Size(2 * r, 2 * r))
            val pr = 4.6f * (1f + p.dilate * 0.3f)
            val pc = Offset(ex + p.px * 3.2f, ey + p.py * 3.2f)
            drawCircle(Owl.pupil, pr, pc)
            drawCircle(Color.White, 1.4f, Offset(pc.x + 1.6f, pc.y - 1.8f))
            // The eyelid slants down toward the beak: the grumpy look.
            val cover = min(1f, p.lid + p.blink * (1f - p.lid))
            val slant = 2.2f * (1f - cover)
            val yInner = ey - r + 2 * r * cover + slant
            val yOuter = ey - r + 2 * r * cover - slant
            val lid = Path().apply {
                moveTo(ex - r - 1f, ey - r - 1f)
                lineTo(ex + r + 1f, ey - r - 1f)
                if (s < 0) {
                    lineTo(ex + r + 1f, yInner)
                    lineTo(ex - r - 1f, yOuter)
                } else {
                    lineTo(ex + r + 1f, yOuter)
                    lineTo(ex - r - 1f, yInner)
                }
                close()
            }
            drawPath(lid, Owl.lid)
            if (p.sleep > 0.05f) {
                val closed = Path().apply {
                    moveTo(ex - 6f, ey + 1f)
                    quadraticTo(ex, ey + 5f, ex + 6f, ey + 1f)
                }
                drawPath(closed, Owl.brow.copy(alpha = p.sleep), style = Stroke(1.6f, cap = StrokeCap.Round))
            }
        }
        drawCircle(Owl.shade, r, Offset(ex, ey), style = Stroke(1.2f))
    }

    // Brows: the inner ends sit low, a V-shaped frown that relaxes when asleep or listening.
    for (s in listOf(-1f, 1f)) {
        val raise = if (s < 0) p.browL else p.browR
        val brow = Path().apply {
            moveTo(50f + s * 22f, 30f - raise * 3f - p.anger * 1.5f)
            quadraticTo(50f + s * 13f, 30f - raise * 3.5f, 50f + s * 3.5f, 36.5f - raise * 4f + p.anger * 1.5f)
        }
        drawPath(brow, Owl.brow, style = Stroke(4.2f, cap = StrokeCap.Round))
    }

    // Beak: the lower half drops when Dexter talks.
    val open = p.beak * 4.5f
    val lower = Path().apply {
        moveTo(46.5f, 53f)
        lineTo(53.5f, 53f)
        lineTo(50f, 58.5f + open)
        close()
    }
    drawPath(lower, Owl.beakShade)
    val upper = Path().apply {
        moveTo(45.5f, 50f)
        quadraticTo(50f, 47.5f, 54.5f, 50f)
        lineTo(50f, 58f)
        close()
    }
    drawPath(upper, Owl.beak)
}

/** A small "z" drawn as strokes (no text measuring every frame). */
private fun DrawScope.drawZ(x: Float, y: Float, h: Float, color: Color) {
    val z = Path().apply {
        moveTo(x, y - h)
        lineTo(x + h * 0.8f, y - h)
        lineTo(x, y)
        lineTo(x + h * 0.8f, y)
    }
    drawPath(z, color, style = Stroke(h * 0.22f, cap = StrokeCap.Round))
}
