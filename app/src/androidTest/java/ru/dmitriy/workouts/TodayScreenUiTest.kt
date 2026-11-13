package ru.dmitriy.workouts

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.dmitriy.workouts.domain.*
import ru.dmitriy.workouts.feature.today.TodayScreen
import ru.dmitriy.workouts.ui.theme.*
import java.io.File
import java.time.LocalDate

class TodayScreenUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val program = ExerciseCatalog.initialProgram().copy(nextDate = LocalDate.now().toString())
    private val initial = LocalState(program = program, exercises = ExerciseCatalog.exercises)

    private fun show(state: State<LocalState>, start: () -> Unit = {}, results: (String) -> Unit = {}) {
        compose.setContent {
            WorkoutsTheme {
                Box(Modifier.fillMaxSize().background(AppColors.Background)) {
                    TodayScreen(state.value, start, {}, {}, {}, {}, {}, onResults = results)
                }
            }
        }
    }

    private fun click(text: String) {
        val node = compose.onNodeWithText(text)
        if (!node.isDisplayed()) node.performScrollTo()
        node.performClick()
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureToday") != "true") return
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("today-bloom").fetchSemanticsNodes().isNotEmpty() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "today-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun session(status: String) = WorkoutSession("today-session", status, System.currentTimeMillis(),
        if (status == "active") null else System.currentTimeMillis(), programVersion = 1,
        assignedSnapshot = program.copy(name = "Верх тела · 8 недель", week = 3),
        exercises = program.targets.take(3).map { SessionExercise(it.exerciseId, it.exerciseId, it.equipmentId, it) })

    @Test fun trainingChangesToContinueAndPreservesAssignedDay() {
        val state = mutableStateOf(initial)
        var starts = 0
        show(state, start = { starts++ })
        compose.onNodeWithText("Всё тело").assertIsDisplayed()
        compose.onNodeWithText("Неделя 1 программы").assertIsDisplayed()
        capture("training")
        click("Начать тренировку")
        compose.runOnIdle { assertEquals(1, starts); state.value = initial.copy(sessions = listOf(session("active"))) }
        compose.onNodeWithText("Верх тела").assertExists()
        compose.onNodeWithText("Неделя 3 программы").assertExists()
        click("Продолжить")
        compose.runOnIdle { assertEquals(2, starts) }
        capture("active")
        compose.onNodeWithText("Упражнения").assertDoesNotExist()
        compose.onNodeWithText("Настроить").assertDoesNotExist()
        capture("actions")
    }

    @Test fun recoveryOpensUpcomingPlanWithoutStartingWorkout() {
        val state = mutableStateOf(initial.copy(program = program.copy(nextDate = LocalDate.now().plusDays(2).toString())))
        var starts = 0
        show(state, start = { starts++ })
        compose.onNodeWithText("День восстановления").assertIsDisplayed()
        compose.onNodeWithText("Начать тренировку").assertDoesNotExist()
        capture("recovery")
        click("Посмотреть следующий день")
        compose.onNodeWithText("Следующая тренировка").assertIsDisplayed()
        compose.onNodeWithText("Всё тело").assertIsDisplayed()
        compose.onNodeWithText(ExerciseCatalog.exercises.first { it.id == program.targets.first().exerciseId }.name).assertExists()
        compose.runOnIdle { assertEquals(0, starts); assertTrue(state.value.sessions.isEmpty()) }
    }

    @Test fun completedAndPartialSessionsOpenTheirOwnResults() {
        val state = mutableStateOf(initial.copy(sessions = listOf(session("completed"))))
        var selected = ""
        show(state, results = { selected = it })
        compose.onNodeWithText("Выполнено").assertExists()
        capture("completed")
        click("Посмотреть результаты")
        compose.runOnIdle {
            assertEquals("today-session", selected)
            selected = ""
            state.value = initial.copy(sessions = listOf(session("partial")))
        }
        compose.onNodeWithText("Выполнено").assertDoesNotExist()
        compose.onNodeWithText("Занятие сохранено частично").assertExists()
        click("Посмотреть результаты")
        compose.runOnIdle { assertEquals("today-session", selected) }
    }
}
