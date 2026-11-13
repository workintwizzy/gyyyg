package ru.dmitriy.workouts.feature.shared

import ru.dmitriy.workouts.domain.*

/** Only @Preview data. Never passed to the production repository. */
object PreviewFixtures {
    private val technique = Technique("Настройте сиденье", "Устойчиво обопритесь", listOf("Двигайтесь плавно"), "Выдох на усилии", listOf("Без рывков"), emptyList())
    private val names = listOf("Жим ногами в тренажёре", "Жим от груди сидя", "Тяга верхнего блока к груди", "Сгибание ног сидя", "Горизонтальная тяга с опорой", "Подъём гантелей в стороны", "Мёртвый жук")
    val exercises = names.mapIndexed { index, name -> Exercise("preview-$index", name, "Мышцы", "Тренажёр", technique = technique) }
    val targets = exercises.map { ExerciseTarget(it.id, "preview-machine", 1, 10, 15, 120) }
    val state = LocalState(profile = Profile(onboarded = true), program = Program(targets = targets, nextDate = "Пятница, 13 ноября"), exercises = exercises)
    val session = WorkoutSession("preview-session", startedAt = 0, programVersion = 1, assignedSnapshot = state.program, exercises = targets.map { SessionExercise(it.exerciseId, it.exerciseId, it.equipmentId, it) })
    val proposal = PlanProposal("preview-proposal", "Уложиться в 30 минут", "Сократим оставшийся объём, сохранив основные движения.", emptyList(), 1, "session", Long.MAX_VALUE, listOf(ProposalOperation("reduceSets", exercises.first().id, "3", "2")))
}
