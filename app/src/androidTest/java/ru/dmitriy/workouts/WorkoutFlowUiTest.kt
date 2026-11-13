package ru.dmitriy.workouts

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import ru.dmitriy.workouts.data.WorkoutRepository
import ru.dmitriy.workouts.data.local.WorkoutDatabase
import ru.dmitriy.workouts.domain.*
import ru.dmitriy.workouts.feature.AppDialog
import ru.dmitriy.workouts.feature.plan.ProposalComparison
import ru.dmitriy.workouts.feature.progress.ProgressScreen
import ru.dmitriy.workouts.feature.shared.ActionButton
import ru.dmitriy.workouts.feature.today.TodayScreen
import ru.dmitriy.workouts.feature.workout.*
import ru.dmitriy.workouts.ui.theme.WorkoutsTheme

/** Real Room + production screens; the proposal fixture replaces only the external AI in this test. */
class WorkoutFlowUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var db: WorkoutDatabase
    private lateinit var repo: WorkoutRepository
    private lateinit var testJob:CompletableJob
    private lateinit var eventScope:CoroutineScope
    private lateinit var stream:StateFlow<LocalState>
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WorkoutDatabase::class.java).build()
        repo = WorkoutRepository(db)
        runBlocking {
            repo.seed(); repo.saveProfile(Profile(onboarded = true))
            repo.updateProgram(repo.snapshot().program.copy(nextDate = java.time.LocalDate.now().toString()))
        }
        testJob=SupervisorJob()
        eventScope=CoroutineScope(testJob+Dispatchers.Main.immediate)
        stream=repo.state.stateIn(eventScope,SharingStarted.Eagerly,LocalState())
    }
    @After fun teardown() { compose.activityRule.scenario.close();runBlocking { testJob.cancelAndJoin() };db.close() }

    @Test fun startRecordFinishAndOpenHistory() {
        compose.setContent {
            val state by stream.collectAsState()
            val scope = eventScope
            var route by remember { mutableStateOf("today") }
            var sessionId by remember { mutableStateOf("") }
            var partial by remember { mutableStateOf(false) }
            WorkoutsTheme {
                when (route) {
                    "today" -> TodayScreen(state, { scope.launch { sessionId = repo.startWorkout(); route = "workout" } }, {}, {}, {}, {}, {})
                    "workout" -> state.sessions.find { it.id == sessionId }?.let { s ->
                        WorkoutScreen(state, s, 0, TimerUi(), {}, { draft -> scope.launch { repo.saveSet(s.id, s.exercises.first().exerciseId, draft.index, draft.weightGrams, draft.reps, draft.rir, draft.technique, draft.warmup) } }, {}, {}, {}, { partial = true }, {}, {}, {}, {}, {}, {})
                    }
                    else -> ProgressScreen(state, {}, {}, {})
                }
                if (partial) AppDialog("Сохранить частичное занятие?", { partial = false }) {
                    ActionButton("Сохранить частичное", { scope.launch { repo.finishWorkout(sessionId, true); partial = false; route = "progress" } })
                }
            }
        }
        val startButton = compose.onNodeWithText("Начать тренировку")
        if (!startButton.isDisplayed()) startButton.performScrollTo()
        startButton.performClick()
        compose.waitUntil(timeoutMillis=10_000) { runBlocking { repo.snapshot().sessions.isNotEmpty() } }
        compose.waitUntil(timeoutMillis=10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size>=2 }
        compose.onNodeWithContentDescription("Вес").performTextReplacement("10")
        compose.onNodeWithContentDescription("Повторения").performTextReplacement("10")
        compose.onNodeWithText("Записать подход").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis=10_000) { runBlocking { repo.snapshot().sessions.first().completedWorkSets() == 1 } }
        compose.onNodeWithText("Журнал").performClick()
        compose.onNodeWithText("Разминка · 0").assertExists()
        compose.onNode(hasText("01") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Разминка · 0").assertDoesNotExist()
        compose.onNode(hasText("01") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Разминка · 0").assertExists()
        compose.onNodeWithText("Подход").performClick()
        compose.onNodeWithText("Ещё").performScrollTo().performClick()
        compose.onNode(hasText("Завершить занятие") and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        compose.onNodeWithText("Сохранить частичное", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(timeoutMillis=10_000) { compose.onAllNodesWithText("История").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("История").performScrollTo().performClick()
        compose.onNodeWithText("Открыть запись").assertExists()
        assertEquals("partial", runBlocking { repo.snapshot().sessions.first().status })
    }

    @Test fun requestCompareConfirmAndSeeUpdatedSession() {
        val id = runBlocking { repo.startWorkout() }
        val initial = runBlocking { repo.snapshot() }.sessions.first()
        val oldRest = initial.exercises.first().target.restSeconds
        compose.setContent { ProposalHarness(repo, id, stale = false,stream,eventScope) }
        compose.onNodeWithText("Запросить изменение").performClick()
        compose.waitUntil(timeoutMillis=10_000) { compose.onAllNodesWithText("СТАНЕТ").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("СТАНЕТ").assertExists()
        compose.onNodeWithText("Применить").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis=10_000) { compose.onAllNodesWithText("Отдых обновлён: ${oldRest - 30} с").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Отдых обновлён: ${oldRest - 30} с").assertExists()
        assertEquals(oldRest - 30, runBlocking { repo.snapshot().sessions.first().exercises.first().target.restSeconds })
    }

    @Test fun staleProposalCannotApplyAndKeepsOriginalAssignment() {
        val id = runBlocking { repo.startWorkout() }
        val oldRest = runBlocking { repo.snapshot().sessions.first().exercises.first().target.restSeconds }
        compose.setContent { ProposalHarness(repo, id, stale = true,stream,eventScope) }
        compose.onNodeWithText("Запросить изменение").performClick()
        compose.waitUntil(timeoutMillis=10_000) { compose.onAllNodesWithText("Устарело").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Устарело").assertExists()
        compose.onNodeWithText("Применить").assertDoesNotExist()
        assertEquals(oldRest, runBlocking { repo.snapshot().sessions.first().exercises.first().target.restSeconds })
    }

    @Test fun rejectedProposalKeepsOriginalAssignment() {
        val id = runBlocking { repo.startWorkout() }
        val before = runBlocking { repo.snapshot().sessions.first() }
        compose.setContent { ProposalHarness(repo, id, stale = false, stream, eventScope) }
        compose.onNodeWithText("Запросить изменение").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Отклонить").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Отклонить").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { runBlocking { repo.snapshot().proposals.firstOrNull()?.status == "rejected" } }
        val after = runBlocking { repo.snapshot() }
        assertEquals(before, after.sessions.first())
        assertTrue(after.changeLogs.isEmpty())
    }
}

@Composable private fun ProposalHarness(repo: WorkoutRepository, sessionId: String, stale: Boolean,stream:StateFlow<LocalState>,scope:CoroutineScope) {
    val state by stream.collectAsState()
    var proposalId by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf(false) }
    var changed by remember { mutableStateOf(false) }
    WorkoutsTheme {
        Column {
            if (changed) Text("Контекст изменился")
            val proposal = state.proposals.find { it.proposalId == proposalId }
            when {
                applied -> Text("Отдых обновлён: ${state.sessions.find { it.id == sessionId }?.exercises?.first()?.target?.restSeconds} с")
                proposal != null -> ProposalComparison(proposal, state.exercises, {
                    scope.launch {
                        runCatching { repo.applyProposal(proposal.proposalId) }.onSuccess { applied = true }.onFailure { changed = true }
                    }
                }, { scope.launch { repo.rejectProposal(proposal.proposalId); proposalId = "" } })
                else -> ActionButton("Запросить изменение", {
                    scope.launch {
                        val s = repo.snapshot().sessions.find { it.id == sessionId }!!
                        val target = s.exercises.first().target
                        val p = PlanProposal("test-proposal", "Сократить отдых", "Тестовый адаптер внешнего ИИ", listOf(s.id), s.version, "session", System.currentTimeMillis() + 60_000,
                            listOf(ProposalOperation("rest", target.exerciseId, target.restSeconds.toString(), (target.restSeconds - 30).toString())), sessionId = s.id)
                        repo.saveProposal(p)
                        if (stale) repo.saveSet(s.id, target.exerciseId, 0, 10_000, target.minReps, 3, "good")
                        proposalId = p.proposalId
                    }
                })
            }
        }
    }
}
