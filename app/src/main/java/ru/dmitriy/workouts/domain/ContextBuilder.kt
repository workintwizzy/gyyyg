package ru.dmitriy.workouts.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.security.MessageDigest
import javax.inject.Inject

data class BuiltContext(val payload:JsonObject,val fingerprint:String,val evidenceIds:List<String>)
class ContextBuilder @Inject constructor(private val progression:ProgressionEngine) {
    private val json=Json { encodeDefaults=true }
    fun build(state:LocalState,kind:String,sessionId:String?=null,exerciseId:String?=null,includeNotes:Boolean=false):BuiltContext {
        val current=state.sessions.find { it.id==sessionId }
        val now=System.currentTimeMillis()
        val completed=state.sessions.filter { it.status=="completed" || (kind=="workout" && it.id==sessionId) }.sortedByDescending { it.finishedAt ?: it.startedAt }
        val selected=when(kind) {
            "period" -> completed.filter { (it.finishedAt ?: it.startedAt)>=now-28L*24*60*60*1000 }.take(12)
            "workout" -> (listOfNotNull(current)+completed.filter { it.id!=sessionId }.take(2)).distinctBy { it.id }
            else -> (listOfNotNull(current)+completed.filter { s -> exerciseId==null || s.exercises.any { it.exerciseId==exerciseId } }.take(3)).distinctBy { it.id }
        }
        val exerciseIds=if(exerciseId!=null) setOf(exerciseId) else (current?.exercises?.map { it.exerciseId } ?: state.program.targets.map { it.exerciseId }).toSet()
        val relevant=state.exercises.filter { it.id in exerciseIds }
        val alternatives=state.exercises.filter { e -> relevant.any { e.id in it.alternatives } }
        val sourceIds=(relevant+alternatives).flatMap { it.technique.sourceIds }.distinct()
        val version=current?.version ?: state.program.version
        val evidence=selected.map { it.id }
        val payload=buildJsonObject {
            put("kind",kind); put("scope",if(current!=null) "session" else "program"); put("expectedVersion",version)
            put("profile",buildJsonObject { put("age",state.profile.age);put("heightCm",state.profile.heightCm);put("goal",state.profile.goal.take(120));state.bodyWeights.maxByOrNull { it.date }?.let { put("bodyWeightGrams",it.grams) };if(includeNotes)put("preferences",state.profile.preferences.take(300)) })
            if(kind in listOf("chat","proposal"))state.checkIns.lastOrNull { it.date==java.time.LocalDate.now().toString() }?.let { check ->
                put("readiness",buildJsonObject { check.fatigue?.let { put("fatigue",it) };check.availableMinutes?.let { put("availableMinutes",it) } })
            }
            put("week",current?.assignedSnapshot?.week ?: state.program.week);put("weekdays",JsonArray((current?.assignedSnapshot?.weekdays ?: state.program.weekdays).map { JsonPrimitive(it) }))
            put("evidenceIds",JsonArray(evidence.map { JsonPrimitive(it) })); put("sourceIds",JsonArray(sourceIds.map { JsonPrimitive(it) }))
            put("exerciseIds",JsonArray((relevant+alternatives).map { JsonPrimitive(it.id) }.distinct()))
            put("targets",json.encodeToJsonElement(current?.exercises?.map { it.target } ?: state.program.targets))
            if(kind in listOf("period","workout")) {
                put("catalog",JsonArray(relevant.map { e -> buildJsonObject { put("id",e.id);put("name",e.name);put("tracking",e.tracking);put("sourceIds",json.encodeToJsonElement(e.technique.sourceIds)) } }))
            } else {
                put("catalog",json.encodeToJsonElement(relevant.map { it.copy(technique=it.technique.copy(setup=it.technique.setup.take(400),steps=it.technique.steps.take(4))) }))
                put("alternatives",json.encodeToJsonElement(alternatives))
            }
            put("equipment",json.encodeToJsonElement(state.equipment.filter { it.exerciseId in exerciseIds }.map { if(includeNotes)it.copy(settings=it.settings.take(300)) else it.copy(settings="") }))
            put("sessions",JsonArray(selected.map { session -> buildJsonObject {
                put("id",session.id);put("startedAt",session.startedAt);put("status",session.status)
                if(includeNotes)put("note",session.note.take(400))
                put("exercises",JsonArray(session.exercises.filter { exerciseId==null || it.exerciseId==exerciseId }.take(12).map { e -> buildJsonObject {
                    put("exerciseId",e.exerciseId);put("equipmentId",e.equipmentId);put("target",json.encodeToJsonElement(e.target))
                    put("sets",JsonArray(e.sets.filter { it.completed }.take(12).map { set -> buildJsonObject {
                        put("index",set.index);put("grams",set.weightGrams?.let { JsonPrimitive(it) } ?: JsonNull);put("reps",set.reps?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("rir",set.rir?.let { JsonPrimitive(it) } ?: JsonNull);put("technique",set.technique);put("warmup",set.warmup)
                    } }))
                    if(includeNotes)put("note",e.note.take(300))
                } }))
            } }))
            put("facts",buildJsonObject {
                put("sessionCount",selected.size)
                put("workSets",selected.sumOf { it.completedWorkSets() })
                put("warmupSets",selected.sumOf { s -> s.exercises.sumOf { e -> e.sets.count { it.completed && it.warmup } } })
                put("comparisons",JsonArray(relevant.map { exercise -> buildJsonObject {
                    put("exerciseId",exercise.id)
                    put("equipmentSeries",JsonArray(state.equipment.filter { it.exerciseId==exercise.id }.map { equipment -> buildJsonObject {
                        put("equipmentId",equipment.id)
                        val entries=selected.flatMap { s -> s.exercises.filter { it.exerciseId==exercise.id && it.equipmentId==equipment.id }.map { s to it } }
                        put("records",JsonArray(entries.map { (s,e) -> buildJsonObject {
                            put("sessionId",s.id);put("date",s.startedAt)
                            val sets=e.sets.filter { it.completed && !it.warmup }
                            put("workSets",sets.size);put("repetitions",sets.sumOf { it.reps ?: 0 })
                            put("volumeGramRepetitions",sets.sumOf { (it.weightGrams ?: 0).toLong()*(it.reps ?: 0) })
                            put("weightAndReps",JsonArray(sets.map { set -> buildJsonObject { put("grams",set.weightGrams?.let { JsonPrimitive(it) } ?: JsonNull);put("reps",set.reps?.let { JsonPrimitive(it) } ?: JsonNull) } }))
                        } }))
                        val currentTarget=(current?.exercises?.map { it.target } ?: state.program.targets).find { it.exerciseId==exercise.id && it.equipmentId==equipment.id }
                        val decision=progression.evaluate(exercise.id,equipment,selected,currentTarget)
                        put("weightIncreaseAllowed",decision.allowed);put("progressionReason",decision.reason)
                        put("progressionEvidenceIds",json.encodeToJsonElement(decision.evidenceIds))
                        put("allowedNextWeightGrams",decision.nextWeightGrams?.let { JsonPrimitive(it) } ?: JsonNull)
                    } }))
                } }))
            })
            put("notesIncluded",includeNotes)
            put("rules","Не диагностировать. Не подтверждать технику по цифрам. Не переносить вес между оборудованием. При острой/усиливающейся боли прекратить провоцирующее упражнение и обратиться за помощью. Ссылки только по sourceIds; выводы подтверждать evidenceIds. План менять только типизированным предложением после подтверждения.")
        }
        val encoded=payload.toString()
        require(encoded.toByteArray(Charsets.UTF_8).size<=60_000) { "Слишком много записей для одного ответа. Выберите одно упражнение или меньше занятий" }
        val fingerprint=MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return BuiltContext(payload,fingerprint,evidence)
    }
}
