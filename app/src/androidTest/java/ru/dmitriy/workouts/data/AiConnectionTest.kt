package ru.dmitriy.workouts.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import org.junit.Assert.*
import org.junit.Test
import ru.dmitriy.workouts.data.ai.AiRepository
import ru.dmitriy.workouts.data.ai.SessionVault
import ru.dmitriy.workouts.data.local.WorkoutDatabase
import ru.dmitriy.workouts.domain.ContextBuilder
import ru.dmitriy.workouts.domain.ProgressionEngine

/** Device encryption and offline independence. It never calls a provider or substitutes an answer. */
class AiConnectionTest {
    @Test fun leavingDialogueDuringPreparationKeepsQuestionAndHidesOldStatus() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val preferences=SettingsRepository(context)
        val old=preferences.settings.first()
        val database=Room.inMemoryDatabaseBuilder(context,WorkoutDatabase::class.java).build()
        val repository=WorkoutRepository(database)
        val assistant=AiRepository(repository,preferences,SessionVault(context),ContextBuilder(ProgressionEngine()),context)
        try {
            repository.seed();preferences.update { it.copy(aiConsent=false) }
            val sessionId=repository.startWorkout()
            val prompt="Объясни первый подход"
            val request=launch(start=CoroutineStart.UNDISPATCHED) { assistant.chat(prompt,sessionId,"leg-press") }
            assistant.resetUi()
            request.join()
            assertEquals("idle",assistant.state.value.phase)
            assertTrue(repository.snapshot().messages.any { it.role=="user" && it.content==prompt })
            val proposalPrompt="У меня тридцать минут"
            assistant.propose(proposalPrompt,sessionId)
            val saved=repository.snapshot()
            assertTrue(saved.messages.any { it.role=="user" && it.content==proposalPrompt })
            assertTrue(saved.messages.any { it.role=="assistant" && it.state=="error" })
            assertTrue(assistant.state.value.phase in setOf("error","offline"))
            assertTrue(saved.proposals.isEmpty())
        } finally { preferences.update { old };database.close() }
    }
    @Test fun questionAcknowledgementFollowsRoomCommitForChatAndProposal() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val preferences=SettingsRepository(context)
        val old=preferences.settings.first()
        val database=Room.inMemoryDatabaseBuilder(context,WorkoutDatabase::class.java).build()
        val repository=WorkoutRepository(database)
        val assistant=AiRepository(repository,preferences,SessionVault(context),ContextBuilder(ProgressionEngine()),context)
        try {
            repository.seed()
            // Stop before any HTTP call, even when this emulator has internet.
            preferences.update { it.copy(aiConsent=false) }
            val sessionId=repository.startWorkout()
            val question="Как выполнить первый подход?"
            var chatAcknowledged=false
            assistant.chat(question,sessionId,"leg-press",onQuestionSaved={
                val saved=repository.snapshot().messages
                assertEquals(1,saved.count { it.role=="user" && it.content==question })
                chatAcknowledged=true
            })
            assertTrue(chatAcknowledged)
            val change="Осталось тридцать минут"
            var proposalAcknowledged=false
            assistant.propose(change,sessionId,onQuestionSaved={
                val saved=repository.snapshot().messages
                assertEquals(1,saved.count { it.role=="user" && it.content==change })
                proposalAcknowledged=true
            })
            assertTrue(proposalAcknowledged)
            val saved=repository.snapshot()
            assertEquals(2,saved.messages.count { it.role=="user" })
            assertEquals(2,saved.messages.count { it.role=="assistant" && it.state=="error" })
            assertTrue(saved.proposals.isEmpty())
        } finally { preferences.update { old };database.close() }
    }
    @Test fun personalKeyEncryptedAndRemovalDoesNotAffectWorkout() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val preferences=SettingsRepository(context)
        val old=preferences.settings.first()
        val database=Room.inMemoryDatabaseBuilder(context,WorkoutDatabase::class.java).build()
        val repository=WorkoutRepository(database)
        val vault=SessionVault(context)
        val previousKey=vault.read()
        val assistant=AiRepository(repository,preferences,vault,ContextBuilder(ProgressionEngine()),context)
        try {
            repository.seed()
            preferences.update { it.copy(aiConsent=true,includeNotes=false) }
            val testKey="sk-device-test-never-used-for-network"
            assistant.connect("",testKey)
            assertTrue(assistant.connected.value);assertNotNull(vault.read())
            assertEquals(testKey,vault.read())
            val stored=context.getSharedPreferences("personal_ai_key",Context.MODE_PRIVATE).all.values.joinToString()
            assertFalse(stored.contains(testKey))
            val sessionId=repository.startWorkout()
            assistant.cancel()
            assertEquals("cancelled",assistant.state.value.phase)
            assertEquals("active",repository.snapshot().sessions.first().status)
            repository.saveSet(sessionId,"leg-press",0,10_000,10,3,"good")
            assertEquals(1,repository.snapshot().sessions.first().exercises.first().sets.size)
            assistant.disconnect()
            assertFalse(assistant.connected.value);assertNull(vault.read())
        } finally {
            if(previousKey!=null)vault.save(previousKey) else vault.clear()
            preferences.update { old }
            database.close()
        }
    }
}
