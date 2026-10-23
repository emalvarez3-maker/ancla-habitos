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

package org.isoron.uhabits.ancla

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.isoron.uhabits.ancla.consciousness.ConscienciaScreen
import org.isoron.uhabits.ancla.consciousness.ConsciousnessStore
import org.isoron.uhabits.ancla.consciousness.Dimension
import org.isoron.uhabits.ancla.finance.FinanceStore
import org.isoron.uhabits.ancla.finance.FinanzasScreen
import org.isoron.uhabits.ancla.ui.Ancla
import org.isoron.uhabits.ancla.ui.AnclaTheme
import org.isoron.uhabits.ancla.ui.CaminoScreen
import org.isoron.uhabits.ancla.ui.DexterHome
import org.isoron.uhabits.ancla.ui.DexterOwl
import org.isoron.uhabits.ancla.ui.OwlState
import org.isoron.uhabits.ancla.ui.RoadmapPoster
import org.isoron.uhabits.ancla.ui.Space
import org.isoron.uhabits.ancla.ui.TodayScreen
import org.isoron.uhabits.core.models.AutomaticityPhase
import org.isoron.uhabits.core.ui.screens.today.TodayHabitItem
import org.isoron.uhabits.core.ui.screens.today.TodayMood
import org.isoron.uhabits.core.ui.screens.today.TodaySummary
import org.isoron.uhabits.core.ui.screens.today.TodayTaskItem
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import javax.crypto.KeyGenerator

