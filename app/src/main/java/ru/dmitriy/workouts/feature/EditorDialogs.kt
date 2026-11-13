package ru.dmitriy.workouts.feature

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import ru.dmitriy.workouts.domain.*
import ru.dmitriy.workouts.feature.plan.weekdays
import ru.dmitriy.workouts.feature.shared.*
import ru.dmitriy.workouts.feature.workout.*
import ru.dmitriy.workouts.ui.theme.*
import java.time.LocalDate

@Composable fun EditorDialogs(kind: String, id: String, state: LocalState, vm: AppViewModel, dismiss: () -> Unit, onFinished: (String) -> Unit, show: (String, String) -> Unit) {
    when (kind) {
        "rest" -> {
            val parts=id.split('|')
            val target=state.sessions.find { it.id==parts[0] }?.exercises?.find { it.exerciseId==parts[1] }?.target
            var seconds by rememberSaveable(id) { mutableStateOf(target?.restSeconds?.toString().orEmpty()) }
            var future by rememberSaveable(id) { mutableStateOf(false) }
            AppDialog("Отдых между подходами",dismiss) {
                Input(seconds,{seconds=it},"Секунды после рабочего подхода",numeric=true)
                Chip("Сохранить и для будущего плана",future) {future=!future}
                Text("Начиная со следующего подхода.",style=AppType.Label,color=AppColors.Secondary)
                ActionButton("Сохранить отдых",{vm.rest(parts[0],parts[1],seconds.toInt(),future);dismiss()},Modifier.fillMaxWidth(),primary=true,enabled=seconds.toIntOrNull() in 30..600)
                ActionButton("Отмена",dismiss,Modifier.fillMaxWidth())
            }
        }
        "date" -> {
            val dateFormat = java.time.format.DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT)
            var date by rememberSaveable { mutableStateOf(runCatching { LocalDate.parse(state.program.nextDate).format(dateFormat) }.getOrElse { LocalDate.now().format(dateFormat) }) }
            val parsedDate = runCatching { LocalDate.parse(date, dateFormat) }.getOrNull()
            AppDialog("Перенести ближайшее занятие", dismiss) {
                Input(date, { date = it }, "Дата, например 15.11.2026")
                ActionButton("Сохранить дату", { vm.program(state.program.copy(nextDate = parsedDate!!.toString())); dismiss() }, Modifier.fillMaxWidth(), primary = true, enabled = parsedDate != null)
                ActionButton("Отмена", dismiss, Modifier.fillMaxWidth())
            }
        }
        "schedule" -> {
            var days by remember { mutableStateOf(state.program.weekdays) }
            AppDialog("Дни тренировок", dismiss) {
                weekdays.forEach { (day, label) -> Chip("${if (day in days) "✓ " else ""}$label", day in days) { days = if (day in days) days - day else (days + day).sorted() } }
                val warning = ProgramRules.restDayWarning(days)
                if (warning) Text("Между занятиями стоит оставить хотя бы один полный день отдыха.", style = AppType.Label, color = AppColors.Accent)
                ActionButton("Сохранить расписание", { vm.program(state.program.copy(weekdays = days)); dismiss() }, Modifier.fillMaxWidth(), primary = true, enabled = days.size in 2..3)
                ActionButton("Отмена", dismiss, Modifier.fillMaxWidth())
            }
        }
        "stage" -> {
            var keepTwo by remember { mutableStateOf(state.program.weekdays.size == 2) }
            AppDialog("Перейти к неделе ${state.program.week + 1}?", dismiss) {
                val next = ProgramRules.forWeek(state.program, state.program.week + 1)
                Text("Рабочих подходов: ${state.program.targets.sumOf { it.sets }} → ${next.targets.sumOf { it.sets }}. Прошлые занятия сохранятся.", style = AppType.Body, color = AppColors.Secondary)
                if (state.program.weekdays.size == 2) Chip("Сохранить два дня в неделю", keepTwo) { keepTwo = !keepTwo }
                ActionButton("Подтвердить переход", { vm.nextStage(keepTwo); dismiss() }, Modifier.fillMaxWidth(), primary = true)
                ActionButton("Остаться на этом этапе", dismiss, Modifier.fillMaxWidth())
            }
        }
        "target" -> state.program.targets.find { it.exerciseId == id }?.let { target ->
            var sets by rememberSaveable { mutableStateOf(target.sets.toString()) }
            var min by rememberSaveable { mutableStateOf(target.minReps.toString()) }
            var max by rememberSaveable { mutableStateOf(target.maxReps.toString()) }
            var rest by rememberSaveable { mutableStateOf(target.restSeconds.toString()) }
            AppDialog("Настроить упражнение", dismiss) {
                Text(state.exercises.find { it.id == id }?.name.orEmpty(), style = AppType.Body, color = AppColors.Text)
                Input(sets, { sets = it }, "Рабочие подходы", numeric = true)
                Input(min, { min = it }, "Повторения: от", numeric = true)
                Input(max, { max = it }, "Повторения: до", numeric = true)
                Input(rest, { rest = it }, "Отдых, секунды", numeric = true)
                ActionButton("Выбрать оборудование", { show("equipment", "program|${target.exerciseId}|${target.equipmentId}") }, Modifier.fillMaxWidth())
                Text("Для будущих занятий. Текущая тренировка сохранится.", style = AppType.Label, color = AppColors.Secondary)
                ActionButton("Сохранить", { vm.program(state.program.copy(targets = state.program.targets.map { if (it.exerciseId == id) it.copy(sets = sets.toInt(), minReps = min.toInt(), maxReps = max.toInt(), restSeconds = rest.toInt()) else it })); dismiss() }, Modifier.fillMaxWidth(), primary = true,
                    enabled = sets.toIntOrNull() in 1..5 && min.toIntOrNull() in 1..30 && max.toIntOrNull() in 1..30 && (max.toIntOrNull() ?: 0) >= (min.toIntOrNull() ?: 99) && rest.toIntOrNull() in 30..600)
                ActionButton("Отмена", dismiss, Modifier.fillMaxWidth())
            }
        }
        "equipment" -> {
            val parts = id.split('|')
            var selectedId by rememberSaveable(id) { mutableStateOf(parts[2]) }
            val equipment = state.equipment.find { it.id == selectedId }
            var name by rememberSaveable(selectedId) { mutableStateOf(equipment?.name.orEmpty()) }
            var settings by rememberSaveable(selectedId) { mutableStateOf(equipment?.settings.orEmpty()) }
            var step by rememberSaveable(selectedId) { mutableStateOf(equipment?.stepGrams?.let(::kg) ?: "2.5") }
            AppDialog("Тренажёр или снаряд", dismiss) {
                state.equipment.filter { it.exerciseId == parts[1] }.forEach { profile -> Chip(profile.name, selectedId == profile.id) { selectedId = profile.id } }
                Chip("Добавить другой", equipment == null) { selectedId = java.util.UUID.randomUUID().toString() }
                Input(name, { name = it }, "Название / номер тренажёра")
                Input(settings, { settings = it }, "Сиденье, валики и другие настройки", multiline = true)
                Input(step, { step = it }, "Шаг веса, кг", numeric = true)
                Text("Для другого тренажёра вес подбирается заново. Сменить его можно до первого подхода.", style = AppType.Label, color = AppColors.Secondary)
                ActionButton("Сохранить и выбрать", { vm.chooseEquipment(EquipmentProfile(selectedId, parts[1], name, settings, grams(step) ?: 2500), parts[0].takeUnless { it == "program" }); dismiss() }, Modifier.fillMaxWidth(), primary = true, enabled = name.isNotBlank() && (grams(step) ?: 0) > 0)
                ActionButton("Отмена", dismiss, Modifier.fillMaxWidth())
            }
        }
        "note" -> {
            val parts = id.split('|')
            val current = state.sessions.find { it.id == parts[0] }?.exercises?.find { it.exerciseId == parts[1] }?.note.orEmpty()
            var note by rememberSaveable { mutableStateOf(current) }
            AppDialog("Заметка упражнения", dismiss) {
                Input(note, { note = it }, "Техника и настройки", multiline = true)
                ActionButton("Сохранить", { vm.exerciseNote(parts[0], parts[1], note); dismiss() }, Modifier.fillMaxWidth(), primary = true)
                ActionButton("Отмена", dismiss, Modifier.fillMaxWidth())
            }
        }
        "weight" -> {
            var weight by rememberSaveable { mutableStateOf("") }
            AppDialog("Масса тела", dismiss) {
                Input(weight, { weight = it }, "Масса тела сегодня, кг", numeric = true)
                ActionButton("Добавить измерение", { vm.bodyWeight(grams(weight)!!); dismiss() }, Modifier.fillMaxWidth(), primary = true, enabled = (grams(weight) ?: 0) in 20_000..400_000)
                ActionButton("Отмена", dismiss, Modifier.fillMaxWidth())
            }
        }
        "checkin" -> {
            var fatigue by rememberSaveable { mutableStateOf("") }
            var minutes by rememberSaveable { mutableStateOf("") }
            AppDialog("Как вы сегодня?", dismiss) {
                Text("Можно пропустить.", style = AppType.Label, color = AppColors.Secondary)
                Input(fatigue, { fatigue = it }, "Усталость от 1 до 5", numeric = true)
                Input(minutes, { minutes = it }, "Доступное время, минуты", numeric = true)
                ActionButton("Сохранить", { vm.checkIn(fatigue.toIntOrNull(), minutes.toIntOrNull()); dismiss() }, Modifier.fillMaxWidth(), primary = true, enabled = (fatigue.isBlank() || fatigue.toIntOrNull() in 1..5) && (minutes.isBlank() || minutes.toIntOrNull() in 10..180))
                ActionButton("Пропустить", dismiss, Modifier.fillMaxWidth())
            }
        }
        "finish" -> AppDialog("Сохранить частичное занятие?", dismiss) {
            Text("Не все подходы выполнены. Сохраним записанные результаты.", style = AppType.Body, color = AppColors.Secondary)
            ActionButton("Сохранить частичное", { vm.finish(id, true) { dismiss(); onFinished(id) } }, Modifier.fillMaxWidth(), primary = true)
            ActionButton("Продолжить тренировку", dismiss, Modifier.fillMaxWidth())
        }
        "rollback" -> AppDialog("Отменить изменение?", dismiss) {
            Text("Вернём прежние значения, если после этого вы ничего не меняли.", style = AppType.Body, color = AppColors.Secondary)
            ActionButton("Вернуть как было", { vm.rollback(id); dismiss() }, Modifier.fillMaxWidth(), primary = true)
            ActionButton("Отмена", dismiss, Modifier.fillMaxWidth())
        }
        "disconnect" -> AppDialog("Отключить помощника?", dismiss) {
            Text("Личный ключ будет удалён с телефона. Тренировки и сохранённые ответы останутся.", style = AppType.Body, color = AppColors.Secondary)
            ActionButton("Отключить", { vm.disconnect(); dismiss() }, Modifier.fillMaxWidth(), primary = true)
            ActionButton("Отмена", dismiss, Modifier.fillMaxWidth())
        }
        "clear" -> AppDialog("Очистить историю тренера?", dismiss) {
            Text("Диалоги и разборы будут удалены. Тренировки останутся.", style = AppType.Body, color = AppColors.Secondary)
            ActionButton("Удалить диалоги и разборы", { vm.clearChats(); dismiss() }, Modifier.fillMaxWidth(), primary = true)
            ActionButton("Отмена", dismiss, Modifier.fillMaxWidth())
        }
        "correction" -> {
            val parts = id.split('|')
            val exercise = state.sessions.find { it.id == parts[0] }?.exercises?.find { it.exerciseId == parts[1] }
            val set = exercise?.sets?.find { it.id == parts[2] }
            if (set != null) {
                var weight by rememberSaveable { mutableStateOf(set.weightGrams?.let(::kg).orEmpty()) }
                var reps by rememberSaveable { mutableStateOf(set.reps?.toString().orEmpty()) }
                var rir by rememberSaveable { mutableStateOf(set.rir?.toString().orEmpty()) }
                var tech by rememberSaveable { mutableStateOf(set.technique) }
                AppDialog("Исправить ошибочную запись", dismiss) {
                    if (set.weightGrams != null) Input(weight, { weight = it }, "Вес, кг", numeric = true)
                    Input(reps, { reps = it }, "Повторения", numeric = true)
                    Input(rir, { rir = it }, "Повторения в запасе, необязательно", numeric = true)
                    Chip("Хорошая техника", tech == "good") { tech = "good" }
                    Chip("Оценка неизвестна", tech == "unknown") { tech = "unknown" }
                    Chip("Техника ухудшилась", tech == "poor") { tech = "poor" }
                    ActionButton("Сохранить исправление", { vm.saveSet(parts[0], parts[1], SetDraft(set.index, if (set.weightGrams == null) null else grams(weight), reps.toIntOrNull(), rir.toIntOrNull(), tech, set.warmup)); dismiss() }, Modifier.fillMaxWidth(), primary = true, enabled = reps.toIntOrNull() in 1..100)
                    ActionButton("Отмена", dismiss, Modifier.fillMaxWidth())
                }
            }
        }
    }
}
