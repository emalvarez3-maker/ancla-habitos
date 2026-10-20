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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** What Dexter is doing, for its face. SLEEPING is quiet mode. */
enum class OwlState { IDLE, LISTENING, THINKING, SPEAKING, SLEEPING }

/** Dexter's colors, the same as the launcher icon: a night owl in deep plum with glowing gold eyes. */
private object Owl {
    val body = Color(0xFF3B2266)
    val light = Color(0xFF55358A)
    val shade = Color(0xFF24133F)
    val tuft = Color(0xFF1C0D33)
    val disc = Color(0xFF2A1A46)
    val belly = Color(0xFF4A3373)
    val lid = Color(0xFF2E1A50)
    val iris = Color(0xFFFFB627)
    val glow = Color(0x2EFFB627)
    val pupil = Color(0xFF0B0614)
    val brow = Color(0xFFC9B8FF)
    val beak = Color(0xFFE8B04A)
    val beakShade = Color(0xFFB8862E)
    val chevron = Color(0xFF7A62B3)
    val cheek = Color(0xFFB98CFF)
    val sleep = Color(0xFFB4A6E6)
}

/** One pose of the face; poses are blended by how much each state is showing. */
private class Pose(
    val lid: Float = 0.08f, // eyelid coverage: wide open and friendly
    val perk: Float = 0f,
    val px: Float = 0f,
    val py: Float = 0f,
    val dilate: Float = 0f,
    val wide: Float = 0f,
    val browL: Float = 0f,
    val browR: Float = 0f,
    val anger: Float = 0f, // 1 frowns; Dexter only does it a little, while thinking hard
    val wingR: Float = 0f,
    val tilt: Float = 0f,
    val droop: Float = 0f,
    val lean: Float = 0f
)

private val POSES = mapOf(
    OwlState.IDLE to Pose(),
    OwlState.LISTENING to Pose(lid = 0.02f, perk = 1f, dilate = 1f, wide = 1f, browL = 0.4f, browR = 0.4f, lean = 1f),
    OwlState.THINKING to Pose(lid = 0.2f, px = 0.6f, py = -0.6f, browR = 1f, anger = 0.25f, wingR = -0.5f, tilt = -6f),
    OwlState.SPEAKING to Pose(lid = 0.1f, browL = 0.25f, browR = 0.25f),
    OwlState.SLEEPING to Pose(lid = 1f, droop = 1f, tilt = 8f)
)

/** The idle face for each mood of the Today screen; CALM is Dexter's usual warm look. */
private val MOOD_POSES = mapOf(
    BroteMood.SLEEPY to Pose(lid = 0.62f, droop = 0.45f, tilt = 5f),
    BroteMood.CALM to Pose(),
    BroteMood.HAPPY to Pose(lid = 0.04f, perk = 0.4f, browL = 0.35f, browR = 0.35f),
    BroteMood.JOY to Pose(lid = 0f, perk = 1f, dilate = 0.6f, wide = 1f, browL = 0.7f, browR = 0.7f)
)

/**
 * Dexter, a friendly purple owl: big open amber eyes, soft brows and rosy cheeks, on the user's
 * side. It breathes, blinks and tilts its head now and then; it perks up while listening (with a
 * ring that follows the mic [level]), looks away while thinking, talks with its beak and dozes in
 * quiet mode. With [interactive], a tap makes it look where you touched, close its eyes in a happy
 * squint, hop and wave. Drawn on a Canvas in a 100×100 box scaled to the smaller side, background
 * left clear.
 *
 * On the Today screen it also does what Brote did: its idle face follows the day's [mood] (drowsy,
 * calm, pleased with blushing cheeks and sparks, overjoyed and flapping), a sprout on its head grows
 * with the habits' automaticity [stage] until it perches on a blooming branch, and it hops whenever
 * [hopKey] changes. With [stage] null (Dexter's own screens) none of that is drawn.
 */
