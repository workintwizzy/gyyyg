package ru.dmitriy.workouts.data.ai

import java.io.IOException
import kotlinx.serialization.json.*

/** Official DeepSeek Chat Completions stream: require stop AND [DONE]. */
class SseAnswer(private val maximumChars:Int=16_000) {
    var text:String="";private set
    var completed:Boolean=false;private set
    private var stopped=false
    fun accept(data:String) {
        if(data=="[DONE]") {
            if(!stopped || text.isBlank())throw IOException("Ответ не завершён. Попробуйте ещё раз")
            completed=true;return
        }
        if(completed)throw IOException("Ответ не удалось прочитать")
        val value=runCatching { Json.parseToJsonElement(data).jsonObject }.getOrElse { throw IOException("Ответ не удалось прочитать") }
        val choices=value["choices"] as? JsonArray ?: throw IOException("Ответ не удалось прочитать")
        for(item in choices) {
            val choice=item as? JsonObject ?: throw IOException("Ответ не удалось прочитать")
            val finish=choice["finish_reason"]?.let { if(it==JsonNull)null else (it as? JsonPrimitive)?.content }
            if(finish!=null && finish!="stop")throw IOException("Ответ оборвался. Попробуйте ещё раз")
            val delta=choice["delta"] as? JsonObject ?: throw IOException("Ответ не удалось прочитать")
            val primitive=delta["content"]
            if(primitive!=null && primitive!=JsonNull) {
                if(primitive !is JsonPrimitive || !primitive.isString)throw IOException("Ответ не удалось прочитать")
                val content=primitive.content
                if(stopped && content.isNotEmpty())throw IOException("Ответ не удалось прочитать")
                if(text.length+content.length>maximumChars)throw IOException("Ответ слишком длинный. Уточните вопрос")
                text+=content
            }
            if(finish=="stop")stopped=true
        }
    }
    fun requireComplete() { if(!completed)throw IOException("Ответ оборвался. Сохранена полученная часть") }
}
