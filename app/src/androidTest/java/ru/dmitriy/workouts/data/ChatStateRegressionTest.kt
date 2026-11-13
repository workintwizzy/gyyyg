package ru.dmitriy.workouts.data

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.dmitriy.workouts.core.timer.RestTimer
import ru.dmitriy.workouts.data.ai.AiRepository
import ru.dmitriy.workouts.data.ai.SessionVault
import ru.dmitriy.workouts.data.local.WorkoutDatabase
import ru.dmitriy.workouts.domain.*
import ru.dmitriy.workouts.feature.AppViewModel

/** Real Room and repository state; consent is disabled before every request.
 * These regressions never contact DeepSeek and never substitute a model answer. */
@RunWith(AndroidJUnit4::class)
class ChatStateRegressionTest {
    private lateinit var context:Context
    private lateinit var db:WorkoutDatabase
    private lateinit var local:WorkoutRepository
    private lateinit var settings:SettingsRepository
    private lateinit var previous:AppSettings
    private lateinit var assistant:AiRepository
    @Before fun before()=runBlocking {
        context=ApplicationProvider.getApplicationContext()
        db=Room.inMemoryDatabaseBuilder(context,WorkoutDatabase::class.java).build()
        local=WorkoutRepository(db);local.seed()
        settings=SettingsRepository(context);previous=settings.settings.first()
        settings.update { it.copy(aiConsent=false) }
        assistant=AiRepository(local,settings,SessionVault(context),ContextBuilder(ProgressionEngine()),context)
    }
    @After fun after()=runBlocking { settings.update { previous };db.close() }
    private suspend fun waitUntil(condition:suspend ()->Boolean) = withTimeout(10_000) {
        while(!condition())delay(20)
    }
    @Test fun duplicateActionDuringPreparationCannotDuplicateQuestionAndCancellationCommitsBeforeStatus()=runBlocking {
        val session=local.startWorkout()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val question="Как настроить жим ногами?"
        val first=launch { assistant.chat(question,session,"leg-press",onQuestionSaved={ entered.complete(Unit);release.await() }) }
        withTimeout(10_000) { entered.await() }
        val pending=assistant.state.value
        assertTrue(pending.busy);assertNotNull(pending.conversationId);assertNotNull(pending.requestId);assertNotNull(pending.messageId)
        assistant.chat(question,session,"leg-press",pending.conversationId)
        assertEquals(1,local.snapshot().messages.count { it.role=="user" })
        // Room workout writes remain independent while the assistant is waiting.
        local.saveSet(session,"leg-press",0,15_000,10,3,"good")
        val terminal=async(start=CoroutineStart.UNDISPATCHED) {
            assistant.state.first { it.phase=="cancelled" }
            local.snapshot()
        }
        assistant.cancel();release.complete(Unit);first.join()
        val saved=withTimeout(10_000) { terminal.await() }
        assertEquals("cancelled",saved.messages.single { it.id==pending.messageId }.state)
        assertEquals(1,saved.messages.count { it.role=="assistant" })
        assertEquals(1,saved.sessions.single().completedWorkSets())
        assertFalse(assistant.state.value.busy)
    }
    @Test fun errorStatusKeepsConversationAndSavedReplyIdentity()=runBlocking {
        val session=local.startWorkout()
        assistant.propose("Мне нужно короткое занятие",session)
        val status=assistant.state.value
        assertTrue(status.phase in setOf("error","offline"));assertFalse(status.busy)
        val saved=local.snapshot()
        val answer=saved.messages.single { it.role=="assistant" }
        assertEquals(status.messageId,answer.id)
        assertEquals(status.conversationId,answer.conversationId)
        assertEquals(status.requestId,answer.requestId)
        assertEquals("error",answer.state)
        assertTrue(saved.proposals.isEmpty())
    }
    @Test fun replyAndProposalLinkCommitTogetherAndRejectStaleVersionWithoutPartialWrite()=runBlocking {
        val session=local.startWorkout()
        local.addConversation(CoachConversation("chat","Занятие",1,sessionId=session))
        val version=local.snapshot().sessions.single().version
        val proposal=PlanProposal("change","Отдых короче","Осталось мало времени",emptyList(),version,"session",System.currentTimeMillis()+60_000,
            listOf(ProposalOperation("rest","leg-press","150","120")),sessionId=session)
        val message=CoachMessage("answer","chat","assistant","Можно сократить отдых",2,proposalId=proposal.proposalId)
        assertTrue(local.saveCoachReply(message,proposal))
        val committed=local.snapshot()
        assertEquals(proposal.proposalId,committed.messages.single().proposalId)
        assertEquals(proposal,committed.proposals.single())
        local.saveSet(session,"leg-press",0,10_000,10,3,"good")
        val before=local.snapshot()
        assertTrue(runCatching { local.saveCoachReply(message.copy(id="stale-answer",proposalId="stale"),proposal.copy(proposalId="stale")) }.isFailure)
        assertEquals(before,local.snapshot())
    }
    @Test fun clearedChatsRejectLateReplyAndDraftWithoutLosingWorkout()=runBlocking {
        val session=local.startWorkout()
        local.addConversation(CoachConversation("chat","Вопрос",1))
        local.saveCoachDraft("Мой вопрос","chat")
        local.clearChats()
        local.saveSet(session,"leg-press",0,10_000,10,3,"good")
        assertFalse(local.saveCoachReply(CoachMessage("late","chat","assistant","Опоздавший ответ",2)))
        local.saveCoachDraft("Опоздавший черновик","chat")
        val saved=local.snapshot()
        assertTrue(saved.conversations.isEmpty());assertTrue(saved.messages.isEmpty())
        assertEquals(1,saved.sessions.single().completedWorkSets())
    }
    @Test fun draftsStayWithTheirDialoguesAndEarlyInputSurvivesRestoreAndClear()=runBlocking {
        local.addConversation(CoachConversation("a","Первый",1))
        local.addConversation(CoachConversation("b","Второй",2))
        local.saveCoachDraft("Старый общий черновик")
        local.saveCoachDraft("Первый черновик","a")
        local.saveCoachDraft("Второй черновик","b")
        val session=local.startWorkout()
        val vm=withContext(Dispatchers.Main) {
            AppViewModel(local,settings,assistant,RestTimer(context)).also { it.updateDraft("Введено до восстановления") }
        }
        try {
            waitUntil { local.snapshot().messages.any { it.id=="local-draft" && it.content=="Введено до восстановления" } }
            withContext(Dispatchers.Main) {
                assertEquals("Введено до восстановления",vm.draft.value)
                vm.conversation("a");assertEquals("Первый черновик",vm.draft.value)
                vm.updateDraft("Новый первый")
                vm.conversation("b");assertEquals("Второй черновик",vm.draft.value)
                vm.updateDraft("Новый второй")
                vm.conversation("a");assertEquals("Новый первый",vm.draft.value)
            }
            waitUntil { local.snapshot().messages.any { it.conversationId=="b" && it.content=="Новый второй" } }
            // Both updates are queued when deletion starts; neither can resurrect chat.
            withContext(Dispatchers.Main) { vm.updateDraft("Будет удалён");vm.clearChats() }
            local.saveSet(session,"leg-press",0,10_000,10,3,"good")
            waitUntil { local.snapshot().conversations.isEmpty() }
            delay(350)
            val saved=local.snapshot()
            assertTrue(saved.messages.isEmpty());assertTrue(saved.conversations.isEmpty())
            assertEquals(1,saved.sessions.single().completedWorkSets())
            assertEquals("",vm.draft.value)
        } finally { withContext(Dispatchers.Main) { vm.viewModelScope.cancel() } }
    }
    @Test fun reopeningSameDialogueDoesNotCancelPendingResponse()=runBlocking {
        local.addConversation(CoachConversation("a","Первый",1))
        val vm=withContext(Dispatchers.Main) { AppViewModel(local,settings,assistant,RestTimer(context)).also { it.conversation("a") } }
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val request=launch { assistant.chat("Объясни упражнение",conversationId="a",onQuestionSaved={ entered.complete(Unit);release.await() }) }
        try {
            withTimeout(10_000) { entered.await() }
            val status=assistant.state.value
            withContext(Dispatchers.Main) { vm.conversation("a") }
            assertEquals(status,assistant.state.value);assertTrue(assistant.state.value.busy)
            release.complete(Unit);request.join()
            assertTrue(assistant.state.value.phase in setOf("error","offline"))
        } finally { release.complete(Unit);request.join();withContext(Dispatchers.Main) { vm.viewModelScope.cancel() } }
    }
    @Test fun deletingHistoryInvalidatesPreparedQuestionAndConversation()=runBlocking {
        val owner=CoachConversation("prepared","Старое окно",1)
        val before=local.coachHistoryEpoch
        local.clearChats()
        local.addConversation(owner,before)
        assertFalse(local.saveCoachQuestion(owner,CoachMessage("question",owner.id,"user","Старый вопрос",2),before))
        assertTrue(local.snapshot().conversations.isEmpty());assertTrue(local.snapshot().messages.isEmpty())
        val fresh=local.coachHistoryEpoch
        assertTrue(local.saveCoachQuestion(owner,CoachMessage("fresh",owner.id,"user","Новый вопрос",3),fresh))
        assertEquals("Новый вопрос",local.snapshot().messages.single().content)
    }

