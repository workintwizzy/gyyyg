package ru.dmitriy.workouts.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.dmitriy.workouts.data.ai.DeepSeekContract
import ru.dmitriy.workouts.domain.BuiltContext

class DeepSeekContractTest {
    private val context=BuiltContext(buildJsonObject {
        put("expectedVersion",7);put("scope","session")
        put("exerciseIds",buildJsonArray { add("leg-press");add("chest-press") })
        put("sourceIds",buildJsonArray { add("ace-leg") })
    },"hash",listOf("session-1"))
    private val valid="""{"summary":"Сократить занятие","reason":"Мало времени","evidenceIds":["session-1"],"operations":[{"type":"reduceSets","exerciseId":"chest-press","previous":"3","value":"2"}]}"""
    @Test fun serviceFieldsComeFromAppAndCannotBeSuppliedByModel() {
        val parsed=DeepSeekContract.proposal(valid,context,"session-1",1000)
        assertEquals(7L,parsed.expectedVersion);assertEquals("session",parsed.scope)
        assertEquals("session-1",parsed.sessionId);assertEquals(1_801_000L,parsed.expiresAt)
        assertEquals("proposed",parsed.status);assertTrue(parsed.proposalId.isNotBlank())
        assertTrue(runCatching { DeepSeekContract.proposal(valid.dropLast(1)+",\"status\":\"applied\"}",context,"session-1") }.isFailure)
    }
    @Test fun rejectsUnknownExerciseEvidenceOperationAndTruncatedJson() {
        listOf(valid.replace("chest-press","made-up"),valid.replace("session-1","not-sent"),valid.replace("reduceSets","executeScript"),valid.dropLast(5)," ","[]").forEach {
            assertTrue(it,runCatching { DeepSeekContract.proposal(it,context,"session-1") }.isFailure)
        }
    }
    @Test fun analysisCannotInventEvidenceOrLinks() {
        assertEquals("Факт",DeepSeekContract.insight("""{"text":"Факт","evidenceIds":["session-1"],"sourceIds":["ace-leg"]}""",context).text)
        listOf("""{"text":"Факт","evidenceIds":["other"],"sourceIds":[]}""","""{"text":"Факт","evidenceIds":[],"sourceIds":["https://made-up"]}""","""{"text":" ","evidenceIds":[],"sourceIds":[]}""").forEach {
            assertTrue(runCatching { DeepSeekContract.insight(it,context) }.isFailure)
        }
    }
    @Test fun distinguishesLengthEmptyAndInvalidProposalWithoutExposingResponse() {
        fun failure(raw:String)=runCatching { DeepSeekContract.content(raw) }.exceptionOrNull()!!.message.orEmpty()
        assertTrue(failure("""{"choices":[{"finish_reason":"length","message":{"content":""}}]}""").contains("предел длины"))
        assertTrue(failure("""{"choices":[{"finish_reason":"stop","message":{"content":" "}}]}""").contains("без итогового текста"))
        val malformed=runCatching { DeepSeekContract.proposal(valid.dropLast(1),context,"session-1") }.exceptionOrNull()!!.message.orEmpty()
        assertTrue(malformed.contains("неверном формате"))
        assertFalse(malformed.contains("summary"))
        val unknown=runCatching { DeepSeekContract.proposal(valid.replace("session-1","other"),context,"session-1") }.exceptionOrNull()!!.message.orEmpty()
        assertTrue(unknown.contains("не передавались"))
    }
    @Test fun responseMustFinishNormallyAndContainText() {
        val response="""{"choices":[{"finish_reason":"stop","message":{"content":"Готово"}}]}"""
        assertEquals("Готово",DeepSeekContract.content(response))
        listOf(response.replace("stop","length"),response.replace("Готово"," "),response.dropLast(3),"{}").forEach {
            assertTrue(runCatching { DeepSeekContract.content(it) }.isFailure)
        }
    }
}