@Composable
fun DexterOwl(
    state: OwlState,
    modifier: Modifier = Modifier,
    level: Float = 0f,
    mood: BroteMood = BroteMood.CALM,
    stage: BroteStage? = null,
    hopKey: Int = 0,
    description: String = "Dexter",
    interactive: Boolean = false
) {
    val weights = OwlState.entries.associateWith { s ->
        animateFloatAsState(if (s == state) 1f else 0f, tween(380), label = "owl-$s")
    }
    val ring by animateFloatAsState(if (state == OwlState.LISTENING) 0.35f + 0.65f * level.coerceIn(0f, 1f) else 0f, tween(140), label = "owl-ring")
    val speech by rememberUpdatedState(level)
    val moods = BroteMood.entries.associateWith { m ->
        animateFloatAsState(if (m == mood) 1f else 0f, tween(600), label = "owl-mood-$m")
    }
    val hop = remember { Animatable(0f) }
    LaunchedEffect(hopKey) {
        if (hopKey > 0) {
            hop.animateTo(-13f, tween(180, easing = FastOutSlowInEasing))
            hop.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 500f))
        }
    }
    val c = Ancla.colors
    val garden = remember(c) { Garden(c.track, c.blush, c.amber, c.lilac, c.mint) }

    // One clock for every motion; only the Canvas reads it, so frames redraw without recomposing.
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time.floatValue = (it - start) / 1e9f }
    }
    val events = remember { OwlEvents() }

    val touch = if (interactive) {
        Modifier.pointerInput(Unit) {
            detectTapGestures { at ->
                // Where the finger landed, from the owl's middle, in -1..1.
                val x = (at.x / size.width * 2f - 1f).coerceIn(-1f, 1f)
                val y = (at.y / size.height * 2f - 1f).coerceIn(-1f, 1f)
                events.poke(time.floatValue, x, y)
            }
        }
    } else {
        Modifier
    }

    Canvas(modifier.then(touch).semantics { contentDescription = description }) {
        val t = time.floatValue
        events.update(t)
        val poke = events.poke(t)
        val look = events.look(t)
        val w = OwlState.entries.associateWith { weights.getValue(it).value }
        val m = BroteMood.entries.associateWith { moods.getValue(it).value }
        // The idle pose is the mood's pose, so moods blend into each other and into the other states.
        fun pose(s: OwlState, f: (Pose) -> Float) =
            if (s == OwlState.IDLE) m.entries.sumOf { (k, v) -> (f(MOOD_POSES.getValue(k)) * v).toDouble() }.toFloat()
            else f(POSES.getValue(s))
        fun blend(f: (Pose) -> Float) = w.entries.sumOf { (s, k) -> (pose(s, f) * k).toDouble() }.toFloat()
        val idle = w.getValue(OwlState.IDLE)
        val pleased = idle * (m.getValue(BroteMood.HAPPY) + m.getValue(BroteMood.JOY))
        val joy = idle * m.getValue(BroteMood.JOY)
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
            px = blend { it.px } + think * 0.22f * sin(t * 1.3f) + look.first,
            py = blend { it.py } + think * 0.15f * sin(t * 1.1f + 1f) + look.second,
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
            ring = ring,
            drowsy = idle * m.getValue(BroteMood.SLEEPY),
            pleased = pleased,
            twinkle = 0.25f + 0.75f * (0.5f + 0.5f * sin(t * PI.toFloat() / 0.8f)),
            // Joy flaps both wings in short bursts; a poke waves them once.
            flap = joy * abs(sin(t * 9f)) * max(0f, sin(t * 1.4f)) + poke * abs(sin(t * 12f)),
            sway = sin(t * PI.toFloat() / 1.4f),
            stage = stage,
            hop = hop.value - 9f * events.pokeHop(t),
            squint = poke,
            garden = garden
        )
        val side = min(size.width, size.height)
        translate((size.width - side) / 2f, (size.height - side) / 2f) {
            scale(side / 100f, side / 100f, pivot = Offset.Zero) {
                if (p.stage != null) drawGround(p)
                translate(0f, p.hop) { drawOwl(p) }
                if (p.stage != null && p.pleased > 0.05f) {
                    val a = p.pleased.coerceIn(0f, 1f)
                    drawSpark(Offset(87f, 24f), 5.5f, garden.amber.copy(alpha = a * p.twinkle))
                    drawSpark(Offset(13f, 38f), 4f, garden.lilac.copy(alpha = a * (1.25f - p.twinkle).coerceIn(0f, 1f)))
                    drawSpark(Offset(91f, 48f), 3.2f, garden.mint.copy(alpha = a * p.twinkle))
                }
            }
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

    // --- Being tapped ---

    private var pokeStart = -10f
    private var lookX = 0f
    private var lookY = 0f

    /** A tap at ([x], [y]) in -1..1 from the owl's middle. */
    fun poke(t: Float, x: Float, y: Float) {
        pokeStart = t
        lookX = x
        lookY = y
        blinkAt = t + 1.2f // no blink in the middle of the squint
    }

    /** The happy squint after a tap: 0 → 1 quickly, held, then back over 0.9 s. */
    fun poke(t: Float): Float {
        val x = (t - pokeStart) / 0.9f
        return when {
            x < 0f || x > 1f -> 0f
            x < 0.15f -> x / 0.15f
            x < 0.6f -> 1f
            else -> 1f - (x - 0.6f) / 0.4f
        }
    }

    /** The little hop after a tap: up and down once, over 0.45 s. */
    fun pokeHop(t: Float): Float {
        val x = (t - pokeStart) / 0.45f
        return if (x in 0f..1f) sin(PI.toFloat() * x) else 0f
    }

    /** Where the eyes look after a tap, fading back to the middle over 1.6 s. */
    fun look(t: Float): Pair<Float, Float> {
        val x = (t - pokeStart) / 1.6f
        if (x !in 0f..1f) return 0f to 0f
        val k = 1f - x * x
        return lookX * 0.8f * k to lookY * 0.8f * k
    }
}

