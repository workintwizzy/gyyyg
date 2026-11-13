package ru.dmitriy.workouts

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import ru.dmitriy.workouts.data.WorkoutRepository
import ru.dmitriy.workouts.data.local.WorkoutDatabase
import ru.dmitriy.workouts.domain.*
import ru.dmitriy.workouts.feature.coach.CoachScreen
import ru.dmitriy.workouts.ui.theme.WorkoutsTheme

/** The production chat reads live Room state; only the cloud proposal is a fixture. */
class CoachUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var db: WorkoutDatabase
    private lateinit var repository: WorkoutRepository
    private lateinit var events: CoroutineScope
    private lateinit var job: CompletableJob
    private lateinit var state: StateFlow<LocalState>
    private lateinit var sessionId: String
    private val proposalId = "coach-proposal"
    private val conversationId = "coach-conversation"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WorkoutDatabase::class.java).build()
        repository = WorkoutRepository(db)
        runBlocking {
            repository.seed()
            repository.saveProfile(Profile(onboarded = true))
            val program = repository.snapshot().program
            repository.updateProgram(program.copy(targets = program.targets.mapIndexed { index, target ->
                if (index == 0) target.copy(sets = 3) else target
            }))
            sessionId = repository.startWorkout()
            val exercise = repository.snapshot().sessions.first().exercises.first()
            repository.saveSet(sessionId, exercise.exerciseId, 0, 20_000, 10, null, "good", warmup = true)
            repository.saveSet(sessionId, exercise.exerciseId, 0, 40_000, 12, 3, "good")
            val session = repository.snapshot().sessions.first()
            repository.addConversation(CoachConversation(conversationId, "Меньше времени", System.currentTimeMillis(), sessionId = sessionId))
            repository.addMessage(CoachMessage("question", conversationId, "user", "Осталось 30 минут", System.currentTimeMillis()))
            repository.addMessage(CoachMessage("answer", conversationId, "assistant", "Можно оставить два подхода вместо трёх.", System.currentTimeMillis() + 1, proposalId = proposalId))
            repository.saveProposal(PlanProposal(
                proposalId, "Оставить два подхода", "Сократить оставшуюся часть занятия", listOf(sessionId),
                session.version, "session", System.currentTimeMillis() + 300_000,
                listOf(ProposalOperation("reduceSets", exercise.exerciseId, "3", "2")), sessionId = sessionId,
            ))
        }
        job = SupervisorJob()
        events = CoroutineScope(job + Dispatchers.Main.immediate)
        state = repository.state.stateIn(events, SharingStarted.Eagerly, LocalState())
    }

    @After fun tearDown() {
        compose.activityRule.scenario.close()
        runBlocking { job.cancelAndJoin() }
        db.close()
    }

    private fun showCoach() {
        compose.setContent {
            val saved by state.collectAsState()
            WorkoutsTheme {
                CoachScreen(
                    state = saved, connected = true, phase = "idle", response = "", error = null,
                    contextLabel = "Это занятие", onSend = {}, onCancel = {}, onConnect = {}, onConversation = {},
                    selectedConversationId = conversationId, onNew = {}, onProposal = {}, onInsight = {},
                    onApplyProposal = { id -> events.launch { repository.applyProposal(id) } },
                    onRejectProposal = { id -> events.launch { repository.rejectProposal(id) } },
                    targetSessionId = sessionId,
                )
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) { state.value.proposals.any { it.proposalId == proposalId } }
    }

    private fun revealAction(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    @Test fun applyFromConversationKeepsRecordedWarmupAndWorkingSets() {
        val before = runBlocking { repository.snapshot().sessions.first() }
        showCoach()
        revealAction("Применить")
        compose.onNodeWithText("Применить").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { runBlocking { repository.snapshot().proposals.first().status == "applied" } }
        val saved = runBlocking { repository.snapshot() }
        val after = saved.sessions.first()
        assertEquals(2, after.exercises.first().target.sets)
        assertEquals(before.exercises.first().sets, after.exercises.first().sets)
        assertEquals(before.exercises.drop(1), after.exercises.drop(1))
        assertEquals(1, after.completedWorkSets())
        assertEquals(1, after.exercises.first().sets.count { it.completed && it.warmup })
        assertEquals(1, saved.changeLogs.size)
        assertEquals(before.assignedSnapshot, after.assignedSnapshot)
    }

    @Test fun leaveAsIsFromConversationKeepsAssignmentAndRecords() {
        val before = runBlocking { repository.snapshot().sessions.first() }
        showCoach()
        revealAction("Оставить как есть")
        compose.onNodeWithText("Оставить как есть").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { runBlocking { repository.snapshot().proposals.first().status == "rejected" } }
        val saved = runBlocking { repository.snapshot() }
        assertEquals(before, saved.sessions.first())
        assertTrue(saved.changeLogs.isEmpty())
    }

    @Test fun recordingAnotherSetMakesVisibleProposalStaleAndRemovesApply() {
        showCoach()
        revealAction("Применить")
        val exercise = runBlocking { repository.snapshot().sessions.first().exercises.first() }
        runBlocking { repository.saveSet(sessionId, exercise.exerciseId, 1, 40_000, 11, 3, "good") }
        val afterRecording = runBlocking { repository.snapshot().sessions.first() }
        compose.waitUntil(timeoutMillis = 10_000) { state.value.proposals.firstOrNull()?.status == "stale" }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Устарело"))
        compose.onNodeWithText("Устарело").assertIsDisplayed()
        compose.onNodeWithText("Применить").assertDoesNotExist()
        val saved = runBlocking { repository.snapshot() }
        assertEquals(afterRecording, saved.sessions.first())
        assertEquals(3, saved.sessions.first().exercises.first().target.sets)
        assertEquals(2, saved.sessions.first().completedWorkSets())
        assertTrue(saved.changeLogs.isEmpty())
    }
}
