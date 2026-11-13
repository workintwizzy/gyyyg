package ru.dmitriy.workouts.feature.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import ru.dmitriy.workouts.domain.*
import ru.dmitriy.workouts.feature.AppDialog
import ru.dmitriy.workouts.feature.shared.*
import ru.dmitriy.workouts.ui.theme.*

data class SetDraft(val index: Int, val weightGrams: Int?, val reps: Int?, val rir: Int?, val technique: String, val warmup: Boolean)
data class TimerUi(val seconds: Int = 0, val running: Boolean = false, val paused: Boolean = false)

@Composable fun WorkoutScreen(state: LocalState, session: WorkoutSession, selectedIndex: Int, timer: TimerUi,
    onSelect: (Int) -> Unit, onSave: (SetDraft) -> Unit, onUnmark: (String) -> Unit, onTechnique: () -> Unit,
    onCoach: (String) -> Unit, onFinish: () -> Unit, onTimerPause: () -> Unit, onTimerResume: () -> Unit,
    onTimerSkip: () -> Unit, onTimerAdd: () -> Unit, onEquipment: () -> Unit, onNote: () -> Unit, progression: ProgressionDecision? = null, onDefaultRest: (() -> Unit)? = null, onBack: (() -> Unit)? = null, onSettings: (() -> Unit)? = null, entryToken: Long = 0L) {
    val current = session.exercises.getOrNull(selectedIndex) ?: return
    val exercise = state.exercises.find { it.id == current.exerciseId } ?: return
    val equipment = state.equipment.find { it.id == current.equipmentId }
    val previous = state.sessions.filter { it.id != session.id && it.status != "active" }.sortedByDescending { it.startedAt }
        .firstNotNullOfOrNull { old -> old.exercises.find { it.exerciseId == exercise.id && it.equipmentId == current.equipmentId && it.sets.any { set -> set.completed && !set.warmup } } }
    val nextIndex = (0 until current.target.sets).firstOrNull { index -> current.sets.none { it.index == index && it.completed && !it.warmup } } ?: current.target.sets
    val hasWeight = exercise.tracking !in listOf("reps_per_side", "bodyweight_reps")
    val lastWeight = current.sets.lastOrNull { it.completed && !it.warmup }?.weightGrams
        ?: current.target.weightGrams ?: previous?.sets?.lastOrNull { it.completed && !it.warmup }?.weightGrams
    var mode by rememberSaveable(session.id, entryToken) { mutableStateOf("active") }
    var journalExpanded by rememberSaveable(session.id, selectedIndex) { mutableStateOf(true) }
    var weight by rememberSaveable(current.id, current.equipmentId, nextIndex) { mutableStateOf(lastWeight?.let(::kg).orEmpty()) }
    var reps by rememberSaveable(current.id, nextIndex) { mutableStateOf("") }
    var rir by rememberSaveable(current.id, nextIndex) { mutableStateOf("") }
    var technique by rememberSaveable(current.id, nextIndex) { mutableStateOf("unknown") }
    var warmup by rememberSaveable(current.id, nextIndex) { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf("") }
    var selectedSetId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(current.id) { selectedSetId = null; details = "" }

    Box(Modifier.fillMaxSize()) {
    val resting = timer.seconds > 0 || timer.paused
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.Large), verticalArrangement = Arrangement.spacedBy(Space.Medium)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconAction(Icons.Default.ArrowBack, "Назад", onBack)
                Text("Тренировка", style = AppType.Title, color = AppColors.Text, modifier = Modifier.weight(1f))
                if (onSettings != null) IconAction(Icons.Default.Settings, "Настройки", onSettings)
            }
            if (mode == "journal") Text("${session.completedWorkSets()} / ${session.assignedWorkSets()} подходов", style = AppType.Label, color = AppColors.Secondary)
            Row(Modifier.fillMaxWidth().clip(AppShapes.Control).background(AppColors.Surface).padding(Space.Tiny)) {
                listOf("journal" to "Журнал", "active" to "Подход").forEach { (value, label) ->
                    Box(Modifier.weight(1f).clip(AppShapes.Chip).background(if (mode == value) AppColors.Raised else AppColors.Transparent)
                        .clickable(role = Role.Tab, onClick = { mode = value }).semantics { selected = mode == value }
                        .heightIn(min = Space.Touch).padding(Space.Small), contentAlignment = Alignment.Center) {
                        Text(label, style = AppType.Label, color = if (mode == value) AppColors.Text else AppColors.Secondary)
                    }
                }
            }
        }
        if (resting && mode == "active") Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Space.Large),
            verticalArrangement = Arrangement.spacedBy(Space.Section), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Отдых", style = AppType.Hero, color = AppColors.Text)
            Text(timeLabel(timer.seconds), style = AppType.ActiveWeight, color = AppColors.Accent)
            if (timer.paused) Text("Пауза", style = AppType.Label, color = AppColors.Secondary)
            Text(exercise.name, style = AppType.Title, color = AppColors.Text, textAlign = TextAlign.Center)
            Text(if (nextIndex < current.target.sets) "Далее — подход ${nextIndex + 1} из ${current.target.sets}" else "Рабочие подходы выполнены",
                style = AppType.Body, color = AppColors.Secondary)
            ActionButton(if (timer.paused) "Продолжить отдых" else "Пауза", if (timer.paused) onTimerResume else onTimerPause, Modifier.fillMaxWidth())
            ActionButton("+30 с", onTimerAdd, Modifier.fillMaxWidth())
            ActionButton("Пропустить отдых", onTimerSkip, Modifier.fillMaxWidth(), primary = true)
            if (nextIndex >= current.target.sets && selectedIndex < session.exercises.lastIndex)
                ActionButton("Следующее упражнение", { onSelect(selectedIndex + 1) }, Modifier.fillMaxWidth())
            if (nextIndex >= current.target.sets && selectedIndex == session.exercises.lastIndex)
                ActionButton("Завершить занятие", onFinish, Modifier.fillMaxWidth())
            onDefaultRest?.let { TextAction("Настроить отдых", it) }
        } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.Large), verticalArrangement = Arrangement.spacedBy(if (mode == "journal") Space.None else Space.Regular)) {
            Spacer(Modifier.height(Space.Small))
            if (mode == "journal") {
                session.exercises.forEachIndexed { index, item ->
                    val catalog = state.exercises.find { it.id == item.exerciseId }
                    val completed = item.sets.count { it.completed && !it.warmup }
                    Column {
                        val expanded = index == selectedIndex && journalExpanded
                        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                            if (index == selectedIndex) journalExpanded = !journalExpanded else onSelect(index)
                        }.heightIn(min = Space.Touch).padding(vertical = Space.Small),
                            horizontalArrangement = Arrangement.spacedBy(Space.Medium), verticalAlignment = Alignment.CenterVertically) {
                            Text((index + 1).toString().padStart(2, '0'), style = AppType.Label, color = AppColors.Secondary)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Tiny)) {
                                Text(catalog?.let(::shortName) ?: "Упражнение", style = AppType.Body, color = AppColors.Text)
                                if (!expanded) Text("${item.target.sets} × ${item.target.minReps}–${item.target.maxReps}", style = AppType.Label, color = AppColors.Secondary)
                            }
                            Text("$completed/${item.target.sets}", style = AppType.Label, color = if (completed > 0) AppColors.Accent else AppColors.Secondary)
                            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = AppColors.Secondary, modifier = Modifier.size(Space.Icon))
                        }
                        if (expanded) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = Space.Medium, vertical = Space.Small), horizontalArrangement = Arrangement.spacedBy(Space.Medium)) {
                                Text("№", style = AppType.Caption, color = AppColors.Secondary, modifier = Modifier.width(Space.Section))
                                if (hasWeight) Text(if (exercise.tracking == "dumbbell_each") "кг / одна" else "кг", style = AppType.Caption, color = AppColors.Secondary, modifier = Modifier.weight(1f))
                                Text(if (exercise.tracking == "reps_per_side") "на сторону" else "повторы", style = AppType.Caption, color = AppColors.Secondary, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(Space.Icon))
                            }
                            (0 until item.target.sets).forEach { setIndex ->
                                val set = item.sets.find { it.index == setIndex && it.completed && !it.warmup }
                                Row(Modifier.fillMaxWidth().clip(AppShapes.Chip).background(if (set == null && setIndex == nextIndex) AppColors.Raised else AppColors.Transparent)
                                    .clickable(role = Role.Button) { if (set != null) selectedSetId = set.id else { warmup = false; mode = "active" } }
                                    .heightIn(min = Space.Touch).padding(Space.Medium), horizontalArrangement = Arrangement.spacedBy(Space.Medium), verticalAlignment = Alignment.CenterVertically) {
                                    Text((setIndex + 1).toString(), style = AppType.Label, color = AppColors.Secondary, modifier = Modifier.width(Space.Section))
                                    if (hasWeight) Text((set?.weightGrams ?: if (setIndex == nextIndex) grams(weight) else null)?.let(::kg) ?: "—", style = AppType.Heading, color = AppColors.Text, modifier = Modifier.weight(1f))
                                    Text(set?.reps?.toString() ?: "—", style = AppType.Heading, color = AppColors.Text, modifier = Modifier.weight(1f))
                                    if (set != null) Icon(Icons.Default.Check, "Записан", tint = AppColors.Accent, modifier = Modifier.size(Space.Icon))
                                    else Box(Modifier.size(Space.Icon), contentAlignment = Alignment.Center) { Icon(Icons.Default.Circle, "Не записан", tint = AppColors.Secondary, modifier = Modifier.size(Space.Small)) }
                                }
                            }
                            TextAction("Разминка · ${item.sets.count { it.completed && it.warmup }}", { details = "warmup" })
                        }
                        DividerLine()
                    }
                }
                ActionButton("Завершить занятие", onFinish, Modifier.fillMaxWidth())
            } else {
                Text(exercise.name, style = AppType.Hero, color = AppColors.Text)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(Space.Small)) {
                    Text(if (warmup) "Разминка" else if (nextIndex < current.target.sets) "Подход ${nextIndex + 1} из ${current.target.sets}" else "Подходы записаны", style = AppType.Label, color = AppColors.Text)
                    Text("${current.target.minReps}–${current.target.maxReps}${if (exercise.tracking == "reps_per_side") " на сторону" else " повторений"}", style = AppType.Label, color = AppColors.Secondary)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.Small), verticalArrangement = Arrangement.spacedBy(Space.Tiny)) {
                    TextAction("Техника", onTechnique, icon = Icons.AutoMirrored.Filled.MenuBook)
                    TextAction("Спросить тренера", { onCoach("Объясни технику этого упражнения простыми словами.") }, icon = Icons.Default.AutoAwesome)
                }
                if (warmup || nextIndex < current.target.sets) {
                    if (hasWeight) {
                        NumberEditor(weight, { weight = it }, if (exercise.tracking == "dumbbell_each") "Вес одной гантели" else "Вес", "кг", AppType.ActiveWeight,
                            { weight = kg(((grams(weight) ?: 0) - (equipment?.stepGrams ?: 2500)).coerceAtLeast(0)) },
                            { weight = kg((grams(weight) ?: 0) + (equipment?.stepGrams ?: 2500)) })
                    }
                    NumberEditor(reps, { reps = it }, if (exercise.tracking == "reps_per_side") "Повторения на сторону" else "Повторения", "", AppType.ActiveReps,
                        { reps = ((reps.toIntOrNull() ?: 0) - 1).coerceAtLeast(0).toString() }, { reps = ((reps.toIntOrNull() ?: 0) + 1).toString() })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.Small), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
                        Chip("В запасе · ${rir.ifBlank { "—" }}", onClick = { details = "effort" })
                        Chip("Техника · ${when (technique) { "good" -> "хорошо"; "poor" -> "хуже"; else -> "—" }}", onClick = { details = "technique" })
                    }
                    ActionButton("Записать подход", {
                        onSave(SetDraft(if (warmup) current.sets.count { it.warmup } else nextIndex,
                            if (hasWeight) grams(weight) else null, reps.toIntOrNull(), rir.toIntOrNull(), technique, warmup))
                    }, Modifier.fillMaxWidth(), primary = true,
                        enabled = reps.toIntOrNull()?.let { it in 1..100 } == true && (!hasWeight || grams(weight)?.let { it in 0..1_000_000 } == true), icon = Icons.Default.Check)
                } else {
                    Text("Все рабочие подходы этого упражнения сохранены.", style = AppType.Body, color = AppColors.Secondary)
                    if (selectedIndex < session.exercises.lastIndex) ActionButton("Следующее упражнение", { warmup = false; onSelect(selectedIndex + 1) }, Modifier.fillMaxWidth(), primary = true, icon = Icons.AutoMirrored.Filled.ArrowForward)
                    else ActionButton("Завершить занятие", onFinish, Modifier.fillMaxWidth(), primary = true)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.Small), verticalArrangement = Arrangement.spacedBy(Space.Tiny)) {
                    TextAction(if (warmup) "К рабочим подходам" else "Разминка", { if (warmup) warmup = false else details = "warmup" })
                    TextAction("Ещё", { details = "more" }, icon = Icons.Default.MoreHoriz)
                }
                val recorded = current.sets.filter { it.completed && !it.warmup }.sortedBy { it.index }
                if (recorded.isNotEmpty()) {
                    DividerLine()
                    Text("Записано", style = AppType.Caption, color = AppColors.Secondary)
                    recorded.forEach { set ->
                        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { selectedSetId = set.id }.heightIn(min = Space.Touch),
                            horizontalArrangement = Arrangement.spacedBy(Space.Medium), verticalAlignment = Alignment.CenterVertically) {
                            Text((set.index + 1).toString(), style = AppType.Label, color = AppColors.Secondary)
                            Text(setValue(set, exercise), style = AppType.Body, color = AppColors.Text, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.Check, "Записан", tint = AppColors.Accent, modifier = Modifier.size(Space.Icon))
                        }
                    }
                }
                if (nextIndex < current.target.sets || selectedIndex < session.exercises.lastIndex) TextAction("Завершить занятие", onFinish)
            }
            Spacer(Modifier.height(Space.Section))
        }
        if (resting && mode == "journal") {
            DividerLine()
            if (mode == "journal") Row(Modifier.fillMaxWidth().background(AppColors.Surface).padding(Space.Regular), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.Medium)) {
                Text(timeLabel(timer.seconds), style = AppType.Heading, color = AppColors.Text)
                ActionButton("Открыть отдых", { mode = "active" }, Modifier.weight(1f), primary = true, icon = Icons.AutoMirrored.Filled.ArrowForward)
            }
        }
    }
    }

    if (details == "effort") AppDialog("Повторения в запасе", { details = "" }) {
        Text("Сколько повторений вы ещё смогли бы сделать с хорошей техникой? Проверять до предела не нужно.", style = AppType.Body, color = AppColors.Secondary)
        Input(rir, { value -> val digits = value.filter(Char::isDigit).take(2); if (digits.isEmpty() || digits.toIntOrNull()?.let { it in 0..10 } == true) rir = digits }, "В запасе", numeric = true)
        Text(if (session.assignedSnapshot.week <= 2) "В первые недели ориентир — 3–4." else "Ориентир — 2–3.", style = AppType.Label, color = AppColors.Secondary)
        ActionButton("Готово", { details = "" }, Modifier.fillMaxWidth(), primary = true)
        TextAction("Не указывать", { rir = ""; details = "" })
    }
    if (details == "technique") AppDialog("Как получилось?", { details = "" }) {
        ActionButton("Хорошо", { technique = "good"; details = "" }, Modifier.fillMaxWidth())
        ActionButton("Техника ухудшилась", { technique = "poor"; details = "" }, Modifier.fillMaxWidth())
        ActionButton("Не уверен", { technique = "unknown"; details = "" }, Modifier.fillMaxWidth())
    }
    if (details == "warmup") AppDialog("Разминка", { details = "" }) {
        Text("5–7 минут лёгкой ходьбы или велосипеда. Затем — лёгкие подходы перед рабочим весом.", style = AppType.Body, color = AppColors.Secondary)
        current.sets.filter { it.completed && it.warmup }.forEach { set ->
            TextAction("${set.index + 1}. ${setValue(set, exercise)}", { details = ""; selectedSetId = set.id })
        }
        ActionButton("Записать разминку", { warmup = true; mode = "active"; details = "" }, Modifier.fillMaxWidth(), primary = true)
    }
    if (details == "more") AppDialog("Это упражнение", { details = "" }) {
        TextAction("Оборудование", { details = ""; onEquipment() })
        equipment?.settings?.takeIf { it.isNotBlank() }?.let { Text(it, style = AppType.Label, color = AppColors.Secondary) }
        TextAction("Заметка", { details = ""; onNote() })
        current.note.takeIf { it.isNotBlank() }?.let { Text(it, style = AppType.Body, color = AppColors.Secondary) }
        onDefaultRest?.let { callback -> TextAction("Отдых · ${timeLabel(current.target.restSeconds)}", { details = ""; callback() }) }
        previous?.let { old ->
            Text("Прошлый раз: ${old.sets.filter { it.completed && !it.warmup }.joinToString(" · ") { setValue(it, exercise) }}", style = AppType.Label, color = AppColors.Secondary)
        }
        progression?.let { decision ->
            Text("Подсказка по журналу", style = AppType.Label, color = AppColors.Accent)
            Text(decision.reason.replace("RIR", "повторения в запасе"), style = AppType.Body, color = AppColors.Secondary)
            decision.nextWeightGrams?.let { Text("Следующий вес: ${kg(it)} кг", style = AppType.Body, color = AppColors.Text) }
        }
        DividerLine()
        TextAction("Тренажёр занят", { details = ""; onCoach("Тренажёр занят. Предложи изменение оставшейся части занятия.") })
        TextAction("Осталось мало времени", { details = ""; onCoach("Осталось мало времени. Предложи сокращение оставшегося занятия.") })
        TextAction("Выбрать упражнение", { details = ""; mode = "journal" })
        TextAction("Завершить занятие", { details = ""; onFinish() })
    }
    selectedSetId?.let { id -> current.sets.find { it.id == id }?.let { set ->
        AppDialog(if (set.warmup) "Разминка ${set.index + 1}" else "Подход ${set.index + 1}", { selectedSetId = null }) {
            Text(setValue(set, exercise), style = AppType.Heading, color = AppColors.Text)
            Text("В запасе: ${set.rir ?: "не указано"}", style = AppType.Label, color = AppColors.Secondary)
            Text("Техника: ${when (set.technique) { "good" -> "хорошо"; "poor" -> "ухудшилась"; else -> "не указано" }}", style = AppType.Label, color = AppColors.Secondary)
            ActionButton("Отменить запись", { selectedSetId = null; onUnmark(set.id) }, Modifier.fillMaxWidth())
            ActionButton("Закрыть", { selectedSetId = null }, Modifier.fillMaxWidth(), primary = true)
        }
    } }
}

