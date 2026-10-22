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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.isoron.uhabits.R

/** The places in Dexter's bottom bar, in order. Dexter itself sits in the middle. */
enum class Space { HOY, CAMINO, FINANZAS, APRENDER, CONSCIENCIA }

/** Every branch the "Espacios" panel opens, beyond the bar's places. */
enum class Branch { HABITS, LIBRARY, NOTES, STUDY, ROBOTICS, DEXTER, PHONE, WHATSAPP, MAIL, SETTINGS }

/**
 * Dexter's home: the bottom bar (Hoy · Camino · Dexter · Finanzas · Aprender) for what is used every
 * day, a side panel ("Espacios", from the ☰ button or a swipe from the left edge) with every area and
 * its branches, and the place shown between them, sliding in the direction of travel.
 */
@Composable
fun DexterHome(
    onDexter: () -> Unit,
    onBranch: (Branch) -> Unit,
    start: Space = Space.HOY,
    content: @Composable (space: Space, openMenu: () -> Unit, go: (Space) -> Unit) -> Unit
) {
    var space by rememberSaveable { mutableStateOf(start) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val openMenu: () -> Unit = { scope.launch { drawer.open() } }
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            SpacesPanel(
                current = space,
                onSpace = { s -> space = s; scope.launch { drawer.close() } },
                onBranch = { b -> scope.launch { drawer.close() }; onBranch(b) }
            )
        }
    ) {
        Box(Modifier.fillMaxSize().background(Ancla.colors.ground)) {
            AnimatedContent(
                space,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    (slideInHorizontally(tween(320)) { w -> if (forward) w / 5 else -w / 5 } + fadeIn(tween(240))) togetherWith
                        (slideOutHorizontally(tween(260)) { w -> if (forward) -w / 6 else w / 6 } + fadeOut(tween(200)))
                },
                label = "space"
            ) { s -> content(s, openMenu) { space = it } }
            DexterBottomBar(
                selected = space,
                onSelect = { space = it },
                onDexter = onDexter,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
fun DexterBottomBar(selected: Space, onSelect: (Space) -> Unit, onDexter: () -> Unit, modifier: Modifier = Modifier) {
    val c = Ancla.colors
    Box(modifier.fillMaxWidth().height(86.dp)) {
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(68.dp)
                .shadow(18.dp, RoundedCornerShape(34.dp), ambientColor = Color(0x66000000), spotColor = Color(0x66000000))
                .clip(RoundedCornerShape(34.dp))
                .background(c.card),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BarItem(Space.HOY, selected, R.string.nav_today, onSelect, Modifier.weight(1f))
            BarItem(Space.CAMINO, selected, R.string.nav_camino, onSelect, Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
            BarItem(Space.FINANZAS, selected, R.string.nav_finanzas, onSelect, Modifier.weight(1f))
            BarItem(Space.APRENDER, selected, R.string.nav_aprender, onSelect, Modifier.weight(1f))
        }
        // Dexter in the middle, raised above the bar: one tap to talk from anywhere.
        val talk = stringResource(R.string.nav_talk_to_dexter)
        Box(
            Modifier.align(Alignment.TopCenter).size(66.dp)
                .shadow(14.dp, CircleShape, ambientColor = c.lilac, spotColor = c.lilac)
                .clip(CircleShape)
                .background(c.lilacSoft)
                .combinedClickableCompat(Role.Button, onDexter)
                .semantics { contentDescription = talk },
            contentAlignment = Alignment.Center
        ) {
            DexterOwl(OwlState.IDLE, Modifier.size(52.dp))
        }
    }
}

@Composable
private fun BarItem(space: Space, selected: Space, label: Int, onSelect: (Space) -> Unit, modifier: Modifier) {
    val c = Ancla.colors
    val on = space == selected
    val lift by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow), label = "bar")
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(24.dp)).combinedClickableCompat(Role.Tab, { onSelect(space) }),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val tint = if (on) c.amber else c.muted
        Canvas(Modifier.size(24.dp).offset(y = (-3 * lift).dp).scale(1f + 0.12f * lift)) { drawSpaceIcon(space, tint) }
        Text(
            stringResource(label), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.sp),
            color = if (on) c.ink else c.muted, maxLines = 1
        )
    }
}


