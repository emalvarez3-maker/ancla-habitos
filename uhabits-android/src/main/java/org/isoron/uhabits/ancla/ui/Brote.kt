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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

enum class BroteMood { SLEEPY, CALM, HAPPY, JOY }

/** Growth stage, following the average automaticity of the user's habits. */
enum class BroteStage { SEED, SPROUT, PLANT, TREE }

private const val VB_W = 128f
private const val VB_H = 124f

/**
 * Ancla's mascot. Drawn in a 128×124 design space and scaled to the modifier's size.
 * Breathes, blinks and sways continuously; hops whenever [hopKey] changes.
 */
@Composable
fun Brote(
    mood: BroteMood,
    stage: BroteStage,
    hopKey: Int,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    description: String = "Brote"
) {
    val c = Ancla.colors
    val infinite = rememberInfiniteTransition(label = "brote")
    val breathe by infinite.animateFloat(
        initialValue = 1f,
        targetValue = if (animate) 1.035f else 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe"
    )
    val sway by infinite.animateFloat(
        initialValue = -6f,
        targetValue = if (animate) 7f else -6f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sway"
    )
    val blink by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 4600
                1f at 0
                1f at 4230
                0.1f at 4370
                1f at 4600
            }
        ),
        label = "blink"
    )
    val twinkle by infinite.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
        label = "twinkle"
    )
    val hop = remember { Animatable(0f) }
    LaunchedEffect(hopKey) {
        if (hopKey > 0 && animate) {
            hop.animateTo(-16f, tween(180, easing = FastOutSlowInEasing))
            hop.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 500f))
        }
    }

    val bodyColor = when (mood) {
        BroteMood.SLEEPY -> Color(0xFFA9DFC4)
        BroteMood.CALM -> Color(0xFF8FE0B6)
        BroteMood.HAPPY -> Color(0xFF7DDBA9)
        BroteMood.JOY -> Color(0xFF6CD69C)
    }
    val ink = Color(0xFF211F33)

    Canvas(modifier.semantics { contentDescription = description }) {
        val k = minOf(size.width / VB_W, size.height / VB_H)
        translate((size.width - VB_W * k) / 2, (size.height - VB_H * k) / 2) {
            scale(k, k, pivot = Offset.Zero) {
                drawOval(c.track, Offset(30f, 108f), Size(68f, 12f))
                translate(0f, hop.value) {
                    scale(1f, breathe, pivot = Offset(64f, 112f)) {
                        drawLeaves(stage, sway)
                        drawPath(bodyPath(), bodyColor)
                        drawOval(Color.White.copy(alpha = 0.35f), Offset(40f, 50f), Size(20f, 12f))
                        drawFace(mood, blink, ink, c.blush)
                    }
                }
                if (mood == BroteMood.HAPPY || mood == BroteMood.JOY) {
                    drawSpark(Offset(111f, 32f), 7f, c.amber.copy(alpha = twinkle))
                    drawSpark(Offset(18f, 47f), 5f, c.lilac.copy(alpha = (1.25f - twinkle).coerceIn(0f, 1f)))
                    drawSpark(Offset(114f, 76f), 4f, c.mint.copy(alpha = twinkle))
                }
                if (mood == BroteMood.SLEEPY) {
                    drawZ(Offset(96f, 22f), 10f, c.lilac, twinkle)
                    drawZ(Offset(108f, 10f), 7f, c.lilac.copy(alpha = 0.6f), 1.25f - twinkle)
                }
            }
        }
    }
}

private fun bodyPath() = Path().apply {
    moveTo(64f, 30f)
    cubicTo(96f, 30f, 106f, 58f, 104f, 80f)
    cubicTo(102f, 104f, 86f, 112f, 64f, 112f)
    cubicTo(42f, 112f, 26f, 104f, 24f, 80f)
    cubicTo(22f, 58f, 32f, 30f, 64f, 30f)
    close()
}

