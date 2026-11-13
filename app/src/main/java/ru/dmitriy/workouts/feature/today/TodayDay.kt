package ru.dmitriy.workouts.feature.today

import ru.dmitriy.workouts.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil

internal enum class TodayPhase { Training, Active, Recovery, Completed, Saved }

internal data class TodayDay(
    val phase: TodayPhase,
    val program: Program,
    val targets: List<ExerciseTarget>,
    val session: WorkoutSession? = null,
    val nextDate: LocalDate,
) {
    val title: String get() = if (phase == TodayPhase.Recovery) "День восстановления" else program.dayTitle()
    val action: String get() = when (phase) {
        TodayPhase.Training -> "Начать тренировку"
        TodayPhase.Active -> "Продолжить"
        TodayPhase.Recovery -> "Посмотреть следующий день"
        TodayPhase.Completed, TodayPhase.Saved -> "Посмотреть результаты"
    }
}

/** An unfinished session takes priority, even if it was started on another day. */
internal fun LocalState.todayDay(today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): TodayDay {
    val scheduled = runCatching { LocalDate.parse(program.nextDate) }.getOrNull()
    val next = scheduled?.takeIf { it >= today }
        ?: (0L..7L).map { today.plusDays(it) }.firstOrNull { it.dayOfWeek.value in program.weekdays }
        ?: today
    sessions.firstOrNull { it.status == "active" }?.let {
        return TodayDay(TodayPhase.Active, it.assignedSnapshot, it.exercises.map { e -> e.target }, it, next)
    }
    sessions.filter { it.status in setOf("completed", "partial") && it.finishedAt != null }
        .filter { Instant.ofEpochMilli(it.finishedAt!!).atZone(zone).toLocalDate() == today }
        .maxByOrNull { it.finishedAt!! }?.let {
            return TodayDay(if (it.status == "completed") TodayPhase.Completed else TodayPhase.Saved,
                it.assignedSnapshot, it.exercises.map { e -> e.target }, it, next)
        }
    // An overdue explicit date must not hide a workout that still needs doing.
    val training = scheduled?.let { it <= today } ?: (today.dayOfWeek.value in program.weekdays)
    return TodayDay(if (training) TodayPhase.Training else TodayPhase.Recovery, program,
        if (training) program.targets else emptyList(), nextDate = next)
}

internal fun Program.dayTitle(): String = name.substringBefore('·').trim().ifBlank { "Всё тело" }

/** Warm-up + working sets + prescribed rests + moving between exercises, rounded to five minutes. */
internal fun List<ExerciseTarget>.estimatedMinutes(): Int {
    if (isEmpty()) return 0
    val seconds = 8 * 60 + sumOf { it.sets * 45 + (it.sets - 1).coerceAtLeast(0) * it.restSeconds } + (size - 1) * 60
    return ceil(seconds / 300.0).toInt() * 5
}

internal fun exerciseCount(count: Int): String = "$count " + when {
    count % 100 in 11..14 -> "упражнений"
    count % 10 == 1 -> "упражнение"
    count % 10 in 2..4 -> "упражнения"
    else -> "упражнений"
}