/** The theme colors the Today screen's extras are drawn with. */
private class Garden(val track: Color, val blush: Color, val amber: Color, val lilac: Color, val mint: Color)

private class Face(
    val breath: Float, val blink: Float, val lid: Float, val perk: Float, val px: Float, val py: Float,
    val dilate: Float, val wide: Float, val browL: Float, val browR: Float, val anger: Float, val beak: Float,
    val wingR: Float, val tilt: Float, val droop: Float, val lean: Float, val sleep: Float, val z: Float, val ring: Float,
    val drowsy: Float, val pleased: Float, val twinkle: Float, val flap: Float, val sway: Float,
    val stage: BroteStage?, val hop: Float, val garden: Garden, val squint: Float
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
            val lift = (if (s > 0) p.wingR else 0f) - p.flap * 0.9f
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
            drawPath(chevron, Owl.chevron, style = Stroke(1.4f, cap = StrokeCap.Round))
        }
    }

    // Head: tilts, droops when asleep, leans in when listening.
    rotate(p.tilt, pivot = Offset(50f, 62f + p.droop * 4f)) {
        translate(0f, p.droop * 4f - p.lean * 1.5f) { drawHead(p) }
    }

    val dozing = max(p.sleep, p.drowsy)
    if (dozing > 0.05f) {
        val a = dozing
        drawZ(78f, 22f - p.z * 6f, 7f, Owl.sleep.copy(alpha = a * (1f - p.z * 0.6f)))
        drawZ(87f, 13f - p.z * 4f, 4.5f, Owl.sleep.copy(alpha = a * (1f - p.z * 0.6f)))
    }
}

