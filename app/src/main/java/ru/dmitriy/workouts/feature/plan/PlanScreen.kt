package ru.dmitriy.workouts.feature.plan

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
import ru.dmitriy.workouts.ui.theme.*

@Composable fun PlanScreen(state: LocalState, onTechnique: (String) -> Unit, onEdit: (String) -> Unit, onSchedule: () -> Unit, onStage: () -> Unit, onProposal: (String) -> Unit, onRollback: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf("Упражнения") }
    var historyVisible by rememberSaveable { mutableStateOf(false) }
    var pastPlansVisible by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.Large), verticalArrangement = Arrangement.spacedBy(Space.Section)) {
        Text("План", style = AppType.Hero, color = AppColors.Text)
        Panel(Modifier.fillMaxWidth()) {
            Text(state.program.name, style = AppType.Title, color = AppColors.Text)
            Text("Неделя ${state.program.week}", style = AppType.Label, color = AppColors.Accent)
            Text(state.program.weekdays.joinToString(" · ") { weekdays[it].orEmpty() }, style = AppType.Body, color = AppColors.Secondary)
            ActionButton("Дни занятий", onSchedule, Modifier.fillMaxWidth())
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.Small), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
            listOf("Упражнения", "Этапы", "Изменения").forEach { title -> Chip(title, tab == title) { tab = title } }
        }
        when (tab) {
            "Упражнения" -> state.program.targets.forEach { target ->
                Column(verticalArrangement = Arrangement.spacedBy(Space.Small)) {
                    Text(state.exercises.find { it.id == target.exerciseId }?.name ?: "Упражнение", style = AppType.Heading, color = AppColors.Text)
                    Text("${target.sets} × ${target.minReps}–${target.maxReps} · отдых ${target.restSeconds} с", style = AppType.Label, color = AppColors.Secondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.Small)) {
                        ActionButton("Техника", { onTechnique(target.exerciseId) }, Modifier.weight(1f))
                        ActionButton("Настроить", { onEdit(target.exerciseId) }, Modifier.weight(1f))
                    }
                    DividerLine()
                }
            }
            "Этапы" -> {
                state.program.stages.forEach { stage ->
                    val selected = state.program.week in stage.weekFrom..stage.weekTo
                    Panel(Modifier.fillMaxWidth(), outlined = selected) {
                        Text("${if (stage.weekFrom == stage.weekTo) "Неделя ${stage.weekFrom}" else "Недели ${stage.weekFrom}–${stage.weekTo}"}${if (selected) " · сейчас" else ""}", style = AppType.Heading, color = if (selected) AppColors.Accent else AppColors.Text)
                        Text("${stage.sessionsPerWeek} занятия в неделю", style = AppType.Body, color = AppColors.Secondary)
                        Text(stage.setsOverride?.let { if (it == 1) "По одному подходу" else "По $it подхода" } ?: "Подходы по плану упражнения", style = AppType.Label, color = AppColors.Secondary)
                    }
                }
                if (state.program.week < 8) ActionButton("Следующая неделя", onStage, Modifier.fillMaxWidth(), primary = true)
                Text("Нагрузка меняется только с вашего согласия.", style = AppType.Label, color = AppColors.Secondary)
            }
            "Изменения" -> {
                if (state.proposals.isEmpty()) EmptyState("Пока без предложений", "Попросите тренера изменить занятие или план.")
                state.proposals.sortedByDescending { it.expiresAt }.forEach { proposal ->
                    Panel(Modifier.fillMaxWidth(), outlined = true) {
                        Text(proposalStatus(proposal.status), style = AppType.Caption, color = AppColors.Accent)
                        Text(proposal.summary, style = AppType.Heading, color = AppColors.Text)
                        ActionButton("Посмотреть", { onProposal(proposal.proposalId) }, Modifier.fillMaxWidth())
                    }
                }
                if (state.changeLogs.isNotEmpty()) {
                    ActionButton(if (historyVisible) "Скрыть принятые изменения" else "Принятые изменения", { historyVisible = !historyVisible }, Modifier.fillMaxWidth())
                    if (historyVisible) state.changeLogs.sortedByDescending { it.createdAt }.forEach { log ->
                        Panel(Modifier.fillMaxWidth()) {
                            Text(formatDate(log.createdAt), style = AppType.Label, color = AppColors.Secondary)
                            Text(state.proposals.find { it.proposalId == log.proposalId }?.summary ?: "Изменение плана", style = AppType.Body, color = AppColors.Text)
                            if (!log.rolledBack) ActionButton("Отменить изменение", { onRollback(log.id) })
                            else Text("Отменено", style = AppType.Label, color = AppColors.Secondary)
                        }
                    }
                }
                if (state.revisions.isNotEmpty()) {
                    ActionButton(if (pastPlansVisible) "Скрыть прошлые планы" else "Прошлые планы", { pastPlansVisible = !pastPlansVisible }, Modifier.fillMaxWidth())
                    if (pastPlansVisible) state.revisions.sortedByDescending { it.createdAt }.forEach { revision ->
                        Panel(Modifier.fillMaxWidth()) {
                            Text("${formatDate(revision.createdAt)} · неделя ${revision.snapshot.week}", style = AppType.Heading, color = AppColors.Text)
                            Text(revision.snapshot.weekdays.joinToString(" · ") { weekdays[it].orEmpty() }, style = AppType.Label, color = AppColors.Secondary)
                            revision.snapshot.targets.forEach { target -> Text("${state.exercises.find { it.id == target.exerciseId }?.name ?: "Упражнение"}: ${target.sets} × ${target.minReps}–${target.maxReps}, ${target.restSeconds} с", style = AppType.Label, color = AppColors.Secondary) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(Space.Section))
    }
}
val weekdays = mapOf(1 to "Понедельник", 2 to "Вторник", 3 to "Среда", 4 to "Четверг", 5 to "Пятница", 6 to "Суббота", 7 to "Воскресенье")
