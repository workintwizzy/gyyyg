package ru.dmitriy.workouts

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.dmitriy.workouts.domain.*
import ru.dmitriy.workouts.feature.coach.CoachScreen
import ru.dmitriy.workouts.feature.coach.rememberCoachUiMemory
import ru.dmitriy.workouts.ui.theme.WorkoutsTheme

/** Real presentation and interactions, with local fixtures and no network/provider calls. */
class CoachInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var data by mutableStateOf(history())
    private var selected by mutableStateOf("a")
    private var phase by mutableStateOf("idle")
    private var response by mutableStateOf("")
    private var streamConversation by mutableStateOf<String?>(null)
    private var streamMessage by mutableStateOf<String?>(null)
    private var draft by mutableStateOf("")
    private var remounted by mutableStateOf(false)
    private val questions = mutableListOf<String>()
    private val changes = mutableListOf<String>()

    private fun show(compact: Boolean = false) {
        compose.setContent {
            val memory = rememberCoachUiMemory()
            WorkoutsTheme {
                Box(if (compact) Modifier.height(300.dp) else Modifier) {
                    // A sheet and the full-screen route have separate composition lifetimes.
                    key(remounted) {
                        CoachScreen(
                            state = data, connected = true, phase = phase, response = response, error = null,
                            contextLabel = "Это занятие", onSend = { questions += it }, onCancel = {}, onConnect = {},
                            onConversation = { selected = it }, selectedConversationId = selected, onNew = {},
                            onProposal = {}, onInsight = {}, persistedDraft = draft, onDraft = { draft = it },
                            onRequestProposal = { changes += it }, onApplyProposal = {}, onRejectProposal = {},
                            targetSessionId = "shared-session", streamConversationId = streamConversation,
                            streamMessageId = streamMessage, uiMemory = memory,
                            onDeleteConversation = { id -> data = data.copy(
                                conversations = data.conversations.filterNot { it.id == id },
                                messages = data.messages.filterNot { it.conversationId == id },
                            ) },
                        )
                    }
                }
            }
        }
    }

    private fun select(title: String) {
        compose.onNodeWithContentDescription("Диалоги и разборы").performClick()
        compose.onNodeWithText(title).performClick()
    }

    @Test fun readingOldMessagesIsNotInterruptedByTokensAndRemovedControlsStayAbsent() {
        phase = "receiving"
        streamConversation = "a"
        streamMessage = "new-answer"
        response = "Начало ответа"
        show()
        compose.onNodeWithTag("coach-messages").performScrollToIndex(0)
        compose.onNodeWithText(messageText("a", 0)).assertIsDisplayed()
        compose.runOnIdle { response = "Новый большой фрагмент. ".repeat(100) }
        compose.onNodeWithText(messageText("a", 0)).assertIsDisplayed()
        compose.onNodeWithContentDescription("Какие записи используются").assertDoesNotExist()
        compose.onNodeWithText("DeepSeek").assertDoesNotExist()
        compose.onNodeWithText("К последнему ответу").assertDoesNotExist()
    }

    @Test fun historyOmitsEmptyAndDraftOnlyDialoguesUntilMessageIsSent() {
        data = history(count = 0).let { it.copy(messages = listOf(
            CoachMessage("draft:a", "a", "user", "Неотправленный вопрос", 1L, state = "draft"),
        )) }
        show()
        compose.onNodeWithContentDescription("Диалоги и разборы").performClick()
        compose.onNodeWithText("Диалог А").assertDoesNotExist()
        compose.onNodeWithText("Диалог Б").assertDoesNotExist()
        compose.onNodeWithText("Здесь появятся сохранённые диалоги и разборы.").assertIsDisplayed()
        compose.runOnIdle {
            data = data.copy(messages = data.messages + CoachMessage("sent", "a", "user", "Отправленный вопрос", 2L))
        }
        compose.onNodeWithText("Диалог А").assertIsDisplayed()
        compose.onNodeWithText("Диалог Б").assertDoesNotExist()
    }

    @Test fun historyDeletionCanBeCancelledAndRemovesOnlyConfirmedDialogue() {
        data = history(count = 1)
        selected = "b"
        show()
        compose.onNodeWithContentDescription("Диалоги и разборы").performClick()
        compose.onNodeWithContentDescription("Удалить диалог: Диалог А").assertDoesNotExist()
        compose.onNodeWithText("Удалить").assertDoesNotExist()
        compose.onNodeWithText("Диалог А").performTouchInput { longClick() }
        compose.onNodeWithText("Удалить").assertIsDisplayed()
        compose.runOnIdle { assertEquals("b", selected) }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("Удалить").assertDoesNotExist()
        compose.onNodeWithText("Диалог А").assertIsDisplayed()
        compose.onNodeWithText("Диалог А").performTouchInput { longClick() }
        compose.onNodeWithText("Удалить").performClick()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText("Диалог А").assertIsDisplayed()
        compose.onNodeWithText("Диалог А").performTouchInput { longClick() }
        compose.onNodeWithText("Удалить").performClick()
        compose.onNodeWithText("Удалить").performClick()
        compose.onNodeWithText("Диалог А").assertDoesNotExist()
        compose.onNodeWithText("Диалог Б").assertIsDisplayed()
        compose.onNodeWithText("Диалог Б").performClick()
        compose.onNodeWithText(messageText("b", 0)).assertIsDisplayed()
    }

    @Test fun conversationSwitchAndSheetExpansionRestoreScrollAndDraftMode() {
        show()
        compose.onNodeWithTag("coach-messages").performScrollToIndex(0)
        compose.onNodeWithText("Изменение").performClick()
        compose.onNodeWithContentDescription("Ваш вопрос").performTextInput("Нужен короткий вариант")
        compose.runOnIdle { remounted = true }
        compose.onNodeWithText(messageText("a", 0)).assertIsDisplayed()
        compose.onNodeWithText("Изменение").assertIsSelected()
        compose.onNodeWithContentDescription("Ваш вопрос").assertTextEquals("Нужен короткий вариант")
        select("Диалог Б")
        compose.onNodeWithTag("coach-messages").performScrollToIndex(0)
        compose.onNodeWithText(messageText("b", 0)).assertIsDisplayed()
        compose.onNodeWithText("Вопрос").assertIsSelected()
        select("Диалог А")
        compose.onNodeWithText(messageText("a", 0)).assertIsDisplayed()
        compose.onNodeWithText("Изменение").assertIsSelected()
    }

    @Test fun proposalAndStreamNeverAppearInAnotherChatWithTheSameWorkout() {
        val proposal = PlanProposal(
            "proposal-b", "Оставить два подхода", "Осталось мало времени", listOf("shared-session"),
            1L, "session", System.currentTimeMillis() + 600_000,
            listOf(ProposalOperation("reduceSets", "leg-press", "3", "2")), sessionId = "shared-session",
        )
        data = history(count = 1).let {
            it.copy(proposals = listOf(proposal), messages = it.messages + CoachMessage(
                "proposal-answer", "b", "assistant", "Предложение относится к диалогу Б", 10L,
                proposalId = proposal.proposalId,
            ))
        }
        phase = "receiving"
        streamConversation = "b"
        streamMessage = "stream-b"
        response = "Ответ в другом диалоге"
        show()
        compose.onNodeWithText("Оставить два подхода").assertDoesNotExist()
        compose.onNodeWithText("Ответ в другом диалоге").assertDoesNotExist()
        compose.onNodeWithText("Остановить").assertDoesNotExist()
        select("Диалог Б")
        compose.onNodeWithTag("coach-messages").performScrollToNode(hasText("Применить"))
        compose.onNodeWithText("Применить").assertIsDisplayed()
        select("Диалог А")
        compose.onNodeWithText("Применить").assertDoesNotExist()
        compose.onNodeWithText("Оставить два подхода").assertDoesNotExist()
    }

    @Test fun terminalResponseStaysVisibleUntilRoomRecordArrivesWithoutDuplication() {
        data = history(count = 1)
        phase = "receiving"
        streamConversation = "a"
        streamMessage = "new-answer"
        response = "Готовый ответ помощника"
        show()
        compose.onNodeWithText(response).assertIsDisplayed()
        compose.runOnIdle { phase = "done" }
        compose.onNodeWithText(response).assertIsDisplayed()
        compose.onNodeWithText("Остановить").assertDoesNotExist()
        compose.runOnIdle {
            data = data.copy(messages = data.messages + CoachMessage("new-answer", "a", "assistant", response, 100L))
        }
        compose.onAllNodesWithText(response).assertCountEquals(1)
        compose.onNodeWithText(response).assertIsDisplayed()
    }

    @Test fun longDraftRemainsEditableAndModeChangesDoNotSendOrEraseItInShortWindow() {
        data = history(count = 1)
        show(compact = true)
        val text = "Мне нужно сократить занятие.\n".repeat(20).trim()
        compose.onNodeWithContentDescription("Ваш вопрос").performTextInput(text)
        compose.onNodeWithContentDescription("Отправить").assertIsDisplayed()
        compose.onNodeWithText("Изменение").performClick()
        compose.onNodeWithContentDescription("Ваш вопрос").assertTextEquals(text)
        compose.onNodeWithContentDescription("Предложить изменение").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, changes.size); assertEquals(0, questions.size) }
        compose.onNodeWithContentDescription("Предложить изменение").performClick()
        compose.runOnIdle { assertEquals(listOf(text), changes); assertEquals(0, questions.size) }
    }

    @Test fun savedPerConversationDraftIsOnlyShownInComposer() {
        draft = "Ещё не отправленный вопрос"
        data = history(count = 1).let { it.copy(messages = it.messages + CoachMessage(
            "draft:a", "a", "user", draft, 100L, state = "draft",
        )) }
        show()
        compose.onAllNodesWithText(draft).assertCountEquals(1)
        compose.onNodeWithContentDescription("Ваш вопрос").assertTextEquals(draft)
        compose.onNodeWithText(messageText("a", 0)).assertIsDisplayed()
    }

    private fun history(count: Int = 18): LocalState = LocalState(
        conversations = listOf(
            CoachConversation("a", "Диалог А", 1L, sessionId = "shared-session"),
            CoachConversation("b", "Диалог Б", 2L, sessionId = "shared-session"),
        ),
        messages = listOf("a", "b").flatMap { conversation ->
            (0 until count).map { index -> CoachMessage(
                "$conversation-$index", conversation, if (index % 2 == 0) "user" else "assistant",
                messageText(conversation, index), index.toLong(),
            ) }
        },
    )

    private fun messageText(conversation: String, index: Int): String =
        "Сообщение $conversation $index. ${"Сохранённая запись тренировки. ".repeat(3)}"
}
