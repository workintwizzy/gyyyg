package ru.dmitriy.workouts.feature.plan

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import ru.dmitriy.workouts.domain.*
import ru.dmitriy.workouts.feature.shared.*
import ru.dmitriy.workouts.ui.theme.*

@Composable fun ProposalComparison(proposal: PlanProposal, exercises: List<Exercise>, onApply: () -> Unit, onReject: () -> Unit, onChangeScope: ((String) -> Unit)? = null, evidenceLabels: Map<String, String> = emptyMap(), onEvidence: ((String) -> Unit)? = null) {
    val current = proposal.status == "proposed" && proposal.expiresAt > System.currentTimeMillis()
    var detailsVisible by rememberSaveable(proposal.proposalId) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.Large), verticalArrangement = Arrangement.spacedBy(Space.Section)) {
        Text("Предложение", style = AppType.Hero, color = AppColors.Text)
        Text(if (proposal.status == "proposed" && !current) "Устарело" else proposalStatus(proposal.status), style = AppType.Label, color = AppColors.Accent)
        Text(proposal.summary, style = AppType.Title, color = AppColors.Text)
        Text(if (proposal.scope == "session") "Только это занятие" else "Будущие занятия", style = AppType.Label, color = AppColors.Secondary)
        proposal.operations.forEach { operation ->
            Panel(Modifier.fillMaxWidth()) {
                Text(exercises.find { it.id == operation.exerciseId }?.name ?: operationLabel(operation.type), style = AppType.Heading, color = AppColors.Text)
                Text(operationLabel(operation.type), style = AppType.Label, color = AppColors.Secondary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.Regular)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
                        Text("БЫЛО", style = AppType.Caption, color = AppColors.Secondary)
                        Text(operationValue(operation.type, operation.previous, exercises), style = AppType.Body, color = AppColors.Secondary)
                    }
                    Text("→", style = AppType.Heading, color = AppColors.Secondary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
                        Text("СТАНЕТ", style = AppType.Caption, color = AppColors.Accent)
                        Text(operationValue(operation.type, operation.value, exercises), style = AppType.Heading, color = AppColors.Text)
                    }
                }
            }
        }
        if (current) {
            ActionButton("Применить", onApply, Modifier.fillMaxWidth(), primary = true)
            ActionButton("Отклонить", onReject, Modifier.fillMaxWidth())
        } else Text(if (proposal.status == "proposed" || proposal.status == "stale") "Записи изменились или прошло слишком много времени. Попросите новое предложение." else "Решение сохранено.", style = AppType.Body, color = AppColors.Secondary)
        ActionButton(if (detailsVisible) "Скрыть подробности" else "Почему эти изменения", { detailsVisible = !detailsVisible }, Modifier.fillMaxWidth())
        if (detailsVisible) {
            Text(proposal.reason, style = AppType.Body, color = AppColors.Text)
            Text("Выполненные подходы сохранятся.", style = AppType.Label, color = AppColors.Secondary)
            if (proposal.evidenceIds.isNotEmpty()) {
                SectionLabel("На основе ваших записей")
                proposal.evidenceIds.forEach { id ->
                    val label = evidenceLabels[id] ?: "Сохранённое занятие"
                    if (onEvidence != null) ActionButton(label, { onEvidence(id) }, Modifier.fillMaxWidth())
                    else Text(label, style = AppType.Label, color = AppColors.Secondary)
                }
            }
            if (current && onChangeScope != null) {
                ActionButton(if (proposal.scope == "session") "Предложить для будущих занятий" else "Предложить для этого занятия", { onChangeScope(if (proposal.scope == "session") "program" else "session") }, Modifier.fillMaxWidth())
                Text("Тренер подготовит отдельное предложение.", style = AppType.Label, color = AppColors.Secondary)
            }
        }
    }
}
fun proposalStatus(status: String) = when (status) { "proposed" -> "Предложено"; "applied" -> "Применено"; "rejected" -> "Отклонено"; "stale" -> "Устарело"; "reverted" -> "Отменено"; else -> "Сохранено" }
fun operationLabel(type: String) = when (type) { "reorder" -> "Порядок упражнений"; "replace" -> "Замена упражнения"; "reduceSets" -> "Рабочие подходы"; "weight" -> "Рабочий вес"; "reps" -> "Повторения"; "rest" -> "Отдых"; "stage" -> "Неделя программы"; "schedule" -> "Дни занятий"; else -> "Изменение" }
fun operationValue(type: String, value: String, exercises: List<Exercise>): String = when (type) {
    "weight" -> value.toIntOrNull()?.let { "${ru.dmitriy.workouts.feature.workout.kg(it)} кг" } ?: "Не задан"
    "rest" -> "$value с"
    "stage" -> "Неделя $value"
    "replace" -> exercises.find { it.id == value }?.name ?: "Другое упражнение"
    "reorder" -> value.split(',').joinToString("\n") { id -> exercises.find { it.id == id }?.name ?: "Упражнение" }
    "schedule" -> value.split(',').mapNotNull { day -> day.toIntOrNull()?.let { weekdays[it] } }.joinToString(", ")
    "reps" -> value.replace('-', '–')
    "reduceSets" -> value.toIntOrNull()?.toString() ?: "Не задано"
    else -> "Изменение"
}

@Preview(name = "Было → Станет", widthDp = 390, heightDp = 850)
@Composable private fun ProposalPreview() = WorkoutsTheme { ProposalComparison(PreviewFixtures.proposal, PreviewFixtures.exercises, {}, {}) }