/**
 * The README's pictures: the real screens, rendered with demo data (nobody's own), in Dexter's
 * dark theme. The app's windows block screenshots on the phone, so they are drawn here instead.
 * Run on demand: DEXTER_SCREENSHOTS=1 ./gradlew :uhabits-android:testDebugUnitTest --tests "*ScreenshotsTest*"
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "es-rES-w412dp-h892dp-night-xhdpi")
class ScreenshotsTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val out = File("../docs/images").apply { mkdirs() }
    private val dir = Stress.tempDir()

    @Before
    fun setUp() {
        assumeTrue("run with DEXTER_SCREENSHOTS=1", System.getenv("DEXTER_SCREENSHOTS") == "1")
        EncryptedStore.cachedKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        // Dexter never stops moving (breathing, blinking): the clock is driven by hand, or the screen
        // would never count as idle.
        compose.mainClock.autoAdvance = false
        compose.setContent { AnclaTheme { content() } }
        // Stores load on a background thread; let them land and the entrance animations settle.
        repeat(8) {
            Thread.sleep(250)
            compose.mainClock.advanceTimeBy(400)
        }
        // Drawn straight from the window: Robolectric never delivers the frame captureToImage waits for.
        val root = compose.activity.window.decorView
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bitmap))
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val summary = TodaySummary(
        items = listOf(
            TodayHabitItem(1, "Meditar 10 minutos", "servirme el café", "3 respiraciones", 7 * 60, true, false, false, 0.0, "", 0.0),
            TodayHabitItem(2, "Ejercicio diario", "", "10 de cada uno", 18 * 60, false, true, false, 0.0, "", 0.0),
            TodayHabitItem(3, "Leer 20 páginas", "cenar", "1 página", 21 * 60, true, false, false, 0.0, "", 0.0),
            TodayHabitItem(4, "Inglés", "almorzar", "5 palabras", null, false, false, false, 0.0, "", 0.0)
        ),
        tasks = listOf(
            TodayTaskItem(10, "Enviar informe de física", "", 16 * 60, true, false, false),
            TodayTaskItem(11, "Pagar el arriendo", "", null, false, false, true)
        ),
        doneCount = 2, totalCount = 6, mood = TodayMood.HAPPY, averageAutomaticity = 0.42,
        phase = AutomaticityPhase.LEARNING, xp = 640, level = 2, xpInLevel = 240
    )

    @Test
    fun hoy() = shoot("hoy") {
            DexterHome(onDexter = {}, onBranch = {}) { _, menu, _ ->
                TodayScreen(summary, 0, {}, {}, {}, {}, {}, {}, menu)
            }
    }

    @Test
    fun camino() {
        val goals = GoalStore(File(dir, "goals.json"))
        val year = LocalDate.now().year
        goals.add("Terminar mi carrera", year + 1, habit = "Estudiar 2 horas")
        goals.setWoop("Terminar mi carrera", "Trabajar en lo que me apasiona", "Postergar los informes", "Si quiero dejarlo para mañana, hago 25 minutos ahora")
        goals.addMilestone("Terminar mi carrera", "Aprobar el semestre", "$year-12")
        goals.addMilestone("Terminar mi carrera", "Tesis aprobada", "${year + 1}-06")
        goals.setMilestoneDone("Terminar mi carrera", "Aprobar el semestre")
        goals.add("Correr una media maratón", year, roadmap = "Salud")
        goals.add("Inglés C1 certificado", year + 2, roadmap = "Estudios")
        goals.update("Terminar mi carrera", roadmap = "Carrera")
        shoot("camino") { DexterHome(onDexter = {}, onBranch = {}, start = Space.CAMINO) { _, menu, _ -> CaminoScreen(goals, menu) } }
    }

    @Test
    fun ahorro() {
        val money = FinanceStore(File(dir, "finance.json"))
        val month = LocalDate.now().withDayOfMonth(1)
        listOf(5L to 52000L, 4L to 48000L, 3L to 61000L, 2L to 39000L, 1L to 57000L, 0L to 44000L).forEach { (back, spent) ->
            val day = month.minusMonths(back)
            money.add(85000, true, "Sueldo", "Pasantía", day)
            money.add(spent, false, "Casa", "Gastos del mes", day.plusDays(1))
        }
        money.addGoal("Viaje a Galápagos", 120000, java.time.YearMonth.now().plusMonths(5))
        money.contribute("Viaje", 48000)
        money.addGoal("Fondo de emergencia", 300000, java.time.YearMonth.now().plusMonths(12))
        money.contribute("Fondo", 90000)
        shoot("ahorro") { DexterHome(onDexter = {}, onBranch = {}, start = Space.FINANZAS) { _, menu, _ -> FinanzasScreen(money, menu, startOnSavings = true) } }
    }

    @Test
    fun roadmap() {
        val goals = GoalStore(File(dir, "goals.json"))
        val year = LocalDate.now().year
        goals.add("Correr una media maratón", year + 1, roadmap = "Salud")
        goals.setWoop("Correr una media maratón", "Cruzar la meta de los 21 km sintiéndome fuerte y orgulloso", "Dejar de entrenar cuando llueve", "Si llueve, hago 30 minutos de cardio en casa")
        goals.addMilestone("Correr una media maratón", "Correr 5 km sin parar", "$year-12")
        goals.addMilestone("Correr una media maratón", "Mi primera carrera de 10 km", "${year + 1}-03")
        goals.addMilestone("Correr una media maratón", "Fondo de 18 km", "${year + 1}-08")
        goals.setMilestoneDone("Correr una media maratón", "Correr 5 km sin parar")
        val bitmap = RoadmapPoster.draw(goals.all().single())
        File(out, "roadmap.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun finanzas() {
        val money = FinanceStore(File(dir, "finance.json"))
        val today = LocalDate.now()
        money.add(85000, true, "Sueldo", "Pasantía", today.withDayOfMonth(1))
        money.add(4200, false, "Comida", "Almuerzo", today)
        money.add(1250, false, "Transporte", "Bus", today)
        money.add(18000, false, "Arriendo", "Cuarto", today.withDayOfMonth(2))
        money.add(2390, false, "Ocio", "Cine", today.minusDays(1))
        money.add(6500, false, "Estudios", "Libro", today.minusDays(2))
        money.setBudget("Comida", 20000)
        money.setBudget("Ocio", 6000)
        shoot("finanzas") { DexterHome(onDexter = {}, onBranch = {}, start = Space.FINANZAS) { _, menu, _ -> FinanzasScreen(money, menu) } }
    }

    @Test
    fun consciencia() {
        val mind = ConsciousnessStore(File(dir, "mind.json"))
        mind.setWheel(mapOf(Dimension.entries[0] to 4, Dimension.entries[1] to 3, Dimension.entries[2] to 5, Dimension.entries[3] to 2, Dimension.entries[4] to 4, Dimension.entries[5] to 3))
        mind.addCheckIn("Ansioso", listOf("Pecho"), time = System.currentTimeMillis() - 86_400_000L * 2)
        mind.addCheckIn("Tranquilo", listOf("Hombros"), time = System.currentTimeMillis() - 86_400_000L)
        mind.addCheckIn("Agradecido", time = System.currentTimeMillis())
        shoot("consciencia") { DexterHome(onDexter = {}, onBranch = {}, start = Space.CONSCIENCIA) { _, menu, _ -> ConscienciaScreen(mind, menu, {}) } }
    }

    @Test
    @Config(qualifiers = "es-rES-w320dp-h320dp-night-xhdpi")
    fun dexter() = shoot("dexter") {
            Box(Modifier.fillMaxSize().background(Ancla.colors.ground), contentAlignment = Alignment.Center) {
                DexterOwl(OwlState.LISTENING, Modifier.size(240.dp), level = 0.6f)
            }
    }
}
