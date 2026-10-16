package org.isoron.uhabits.core.database

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.DayTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskRepositoryTest {
    private val today = LocalDate(2026, 10, 16)

    @Test
    fun testInsertFindUpdateDelete() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        val repo = TaskRepository(db)

        val id = repo.insert(DayTask(title = "Pagar luz", day = today, minuteOfDay = 18 * 60, remind = true))
        assertTrue(id > 0)
        val loaded = repo.findById(id)!!
        assertEquals("Pagar luz", loaded.title)
        assertEquals(1080, loaded.minuteOfDay)
        assertTrue(loaded.remind)

        repo.update(loaded.copy(isDone = true, minuteOfDay = null))
        val updated = repo.findById(id)!!
        assertTrue(updated.isDone)
        assertNull(updated.minuteOfDay)

        repo.delete(id)
        assertNull(repo.findById(id))
        db.close()
    }

    @Test
    fun testFindForTodayCarriesOverPendingTasks() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        val repo = TaskRepository(db)
        repo.insert(DayTask(title = "Atrasada", day = today.minus(2)))
        repo.insert(DayTask(title = "Vieja hecha", day = today.minus(1), isDone = true))
        repo.insert(DayTask(title = "Tarde", day = today, minuteOfDay = 20 * 60))
        repo.insert(DayTask(title = "Temprano", day = today, minuteOfDay = 8 * 60))
        repo.insert(DayTask(title = "Sin hora", day = today))
        repo.insert(DayTask(title = "Mañana", day = today.plus(1)))

        val titles = repo.findForToday(today).map { it.title }
        assertEquals(listOf("Atrasada", "Temprano", "Tarde", "Sin hora"), titles)
        db.close()
    }

    @Test
    fun testFindUpcomingReminders() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        val repo = TaskRepository(db)
        repo.insert(DayTask(title = "Con aviso", day = today, minuteOfDay = 600, remind = true))
        repo.insert(DayTask(title = "Sin aviso", day = today, minuteOfDay = 600))
        repo.insert(DayTask(title = "Pasada", day = today.minus(1), minuteOfDay = 600, remind = true))
        assertEquals(listOf("Con aviso"), repo.findUpcomingReminders(today).map { it.title })
        db.close()
    }
}
