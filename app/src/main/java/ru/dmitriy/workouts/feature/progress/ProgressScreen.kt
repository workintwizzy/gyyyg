package ru.dmitriy.workouts.feature.progress

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ru.dmitriy.workouts.domain.*
import ru.dmitriy.workouts.feature.coach.formatDate
import ru.dmitriy.workouts.feature.shared.*
import ru.dmitriy.workouts.feature.workout.kg
import ru.dmitriy.workouts.ui.theme.*

@Composable fun ProgressScreen(state: LocalState, onSession: (String) -> Unit, onAnalysis: () -> Unit, onWeight: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf("Статистика") }
    var exerciseId by rememberSaveable { mutableStateOf("") }
    var equipmentId by rememberSaveable { mutableStateOf("") }
    var exercisePickerVisible by rememberSaveable { mutableStateOf(false) }
    var weightVisible by rememberSaveable { mutableStateOf(false) }
    var allResultsVisible by rememberSaveable { mutableStateOf(false) }
    val sessions = state.sessions.filter { it.status != "active" }.sortedByDescending { it.startedAt }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.Large), verticalArrangement = Arrangement.spacedBy(Space.Section)) {
        Text("Прогресс", style = AppType.Hero, color = AppColors.Text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.Small), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
            listOf("Статистика", "История", "Календарь").forEach { name -> Chip(name, tab == name) { tab = name } }
        }
        if (sessions.isEmpty()) EmptyState("Пока нет занятий", "Здесь появятся сохранённые результаты.")
        when (tab) {
            "Статистика" -> {
                if (sessions.isNotEmpty()) {
                    Panel(Modifier.fillMaxWidth()) {
                        Text("${sessions.size}", style = AppType.Number, color = AppColors.Text)
                        Text("занятий · ${sessions.sumOf { it.completedWorkSets() }} рабочих подходов", style = AppType.Body, color = AppColors.Secondary)
                    }
                    ActionButton(state.exercises.find { it.id == exerciseId }?.name ?: "Выбрать упражнение", { exercisePickerVisible = !exercisePickerVisible }, Modifier.fillMaxWidth())
                    if (exercisePickerVisible) state.exercises.filter { exercise -> sessions.any { s -> s.exercises.any { it.exerciseId == exercise.id } } }.forEach { exercise ->
                        Chip(exercise.name, exerciseId == exercise.id) {
                            exerciseId = exercise.id
                            equipmentId = state.equipment.filter { equipment -> equipment.exerciseId == exercise.id && sessions.any { session -> session.exercises.any { it.exerciseId == exercise.id && it.equipmentId == equipment.id } } }.singleOrNull()?.id.orEmpty()
                            exercisePickerVisible = false
                            allResultsVisible = false
                        }
                    }
                    if (exerciseId.isNotBlank()) {
                        state.equipment.filter { equipment -> equipment.exerciseId == exerciseId && sessions.any { session -> session.exercises.any { it.exerciseId == exerciseId && it.equipmentId == equipment.id } } }.forEach { equipment -> Chip(equipment.name, equipmentId == equipment.id) { equipmentId = equipment.id } }
                        val results = sessions.reversed().flatMap { s -> s.exercises.filter { it.exerciseId == exerciseId && equipmentId.isNotBlank() && it.equipmentId == equipmentId }.flatMap { e -> e.sets.filter { it.completed && !it.warmup }.map { Triple(s, e, it) } } }
                        if (equipmentId.isBlank()) Text("Выберите тренажёр для сравнения.", style = AppType.Label, color = AppColors.Secondary)
                        val tracksWeight = state.exercises.find { it.id == exerciseId }?.tracking != "reps_per_side"
                        val values = results.mapNotNull { if (tracksWeight) it.third.weightGrams?.div(1000f) else it.third.reps?.toFloat() }
                        if (equipmentId.isNotBlank() && values.size > 1) ResultChart(values, if (tracksWeight) "кг" else "повторений на сторону")
                        results.takeLast(if (allResultsVisible) 24 else 6).reversed().forEach { (session, _, set) ->
                            Text("${formatDate(session.startedAt)} · ${set.weightGrams?.let { "${kg(it)} кг × " } ?: ""}${set.reps ?: "—"}${if (tracksWeight) " повторений" else " на сторону"}", style = AppType.Body, color = AppColors.Text)
                        }
                        if (results.size > 6) ActionButton(if (allResultsVisible) "Последние подходы" else "Больше записей", { allResultsVisible = !allResultsVisible }, Modifier.fillMaxWidth())
                    }
                }
                ActionButton(if (weightVisible) "Скрыть массу тела" else "Масса тела", { weightVisible = !weightVisible }, Modifier.fillMaxWidth())
                if (weightVisible) Panel(Modifier.fillMaxWidth()) {
                    Text(state.bodyWeights.lastOrNull()?.let { "${kg(it.grams)} кг · ${it.date}" } ?: "Пока нет измерений", style = AppType.Body, color = AppColors.Secondary)
                    if (state.bodyWeights.size > 1) ResultChart(state.bodyWeights.map { it.grams / 1000f }, "кг")
                    ActionButton("Добавить измерение", onWeight, Modifier.fillMaxWidth())
                }
                ActionButton("Разобрать последние недели с ИИ", onAnalysis, Modifier.fillMaxWidth(), enabled = sessions.isNotEmpty())
            }
            "История" -> sessions.forEach { session -> SessionRow(session) { onSession(session.id) } }
            "Календарь" -> WorkoutCalendar(sessions,onSession)
        }
        Spacer(Modifier.height(Space.Section))
    }
}

