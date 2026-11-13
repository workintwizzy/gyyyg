package ru.dmitriy.workouts.data

import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import ru.dmitriy.workouts.data.ai.ResponseBodyReader

class ResponseBodyReaderTest {
    @Test fun readsShortJsonAndFinalPartialChunkWithoutEofFailure() {
        val short="{\"accessToken\":\"test-only-token\"}"
        assertEquals(short,ResponseBodyReader.read(Buffer().writeUtf8(short)))
        val multi="x".repeat(8192)+"короткий остаток"
        assertEquals(multi,ResponseBodyReader.read(Buffer().writeUtf8(multi)))
    }
    @Test fun rejectsEmptyResponse() { assertTrue(runCatching { ResponseBodyReader.read(Buffer()) }.isFailure) }
    @Test fun limitCountsBytesBeforeDecodingUtf8() {
        assertTrue(runCatching { ResponseBodyReader.read(Buffer().writeUtf8("я".repeat(10)),10) }.isFailure)
    }
}
