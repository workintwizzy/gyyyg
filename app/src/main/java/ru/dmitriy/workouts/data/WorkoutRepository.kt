package ru.dmitriy.workouts.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import ru.dmitriy.workouts.data.local.*
import ru.dmitriy.workouts.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Room is the only persisted workout source. Every user action commits before returning. */
@Singleton
class WorkoutRepository @Inject constructor(private val db:WorkoutDatabase) {
    private val dao=db.dao()
    private val json=Json { encodeDefaults=true; ignoreUnknownKeys=false }
    private val validator=ProposalValidator(ProgressionEngine())
    private val historyEpoch=AtomicLong()
    // IDs are never reused. Tombstones reject work already preparing when one chat is deleted.
    private val deletedConversations=mutableSetOf<String>()
    val coachHistoryEpoch:Long get()=historyEpoch.get()
    val state:Flow<LocalState> = dao.observeEpoch().map {
        val saved=snapshot()
        saved.copy(proposals=saved.proposals.map { proposal ->
            if(proposal.status=="proposed" && validator.validate(proposal,saved).isNotEmpty())proposal.copy(status="stale")else proposal
        })
    }
    private fun id()=UUID.randomUUID().toString()
    suspend fun seed() = db.withTransaction {
        if(dao.profile()==null) persist(LocalState(program=ExerciseCatalog.initialProgram().copy(nextDate=nextDate(LocalDate.now(),listOf(1,5)).toString()),exercises=ExerciseCatalog.exercises,equipment=ExerciseCatalog.equipment))
    }
    suspend fun snapshot():LocalState = db.withTransaction { read() }
    private suspend fun read():LocalState {
        val exerciseRows=dao.sessionExercises(); val setRows=dao.sets()
        val sessions=dao.sessions().map { row -> json.decodeFromString<WorkoutSession>(row.json).copy(exercises=exerciseRows.filter { it.sessionId==row.id }.sortedBy { it.position }.map { e -> json.decodeFromString<SessionExercise>(e.json).copy(sets=setRows.filter { it.sessionExerciseId==e.id }.map { json.decodeFromString<SetEntry>(it.json) }) }) }
        return LocalState(
            profile=dao.profile()?.let { json.decodeFromString(it.json) } ?: Profile(),
            program=dao.program()?.let { json.decodeFromString(it.json) } ?: ExerciseCatalog.initialProgram(),
            exercises=dao.exercises().map { json.decodeFromString(it.json) },
            equipment=dao.equipment().map { json.decodeFromString(it.json) },sessions=sessions,
            bodyWeights=dao.weights().map { json.decodeFromString(it.json) },checkIns=dao.readiness().map { json.decodeFromString(it.json) },
            conversations=dao.conversations().map { json.decodeFromString(it.json) },messages=dao.messages().map { json.decodeFromString(it.json) },
            insights=dao.insights().map { json.decodeFromString(it.json) },proposals=dao.proposals().map { json.decodeFromString(it.json) },
            changeLogs=dao.logs().map { json.decodeFromString(it.json) },revisions=dao.revisions().map { json.decodeFromString(it.json) })
    }
    private suspend fun persist(s:LocalState) {
        // Cascades remove the child exercise/set rows; all replacement writes share one transaction.
        dao.clearSessions();dao.clearProfile();dao.clearExercises();dao.clearEquipment();dao.clearProgram();dao.clearStages();dao.clearRevisions();dao.clearWeights();dao.clearReadiness();dao.clearMessages();dao.clearConversations();dao.clearInsights();dao.clearProposals();dao.clearLogs()
        dao.putProfile(ProfileRow(json=json.encodeToString(s.profile)))
        dao.putExercises(s.exercises.map { ExerciseRow(it.id,json.encodeToString(it)) })
        dao.putEquipment(s.equipment.map { EquipmentRow(it.id,it.exerciseId,json.encodeToString(it)) })
        dao.putProgram(ProgramRow(s.program.id,s.program.version,json.encodeToString(s.program)))
        dao.putStages(s.program.stages.mapIndexed { i,x -> StageRow("${s.program.id}:$i",s.program.id,json.encodeToString(x)) })
        dao.putRevisions(s.revisions.map { RevisionRow(it.id,json.encodeToString(it)) })
        dao.putSessions(s.sessions.map { SessionRow(it.id,it.status,it.startedAt,json.encodeToString(it.copy(exercises=emptyList()))) })
        dao.putSessionExercises(s.sessions.flatMap { session -> session.exercises.mapIndexed { index,e -> SessionExerciseRow(e.id,session.id,index,json.encodeToString(e.copy(sets=emptyList()))) } })
        dao.putSets(s.sessions.flatMap { session -> session.exercises.flatMap { e -> e.sets.map { SetRow(it.id,e.id,it.index,it.warmup,it.completed,json.encodeToString(it)) } } })
        dao.putWeights(s.bodyWeights.map { BodyWeightRow(it.id,json.encodeToString(it)) });dao.putReadiness(s.checkIns.map { ReadinessRow(it.id,json.encodeToString(it)) })
        dao.putConversations(s.conversations.map { ConversationRow(it.id,json.encodeToString(it)) });dao.putMessages(s.messages.map { MessageRow(it.id,it.conversationId,json.encodeToString(it)) })
        dao.putInsights(s.insights.map { InsightRow(it.id,it.type,it.fingerprint,json.encodeToString(it)) });dao.putProposals(s.proposals.map { ProposalRow(it.proposalId,json.encodeToString(it)) });dao.putLogs(s.changeLogs.map { ChangeLogRow(it.id,json.encodeToString(it)) })
        dao.putEpoch(EpochRow(value=System.nanoTime()))
    }
    private suspend fun mutate(block:(LocalState)->LocalState) = db.withTransaction { val before=read(); val after=block(before); if(after!=before)persist(after) }
    suspend fun saveProfile(profile:Profile) = mutate { require(profile.age in 1..120 && profile.heightCm in 80..250 && profile.goal.length<=200 && profile.preferences.length<=2000);it.copy(profile=profile) }
    suspend fun startWorkout():String = db.withTransaction {
        val s=read();s.sessions.firstOrNull { it.status=="active" }?.id ?: run {
            val sessionId=id();val session=WorkoutSession(id=sessionId,startedAt=System.currentTimeMillis(),programVersion=s.program.version,assignedSnapshot=s.program,exercises=s.program.targets.map { t -> SessionExercise("$sessionId:${t.exerciseId}",t.exerciseId,t.equipmentId,t) })
            persist(s.copy(sessions=listOf(session)+s.sessions));sessionId
        }
    }
    suspend fun saveSet(sessionId:String,exerciseId:String,setIndex:Int,weightGrams:Int?,reps:Int?,rir:Int?,technique:String,warmup:Boolean=false) = mutate { state ->
        require(reps!=null && reps in 0..100 && (weightGrams==null || weightGrams in 0..1_000_000) && (rir==null || rir in 0..10) && technique in listOf("good","unknown","poor")) { "Проверьте вес, повторения и запас" }
        val catalog=state.exercises.first { it.id==exerciseId }
        require(catalog.tracking in listOf("reps_per_side","bodyweight_reps") || weightGrams!=null) { "Укажите вес; ноль можно записать явно" }
        require(catalog.tracking !in listOf("reps_per_side","bodyweight_reps") || weightGrams==null) { "У этого упражнения нет поля веса" }
        state.copy(sessions=state.sessions.map { session -> if(session.id!=sessionId)session else {
            val exercise=session.exercises.first { it.exerciseId==exerciseId }
            require(setIndex>=0 && setIndex<(if(warmup)10 else exercise.target.sets)) { "Номер подхода вне назначенного объёма" }
            val previous=exercise.sets.find { it.index==setIndex && it.warmup==warmup }
            if(previous?.completed==true && previous.weightGrams==weightGrams && previous.reps==reps && previous.rir==rir && previous.technique==technique)session else {
                val set=SetEntry(previous?.id ?: "$sessionId:$exerciseId:$warmup:$setIndex",setIndex,weightGrams,reps,rir,technique,warmup,true,System.currentTimeMillis())
                session.copy(version=session.version+1,exercises=session.exercises.map { e -> if(e.exerciseId==exerciseId)e.copy(sets=e.sets.filterNot { it.index==setIndex && it.warmup==warmup }+set) else e })
            }
        } })
    }
    suspend fun unmarkSet(setId:String) = mutate { state -> state.copy(sessions=state.sessions.map { session ->
        if(session.exercises.none { e -> e.sets.any { it.id==setId && it.completed } })session else session.copy(version=session.version+1,status=if(session.status=="completed")"partial" else session.status,exercises=session.exercises.map { e -> e.copy(sets=e.sets.map { if(it.id==setId)it.copy(completed=false)else it }) })
    }) }
    suspend fun finishWorkout(sessionId:String,allowPartial:Boolean) = mutate { state ->
        val session=state.sessions.first { it.id==sessionId }
        if(session.status!="active")state else {
            require(session.isComplete() || allowPartial) { "Остались невыполненные подходы. Сохранить частичное занятие?" }
            state.copy(sessions=state.sessions.map { if(it.id==sessionId)it.copy(status=if(it.isComplete())"completed" else "partial",finishedAt=System.currentTimeMillis(),version=it.version+1) else it },program=state.program.copy(nextDate=nextDate(LocalDate.now().plusDays(1),state.program.weekdays).toString()))
        }
    }
    suspend fun saveExerciseNote(sessionId:String,exerciseId:String,note:String)=mutate { s -> require(note.length<=2000);s.copy(sessions=s.sessions.map { if(it.id==sessionId)it.copy(version=it.version+1,exercises=it.exercises.map { e -> if(e.exerciseId==exerciseId)e.copy(note=note)else e })else it }) }
    suspend fun saveSessionNote(sessionId:String,note:String)=mutate { s -> require(note.length<=2000);s.copy(sessions=s.sessions.map { if(it.id==sessionId)it.copy(note=note,version=it.version+1)else it }) }
    suspend fun updateSessionRest(sessionId:String,exerciseId:String,seconds:Int,saveToPlan:Boolean)=mutate { s ->
        require(seconds in 30..600) { "Отдых должен быть от 30 до 600 секунд" }
        require(!saveToPlan || s.program.targets.any { it.exerciseId==exerciseId }) { "В будущем плане нет этого упражнения. Сохраните отдых только для текущего занятия" }
        val session=s.sessions.first { it.id==sessionId }
        require(session.status=="active" && session.exercises.any { it.exerciseId==exerciseId }) { "Активное упражнение не найдено" }
        val updated=session.copy(version=session.version+1,exercises=session.exercises.map { if(it.exerciseId==exerciseId)it.copy(target=it.target.copy(restSeconds=seconds))else it })
        s.copy(sessions=s.sessions.map { if(it.id==sessionId)updated else it },
            program=if(saveToPlan)s.program.copy(version=s.program.version+1,targets=s.program.targets.map { if(it.exerciseId==exerciseId)it.copy(restSeconds=seconds)else it })else s.program,
            revisions=if(saveToPlan)s.revisions+ProgramRevision(id(),s.program.id,s.program.version,System.currentTimeMillis(),s.program)else s.revisions)
    }
    suspend fun updateProgram(program:Program)=mutate { s ->
        ProgramRules.validate(program);require(program.targets.all { t -> s.exercises.any { it.id==t.exerciseId } && s.equipment.any { it.id==t.equipmentId && it.exerciseId==t.exerciseId } }) { "Упражнение или оборудование не найдены" }
        if(program.week!=s.program.week) {
            require(program.week==s.program.week+1) { "Переходите к следующей неделе по очереди" }
            require(s.sessions.count { it.status=="completed" && it.isComplete() && it.assignedSnapshot.week==s.program.week }>=s.program.weekdays.size) { "Сначала завершите занятия текущей недели" }
        }
        if(program.weekdays.size>s.program.weekdays.size)require(program.weekdays.size<=ProgramRules.stage(program.week).sessionsPerWeek) { "Для текущего этапа предусмотрено два занятия" }
        s.copy(program=program.copy(version=s.program.version+1),revisions=s.revisions+ProgramRevision(id(),s.program.id,s.program.version,System.currentTimeMillis(),s.program))
    }
    suspend fun saveEquipment(equipment:EquipmentProfile)=mutate { s -> require(equipment.stepGrams in 1..100_000 && s.exercises.any { it.id==equipment.exerciseId } && equipment.name.isNotBlank() && equipment.settings.length<=2000);s.copy(equipment=s.equipment.filterNot { it.id==equipment.id }+equipment) }
    suspend fun selectSessionEquipment(sessionId:String,exerciseId:String,equipmentId:String)=mutate { s ->
        require(s.equipment.any { it.id==equipmentId && it.exerciseId==exerciseId }) { "Оборудование не относится к упражнению" }
        val session=s.sessions.first { it.id==sessionId };require(session.status=="active") { "Завершённое занятие не меняется" }
        val exercise=session.exercises.first { it.exerciseId==exerciseId }
        require(exercise.sets.none { it.completed }) { "Смена оборудования доступна до первого подхода. Начатое упражнение сохраняет выбранный тренажёр" }
        if(exercise.equipmentId==equipmentId)s else s.copy(sessions=s.sessions.map { current -> if(current.id==sessionId)current.copy(version=current.version+1,exercises=current.exercises.map { e -> if(e.exerciseId==exerciseId)e.copy(equipmentId=equipmentId,target=e.target.copy(equipmentId=equipmentId,weightGrams=null),sets=emptyList())else e })else current })
    }
    suspend fun addBodyWeight(grams:Int)=mutate { s -> require(grams in 20_000..400_000);val date=LocalDate.now().toString();s.copy(bodyWeights=s.bodyWeights.filterNot { it.date==date }+BodyWeightEntry("body:$date",date,grams)) }
    suspend fun saveCheckIn(checkIn:ReadinessCheckIn)=mutate { s -> require((checkIn.fatigue==null || checkIn.fatigue in 1..5)&&(checkIn.availableMinutes==null || checkIn.availableMinutes in 5..240));s.copy(checkIns=s.checkIns.filterNot { it.id==checkIn.id }+checkIn) }
    suspend fun addConversation(conversation:CoachConversation,expectedHistoryEpoch:Long?=null)=mutate { s ->
        if(conversation.id in deletedConversations || expectedHistoryEpoch!=null && expectedHistoryEpoch!=historyEpoch.get())s
        else s.copy(conversations=s.conversations.filterNot { it.id==conversation.id }+conversation)
    }
    /** Accept a question once, together with its owner and matching draft removal.
     * A history deletion committed during preparation wins over the old request. */
    suspend fun saveCoachQuestion(conversation:CoachConversation,message:CoachMessage,expectedHistoryEpoch:Long):Boolean = db.withTransaction {
        if(conversation.id in deletedConversations || expectedHistoryEpoch!=historyEpoch.get())return@withTransaction false
        val saved=read()
        require(message.role=="user" && message.conversationId==conversation.id && message.content.length<=2000)
        val conversations=if(saved.conversations.any { it.id==conversation.id })saved.conversations else saved.conversations+conversation
        persist(saved.copy(conversations=conversations,messages=saved.messages.filterNot {
            it.id==message.id || it.state=="draft" && it.conversationId==conversation.id && it.content==message.content
        }+message))
        true
    }
    suspend fun saveMessage(message:CoachMessage)=mutate { s -> require(s.conversations.any { it.id==message.conversationId } && message.content.length<=16_000);s.copy(messages=s.messages.filterNot { it.id==message.id }+message) }
    suspend fun addMessage(message:CoachMessage)=saveMessage(message)
    /** The conversation check and reply commit share one transaction: clearing chat
     * cannot resurrect a late answer or overwrite a concurrently saved workout. */
    suspend fun saveCoachReply(message:CoachMessage,proposal:PlanProposal?=null):Boolean = db.withTransaction {
        val saved=read()
        if(saved.conversations.none { it.id==message.conversationId })return@withTransaction false
        require(message.content.length<=16_000 && message.role=="assistant")
        if(proposal!=null)require(validator.validate(proposal,saved).isEmpty()) { "Занятие изменилось. Запросите новый совет" }
        persist(saved.copy(messages=saved.messages.filterNot { it.id==message.id }+message,
            proposals=if(proposal==null)saved.proposals else saved.proposals.filterNot { it.proposalId==proposal.proposalId }+proposal))
        true
    }
    suspend fun saveCoachDraft(content:String,conversationId:String?=null)=mutate { s ->
        require(content.length<=2000)
        val owner=conversationId ?: "local-draft"
        // A queued draft from a deleted dialogue must not recreate that dialogue.
        if(conversationId!=null && s.conversations.none { it.id==conversationId })return@mutate s
        val id=if(conversationId==null)"local-draft" else "draft:$conversationId"
        val conversations=if(content.isNotEmpty() && s.conversations.none { it.id==owner })s.conversations+CoachConversation(owner,"Черновик",System.currentTimeMillis())else s.conversations
        val messages=s.messages.filterNot { it.id==id }
        s.copy(conversations=conversations,messages=if(content.isEmpty())messages else messages+CoachMessage(id,owner,"user",content,System.currentTimeMillis(),"draft"))
    }
    suspend fun saveInsight(insight:AiInsight,expectedHistoryEpoch:Long?=null)=mutate { s -> if(expectedHistoryEpoch!=null && expectedHistoryEpoch!=historyEpoch.get())return@mutate s;require(insight.text.isNotBlank() && insight.text.length<=16_000);s.copy(insights=s.insights.filterNot { it.id==insight.id || it.type==insight.type && it.fingerprint==insight.fingerprint }+insight) }
    suspend fun addInsight(insight:AiInsight,expectedHistoryEpoch:Long?=null)=saveInsight(insight,expectedHistoryEpoch)
    suspend fun saveProposal(proposal:PlanProposal)=mutate { s -> val errors=validator.validate(proposal,s);s.copy(proposals=s.proposals.filterNot { it.proposalId==proposal.proposalId }+proposal.copy(status=if(errors.isEmpty())"proposed" else "stale")) }
    suspend fun addProposal(proposal:PlanProposal)=saveProposal(proposal)
    suspend fun rejectProposal(id:String)=mutate { s -> s.copy(proposals=s.proposals.map { if(it.proposalId==id && it.status=="proposed")it.copy(status="rejected")else it }) }
    suspend fun clearChats() = db.withTransaction {
        val saved=read()
        persist(saved.copy(conversations=emptyList(),messages=emptyList(),insights=emptyList()))
        historyEpoch.incrementAndGet()
        Unit
    }
    suspend fun deleteConversation(id:String) = db.withTransaction {
        val saved=read()
        persist(saved.copy(conversations=saved.conversations.filterNot { it.id==id },
            messages=saved.messages.filterNot { it.conversationId==id }))
        deletedConversations.add(id)
        Unit
    }
    suspend fun replaceAll(state:LocalState) {
        ImportValidator.validate(state)
        db.withTransaction {
            persist(state)
            historyEpoch.incrementAndGet()
            deletedConversations.clear()
        }
    }
    suspend fun applyProposal(proposalId:String)=mutate { s ->
        val proposal=s.proposals.first { it.proposalId==proposalId }
        if(proposal.status=="applied")s else {
            validator.requireValid(proposal,s)
            val session=s.sessions.find { it.id==proposal.sessionId }
            val beforeProgram=if(proposal.scope=="program")s.program else null
            val beforeSession=if(proposal.scope=="session")session else null
            val newProgram=if(proposal.scope=="program")applyProgram(s.program,proposal,s).copy(version=s.program.version+1)else s.program
            val newSession=if(proposal.scope=="session")applySession(session!!,proposal,s).copy(version=session.version+1)else null
            val log=PlanChangeLog(id(),proposalId,proposal.scope,proposal.sessionId,System.currentTimeMillis(),beforeProgram,beforeSession,newSession?.version ?: newProgram.version)
            s.copy(program=newProgram,sessions=s.sessions.map { if(it.id==newSession?.id)newSession ?: it else it },proposals=s.proposals.map { if(it.proposalId==proposalId)it.copy(status="applied")else it },changeLogs=s.changeLogs+log,revisions=if(beforeProgram!=null)s.revisions+ProgramRevision(id(),beforeProgram.id,beforeProgram.version,System.currentTimeMillis(),beforeProgram)else s.revisions)
        }
    }
    suspend fun rollback(logId:String)=mutate { s ->
        val log=s.changeLogs.first { it.id==logId };require(!log.rolledBack) { "Изменение уже отменено" }
        if(log.scope=="program") {
            require(s.program.version==log.appliedVersion) { "После предложения план менялся. Откат не должен затирать правки" }
            s.copy(program=log.beforeProgram!!.copy(version=s.program.version+1),changeLogs=s.changeLogs.map { if(it.id==logId)it.copy(rolledBack=true)else it },proposals=s.proposals.map { if(it.proposalId==log.proposalId)it.copy(status="reverted")else it })
        } else {
            val current=s.sessions.first { it.id==log.sessionId }
            require(current.version==log.appliedVersion && current.status=="active") { "Занятие уже менялось: безопасный откат невозможен" }
            s.copy(sessions=s.sessions.map { if(it.id==log.sessionId)log.beforeSession!!.copy(version=current.version+1)else it },changeLogs=s.changeLogs.map { if(it.id==logId)it.copy(rolledBack=true)else it },proposals=s.proposals.map { if(it.proposalId==log.proposalId)it.copy(status="reverted")else it })
        }
    }
    private fun applyProgram(program:Program,p:PlanProposal,s:LocalState):Program {
        var result=program
        p.operations.forEach { op -> result=when(op.type) {
            "stage" -> ProgramRules.forWeek(result,op.value.toInt())
            "schedule" -> result.copy(weekdays=op.value.split(',').map { it.toInt() })
            else -> result.copy(targets=result.targets.map { target -> if(target.exerciseId==op.exerciseId)applyTarget(target,op,s)else target })
        } };return result
    }
    private fun applySession(session:WorkoutSession,p:PlanProposal,s:LocalState):WorkoutSession {
        var result=session
        p.operations.forEach { op -> result=if(op.type=="reorder") {
            val reordered=op.value.split(',').map { eid -> result.exercises.first { it.exerciseId==eid } }.iterator()
            result.copy(exercises=result.exercises.map { if(it.sets.none { set -> set.completed })reordered.next()else it })
        } else result.copy(exercises=result.exercises.map { e -> if(e.exerciseId!=op.exerciseId)e else {
            val target=applyTarget(e.target,op,s)
            if(op.type=="replace")e.copy(id="${session.id}:${target.exerciseId}",exerciseId=target.exerciseId,equipmentId=target.equipmentId,target=target,sets=emptyList(),note="")else e.copy(target=target,sets=e.sets.filter { it.warmup || it.index<target.sets })
        } }) };return result
    }
    private fun applyTarget(t:ExerciseTarget,op:ProposalOperation,s:LocalState)=when(op.type) {
        "replace" -> t.copy(exerciseId=op.value,equipmentId=s.equipment.first { it.exerciseId==op.value }.id,weightGrams=null)
        "reduceSets" -> t.copy(sets=op.value.toInt())
        "weight" -> t.copy(weightGrams=op.value.toInt())
        "reps" -> t.copy(minReps=op.value.substringBefore('-').toInt(),maxReps=op.value.substringAfter('-').toInt())
        "rest" -> t.copy(restSeconds=op.value.toInt())
        else -> t
    }
    private fun nextDate(from:LocalDate,days:List<Int>):LocalDate=(0L..7L).map { from.plusDays(it) }.first { it.dayOfWeek.value in days }
}