@Composable private fun WorkoutCalendar(sessions:List<WorkoutSession>,onSession:(String)->Unit) {
    var monthText by rememberSaveable { mutableStateOf(java.time.LocalDate.now().withDayOfMonth(1).toString()) }
    var dayText by rememberSaveable { mutableStateOf(java.time.LocalDate.now().toString()) }
    val month=java.time.LocalDate.parse(monthText)
    val grouped=sessions.groupBy { java.time.Instant.ofEpochMilli(it.startedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() }
    Panel(Modifier.fillMaxWidth()) {
        Text(month.format(java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy",java.util.Locale.forLanguageTag("ru"))),style=AppType.Heading,color=AppColors.Text)
        Row(horizontalArrangement=Arrangement.spacedBy(Space.Small)) {
            ActionButton("Предыдущий",{monthText=month.minusMonths(1).toString()},Modifier.weight(1f))
            ActionButton("Следующий",{monthText=month.plusMonths(1).toString()},Modifier.weight(1f))
        }
        Column(Modifier.horizontalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(Space.Tiny)) {
            Row { listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс").forEach { label -> Box(Modifier.width(Space.Touch).heightIn(min=Space.Touch),contentAlignment=androidx.compose.ui.Alignment.Center) { Text(label,style=AppType.Label,color=AppColors.Secondary) } } }
            val offset=month.dayOfWeek.value-1
            val count=((offset+month.lengthOfMonth()+6)/7)*7
            (0 until count).chunked(7).forEach { week -> Row {
                week.forEach { cell ->
                    val day=cell-offset+1
                    Box(Modifier.width(Space.Touch)) {
                        if(day in 1..month.lengthOfMonth()) {
                            val date=month.withDayOfMonth(day).toString()
                            Chip("$day${if(grouped[date].isNullOrEmpty()) "" else " •"}",dayText==date) {dayText=date}
                        } else Spacer(Modifier.height(Space.Touch))
                    }
                }
            } }
        }
        Text("• — день тренировки",style=AppType.Label,color=AppColors.Secondary)
    }
    SectionLabel(java.time.LocalDate.parse(dayText).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")))
    val selected=grouped[dayText].orEmpty()
    if(selected.isEmpty())Text("В этот день нет сохранённых занятий.",style=AppType.Body,color=AppColors.Secondary)
    selected.forEach { session -> SessionRow(session) {onSession(session.id)} }
}

@Composable private fun SessionRow(session: WorkoutSession, onClick: () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Text("${formatDate(session.startedAt)} · Всё тело", style = AppType.Heading, color = AppColors.Text)
        Text("${if (session.status == "partial") "Частичное занятие" else "Завершено"} · ${session.completedWorkSets()} / ${session.assignedWorkSets()} рабочих подходов", style = AppType.Label, color = AppColors.Secondary)
        ActionButton("Открыть запись", onClick, Modifier.fillMaxWidth())
    }
}

@Composable private fun ResultChart(values: List<Float>, unit: String) {
    val lowest = values.minOrNull() ?: 0f
    val highest = values.maxOrNull() ?: lowest
    val number: (Float) -> String = { value -> java.text.DecimalFormat("0.#").format(value) }
    Text("${number(lowest)}–${number(highest)} $unit", style = AppType.Label, color = AppColors.Secondary)
    Canvas(Modifier.fillMaxWidth().height(Space.Chart).padding(vertical = Space.Small).semantics { contentDescription = "График ${values.size} измерений, от ${number(lowest)} до ${number(highest)} $unit, от ранних к последним" }) {
        val min = values.minOrNull() ?: 0f
        val max = values.maxOrNull() ?: min
        val range = (max - min).coerceAtLeast(1f)
        val points = values.mapIndexed { index, value -> Offset(size.width * index / (values.size - 1).coerceAtLeast(1), size.height - (value - min) / range * size.height) }
        points.zipWithNext().forEach { (a, b) -> drawLine(AppColors.Accent, a, b, Space.Stroke.toPx()) }
        points.forEach { point -> drawCircle(AppColors.Accent, Space.Tiny.toPx(), point) }
    }
}
