package ru.dmitriy.workouts.data

import org.junit.Assert.*
import org.junit.Test
import ru.dmitriy.workouts.core.timer.RestDeadline

class RestClockTest {
    @Test fun monotonicCountdownSurvivesProcessRestart() {
        val saved=RestDeadline(150_000,5,10)
        assertEquals(30,saved.remaining(120_001,5));assertEquals(0,saved.remaining(150_001,5))
        assertTrue(saved.canFire(150_001,5,10))
    }
    @Test fun rebootInvalidatesRestInsteadOfUsingWallClock() {
        val saved=RestDeadline(150_000,5,10)
        assertEquals(0,saved.remaining(100,6));assertFalse(saved.canFire(160_000,6,10))
    }
    @Test fun stalePausedOrDeliveredAlarmCannotSignal() {
        val saved=RestDeadline(150_000,5,10)
        assertFalse(saved.canFire(149_999,5,10));assertFalse(saved.canFire(160_000,5,9))
        assertFalse(saved.copy(paused=true).canFire(160_000,5,10))
        assertFalse(saved.copy(delivered=true).canFire(160_000,5,10))
        assertFalse(saved.copy(deadline=0).canFire(160_000,5,10))
    }
}