    @Test fun deletingOneDialogueRejectsLateWritesAndPreservesOtherHistoryAndWorkout()=runBlocking {
        val session=local.startWorkout()
        val removed=CoachConversation("removed","Удаляемый",1)
        val kept=CoachConversation("kept","Оставшийся",2)
        val epoch=local.coachHistoryEpoch
        local.addConversation(removed);local.addConversation(kept)
        local.saveMessage(CoachMessage("question-a",removed.id,"user","Первый вопрос",3))
        local.saveMessage(CoachMessage("question-b",kept.id,"user","Второй вопрос",4))
        local.saveCoachDraft("Черновик",removed.id)
        local.saveInsight(AiInsight("insight","workout","Разбор",5,"Период",emptyList(),"fingerprint"))
        val before=local.snapshot()
        local.deleteConversation(removed.id)
        local.saveSet(session,"leg-press",0,10_000,10,3,"good")
        local.addConversation(removed,epoch)
        assertFalse(local.saveCoachQuestion(removed,CoachMessage("late-question",removed.id,"user","Поздний вопрос",6),epoch))
        assertFalse(local.saveCoachReply(CoachMessage("late-answer",removed.id,"assistant","Поздний ответ",7)))
        local.saveCoachDraft("Поздний черновик",removed.id)
        // Deleting another dialogue must not invalidate a question already preparing here.
        assertTrue(local.saveCoachQuestion(kept,CoachMessage("fresh",kept.id,"user","Продолжение",8),epoch))
        val saved=local.snapshot()
        assertEquals(listOf(kept),saved.conversations)
        assertTrue(saved.messages.none { it.conversationId==removed.id })
        assertTrue(saved.messages.any { it.id=="question-b" })
        assertEquals(before.insights,saved.insights)
        assertEquals(1,saved.sessions.single().completedWorkSets())
        // Even if preparation reads Room after deletion, an explicit owner cannot become a new ID.
        assertTrue(runCatching { assistant.chat("Поздний запрос",conversationId=removed.id) }.isFailure)
        assertTrue(runCatching { assistant.propose("Позднее изменение",conversationId=removed.id) }.isFailure)
        assertEquals(saved,local.snapshot())
    }

