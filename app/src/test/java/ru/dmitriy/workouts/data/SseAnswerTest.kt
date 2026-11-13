package ru.dmitriy.workouts.data

import org.junit.Assert.*
import org.junit.Test
import ru.dmitriy.workouts.data.ai.SseAnswer

class SseAnswerTest {
    @Test fun streamIsSuccessfulOnlyAfterDone() {
        val answer=SseAnswer();answer.accept(chunk("Техника"))
        assertFalse(answer.completed);assertTrue(runCatching { answer.requireComplete() }.isFailure)
        answer.accept(chunk(" важнее веса"));answer.accept("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}")
        assertFalse(answer.completed)
        answer.accept("[DONE]")
        answer.requireComplete();assertEquals("Техника важнее веса",answer.text)
    }
    @Test fun rejectsMalformedTruncatedAndEmptyAnswers() {
        listOf("{\"choices\":","[]","{\"choices\":[{\"delta\":{\"content\":17}}]}").forEach { data -> assertTrue(runCatching { SseAnswer().accept(data) }.isFailure) }
        assertTrue(runCatching { SseAnswer().accept("[DONE]") }.isFailure)
        val empty=SseAnswer();empty.accept("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}")
        assertTrue(runCatching { empty.accept("[DONE]") }.isFailure)
    }
    @Test fun providerErrorKeepsPartialTextWithoutSuccess() {
        val answer=SseAnswer();answer.accept(chunk("Полученная часть"))
        assertTrue(runCatching { answer.accept("{\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}") }.isFailure)
        assertEquals("Полученная часть",answer.text);assertFalse(answer.completed)
    }
    @Test fun boundedAnswerRejectsExcessInsteadOfTruncatingSilently() {
        val answer=SseAnswer(3);answer.accept(chunk("123"))
        assertTrue(runCatching { answer.accept(chunk("4")) }.isFailure)
        assertEquals("123",answer.text);assertFalse(answer.completed)
    }
    @Test fun ignoresReasoningAndAcceptsUsageOnLastChunk() {
        val answer=SseAnswer();answer.accept("{\"choices\":[{\"delta\":{\"reasoning_content\":\"private reasoning\"}}]}")
        assertEquals("",answer.text)
        answer.accept(chunk("Готово"))
        answer.accept("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"total_tokens\":12}}")
        answer.accept("[DONE]");answer.requireComplete();assertEquals("Готово",answer.text)
    }
    private fun chunk(text:String)="{\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":null}]}"
}