/** The bar's line icons, drawn in a 24×24 box like the rest of Dexter's drawings. */
internal fun DrawScope.drawSpaceIcon(space: Space, color: Color) {
    val k = size.minDimension / 24f
    fun o(x: Float, y: Float) = Offset(x * k, y * k)
    val stroke = Stroke(2.1f * k, cap = StrokeCap.Round)
    when (space) {
        Space.HOY -> {
            drawCircle(color, 4.5f * k, o(12f, 12f), style = stroke)
            for (i in 0 until 8) {
                val a = i * Math.PI.toFloat() / 4f
                val dx = kotlin.math.cos(a)
                val dy = kotlin.math.sin(a)
                drawLine(color, o(12f + 7.5f * dx, 12f + 7.5f * dy), o(12f + 9.8f * dx, 12f + 9.8f * dy), 2.1f * k, StrokeCap.Round)
            }
        }
        Space.CAMINO -> {
            drawPath(Path().apply { moveTo(5f * k, 21f * k); cubicTo(5f * k, 15f * k, 12f * k, 16f * k, 12f * k, 11f * k); cubicTo(12f * k, 7f * k, 8f * k, 7f * k, 8f * k, 3.5f * k) }, color, style = stroke)
            drawCircle(color, 2.6f * k, o(18f, 6f), style = stroke)
            drawLine(color, o(15f, 20f), o(20f, 20f), 2.1f * k, StrokeCap.Round)
        }
        Space.FINANZAS -> {
            drawCircle(color, 8.5f * k, o(12f, 12f), style = stroke)
            drawPath(Path().apply { moveTo(15f * k, 8.8f * k); cubicTo(13f * k, 7.4f * k, 9f * k, 7.6f * k, 9f * k, 10f * k); cubicTo(9f * k, 12.6f * k, 15f * k, 11.4f * k, 15f * k, 14f * k); cubicTo(15f * k, 16.4f * k, 11f * k, 16.6f * k, 9f * k, 15.2f * k) }, color, style = stroke)
            drawLine(color, o(12f, 5.8f), o(12f, 7.3f), 2.1f * k, StrokeCap.Round)
            drawLine(color, o(12f, 16.7f), o(12f, 18.2f), 2.1f * k, StrokeCap.Round)
        }
        Space.CONSCIENCIA -> {
            // The north star: four long points and four short ones.
            val star = Path().apply {
                for (i in 0 until 16) {
                    val a = Math.PI.toFloat() / 8f * i - Math.PI.toFloat() / 2f
                    val r = when { i % 4 == 0 -> 10f; i % 2 == 0 -> 5f; else -> 2.2f } * k
                    val x = 12f * k + r * kotlin.math.cos(a)
                    val y = 12f * k + r * kotlin.math.sin(a)
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            drawPath(star, color, style = Stroke(1.8f * k, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
        Space.APRENDER -> {
            drawPath(Path().apply { moveTo(4f * k, 5.5f * k); cubicTo(7f * k, 4f * k, 9.5f * k, 4f * k, 12f * k, 5.5f * k); lineTo(12f * k, 19.5f * k); cubicTo(9.5f * k, 18f * k, 7f * k, 18f * k, 4f * k, 19.5f * k); close() }, color, style = stroke)
            drawPath(Path().apply { moveTo(20f * k, 5.5f * k); cubicTo(17f * k, 4f * k, 14.5f * k, 4f * k, 12f * k, 5.5f * k); lineTo(12f * k, 19.5f * k); cubicTo(14.5f * k, 18f * k, 17f * k, 18f * k, 20f * k, 19.5f * k); close() }, color, style = stroke)
        }
    }
}

/** The round ☰ button that opens the Espacios panel, for each place's header. */
@Composable
fun MenuButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Ancla.colors
    val label = stringResource(R.string.spaces_title)
    Box(
        modifier.size(44.dp).clip(CircleShape).background(c.card).combinedClickableCompat(Role.Button, onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = size.width
            for (y in listOf(0.25f, 0.5f, 0.75f)) drawLine(c.ink, Offset(w * 0.12f, size.height * y), Offset(w * (if (y == 0.5f) 0.72f else 0.88f), size.height * y), 2.2.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
private fun SpacesPanel(current: Space, onSpace: (Space) -> Unit, onBranch: (Branch) -> Unit) {
    val c = Ancla.colors
    ModalDrawerSheet(drawerContainerColor = c.ground, drawerContentColor = c.ink, modifier = Modifier.width(300.dp)) {
        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).statusBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DexterOwl(OwlState.IDLE, Modifier.size(52.dp))
                Text(stringResource(R.string.spaces_title), style = MaterialTheme.typography.headlineSmall, color = c.ink)
            }
            Spacer(Modifier.height(8.dp))
            // The north star first: everything else serves it.
            Area(R.string.nav_consciencia, current == Space.CONSCIENCIA, { onSpace(Space.CONSCIENCIA) }, emptyList(), onBranch)
            Area(R.string.nav_today, current == Space.HOY, { onSpace(Space.HOY) }, listOf(R.string.branch_habits to Branch.HABITS), onBranch)
            Area(R.string.nav_camino, current == Space.CAMINO, { onSpace(Space.CAMINO) }, emptyList(), onBranch)
            Area(R.string.nav_finanzas, current == Space.FINANZAS, { onSpace(Space.FINANZAS) }, emptyList(), onBranch)
            Area(
                R.string.nav_aprender, current == Space.APRENDER, { onSpace(Space.APRENDER) },
                listOf(R.string.branch_library to Branch.LIBRARY, R.string.branch_notes to Branch.NOTES, R.string.branch_study to Branch.STUDY, R.string.branch_robotics to Branch.ROBOTICS),
                onBranch
            )
            Area(
                R.string.nav_tu, false, { onBranch(Branch.DEXTER) },
                listOf(R.string.branch_dexter to Branch.DEXTER, R.string.branch_phone to Branch.PHONE, R.string.branch_whatsapp to Branch.WHATSAPP, R.string.branch_mail to Branch.MAIL, R.string.branch_settings to Branch.SETTINGS),
                onBranch
            )
        }
    }
}

@Composable
private fun Area(title: Int, on: Boolean, onOpen: () -> Unit, branches: List<Pair<Int, Branch>>, onBranch: (Branch) -> Unit) {
    val c = Ancla.colors
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(title), style = MaterialTheme.typography.titleMedium, color = if (on) c.amber else c.ink,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) c.lilacSoft else Color.Transparent)
                .combinedClickableCompat(Role.Tab, onOpen).padding(horizontal = 14.dp, vertical = 12.dp)
        )
        branches.forEach { (label, branch) ->
            Text(
                stringResource(label), style = MaterialTheme.typography.bodyLarge, color = c.inkSoft,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).combinedClickableCompat(Role.Button, { onBranch(branch) })
                    .padding(start = 30.dp, end = 14.dp, top = 9.dp, bottom = 9.dp)
            )
        }
    }
}

