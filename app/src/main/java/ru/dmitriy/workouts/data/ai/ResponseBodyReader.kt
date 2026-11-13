package ru.dmitriy.workouts.data.ai

import okio.Buffer
import okio.BufferedSource
import java.io.IOException

object ResponseBodyReader {
    fun read(source:BufferedSource,maximumBytes:Long=512_000):String {
        val buffer=Buffer()
        while(true) {
            val count=source.read(buffer,8192)
            if(count==-1L)break
            if(buffer.size>maximumBytes)throw IOException("Ответ слишком большой")
        }
        return buffer.readUtf8().also { if(it.isBlank())throw IOException("Ответ не получен. Попробуйте ещё раз") }
    }
}
