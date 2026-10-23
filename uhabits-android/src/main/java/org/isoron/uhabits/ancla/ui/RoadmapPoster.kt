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

import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.isoron.uhabits.ancla.Goal
import kotlin.random.Random

/**
 * A goal's roadmap as a picture to keep in sight: the vision (the WOOP's outcome) as a quote, the
 * milestones along a winding path from today to the year of the goal, reached ones in gold. Drawn
 * at phone-wallpaper size in Dexter's night palette, to save, share or set as the wallpaper.
 */
object RoadmapPoster {
    const val WIDTH = 1080
    const val HEIGHT = 2340

    private const val NIGHT = 0xFF07040E.toInt()
    private const val PLUM = 0xFF2E1650.toInt()
    private const val INK = 0xFFF3EEFF.toInt()
    private const val LILAC = 0xFFC9B8FF.toInt()
    private const val VIOLET = 0xFF9D7BFF.toInt()
    private const val GOLD = 0xFFFFB627.toInt()
    private const val MUTED = 0xFF8C7AB8.toInt()

    fun draw(goal: Goal): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Night sky, stars and the moon
        p.shader = RadialGradient(WIDTH * 0.7f, HEIGHT * 0.12f, HEIGHT * 0.9f, intArrayOf(PLUM, 0xFF140A24.toInt(), NIGHT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), p)
        p.shader = null
        val rnd = Random(goal.id.hashCode())
        p.color = LILAC
        repeat(70) { p.alpha = 60 + rnd.nextInt(150); c.drawCircle(rnd.nextFloat() * WIDTH, rnd.nextFloat() * HEIGHT * 0.95f, 1.5f + rnd.nextFloat() * 2.5f, p) }
        p.alpha = 255
        p.color = 0xFFE9DFFF.toInt()
        c.drawCircle(WIDTH - 170f, 230f, 70f, p)
        p.color = 0xFF1E0F38.toInt()
        c.drawCircle(WIDTH - 140f, 205f, 64f, p)

        val left = 96f
        val width = WIDTH - left * 2
        var y = 360f
        // The roadmap and the goal
        val label = text(26f, GOLD, bold = true).apply { letterSpacing = 0.25f }
        c.drawText((goal.roadmap.ifBlank { "Camino" } + " · " + goal.year).uppercase(), left, y, label)
        y += 40f
        y += layout(c, goal.title, text(88f, INK, bold = true), left, y, width) + 34f
        // The vision
        if (goal.outcome.isNotBlank()) {
            y += layout(c, "«${goal.outcome.trim()}»", text(42f, LILAC, italic = true), left, y, width) + 30f
        }

        // The path: from today (bottom) up to the goal (top), with the milestones along it
        val top = y + 70f
        val bottom = HEIGHT - 330f
        val steps = goal.milestones.ifEmpty { emptyList() }
        val nodes = steps.size + 2 // today, the milestones, the goal
        val points = (0 until nodes).map { i ->
            val t = i.toFloat() / (nodes - 1)
            val py = bottom - (bottom - top) * t
            val px = WIDTH / 2f + (if (i % 2 == 0) -1 else 1) * WIDTH * 0.22f * (if (i == 0 || i == nodes - 1) 0.3f else 1f)
            px to py
        }
        val path = Path().apply {
            moveTo(points[0].first, points[0].second)
            for (i in 1 until points.size) {
                val (x0, y0) = points[i - 1]
                val (x1, y1) = points[i]
                val my = (y0 + y1) / 2
                cubicTo(x0, my, x1, my, x1, y1)
            }
        }
        val road = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 34f; color = 0xFF2A1A46.toInt() }
        c.drawPath(path, road)
        // Lit up as far as the reached milestones go
        val reached = steps.count { it.done }
        val lit = Path()
        val measure = android.graphics.PathMeasure(path, false)
        val share = if (goal.done) 1f else (reached + 0.5f) / (nodes - 1)
        measure.getSegment(0f, measure.length * share.coerceIn(0.04f, 1f), lit, true)
        road.shader = LinearGradient(0f, bottom, 0f, top, VIOLET, GOLD, Shader.TileMode.CLAMP)
        c.drawPath(lit, road)
        road.shader = null
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = 0x66C9B8FF; pathEffect = DashPathEffect(floatArrayOf(4f, 22f), 0f) })

        val small = text(30f, INK, bold = true)
        val tiny = text(24f, MUTED)
        points.forEachIndexed { i, (px, py) ->
            val done = when (i) {
                0 -> true
                nodes - 1 -> goal.done
                else -> steps[i - 1].done
            }
            val r = if (i == nodes - 1) 34f else 24f
            p.color = if (done) GOLD else NIGHT
            c.drawCircle(px, py, r, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 6f
            p.color = if (done) GOLD else VIOLET
            c.drawCircle(px, py, r, p)
            p.style = Paint.Style.FILL
            val name = when (i) {
                0 -> "Hoy"
                nodes - 1 -> "🏆 " + goal.year
                else -> steps[i - 1].title
            }
            val due = if (i in 1 until nodes - 1) steps[i - 1].due else ""
            // The label goes on the open side of the curve.
            val onRight = px < WIDTH / 2f
            val tx = if (onRight) px + 52f else 60f
            val tw = if (onRight) WIDTH - tx - 60f else px - 52f - 60f
            val h = layout(c, name, small, tx, py - 22f, tw, if (onRight) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_OPPOSITE)
            if (due.isNotEmpty()) layout(c, due, tiny, tx, py - 22f + h + 4f, tw, if (onRight) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_OPPOSITE)
        }

        // The if-then plan, and who drew it
        if (goal.plan.isNotBlank()) layout(c, goal.plan.trim(), text(30f, LILAC, italic = true), left, HEIGHT - 290f, width, Layout.Alignment.ALIGN_CENTER)
        c.drawText("Dexter", WIDTH / 2f, HEIGHT - 120f, text(28f, MUTED, bold = true).apply { textAlign = Paint.Align.CENTER; letterSpacing = 0.3f })
        return bmp
    }

    private fun text(size: Float, color: Int, bold: Boolean = false, italic: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        })
    }

    /** Draws wrapped [s] at ([x], [y]) within [width]; returns its height. */
    private fun layout(c: Canvas, s: String, paint: TextPaint, x: Float, y: Float, width: Float, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): Float {
        val l = StaticLayout.Builder.obtain(s, 0, s.length, paint, width.toInt().coerceAtLeast(10)).setAlignment(align).setMaxLines(4).build()
        c.save()
        c.translate(x, y)
        l.draw(c)
        c.restore()
        return l.height.toFloat()
    }

    /** Saves the picture in Pictures/Dexter; returns its address, for sharing. */
    fun save(context: Context, goal: Goal, bmp: Bitmap): Uri? {
        // Android 9 would need storage permission for this; there, sharing and the wallpaper still work.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return null
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Dexter-roadmap-${goal.title.take(30).replace(Regex("[^\\p{L}\\p{N}]+"), "-")}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Dexter")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return uri
    }

    fun share(context: Context, uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The home screen's wallpaper only: the lock screen stays as it was, so the goal isn't shown to everyone. */
    fun setWallpaper(context: Context, bmp: Bitmap) {
        WallpaperManager.getInstance(context).setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM)
    }
}
