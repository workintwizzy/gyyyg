package ru.dmitriy.workouts.feature

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import ru.dmitriy.workouts.domain.*
import ru.dmitriy.workouts.feature.coach.*
import ru.dmitriy.workouts.feature.plan.*
import ru.dmitriy.workouts.feature.progress.ProgressScreen
import ru.dmitriy.workouts.feature.settings.*
import ru.dmitriy.workouts.feature.shared.*
import ru.dmitriy.workouts.feature.today.TodayScreen
import ru.dmitriy.workouts.feature.workout.*
import ru.dmitriy.workouts.ui.theme.*
import java.time.LocalDate

@Serializable private data class Route(val screen: String, val id: String = "") : NavKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun WorkoutsApp(viewModel: AppViewModel, onExportJson: (Boolean) -> Unit, onExportCsv: () -> Unit, onImport: () -> Unit) {
    val state by viewModel.local.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ai by viewModel.ai.collectAsStateWithLifecycle()
    val requestBusy by viewModel.busy.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val timer by viewModel.timer.state.collectAsStateWithLifecycle()
    val exerciseIndex by viewModel.selectedExercise.collectAsStateWithLifecycle()
    val workoutEntry by viewModel.workoutEntry.collectAsStateWithLifecycle()
    val conversationId by viewModel.conversationId.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val backStack = rememberNavBackStack(Route("today"))
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val coachUiMemory = rememberCoachUiMemory()
    val coachSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var dialog by rememberSaveable { mutableStateOf("") }
    var dialogId by rememberSaveable { mutableStateOf("") }
    var coachSheet by rememberSaveable { mutableStateOf(false) }
    var coachSession by rememberSaveable { mutableStateOf<String?>(null) }
    var coachExercise by rememberSaveable { mutableStateOf<String?>(null) }
    var coachPrompt by rememberSaveable { mutableStateOf("") }
    fun hideKeyboard() { focusManager.clearFocus(); keyboard?.hide() }
    fun open(screen: String, id: String = "") {
        val destination = Route(screen, id)
        if (backStack.lastOrNull() == destination) return
        hideKeyboard()
        backStack.add(destination)
    }
    fun pop() { hideKeyboard(); if (backStack.size > 1) backStack.removeLastOrNull() }
    fun selectTab(screen: String) {
        if ((backStack.lastOrNull() as? Route)?.screen == screen) return
        hideKeyboard()
        Snapshot.withMutableSnapshot {
            backStack[0] = Route(screen)
            while (backStack.size > 1) backStack.removeLastOrNull()
        }
    }
    fun show(kind: String, id: String = "") { dialog = kind; dialogId = id }
    val uri = LocalUriHandler.current
    val onProposal: (String) -> Unit = { coachSheet = false; open("proposal", it) }
    val onInsight: (String) -> Unit = { coachSheet = false; open("insight", it) }
    val selectConversation: (String) -> Unit = { id ->
        state.conversations.find { it.id == id }?.let { conversation ->
            coachSession = conversation.sessionId
            coachExercise = conversation.exerciseId
        }
        viewModel.conversation(id)
    }
    val selectedConversation = state.conversations.find { it.id == conversationId }
    val contextSession = if (selectedConversation != null) selectedConversation.sessionId else coachSession
    val contextExercise = if (selectedConversation != null) selectedConversation.exerciseId else coachExercise
    val contextLabel = buildString {
        contextExercise?.let { id -> append(state.exercises.find { it.id == id }?.name ?: "упражнение"); append(" · ") }
        append(if (contextSession != null) "Это занятие" else "Программа · неделя ${state.program.week}")
    }
    WorkoutsTheme {
        Box(Modifier.fillMaxSize().background(AppColors.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            when {
                state.exercises.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Готовим журнал…", style = AppType.Body, color = AppColors.Secondary) }
                !state.profile.onboarded -> ProfileScreen(state.profile, true, viewModel::bodyWeight, viewModel::profile)
                else -> Box(Modifier.fillMaxSize().imePadding()) {
                        NavDisplay(backStack = backStack, onBack = ::pop, modifier = Modifier.fillMaxSize(),
                            transitionSpec = { (fadeIn(tween(Motion.Duration, easing = FastOutSlowInEasing)) togetherWith fadeOut(tween(Motion.Duration, easing = FastOutSlowInEasing))).using(null) },
                            popTransitionSpec = { (fadeIn(tween(Motion.Duration, easing = FastOutSlowInEasing)) togetherWith fadeOut(tween(Motion.Duration, easing = FastOutSlowInEasing))).using(null) },
                            predictivePopTransitionSpec = { (fadeIn(tween(Motion.Duration, easing = FastOutSlowInEasing)) togetherWith fadeOut(tween(Motion.Duration, easing = FastOutSlowInEasing))).using(null) },
                            entryProvider = { key ->
                                val route = key as Route
                                NavEntry(key) {
                                    // Keep each destination's chrome inside its own fixed-size scene.
                                    // Removing the bottom bar must not resize the outgoing screen mid-transition.
                                    Column(Modifier.fillMaxSize()) {
                                        DestinationHeader(route.screen, backStack.size > 1, ::pop) { open("settings") }
                                        Box(Modifier.weight(1f).fillMaxWidth()) {
                                    when (route.screen) {
                                        "today" -> TodayScreen(state, { viewModel.start { open("workout", it) } }, { open("technique", it) },
                                            { prompt -> if (connected) viewModel.proposeForToday(prompt, onProposal) else open("settings") },
                                            { show("date") }, { show("checkin") }, onInsight, onSettings = { open("settings") }, onResults = { open("session", it) })
                                        "plan" -> PlanScreen(state, { open("technique", it) }, { show("target", it) }, { show("schedule") }, { show("stage") }, onProposal, { show("rollback", it) })
                                        "workout" -> state.sessions.find { it.id == route.id }?.let { session ->
                                            val index = exerciseIndex.coerceIn(0, session.exercises.lastIndex.coerceAtLeast(0))
                                            val exerciseId = session.exercises.getOrNull(index)?.exerciseId.orEmpty()
                                            WorkoutScreen(state, session, index, TimerUi(timer.remainingSeconds, timer.running, timer.paused),
                                                viewModel::selectExercise, { viewModel.saveSet(session.id, exerciseId, it) }, viewModel::unmark,
                                                { open("technique", exerciseId) }, { prompt ->
                                                    coachSession = session.id; coachExercise = exerciseId; coachPrompt = prompt
                                                    val existing = state.conversations.lastOrNull { it.sessionId == session.id && it.exerciseId == exerciseId }
                                                    if (existing != null) viewModel.conversation(existing.id) else viewModel.newConversation(session.id, exerciseId)
                                                    if (viewModel.draft.value.isBlank()) viewModel.updateDraft(prompt)
                                                    coachSheet = true
                                                },
                                                { if (session.isComplete()) viewModel.finish(session.id, false) { pop(); open("session", session.id) } else show("finish", session.id) },
                                                viewModel.timer::pause, viewModel.timer::resume, viewModel.timer::skip, viewModel.timer::add30,
                                                { show("equipment", "${session.id}|$exerciseId|${session.exercises[index].equipmentId}") }, { show("note", "${session.id}|$exerciseId") }, viewModel.progression(exerciseId, session.exercises[index].equipmentId),{show("rest","${session.id}|$exerciseId")}, onBack = ::pop, onSettings = { open("settings") }, entryToken = workoutEntry)
                                        }
                                        "coach" -> CoachScreen(state, connected, ai.phase, ai.text, ai.error, contextLabel,
                                            { viewModel.send(it, contextSession, contextExercise) }, viewModel::cancelAi, { open("settings") }, selectConversation, conversationId,
                                            { viewModel.newConversation(contextSession, contextExercise) }, onProposal, onInsight, persistedDraft = draft, onDraft = viewModel::updateDraft,
                                            onRequestProposal = { viewModel.propose(it, contextSession) {} }, onApplyProposal = { viewModel.apply(it) {} }, onRejectProposal = { viewModel.reject(it) {} }, targetSessionId = contextSession,
                                            onBack = if (backStack.size > 1) ({ pop() }) else null,
                                            requestBusy = requestBusy, streamConversationId = ai.conversationId, streamMessageId = ai.messageId, uiMemory = coachUiMemory,
                                            onDeleteConversation = viewModel::deleteConversation)
                                        "progress" -> ProgressScreen(state, { open("session", it) }, { if (connected) viewModel.insight("period", null, onDone = onInsight) else open("settings") }, { show("weight") })
                                        "settings" -> SettingsScreen(state.profile, settings, connected, { open("profile") }, viewModel::settings, viewModel::connect,
                                            { show("disconnect") }, { show("clear") }, onExportJson, onExportCsv, onImport)
                                        "profile" -> ProfileScreen(state.profile, false) { viewModel.profile(it); pop() }
                                        "proposal" -> state.proposals.find { it.proposalId == route.id }?.let { proposal -> ProposalComparison(proposal, state.exercises,
                                            { viewModel.apply(proposal.proposalId) { pop() } }, { viewModel.reject(proposal.proposalId) { pop() } },
                                            { scope -> val prompt="Пересчитай предложение для выбранной области: ${proposal.summary}. Основание: ${proposal.reason}";if(scope=="session")viewModel.proposeForToday(prompt,onProposal)else viewModel.propose(prompt,null,onProposal) },state.sessions.associate { it.id to "Занятие ${formatDate(it.startedAt)}" },{open("session",it)}) }
                                        "technique" -> state.exercises.find { it.id == route.id }?.let { exercise -> TechniqueScreen(exercise) { sourceId -> ExerciseCatalog.sources.find { it.id == sourceId }?.url?.let(uri::openUri) } }
                                        "session" -> state.sessions.find { it.id == route.id }?.let { session -> SessionDetail(state, session,
                                            { if (connected) viewModel.insight("workout", session.id, onDone = onInsight) else open("settings") },
                                            { ex, set -> show("correction", "${session.id}|$ex|${set.id}") }) }
                                        "insight" -> state.insights.find { it.id == route.id }?.let { insight -> InsightScreen(insight, state, { open("session", it) }, { show("refresh", insight.id) }) }
                                    }
                                        }
                                        if (route.screen in listOf("today", "plan", "coach", "progress")) {
                                            BottomNavigation(route.screen, ::selectTab)
                                        }
                                    }
                                }
                            })
                }
            }

            if (coachSheet) ModalBottomSheet(onDismissRequest = { coachSheet = false }, sheetState = coachSheetState, containerColor = AppColors.Background, contentColor = AppColors.Text, shape = AppShapes.Surface) {
                Column(Modifier.fillMaxWidth().fillMaxHeight(.94f)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = Space.Large), horizontalArrangement = Arrangement.SpaceBetween) {
                    ActionButton("Раскрыть диалог", { coachSheet = false; open("coach") })
                    IconAction(Icons.Default.Close, "Закрыть помощника", { coachSheet = false })
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    CoachScreen(state, connected, ai.phase, ai.text, ai.error, contextLabel,
                        { viewModel.send(it, contextSession, contextExercise) }, viewModel::cancelAi, { coachSheet = false; open("settings") }, selectConversation,
                        conversationId, { viewModel.newConversation(contextSession, contextExercise) }, onProposal, onInsight, coachPrompt, draft, viewModel::updateDraft,
                        { viewModel.propose(it, contextSession) {} }, { viewModel.apply(it) {} }, { viewModel.reject(it) {} }, targetSessionId = contextSession,
                        requestBusy = requestBusy, streamConversationId = ai.conversationId, streamMessageId = ai.messageId, uiMemory = coachUiMemory,
                        onDeleteConversation = viewModel::deleteConversation)
                }
                }
            }
            if (dialog.isNotBlank()) EditorDialogs(dialog, dialogId, state, viewModel, { dialog = "" }, { id -> pop(); open("session", id) }, ::show)
            if (dialog == "refresh") state.insights.find { it.id == dialogId }?.let { insight ->
                AppDialog("Запросить новый разбор?", { dialog = "" }) {
                    Text("DeepSeek подготовит новый ответ. Это платный запрос по вашему ключу.", style = AppType.Body, color = AppColors.Secondary)
                    ActionButton("Обновить разбор с ИИ", {
                        dialog = ""
                        val sessionId = if (insight.type == "workout") state.sessions.firstOrNull { it.id in insight.evidenceIds }?.id else null
                        if (connected) viewModel.insight(insight.type, sessionId, force = true, onDone = onInsight) else open("settings")
                    }, Modifier.fillMaxWidth(), primary = true)
                    ActionButton("Отмена", { dialog = "" }, Modifier.fillMaxWidth())
                }
            }
            if (error != null) AppDialog("Действие не выполнено", onDismiss = viewModel::clearError) {
                Text(error.orEmpty(), style = AppType.Body, color = AppColors.Secondary)
                ActionButton("Понятно", viewModel::clearError, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable private fun DestinationHeader(screen: String, canGoBack: Boolean, onBack: () -> Unit, onSettings: () -> Unit) {
    if (screen in listOf("today", "coach", "workout")) return
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.Large, vertical = Space.Small), verticalAlignment = Alignment.CenterVertically) {
        if (canGoBack) IconAction(Icons.Default.ArrowBack, "Назад", onBack)
        if (screen in listOf("plan", "progress", "settings", "proposal", "profile", "insight")) Spacer(Modifier.weight(1f))
        else Text(when (screen) { "technique" -> "Техника"; "session" -> "Запись занятия"; "insight" -> "Разбор"; else -> "" },
            style = AppType.Label, color = AppColors.Secondary, modifier = Modifier.weight(1f).padding(start = Space.Small))
        if (screen != "settings") IconAction(Icons.Default.Settings, "Настройки", onSettings)
    }
}

@Composable private fun BottomNavigation(currentScreen: String, onSelect: (String) -> Unit) {
    val largeNavigation = LocalDensity.current.fontScale > 1.4f
    Row(Modifier.fillMaxWidth().background(AppColors.Navigation).padding(horizontal = Space.Small, vertical = Space.Tiny),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        listOf(Triple("today", "Сегодня", Icons.Outlined.Home), Triple("plan", "План", Icons.Outlined.ListAlt),
            Triple("coach", "Тренер", Icons.Outlined.AutoAwesome), Triple("progress", "Прогресс", Icons.Outlined.BarChart))
            .forEach { (screen, label, icon) ->
                val selected = screen == currentScreen
                Column(Modifier.weight(if (largeNavigation) (if (selected) 1.8f else .8f) else 1f).clip(AppShapes.Control)
                    .clickable(role = Role.Tab) { onSelect(screen) }
                    .semantics { this.selected = selected; contentDescription = label }
                    .heightIn(min = if (largeNavigation) Space.NavigationLargeItem else Space.NavigationItem).padding(vertical = Space.Tiny),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.Tiny)) {
                    Box(Modifier.size(Space.NavigationHaloWidth, Space.NavigationHaloHeight)
                        .background(if (selected) AppColors.AccentSoft else AppColors.Transparent, AppShapes.Round), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = if (selected) AppColors.Accent else AppColors.Secondary, modifier = Modifier.size(Space.Icon))
                    }
                    if (!largeNavigation || selected) Text(label, style = AppType.Caption, color = if (selected) AppColors.Accent else AppColors.Secondary, textAlign = TextAlign.Center)
                }
            }
    }
}

@Composable fun AppDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Panel(Modifier.fillMaxWidth().heightIn(max = Space.MaxContent).verticalScroll(rememberScrollState()), outlined = true) {
            Text(title, style = AppType.Heading, color = AppColors.Text)
            content()
        }
    }
}