/** Aprender: books, notes and ideas, study and the robotics course, as one place. */
@Composable
fun AprenderScreen(openMenu: () -> Unit, onBranch: (Branch) -> Unit) {
    val c = Ancla.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PlaceHeader(R.string.aprender_caption, R.string.nav_aprender, openMenu)
        listOf(
            Triple(R.string.branch_library, R.string.aprender_library, Branch.LIBRARY),
            Triple(R.string.branch_notes, R.string.aprender_notes, Branch.NOTES),
            Triple(R.string.branch_study, R.string.aprender_study, Branch.STUDY),
            Triple(R.string.branch_robotics, R.string.aprender_robotics, Branch.ROBOTICS)
        ).forEach { (title, detail, branch) ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card).combinedClickableCompat(Role.Button, { onBranch(branch) }).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(stringResource(title), style = MaterialTheme.typography.titleLarge, color = c.ink)
                Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
            }
        }
    }
}

/** A place's header: ☰, a small caption and the big title. */
@Composable
fun PlaceHeader(caption: Int, title: Int, openMenu: () -> Unit) {
    val c = Ancla.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        MenuButton(openMenu)
        Column {
            Text(stringResource(caption), style = MaterialTheme.typography.labelSmall, color = c.muted)
            Text(stringResource(title), style = MaterialTheme.typography.displaySmall, color = c.ink)
        }
    }
}