    @Test fun deletingSelectedDialogueResetsSelectionAndKeepsOtherDrafts()=runBlocking {
        local.addConversation(CoachConversation("a","Первый",1))
        local.addConversation(CoachConversation("b","Второй",2))
        local.saveCoachDraft("Первый черновик","a")
        local.saveCoachDraft("Второй черновик","b")
        val vm=withContext(Dispatchers.Main) {
            AppViewModel(local,settings,assistant,RestTimer(context)).also { it.conversation("a") }
        }
        try {
            waitUntil { vm.draft.value=="Первый черновик" }
            withContext(Dispatchers.Main) {
                vm.updateDraft("Запоздавшая запись")
                vm.deleteConversation("a")
                assertNull(vm.conversationId.value)
                assertEquals("",vm.draft.value)
            }
            waitUntil { local.snapshot().conversations.none { it.id=="a" } }
            delay(350)
            val saved=local.snapshot()
            assertTrue(saved.messages.none { it.conversationId=="a" })
            assertEquals("Второй черновик",saved.messages.single { it.conversationId=="b" }.content)
            withContext(Dispatchers.Main) {
                vm.conversation("b")
                assertEquals("Второй черновик",vm.draft.value)
            }
        } finally { withContext(Dispatchers.Main) { vm.viewModelScope.cancel() } }
    }

    @Test fun explicitBackupRestoreAllowsDeletedDialogueAndInvalidatesOldQuestions()=runBlocking {
        val owner=CoachConversation("chat","Сохранённый",1)
        local.addConversation(owner)
        local.saveMessage(CoachMessage("question","chat","user","Вопрос",2))
        val backup=local.snapshot()
        val oldEpoch=local.coachHistoryEpoch
        local.deleteConversation(owner.id)
        local.replaceAll(backup)
        val next=CoachMessage("next","chat","user","Продолжение",3)
        assertFalse(local.saveCoachQuestion(owner,next,oldEpoch))
        assertTrue(local.saveCoachQuestion(owner,next,local.coachHistoryEpoch))
        assertEquals(2,local.snapshot().messages.size)
    }
}
