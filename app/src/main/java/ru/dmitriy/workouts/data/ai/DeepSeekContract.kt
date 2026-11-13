package ru.dmitriy.workouts.data.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import ru.dmitriy.workouts.domain.*
import java.io.IOException
import java.util.UUID

@Serializable data class InsightResponse(val text:String,val evidenceIds:List<String>,val sourceIds:List<String>)
@Serializable private data class ProposalCandidate(val summary:String,val reason:String,val evidenceIds:List<String>,val operations:List<ProposalOperation>)

/** Pure validation boundary. A valid JSON document is still untrusted data. */
object DeepSeekContract {
    const val Endpoint="https://api.deepseek.com/chat/completions"
    const val Model="deepseek-flash"
    private val strict=Json { ignoreUnknownKeys=false; isLenient=false; coerceInputValues=false }
    private fun invalid(message:String="Помощник вернул ответ в неверном формате. Попросите другой вариант"):Nothing=throw IOException(message)
    fun content(raw:String):String = try {
        val root=strict.parseToJsonElement(raw).jsonObject
        val choices=root["choices"]?.jsonArray ?: invalid()
        if(choices.size!=1)invalid()
        val choice=choices[0].jsonObject
        val finish=choice["finish_reason"]?.jsonPrimitive?.content
        if(finish=="length")invalid("DeepSeek не закончил ответ: достигнут предел длины. Сократите запрос и повторите")
        if(finish!="stop")invalid("DeepSeek прервал подготовку ответа. Повторите запрос")
        val text=choice["message"]?.jsonObject?.get("content") as? JsonPrimitive ?: invalid()
        if(!text.isString)invalid()
        if(text.content.isBlank())invalid("DeepSeek завершил запрос без итогового текста. Повторите запрос")
        if(text.content.length>16_000)invalid("Ответ слишком длинный для приложения. Попросите более короткое предложение")
        text.content
    } catch(e:IOException) { throw e } catch(_:Exception) { invalid() }
    fun insight(raw:String,context:BuiltContext):InsightResponse = try {
        val parsed=strict.decodeFromString<InsightResponse>(raw)
        if(parsed.text.isBlank() || parsed.text.length>12_000 || parsed.evidenceIds.size>30 || parsed.sourceIds.size>20)invalid()
        if(parsed.evidenceIds.any { it !in context.evidenceIds })invalid()
        val allowed=context.payload["sourceIds"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        if(parsed.sourceIds.any { it !in allowed })invalid()
        parsed
    } catch(e:IOException) { throw e } catch(_:Exception) { invalid() }
    fun proposal(raw:String,context:BuiltContext,sessionId:String?,now:Long=System.currentTimeMillis()):PlanProposal = try {
        val candidate=strict.decodeFromString<ProposalCandidate>(raw)
        if(candidate.summary.isBlank() || candidate.summary.length>500 || candidate.reason.isBlank() || candidate.reason.length>1500 || candidate.evidenceIds.size>30 || candidate.operations.size !in 1..12)invalid()
        if(candidate.evidenceIds.any { it !in context.evidenceIds })invalid("Помощник сослался на занятия, которые ему не передавались. Попросите другой вариант")
        val allowed=context.payload["exerciseIds"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        if(candidate.operations.any { it.type !in setOf("reorder","replace","reduceSets","weight","reps","rest","stage","schedule") || it.exerciseId !in allowed || it.previous.length>500 || it.value.isBlank() || it.value.length>500 || (it.type=="replace" && it.value !in allowed) })invalid()
        PlanProposal(UUID.randomUUID().toString(),candidate.summary,candidate.reason,candidate.evidenceIds,
            context.payload.getValue("expectedVersion").jsonPrimitive.long,
            context.payload.getValue("scope").jsonPrimitive.content,now+30*60_000L,candidate.operations,sessionId=sessionId)
    } catch(e:IOException) { throw e } catch(_:Exception) { invalid() }
}
