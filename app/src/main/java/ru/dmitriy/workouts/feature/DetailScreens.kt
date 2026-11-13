package ru.dmitriy.workouts.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import ru.dmitriy.workouts.domain.*
import ru.dmitriy.workouts.feature.coach.formatDate
import ru.dmitriy.workouts.feature.shared.*
import ru.dmitriy.workouts.feature.workout.kg
import ru.dmitriy.workouts.ui.theme.*

@Composable fun TechniqueScreen(exercise: Exercise, onSource: (String) -> Unit) {
    var setupVisible by rememberSaveable(exercise.id) { mutableStateOf(false) }
    var mistakesVisible by rememberSaveable(exercise.id) { mutableStateOf(false) }
    var sourcesVisible by rememberSaveable(exercise.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.Large), verticalArrangement = Arrangement.spacedBy(Space.Section)) {
        Text(exercise.name, style = AppType.Title, color = AppColors.Text)
        Text(exercise.muscles, style = AppType.Body, color = AppColors.Secondary)
        ActionButton(if (setupVisible) "Скрыть подготовку" else "Как настроить и сесть", { setupVisible = !setupVisible }, Modifier.fillMaxWidth())
        if (setupVisible) {
            Panel(Modifier.fillMaxWidth()) {
                Text(exercise.technique.setup, style = AppType.Body, color = AppColors.Text)
                Text(exercise.technique.start, style = AppType.Body, color = AppColors.Text)
            }
        }
        SectionLabel("Как выполнять")
        exercise.technique.steps.forEachIndexed { index, step -> Text("${index + 1}. $step", style = AppType.Body, color = AppColors.Text) }
        Panel(Modifier.fillMaxWidth()) {
            SectionLabel("Дыхание")
            Text(exercise.technique.breathing, style = AppType.Body, color = AppColors.Text)
        }
        ActionButton(if (mistakesVisible) "Скрыть частые ошибки" else "Частые ошибки", { mistakesVisible = !mistakesVisible }, Modifier.fillMaxWidth())
        if (mistakesVisible) exercise.technique.mistakes.forEach { Text("• $it", style = AppType.Body, color = AppColors.Secondary) }
        Text("При острой или усиливающейся боли остановитесь и обратитесь за помощью.", style = AppType.Label, color = AppColors.Secondary)
        ActionButton(if (sourcesVisible) "Скрыть источники" else "Источники и показ упражнения", { sourcesVisible = !sourcesVisible }, Modifier.fillMaxWidth())
        if (sourcesVisible) exercise.technique.sourceIds.forEach { id ->
            ExerciseCatalog.sources.find { it.id == id }?.let { source -> ActionButton(source.title, { onSource(id) }, Modifier.fillMaxWidth()) }
        }
        Spacer(Modifier.height(Space.Section))
    }
}

@Composable fun SessionDetail(state: LocalState, session: WorkoutSession, onAnalysis: () -> Unit, onCorrection: (String, SetEntry) -> Unit) {
    var expandedExercise by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.Large), verticalArrangement = Arrangement.spacedBy(Space.Section)) {
        Text("Занятие\n${formatDate(session.startedAt)}", style = AppType.Hero, color = AppColors.Text)
        Text("${if (session.status == "partial") "Частично" else if (session.status == "active") "В процессе" else "Завершено"} · ${session.completedWorkSets()} / ${session.assignedWorkSets()} подходов", style = AppType.Body, color = AppColors.Secondary)
        if (session.status != "active") ActionButton("Разобрать тренировку с ИИ", onAnalysis, Modifier.fillMaxWidth(), primary = true)
        session.exercises.forEach { exercise ->
            Panel(Modifier.fillMaxWidth()) {
                Text(state.exercises.find { it.id == exercise.exerciseId }?.name ?: "Упражнение", style = AppType.Heading, color = AppColors.Text)
                state.equipment.find { it.id == exercise.equipmentId }?.let { Text(it.name, style = AppType.Label, color = AppColors.Secondary) }
                val done = exercise.sets.filter { it.completed }
                if (done.isEmpty()) Text("Подходы не записаны", style = AppType.Body, color = AppColors.Secondary)
                else ActionButton(if (expandedExercise == exercise.id) "Скрыть подходы" else "Подходы · ${done.size}", { expandedExercise = if (expandedExercise == exercise.id) null else exercise.id }, Modifier.fillMaxWidth())
                if (expandedExercise == exercise.id) {
                done.forEach { set ->
                    Text("${if (set.warmup) "Разминка" else "Подход"} ${set.index + 1} · ${set.weightGrams?.let { "${kg(it)} кг × " } ?: ""}${set.reps ?: "—"}${if (state.exercises.find { it.id == exercise.exerciseId }?.tracking == "reps_per_side") " на сторону" else ""}", style = AppType.Body, color = AppColors.Text)
                    if (set.rir != null) Text("В запасе: ${set.rir}", style = AppType.Label, color = AppColors.Secondary)
                    Text(when (set.technique) { "good" -> "Техника: хорошо"; "poor" -> "Техника: ухудшилась"; else -> "Техника: не оценена" }, style = AppType.Label, color = AppColors.Secondary)
                    ActionButton("Исправить запись", { onCorrection(exercise.exerciseId, set) })
                }
                if (exercise.note.isNotBlank()) Text(exercise.note, style = AppType.Label, color = AppColors.Secondary)
                }
            }
        }
        Spacer(Modifier.height(Space.Section))
    }
}

@Composable fun InsightScreen(insight: AiInsight, state: LocalState, onSession: (String) -> Unit, onRefresh: () -> Unit) {
    var recordsVisible by rememberSaveable(insight.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.Large), verticalArrangement = Arrangement.spacedBy(Space.Section)) {
        Text("Разбор", style = AppType.Hero, color = AppColors.Text)
        Text("ИИ-ПОМОЩНИК · ${formatDate(insight.createdAt)}", style = AppType.Caption, color = AppColors.Accent)
        Text(insight.period, style = AppType.Heading, color = AppColors.Text)
        Panel(Modifier.fillMaxWidth()) { Text(insight.text, style = AppType.Body, color = AppColors.Text) }
        ActionButton("Запросить новый разбор", onRefresh, Modifier.fillMaxWidth())
        ActionButton(if (recordsVisible) "Скрыть использованные занятия" else "На основе каких занятий", { recordsVisible = !recordsVisible }, Modifier.fillMaxWidth())
        if (recordsVisible) {
        state.sessions.filter { it.id in insight.evidenceIds || it.exercises.any { e -> e.sets.any { set -> set.id in insight.evidenceIds } } }.forEach { session ->
            ActionButton("Открыть занятие ${formatDate(session.startedAt)}", { onSession(session.id) }, Modifier.fillMaxWidth())
        }
        if (insight.evidenceIds.isEmpty()) Text("Данных для сравнения пока недостаточно.", style = AppType.Body, color = AppColors.Secondary)
        }
    }
}
