package ru.dmitriy.workouts.feature.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.clickable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import ru.dmitriy.workouts.domain.*
import ru.dmitriy.workouts.feature.AppDialog
import ru.dmitriy.workouts.feature.shared.*
import ru.dmitriy.workouts.ui.theme.*
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable fun TodayScreen(
    state: LocalState,
    onStart: () -> Unit,
    onTechnique: (String) -> Unit,
    onAdapt: (String) -> Unit,
    onReschedule: () -> Unit,
    onCheckIn: () -> Unit,
    onInsight: (String) -> Unit,
    onSettings: () -> Unit = {},
    onResults: (String) -> Unit = {},
) {
    var today by remember { mutableStateOf(LocalDate.now()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { today = LocalDate.now() }
    LaunchedEffect(today) {
        val now = ZonedDateTime.now()
        delay(Duration.between(now, today.plusDays(1).atStartOfDay(now.zone)).toMillis().coerceAtLeast(1))
        today = LocalDate.now()
    }
    val day = remember(state, today) { state.todayDay(today) }
    var sheet by rememberSaveable { mutableStateOf("") }
    val fontScale = LocalDensity.current.fontScale
    val dateFormat = remember { DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru")) }
    val status = when (day.phase) {
        TodayPhase.Training -> "Время для себя"
        TodayPhase.Active -> "Тренировка в процессе"
        TodayPhase.Recovery -> "Отдых — часть прогресса"
        TodayPhase.Completed -> "Тренировка выполнена"
        TodayPhase.Saved -> "Занятие сохранено частично"
    }
    val header: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.Medium)) {
            Text(day.title, style = AppType.TodayTitle, color = AppColors.Text, textAlign = TextAlign.Center)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.Tiny)) {
                Text("Неделя ${day.program.week} программы", style = AppType.Label, color = AppColors.Secondary)
                Text(if (day.phase == TodayPhase.Recovery) "0 упражнений · без нагрузки"
                    else "${exerciseCount(day.targets.size)} · ≈ ${day.targets.estimatedMinutes()} мин",
                    style = AppType.Label, color = AppColors.Secondary, textAlign = TextAlign.Center)
            }
        }
    }
    val footer: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().animateContentSize(tween(Motion.Duration)),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.Medium)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.Tiny)) {
                Text(status, style = AppType.Body, color = if (day.phase == TodayPhase.Recovery) AppColors.Secondary else AppColors.Accent,
                    textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text(when (day.phase) {
                    TodayPhase.Training -> "Начните в своём темпе"
                    TodayPhase.Active -> "${day.session!!.completedWorkSets()} из ${day.session.assignedWorkSets()} рабочих подходов"
                    TodayPhase.Recovery -> "Следующая тренировка — ${day.nextDate.format(dateFormat)}"
                    TodayPhase.Completed -> "Всё на сегодня. Вы хорошо поработали"
                    TodayPhase.Saved -> "${day.session!!.completedWorkSets()} из ${day.session.assignedWorkSets()} рабочих подходов"
                }, style = AppType.Label, color = AppColors.Secondary, textAlign = TextAlign.Center)
            }
            AnimatedContent(day, transitionSpec = {
                (fadeIn(tween(Motion.Duration)) togetherWith fadeOut(tween(Motion.Duration))).using(null)
            }, label = "Действие дня") { shown ->
                ActionButton(shown.action, {
                    when (shown.phase) {
                        TodayPhase.Training, TodayPhase.Active -> onStart()
                        TodayPhase.Recovery -> sheet = "next"
                        TodayPhase.Completed, TodayPhase.Saved -> shown.session?.let { onResults(it.id) }
                    }
                }, Modifier.fillMaxWidth().heightIn(min = Space.TodayAction), primary = true,
                    icon = when (shown.phase) {
                        TodayPhase.Training, TodayPhase.Active -> Icons.Default.PlayArrow
                        TodayPhase.Recovery -> Icons.AutoMirrored.Filled.KeyboardArrowRight
                        TodayPhase.Completed, TodayPhase.Saved -> Icons.Default.BarChart
                    })
            }
            if (day.phase != TodayPhase.Training && day.phase != TodayPhase.Active) {
                Text(if (day.phase == TodayPhase.Recovery) "Дайте телу время восстановиться" else "Прогресс складывается из таких дней",
                    style = AppType.Caption, color = AppColors.Secondary, textAlign = TextAlign.Center)
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.Section, vertical = Space.Small), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = onReschedule).heightIn(min = Space.Touch), verticalArrangement = Arrangement.Center) {
                Text("Сегодня", style = AppType.Heading, color = AppColors.Text)
                Text(today.format(dateFormat), style = AppType.Caption, color = AppColors.Secondary)
            }
            IconAction(Icons.Outlined.Settings, "Настройки", onSettings)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            val compact = maxHeight < Space.TodayCompactHeight || fontScale > 1.4f
            if (compact) {
                Column(Modifier.widthIn(max = Space.TodayContent).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.Section, vertical = Space.Large), horizontalAlignment = Alignment.CenterHorizontally) {
                    header()
                    BloomStage(day.phase, Modifier.fillMaxWidth().height(Space.TodayArtCompact))
                    footer()
                }
            } else {
                val artSize = minOf(maxWidth + Space.Huge, (maxHeight * .57f).coerceIn(Space.TodayArtMin, Space.TodayArtMax))
                Column(Modifier.widthIn(max = Space.TodayContent).fillMaxSize().padding(horizontal = Space.Section, vertical = Space.Large),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    header()
                    BloomStage(day.phase, Modifier.weight(1f).fillMaxWidth(), artSize)
                    footer()
                }
            }
        }
    }
    if (sheet == "next") AppDialog("Следующая тренировка", { sheet = "" }) {
        Text(day.nextDate.format(dateFormat), style = AppType.Label, color = AppColors.Accent)
        Text(state.program.dayTitle(), style = AppType.Title, color = AppColors.Text)
        Text("Неделя ${state.program.week} · ${exerciseCount(state.program.targets.size)} · ≈ ${state.program.targets.estimatedMinutes()} мин",
            style = AppType.Label, color = AppColors.Secondary)
        PlanExercises(state.program.targets, state, onTechnique = { sheet = ""; onTechnique(it) })
        TextAction("Перенести дату", { sheet = ""; onReschedule() }, icon = Icons.Default.CalendarToday)
    }
}