@Composable private fun NumberEditor(value: String, onValue: (String) -> Unit, label: String, unit: String, style: TextStyle, onMinus: () -> Unit, onPlus: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.Tiny)) {
        Text(label, style = AppType.Label, color = AppColors.Secondary)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.Small)) {
            ActionButton("−", onMinus, Modifier.width(Space.Step))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicTextField(value, onValue, Modifier.fillMaxWidth().semantics { contentDescription = label }, singleLine = true,
                    textStyle = style.copy(color = AppColors.Text, textAlign = TextAlign.Center), cursorBrush = SolidColor(AppColors.Accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    decorationBox = { inner -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { if (value.isEmpty()) Text("—", style = style, color = AppColors.Secondary); inner() } })
                if (unit.isNotBlank()) Text(unit, style = AppType.Label, color = AppColors.Secondary)
            }
            ActionButton("+", onPlus, Modifier.width(Space.Step))
        }
    }
}

private fun shortName(exercise: Exercise): String = when (exercise.id) {
    "leg-press" -> "Жим ногами"
    "chest-press" -> "Жим от груди"
    "lat-pulldown" -> "Верхний блок"
    "supported-row" -> "Тяга с опорой"
    "lateral-raise" -> "Гантели в стороны"
    else -> exercise.name
}
private fun setValue(set: SetEntry, exercise: Exercise) = "${set.weightGrams?.let { "${kg(it)} кг × " }.orEmpty()}${set.reps ?: "—"}${if (exercise.tracking == "reps_per_side") " на сторону" else ""}"
private fun timeLabel(seconds: Int) = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
fun kg(grams: Int): String = java.math.BigDecimal(grams).divide(java.math.BigDecimal(1000)).stripTrailingZeros().toPlainString()
fun grams(input: String): Int? = runCatching { input.replace(',', '.').toBigDecimal().multiply(java.math.BigDecimal(1000)).intValueExact() }.getOrNull()

@Preview(name = "Текущая тренировка", widthDp = 390, heightDp = 900)
@Composable private fun WorkoutPreview() = WorkoutsTheme { WorkoutScreen(PreviewFixtures.state, PreviewFixtures.session, 0, TimerUi(92, true), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
