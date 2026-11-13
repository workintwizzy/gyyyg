package ru.dmitriy.workouts

import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import ru.dmitriy.workouts.core.timer.RestTimer
import ru.dmitriy.workouts.data.*
import ru.dmitriy.workouts.data.ai.*
import ru.dmitriy.workouts.data.local.WorkoutDatabase
import ru.dmitriy.workouts.domain.*
import ru.dmitriy.workouts.feature.*

/** Real navigation, ViewModel and Room. No HTTP request is made by these paths. */
class AppNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var db: WorkoutDatabase
    private lateinit var repo: WorkoutRepository
    private lateinit var vm: AppViewModel
    private lateinit var timer: RestTimer

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, WorkoutDatabase::class.java).build()
        repo = WorkoutRepository(db)
        runBlocking {
            repo.seed()
            repo.saveProfile(Profile(onboarded = true))
            repo.updateProgram(repo.snapshot().program.copy(nextDate = java.time.LocalDate.now().toString()))
            repo.addConversation(CoachConversation("navigation-thread", "Выбранный диалог", 100))
            repo.addMessage(CoachMessage("navigation-answer", "navigation-thread", "assistant", "Сохранённый ответ выбранного диалога", 101))
        }
        val preferences = SettingsRepository(context)
        val assistant = AiRepository(repo, preferences, SessionVault(context), ContextBuilder(ProgressionEngine()), context)
        timer = RestTimer(context)
        compose.runOnUiThread {
            vm = ViewModelProvider(compose.activity, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppViewModel(repo, preferences, assistant, timer) as T
            })[AppViewModel::class.java]
        }
        compose.setContent { WorkoutsApp(vm, {}, {}, {}) }
        compose.waitUntil(10_000) { vm.local.value.exercises.isNotEmpty() }
    }

    @After fun teardown() {
        timer.skip()
        compose.activityRule.scenario.close()
        db.close()
    }

    @Test fun returningToCoachRetainsSelectedConversationAndDraft() {
        compose.runOnUiThread { vm.conversation("navigation-thread"); vm.updateDraft("Мой несохранённый вопрос") }
        compose.onNodeWithContentDescription("Тренер").performClick()
        // Read the saved answer before leaving. At 200% the connection card occupies
        // the tail viewport, so the answer must first be brought into view by the user.
        compose.onNodeWithTag("coach-messages").performScrollToIndex(0)
        compose.onNodeWithText("Сохранённый ответ выбранного диалога").assertIsDisplayed()
        compose.onNodeWithContentDescription(if (vm.connected.value) "Ваш вопрос" else "Черновик").assertTextEquals("Мой несохранённый вопрос")
        compose.onNodeWithContentDescription("Прогресс").performClick()
        compose.onNodeWithContentDescription("Тренер").performClick()
        compose.onNodeWithText("Сохранённый ответ выбранного диалога").assertIsDisplayed()
        assertEquals("navigation-thread", vm.conversationId.value)
        compose.onNodeWithContentDescription(if (vm.connected.value) "Ваш вопрос" else "Черновик").assertTextEquals("Мой несохранённый вопрос")
    }

    @Test fun expandingWorkoutAssistantRetainsDraftAndWorkout() {
        val start = compose.onNodeWithText("Начать тренировку")
        if (!start.isDisplayed()) start.performScrollTo()
        start.performClick()
        compose.waitUntil(10_000) { vm.local.value.sessions.any { it.status == "active" } }
        val before = runBlocking { repo.snapshot().sessions.first() }
        val ask = compose.onNodeWithText("Спросить тренера")
        if (!ask.isDisplayed()) ask.performScrollTo()
        ask.performClick()
        val field = compose.onNodeWithContentDescription(if (vm.connected.value) "Ваш вопрос" else "Черновик")
        field.performTextReplacement("Вопрос из занятия")
        compose.onNodeWithContentDescription("Закрыть помощника").performClick()
        val reopen = compose.onNodeWithText("Спросить тренера")
        if (!reopen.isDisplayed()) reopen.performScrollTo()
        reopen.performClick()
        compose.onNodeWithContentDescription(if (vm.connected.value) "Ваш вопрос" else "Черновик").assertTextEquals("Вопрос из занятия")
        compose.onNodeWithText("Раскрыть диалог").performClick()
        compose.onNodeWithContentDescription(if (vm.connected.value) "Ваш вопрос" else "Черновик").assertTextEquals("Вопрос из занятия")
        compose.onNodeWithContentDescription("Назад к занятию").performClick()
        compose.onNodeWithText("Записать подход").assertExists()
        assertEquals(before, runBlocking { repo.snapshot().sessions.first() })
    }

    @Test fun todayResultsOpenSavedSessionWithoutCreatingAnotherWorkout() {
        val id = runBlocking { repo.startWorkout().also { repo.finishWorkout(it, true) } }
        compose.waitUntil(10_000) { vm.local.value.sessions.any { it.id == id && it.status == "partial" } }
        val results = compose.onNodeWithText("Посмотреть результаты")
        if (!results.isDisplayed()) results.performScrollTo()
        results.performClick()
        compose.onNodeWithText("Запись занятия").assertIsDisplayed()
        compose.onNodeWithText("Частично", substring = true).assertIsDisplayed()
        assertEquals(listOf(id), runBlocking { repo.snapshot().sessions.map { it.id } })
    }

    @Test fun continueSkipsFinishedExerciseAndShowsRestUntilSkipped() {
        val session = runBlocking {
            val id = repo.startWorkout()
            val first = repo.snapshot().sessions.first().exercises.first()
            repeat(first.target.sets) { repo.saveSet(id, first.exerciseId, it, 10_000, 10, 3, "good") }
            repo.snapshot().sessions.first()
        }
        compose.waitUntil(10_000) { vm.local.value.sessions.firstOrNull()?.completedWorkSets() == session.completedWorkSets() }
        compose.runOnUiThread { timer.start(120) }
        compose.onNodeWithText("Продолжить").performScrollTo().performClick()
        compose.onNodeWithText("Отдых").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, vm.selectedExercise.value) }
        compose.onNodeWithText("Пропустить отдых").performScrollTo().performClick()
        compose.onNodeWithText(session.exercises[1].let { e -> ExerciseCatalog.exercises.first { it.id == e.exerciseId }.name }).assertExists()
        compose.onNodeWithText("Записать подход").assertExists()
        compose.runOnIdle { assertFalse(timer.state.value.running); assertEquals(0, timer.state.value.remainingSeconds) }
    }

    @Test fun continuePrefersExerciseAlreadyInProgress() {
        val id = runBlocking {
            val program = repo.snapshot().program
            repo.updateProgram(program.copy(targets = program.targets.map { it.copy(sets = 2) }))
            repo.startWorkout().also { sessionId ->
                val exercise = repo.snapshot().sessions.first().exercises[2]
                repo.saveSet(sessionId, exercise.exerciseId, 0, 10_000, 10, 3, "good")
            }
        }
        compose.waitUntil(10_000) { vm.local.value.sessions.any { it.id == id && it.completedWorkSets() == 1 } }
        compose.onNodeWithText("Продолжить").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, vm.selectedExercise.value) }
        compose.onNodeWithText("Подход 2 из 2").assertExists()
    }

    @Test fun nextExerciseStopsRestAndNavigationDoesNotDuplicateTitles() {
        val id = runBlocking { repo.startWorkout() }
        val first = runBlocking { repo.snapshot().sessions.first().exercises.first() }
        runBlocking { repeat(first.target.sets) { repo.saveSet(id, first.exerciseId, it, 10_000, 10, 3, "good") } }
        compose.waitUntil(10_000) { vm.local.value.sessions.firstOrNull()?.completedWorkSets() == first.target.sets }
        compose.runOnUiThread { vm.selectedExercise.value = 0; timer.start(120) }
        // Open the active workout without re-running Continue's selection logic.
        compose.onNodeWithText("Продолжить").performScrollTo().performClick()
        compose.runOnUiThread { vm.selectedExercise.value = 0 }
        compose.onNodeWithText("Следующее упражнение").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, vm.selectedExercise.value); assertEquals(0, timer.state.value.remainingSeconds) }
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithContentDescription("План").performClick()
        compose.onAllNodesWithText("План").assertCountEquals(2) // Page title and bottom navigation label.
    }
}
