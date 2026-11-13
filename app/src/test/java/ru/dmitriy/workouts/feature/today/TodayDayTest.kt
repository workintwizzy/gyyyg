package ru.dmitriy.workouts.feature.today

import org.junit.Assert.*
import org.junit.Test
import ru.dmitriy.workouts.domain.*
import java.time.LocalDate
import java.time.ZoneId

class TodayDayTest {
    private val today = LocalDate.of(2026, 11, 13)
    private val zone = ZoneId.of("Europe/Moscow")
    private val program = ExerciseCatalog.initialProgram().copy(nextDate = today.toString())
    private val state = LocalState(program = program)
    private fun session(status: String, date: LocalDate = today) = WorkoutSession(
        "session-$status", status, date.atStartOfDay(zone).toInstant().toEpochMilli(),
        if (status == "active") null else date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
        programVersion = 1, assignedSnapshot = program.copy(name = "Верх тела · 8 недель", week = 2),
        exercises = program.targets.take(3).map { SessionExercise(it.exerciseId, it.exerciseId, it.equipmentId, it) },
    )

    @Test fun scheduledDayHasWorkoutAndDuration() {
        val day = state.todayDay(today, zone)
        assertEquals(TodayPhase.Training, day.phase)
        assertEquals("Всё тело", day.title)
        assertEquals("Начать тренировку", day.action)
        assertEquals(program.targets, day.targets)
        assertTrue(day.targets.estimatedMinutes() > 0)
    }

    @Test fun futureExplicitDateIsRecoveryEvenOnUsualTrainingWeekday() {
        val next = today.plusDays(3)
        val day = state.copy(program = program.copy(nextDate = next.toString())).todayDay(today, zone)
        assertEquals(TodayPhase.Recovery, day.phase)
        assertEquals("День восстановления", day.title)
        assertEquals("Посмотреть следующий день", day.action)
        assertTrue(day.targets.isEmpty())
        assertEquals(next, day.nextDate)
    }

    @Test fun overdueWorkoutRemainsAvailable() {
        assertEquals(TodayPhase.Training, state.copy(program = program.copy(nextDate = today.minusDays(1).toString())).todayDay(today, zone).phase)
    }

    @Test fun missingDateUsesWeekdaySchedule() {
        val noDate = state.copy(program = program.copy(nextDate = ""))
        assertEquals(TodayPhase.Training, noDate.todayDay(today, zone).phase)
        val day = noDate.todayDay(today.plusDays(1), zone)
        assertEquals(TodayPhase.Recovery, day.phase)
        assertEquals(today.plusDays(3), day.nextDate)
    }

    @Test fun activeSessionOverridesRestAndUsesAssignedSnapshot() {
        val active = session("active", today.minusDays(1))
        val day = state.copy(program = program.copy(nextDate = today.plusDays(3).toString()),
            sessions = listOf(session("completed"), active)).todayDay(today, zone)
        assertEquals(TodayPhase.Active, day.phase)
        assertEquals("Продолжить", day.action)
        assertEquals("Верх тела", day.title)
        assertEquals(2, day.program.week)
        assertEquals(3, day.targets.size)
        assertEquals(active.id, day.session?.id)
    }

    @Test fun completedTodayShowsResultsAfterScheduleAdvances() {
        val day = state.copy(program = program.copy(nextDate = today.plusDays(3).toString()),
            sessions = listOf(session("completed"))).todayDay(today, zone)
        assertEquals(TodayPhase.Completed, day.phase)
        assertEquals("Посмотреть результаты", day.action)
        assertEquals("session-completed", day.session?.id)
    }

    @Test fun partialSaveHasResultsWithoutClaimingAllWorkCompleted() {
        assertEquals(TodayPhase.Saved, state.copy(sessions = listOf(session("partial"))).todayDay(today, zone).phase)
    }

    @Test fun yesterdayResultDoesNotOverrideTodayAndLocalMidnightIsRespected() {
        val finished = today.atTime(0, 30).atZone(zone).toInstant().toEpochMilli()
        val localToday = session("completed").copy(finishedAt = finished)
        assertEquals(TodayPhase.Completed, state.copy(sessions = listOf(localToday)).todayDay(today, zone).phase)
        assertEquals(TodayPhase.Training, state.copy(sessions = listOf(session("completed", today.minusDays(1)))).todayDay(today, zone).phase)
    }

    @Test fun estimateRespondsToVolumeAndRestAndEmptyPlanIsZero() {
        val light = program.targets.map { it.copy(sets = 1) }
        val heavy = light.map { it.copy(sets = 3, restSeconds = 180) }
        assertTrue(heavy.estimatedMinutes() > light.estimatedMinutes())
        assertEquals(0, emptyList<ExerciseTarget>().estimatedMinutes())
        assertEquals("1 упражнение", exerciseCount(1))
        assertEquals("3 упражнения", exerciseCount(3))
        assertEquals("12 упражнений", exerciseCount(12))
    }
}