private fun DrawScope.drawLeaves(stage: BroteStage, sway: Float) {
    val dark = Color(0xFF2FB57E)
    val light = Color(0xFF46C793)
    rotate(sway, pivot = Offset(64f, 32f)) {
        when (stage) {
            BroteStage.SEED -> {
                drawLine(Color(0xFF239467), Offset(64f, 32f), Offset(64f, 24f), 3f, StrokeCap.Round)
                drawPath(
                    Path().apply {
                        moveTo(64f, 26f); cubicTo(60f, 18f, 52f, 16f, 46f, 18f); cubicTo(48f, 26f, 56f, 28f, 64f, 26f); close()
                    },
                    light
                )
            }
            BroteStage.TREE -> {
                drawCircle(dark, 15f, Offset(46f, 22f))
                drawCircle(light, 16f, Offset(64f, 14f))
                drawCircle(dark, 15f, Offset(82f, 22f))
                drawCircle(Color(0xFFFFB4A2), 4f, Offset(54f, 10f))
                drawCircle(Color(0xFFF2A93B), 4f, Offset(76f, 9f))
                drawCircle(Color(0xFFFFB4A2), 3.5f, Offset(88f, 26f))
            }
            else -> {
                drawPath(
                    Path().apply {
                        moveTo(64f, 30f); cubicTo(60f, 16f, 48f, 10f, 38f, 12f); cubicTo(40f, 24f, 52f, 30f, 64f, 30f); close()
                    },
                    dark
                )
                drawPath(
                    Path().apply {
                        moveTo(64f, 30f); cubicTo(68f, 14f, 82f, 8f, 92f, 12f); cubicTo(88f, 26f, 76f, 31f, 64f, 30f); close()
                    },
                    light
                )
                drawLine(Color(0xFF239467), Offset(64f, 32f), Offset(64f, 22f), 3f, StrokeCap.Round)
            }
        }
    }
    if (stage == BroteStage.PLANT || stage == BroteStage.TREE) {
        drawPath(
            Path().apply { moveTo(30f, 62f); cubicTo(18f, 58f, 12f, 62f, 10f, 68f); cubicTo(18f, 72f, 26f, 70f, 30f, 62f); close() },
            light
        )
        drawPath(
            Path().apply { moveTo(98f, 62f); cubicTo(110f, 58f, 116f, 62f, 118f, 68f); cubicTo(110f, 72f, 102f, 70f, 98f, 62f); close() },
            dark
        )
    }
}

private fun DrawScope.drawFace(mood: BroteMood, blink: Float, ink: Color, blush: Color) {
    val stroke = Stroke(width = 3f, cap = StrokeCap.Round)
    when (mood) {
        BroteMood.SLEEPY -> {
            drawPath(Path().apply { moveTo(44f, 73f); quadraticTo(50f, 77f, 56f, 73f) }, ink, style = stroke)
            drawPath(Path().apply { moveTo(72f, 73f); quadraticTo(78f, 77f, 84f, 73f) }, ink, style = stroke)
        }
        BroteMood.JOY -> {
            drawPath(Path().apply { moveTo(44f, 74f); quadraticTo(50f, 66f, 56f, 74f) }, ink, style = Stroke(3.2f, cap = StrokeCap.Round))
            drawPath(Path().apply { moveTo(72f, 74f); quadraticTo(78f, 66f, 84f, 74f) }, ink, style = Stroke(3.2f, cap = StrokeCap.Round))
        }
        else -> {
            scale(1f, blink, pivot = Offset(64f, 72f)) {
                drawOval(ink, Offset(44.5f, 65f), Size(11f, 14f))
                drawOval(ink, Offset(72.5f, 65f), Size(11f, 14f))
                drawCircle(Color.White, 2f, Offset(52f, 69f))
                drawCircle(Color.White, 2f, Offset(80f, 69f))
            }
        }
    }
    drawOval(blush.copy(alpha = 0.8f), Offset(33f, 79.5f), Size(14f, 9f))
    drawOval(blush.copy(alpha = 0.8f), Offset(81f, 79.5f), Size(14f, 9f))
    when (mood) {
        BroteMood.SLEEPY -> drawPath(Path().apply { moveTo(58f, 92f); quadraticTo(64f, 94f, 70f, 92f) }, ink, style = stroke)
        BroteMood.CALM -> drawPath(Path().apply { moveTo(56f, 90f); quadraticTo(64f, 96f, 72f, 90f) }, ink, style = stroke)
        BroteMood.HAPPY -> drawPath(Path().apply { moveTo(54f, 89f); quadraticTo(64f, 100f, 74f, 89f) }, ink, style = stroke)
        BroteMood.JOY -> {
            val mouth = Path().apply { moveTo(53f, 88f); quadraticTo(64f, 104f, 75f, 88f); close() }
            drawPath(mouth, Color(0xFFFF8E7A))
            drawPath(mouth, ink, style = Stroke(3f, cap = StrokeCap.Round))
        }
    }
}

private fun DrawScope.drawSpark(center: Offset, r: Float, color: Color) {
    val p = Path().apply {
        moveTo(center.x, center.y - r)
        lineTo(center.x + r * 0.3f, center.y - r * 0.3f)
        lineTo(center.x + r, center.y)
        lineTo(center.x + r * 0.3f, center.y + r * 0.3f)
        lineTo(center.x, center.y + r)
        lineTo(center.x - r * 0.3f, center.y + r * 0.3f)
        lineTo(center.x - r, center.y)
        lineTo(center.x - r * 0.3f, center.y - r * 0.3f)
        close()
    }
    drawPath(p, color)
}

private fun DrawScope.drawZ(origin: Offset, s: Float, color: Color, alpha: Float) {
    val p = Path().apply {
        moveTo(origin.x, origin.y)
        lineTo(origin.x + s, origin.y)
        lineTo(origin.x, origin.y + s)
        lineTo(origin.x + s, origin.y + s)
    }
    drawPath(p, color.copy(alpha = (color.alpha * alpha).coerceIn(0f, 1f)), style = Stroke(2.4f, cap = StrokeCap.Round))
}