private fun DrawScope.drawHead(p: Face) {
    val perk = p.perk * 5f
    for (s in listOf(-1f, 1f)) {
        val tuft = Path().apply {
            moveTo(50f + s * 21f, 30f)
            quadraticTo(50f + s * 27.5f, 16f - perk, 50f + s * 27f, 3.5f - perk) // tall, pointed tufts: the night look
            quadraticTo(50f + s * 18f, 13f, 50f + s * 8f, 20f)
            close()
        }
        drawPath(tuft, Owl.tuft)
    }
    p.stage?.let { drawSprout(it, p.sway) }
    drawOval(Brush.verticalGradient(listOf(Owl.light, Owl.body), 15f, 70f), Offset(22f, 16f), Size(56f, 52f))
    // Facial disc around both eyes.
    drawCircle(Owl.disc, 13f, Offset(38.5f, 44f))
    drawCircle(Owl.disc, 13f, Offset(61.5f, 44f))
    // A faint violet warmth on the cheeks, brighter when pleased or tapped.
    val warmth = (0.12f + 0.45f * max(p.pleased, p.squint)).coerceIn(0f, 0.6f) * (1f - p.sleep * 0.5f)
    oval(Owl.cheek.copy(alpha = warmth), 33f, 55.5f, 4.6f, 2.8f)
    oval(Owl.cheek.copy(alpha = warmth), 67f, 55.5f, 4.6f, 2.8f)

    for (s in listOf(-1f, 1f)) {
        val ex = 50f + s * 11.5f
        val ey = 44f
        val r = 9f * (1f + p.wide * 0.08f)
        // The gold eyes glow a little, more when Dexter listens.
        drawCircle(Owl.glow, r + 2.6f + p.wide * 1.6f, Offset(ex, ey))
        val eye = Path().apply { addOval(Rect(ex - r, ey - r, ex + r, ey + r)) }
        clipPath(eye) {
            drawRect(Owl.iris, Offset(ex - r, ey - r), Size(2 * r, 2 * r))
            val pr = 4.6f * (1f + p.dilate * 0.3f)
            val pc = Offset(ex + p.px * 3.2f, ey + p.py * 3.2f)
            drawCircle(Owl.pupil, pr, pc)
            // Two highlights: the big eyes read as warm, not staring.
            drawCircle(Color.White, 1.9f, Offset(pc.x + 1.8f, pc.y - 2f))
            drawCircle(Color.White, 0.9f, Offset(pc.x - 2f, pc.y + 2f))
            // The eyelid comes down level: friendly, never the old skeptical slant.
            val cover = min(1f, max(p.lid + p.blink * (1f - p.lid), p.squint))
            val lid = Path().apply {
                moveTo(ex - r - 1f, ey - r - 1f)
                lineTo(ex + r + 1f, ey - r - 1f)
                lineTo(ex + r + 1f, ey - r + 2 * r * cover)
                quadraticTo(ex, ey - r + 2 * r * cover + 1.6f * (1f - cover), ex - r - 1f, ey - r + 2 * r * cover)
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
        drawCircle(Owl.shade.copy(alpha = 0.6f), r, Offset(ex, ey), style = Stroke(1f))
        // The happy squint: closed eyes curved up, "^ ^".
        if (p.squint > 0.3f) {
            val happy = Path().apply {
                moveTo(ex - 6f, ey + 2f)
                quadraticTo(ex, ey - 4.5f, ex + 6f, ey + 2f)
            }
            drawPath(happy, Owl.brow.copy(alpha = ((p.squint - 0.3f) / 0.7f).coerceIn(0f, 1f)), style = Stroke(2f, cap = StrokeCap.Round))
        }
    }

    // Brows: short soft arches that lift with interest; they only dip a little when Dexter thinks hard.
    for (s in listOf(-1f, 1f)) {
        val raise = (if (s < 0) p.browL else p.browR) + p.squint * 0.5f
        val brow = Path().apply {
            moveTo(50f + s * 19.5f, 31f - raise * 3f)
            quadraticTo(50f + s * 13f, 27f - raise * 3.5f, 50f + s * 6.5f, 30f - raise * 3.5f + p.anger * 2.5f)
        }
        drawPath(brow, Owl.brow, style = Stroke(2.4f, cap = StrokeCap.Round))
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

private object Leaf {
    val dark = Color(0xFF2F8F73)
    val light = Color(0xFF3FBF95)
    val stem = Color(0xFF23705A)
    val wood = Color(0xFF3A2A4A)
    val petal = Color(0xFFB98CFF)
    val bud = Color(0xFFFFB627)
}

/** A pointed leaf from its base to its tip, swelling to one side by [bulge]. */
private fun leaf(baseX: Float, baseY: Float, tipX: Float, tipY: Float, bulge: Float) = Path().apply {
    val mx = (baseX + tipX) / 2f
    val my = (baseY + tipY) / 2f
    val len = max(0.001f, sqrt((tipX - baseX) * (tipX - baseX) + (tipY - baseY) * (tipY - baseY)))
    val nx = -(tipY - baseY) / len
    val ny = (tipX - baseX) / len
    moveTo(baseX, baseY)
    quadraticTo(mx + nx * bulge, my + ny * bulge, tipX, tipY)
    quadraticTo(mx - nx * bulge * 0.35f, my - ny * bulge * 0.35f, baseX, baseY)
    close()
}

/** Brote's sprout, now growing from the top of Dexter's head; it sways like Brote's leaves did. */
private fun DrawScope.drawSprout(stage: BroteStage, sway: Float) {
    val base = Offset(50f, 19f)
    rotate(sway * 7f, pivot = base) {
        when (stage) {
            BroteStage.SEED -> {
                drawLine(Leaf.stem, base, Offset(50f, 12f), 2.2f, StrokeCap.Round)
                drawPath(leaf(50f, 13f, 42f, 9f, 3f), Leaf.light)
            }
            BroteStage.SPROUT, BroteStage.PLANT -> {
                val big = if (stage == BroteStage.PLANT) 1.25f else 1f
                drawLine(Leaf.stem, base, Offset(50f, 10f), 2.4f, StrokeCap.Round)
                drawPath(leaf(50f, 11f, 50f - 11f * big, 4f - 2f * big, 3.6f * big), Leaf.dark)
                drawPath(leaf(50f, 11f, 50f + 11f * big, 4f - 2f * big, -3.6f * big), Leaf.light)
            }
            BroteStage.TREE -> {
                drawLine(Leaf.stem, base, Offset(50f, 9f), 2.4f, StrokeCap.Round)
                drawPath(leaf(50f, 10f, 37f, 1f, 4.4f), Leaf.dark)
                drawPath(leaf(50f, 10f, 63f, 1f, -4.4f), Leaf.light)
                // A little flower on top.
                for (k in 0 until 5) {
                    val a = k * 2f * PI.toFloat() / 5f
                    drawCircle(Leaf.petal, 2.2f, Offset(50f + 2.6f * cos(a), 6f + 2.6f * sin(a)))
                }
                drawCircle(Leaf.bud, 1.8f, Offset(50f, 6f))
            }
        }
    }
}

/**
 * What Dexter stands on, which stays put while it hops: a shadow on the ground while the sprout is
 * young, then a leafy branch to perch on, blooming once the habits are automatic.
 */
private fun DrawScope.drawGround(p: Face) {
    val stage = p.stage ?: return
    if (stage == BroteStage.SEED || stage == BroteStage.SPROUT) {
        // The shadow shrinks while Dexter is in the air.
        val k = 1f + p.hop / 40f
        oval(p.garden.track, 50f, 96f, 21f * k, 3.4f * k)
        return
    }
    val branch = Path().apply {
        moveTo(3f, 97f)
        quadraticTo(50f, 92f, 97f, 95f)
    }
    drawPath(branch, Leaf.wood, style = Stroke(4.2f, cap = StrokeCap.Round))
    drawLine(Leaf.wood, Offset(80f, 94.5f), Offset(90f, 88f), 2.2f, StrokeCap.Round)
    val lean = p.sway * 2f
    drawPath(leaf(8f, 96.5f, 1f + lean, 88f, 3f), Leaf.dark)
    drawPath(leaf(90f, 88f, 97f + lean, 81f, -3f), Leaf.light)
    drawPath(leaf(18f, 95.5f, 14f + lean, 87f, -2.6f), Leaf.light)
    if (stage == BroteStage.TREE) {
        drawPath(leaf(84f, 92f, 78f + lean, 85f, 2.6f), Leaf.dark)
        drawCircle(Leaf.petal, 2.4f, Offset(12f, 91f))
        drawCircle(Leaf.bud, 2f, Offset(93f, 84f))
        drawCircle(Leaf.petal, 2f, Offset(72f, 92f))
    }
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
