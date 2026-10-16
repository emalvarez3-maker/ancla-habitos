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

import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.ui.Ancla
import org.isoron.uhabits.ancla.ui.combinedClickableCompat
import kotlin.math.roundToInt

private fun hex(s: String) = Color(android.graphics.Color.parseColor(s))

@Composable
fun ReaderScreen(
    title: String,
    webView: WebView,
    chapter: Int,
    chapterCount: Int,
    progress: Double,
    barsVisible: Boolean,
    theme: ReaderTheme,
    askFinish: Boolean,
    onBack: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSmaller: () -> Unit,
    onBigger: () -> Unit,
    onTheme: () -> Unit,
    onFinish: (Int) -> Unit,
    onDismissFinish: () -> Unit
) {
    val bg = hex(theme.bg)
    val fg = hex(theme.fg)
    val accent = hex(theme.link)
    val themeDesc = stringResource(R.string.reader_theme)
    Box(Modifier.fillMaxSize().background(bg)) {
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize().statusBarsPadding())

        AnimatedVisibility(
            visible = barsVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                Modifier.fillMaxWidth().background(bg.copy(alpha = 0.96f)).statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BarButton(stringResource(R.string.reader_back), fg, onBack, stringResource(R.string.reader_back_desc))
                Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.reader_chapter, chapter + 1, chapterCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = fg.copy(alpha = 0.65f)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = barsVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                Modifier.fillMaxWidth().background(bg.copy(alpha = 0.96f)).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(fg.copy(alpha = 0.12f))) {
                        Box(Modifier.fillMaxWidth(progress.toFloat().coerceIn(0.01f, 1f)).height(6.dp).clip(CircleShape).background(accent))
                    }
                    Text("${(progress * 100).roundToInt()} %", style = MaterialTheme.typography.labelMedium, color = fg)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    BarButton("‹", fg, onPrev, stringResource(R.string.reader_prev), enabled = chapter > 0)
                    BarButton("A−", fg, onSmaller, stringResource(R.string.reader_smaller))
                    BarButton("A+", fg, onBigger, stringResource(R.string.reader_bigger))
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .combinedClickableCompat(role = Role.Button, onClick = onTheme)
                            .semantics { contentDescription = themeDesc },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.size(26.dp).clip(CircleShape).background(fg))
                        Box(Modifier.size(20.dp).clip(CircleShape).background(bg))
                    }
                    BarButton("›", fg, onNext, stringResource(R.string.reader_next))
                }
            }
        }

        if (askFinish) FinishDialog(onFinish, onDismissFinish)
    }
}

@Composable
private fun BarButton(label: String, color: Color, onClick: () -> Unit, description: String, enabled: Boolean = true) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .combinedClickableCompat(role = Role.Button, onClick = { if (enabled) onClick() })
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = color.copy(alpha = if (enabled) 1f else 0.3f), style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp))
    }
}

@Composable
private fun FinishDialog(onFinish: (Int) -> Unit, onDismiss: () -> Unit) {
    val c = Ancla.colors
    var stars by remember { mutableIntStateOf(4) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { Text(stringResource(R.string.reader_finish_title), style = MaterialTheme.typography.titleLarge, color = c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.reader_finish_text), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..5).forEach { i ->
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).combinedClickableCompat(role = Role.Button, onClick = { stars = i }),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(if (i <= stars) "★" else "☆", fontSize = 30.sp, color = if (i <= stars) c.amber else c.muted)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onFinish(stars) }) {
                Text(stringResource(R.string.reader_finish_save), color = c.mintInk, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.reader_finish_keep), color = c.muted, style = MaterialTheme.typography.labelLarge)
            }
        }
    )
}
