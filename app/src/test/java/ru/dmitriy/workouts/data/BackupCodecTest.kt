package ru.dmitriy.workouts.data

import org.junit.Assert.*
import org.junit.Test
import ru.dmitriy.workouts.domain.*

class BackupCodecTest {
    private fun state()=LocalState(program=ExerciseCatalog.initialProgram(),exercises=ExerciseCatalog.exercises,equipment=ExerciseCatalog.equipment,
        conversations=listOf(CoachConversation("conversation","Техника",1)),messages=listOf(CoachMessage("message","conversation","user","Личная заметка",1)))
    @Test fun completeRoundTripHasNoSessionCredentials() {
        val text=BackupCodec.encode(state(),true)
        assertEquals(state(),BackupCodec.decode(text).data)
        assertFalse(text.contains("accessToken")); assertFalse(text.contains("refreshToken")); assertFalse(text.contains("API_KEY"))
    }
    @Test fun chatsExcludedByChoice() {
        val text=BackupCodec.encode(state(),false)
        assertFalse(text.contains("Личная заметка"))
        assertTrue(BackupCodec.decode(text).data.messages.isEmpty())
    }
    @Test fun rejectsCorruptUnsupportedAndBrokenLinks() {
        val valid=BackupCodec.encode(state(),true)
        listOf(valid.dropLast(1),valid.replace("\"formatVersion\":1","\"formatVersion\":99"),valid.replace("\"conversationId\":\"conversation\"","\"conversationId\":\"missing\"")).forEach {
            assertTrue(runCatching { BackupCodec.decode(it) }.isFailure)
        }
    }
    @Test fun nullWeightRemainsDistinctFromZero() {
        val initial=state()
        val text=BackupCodec.encode(initial.copy(program=initial.program.copy(targets=initial.program.targets.mapIndexed { i,t -> if(i==0)t.copy(weightGrams=0) else t })),false)
        val restored=BackupCodec.decode(text).data.program.targets
        assertEquals(0,restored[0].weightGrams);assertNull(restored[1].weightGrams)
    }
}