@Composable private fun BloomStage(phase: TodayPhase, modifier: Modifier, artSize: androidx.compose.ui.unit.Dp = Space.TodayArtCompact) {
    Box(modifier, contentAlignment = Alignment.Center) {
        TrainingBloom(phase, Modifier.size(artSize))
        if (phase == TodayPhase.Completed || phase == TodayPhase.Saved) {
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = Space.BloomBadgeOffset)
                .background(AppColors.AccentSoft, AppShapes.Round)
                .border(Space.Hairline, AppColors.Glow.copy(alpha = .3f), AppShapes.Round)
                .heightIn(min = Space.TodayStatus).padding(horizontal = Space.Regular, vertical = Space.Small),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.Small)) {
                Icon(if (phase == TodayPhase.Completed) Icons.Default.Check else Icons.Default.DoneAll, null,
                    tint = AppColors.Accent, modifier = Modifier.size(Space.Regular))
                Text(if (phase == TodayPhase.Completed) "Выполнено" else "Сохранено", style = AppType.Caption, color = AppColors.Accent)
            }
        }
    }
}

@Composable private fun PlanExercises(targets: List<ExerciseTarget>, state: LocalState, onTechnique: (String) -> Unit) {
    Column {
        targets.forEachIndexed { index, target ->
            val exercise = state.exercises.find { it.id == target.exerciseId }
            ExerciseRow((index + 1).toString().padStart(2, '0'), exercise?.name ?: "Упражнение",
                "${target.sets} × ${target.minReps}–${target.maxReps}${if (exercise?.tracking == "reps_per_side") " на сторону" else ""}") { onTechnique(target.exerciseId) }
        }
    }
}

@Composable fun ExerciseRow(number: String, name: String, detail: String, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = Space.Touch).padding(vertical = Space.Medium),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.Regular)) {
            Text(number, style = AppType.Label, color = AppColors.Secondary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Tiny)) {
                Text(name, style = AppType.Body, color = AppColors.Text)
                Text(detail, style = AppType.Label, color = AppColors.Secondary)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Техника", tint = AppColors.Secondary, modifier = Modifier.size(Space.Icon))
        }
        DividerLine()
    }
}

@Preview(name = "Сегодня · тренировка", widthDp = 360, heightDp = 800, showBackground = true)
@Composable private fun TodayPreview() = WorkoutsTheme {
    TodayScreen(PreviewFixtures.state.copy(program = PreviewFixtures.state.program.copy(nextDate = LocalDate.now().toString())), {}, {}, {}, {}, {}, {})
}

@Preview(name = "Сегодня · восстановление", widthDp = 360, heightDp = 800, showBackground = true)
@Composable private fun RecoveryPreview() = WorkoutsTheme {
    TodayScreen(PreviewFixtures.state.copy(program = PreviewFixtures.state.program.copy(nextDate = LocalDate.now().plusDays(1).toString())), {}, {}, {}, {}, {}, {})
}
