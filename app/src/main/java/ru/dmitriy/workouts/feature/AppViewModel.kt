package ru.dmitriy.workouts.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import ru.dmitriy.workouts.core.timer.RestTimer
import ru.dmitriy.workouts.data.*
import ru.dmitriy.workouts.data.ai.AiRepository
import ru.dmitriy.workouts.domain.*
import ru.dmitriy.workouts.feature.workout.SetDraft
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject


@HiltViewModel class AppViewModel @Inject constructor(
    private val repository: WorkoutRepository,
    private val preferences: SettingsRepository,
    private val assistant: AiRepository,
    val timer: RestTimer,
) : ViewModel() {
    val local = repository.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalState())
    val settings = preferences.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val ai = assistant.state
    val connected = assistant.connected
    val error = MutableStateFlow<String?>(null)
    val selectedExercise = MutableStateFlow(0)
    val workoutEntry = MutableStateFlow(0L)
    val conversationId = MutableStateFlow<String?>(null)
    val draft = MutableStateFlow("")
    private var savingSet = false
    private var dialogueGeneration = 0L
    private val sendingGeneration = MutableStateFlow<Long?>(null)
    private val clearingChats=MutableStateFlow(false)
    val busy = combine(sendingGeneration, ai, clearingChats) { preparing, status, clearing -> preparing != null || status.busy || clearing }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private data class DraftWrite(val conversationId:String?,val text:String,val epoch:Long)
    private val drafts=mutableMapOf<String?,String>()
    private val draftWrites=Channel<DraftWrite>(Channel.UNLIMITED)
    private val draftMutex=Mutex()
    private var draftEpoch=0L
    init {
        action {
            val loadingEpoch=draftEpoch
            repository.seed()
            val saved=repository.snapshot()
            if(loadingEpoch!=draftEpoch)return@action
            saved.messages.filter { it.state=="draft" }.forEach {
                val key=it.conversationId.takeUnless { owner -> owner=="local-draft" }
                // The asynchronous initial read must not replace text already typed.
                if(!drafts.containsKey(key))drafts[key]=it.content
            }
            draft.value=drafts[conversationId.value].orEmpty()
        }
        action {
            for(first in draftWrites) {
                delay(250)
                val pending=linkedMapOf(first.conversationId to first)
                while(true) {
                    val next=draftWrites.tryReceive().getOrNull() ?: break
                    pending[next.conversationId]=next
                }
                pending.values.forEach { update ->
                    draftMutex.withLock {
                        if(update.epoch==draftEpoch && drafts[update.conversationId]==update.text)repository.saveCoachDraft(update.text,update.conversationId)
                    }
                }
            }
        }
    }
    private fun saveDraft(key:String?,text:String) {
        drafts[key]=text
        draftWrites.trySend(DraftWrite(key,text,draftEpoch))
    }
    fun updateDraft(value: String) {
        val text=value.take(2000)
        draft.value=text
        saveDraft(conversationId.value,text)
    }
    fun progression(exerciseId: String, equipmentId: String): ProgressionDecision? {
        val saved=local.value
        val target=saved.sessions.firstOrNull { it.status=="active" }?.exercises?.find { it.exerciseId==exerciseId && it.equipmentId==equipmentId }?.target
            ?: saved.program.targets.find { it.exerciseId==exerciseId && it.equipmentId==equipmentId }
        return saved.equipment.find { it.id==equipmentId }?.let { ProgressionEngine().evaluate(exerciseId,it,saved.sessions,target) }
    }
    private fun action(block: suspend () -> Unit) { viewModelScope.launch {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error.value = failure.message ?: "Не удалось выполнить действие" }
    } }
    fun clearError() { error.value = null }
    fun profile(profile: Profile) = action { repository.saveProfile(profile); preferences.update { it.copy(onboarded = true) } }
    fun settings(value: AppSettings) = action { preferences.update { value } }
    fun connect(base: String, code: String) = action { assistant.connect(base, code) }
    fun disconnect() = action { assistant.disconnect() }
    fun start(onStarted: (String) -> Unit) = action {
        val id = repository.startWorkout()
        val session = repository.snapshot().sessions.first { it.id == id }
        val unfinished = session.exercises.withIndex().filter { (_, exercise) ->
            (0 until exercise.target.sets).any { index -> exercise.sets.none { it.completed && !it.warmup && it.index == index } }
        }
        selectedExercise.value = unfinished.filter { (_, exercise) -> exercise.sets.any { it.completed } }
            .maxByOrNull { (_, exercise) -> exercise.sets.filter { it.completed }.maxOf { it.recordedAt } }?.index
            ?: unfinished.firstOrNull()?.index ?: session.exercises.lastIndex.coerceAtLeast(0)
        workoutEntry.value++
        onStarted(id)
    }
    fun selectExercise(index: Int) {
        if (selectedExercise.value != index) timer.skip()
        selectedExercise.value = index
    }
    fun saveSet(sessionId: String, exerciseId: String, draft: SetDraft) {
        if (savingSet) return
        savingSet = true
        action {
            try {
                repository.saveSet(sessionId, exerciseId, draft.index, draft.weightGrams, draft.reps, draft.rir, draft.technique, draft.warmup)
                if (!draft.warmup) repository.snapshot().sessions.find { it.id == sessionId && it.status == "active" }
                    ?.exercises?.getOrNull(selectedExercise.value)?.takeIf { it.exerciseId == exerciseId }?.let { timer.start(it.target.restSeconds) }
            } finally { savingSet = false }
        }
    }
    fun unmark(id: String) = action { repository.unmarkSet(id) }
    fun rest(sessionId:String,exerciseId:String,seconds:Int,saveToPlan:Boolean)=action { repository.updateSessionRest(sessionId,exerciseId,seconds,saveToPlan) }
    fun finish(id: String, partial: Boolean, onFinished: () -> Unit) = action { repository.finishWorkout(id, partial); timer.skip(); onFinished() }
    fun program(program: Program) = action { repository.updateProgram(program) }
    fun nextStage(keepTwoDays: Boolean) = action {
        val old = repository.snapshot().program
        val next = ProgramRules.forWeek(old, (old.week + 1).coerceAtMost(8))
        repository.updateProgram(if (keepTwoDays && old.weekdays.size == 2) next.copy(weekdays = old.weekdays) else next)
    }
    fun equipment(value: EquipmentProfile) = action { repository.saveEquipment(value) }
    fun chooseEquipment(value: EquipmentProfile, sessionId: String?) = action {
        repository.saveEquipment(value)
        if (sessionId != null) repository.selectSessionEquipment(sessionId, value.exerciseId, value.id)
        else {
            val program = repository.snapshot().program
            repository.updateProgram(program.copy(targets = program.targets.map { if (it.exerciseId == value.exerciseId) it.copy(equipmentId = value.id, weightGrams = null) else it }))
        }
    }
    fun exerciseNote(sessionId: String, exerciseId: String, note: String) = action { repository.saveExerciseNote(sessionId, exerciseId, note) }
    fun bodyWeight(grams: Int) = action { repository.addBodyWeight(grams) }
    fun checkIn(fatigue: Int?, minutes: Int?) = action { repository.saveCheckIn(ReadinessCheckIn(UUID.randomUUID().toString(), LocalDate.now().toString(), fatigue, minutes)) }
    fun apply(id: String, onDone: () -> Unit) = action { repository.applyProposal(id); onDone() }
    fun reject(id: String, onDone: () -> Unit) = action { repository.rejectProposal(id); onDone() }
    fun rollback(id: String) = action { repository.rollback(id) }
    private suspend fun prepareConversation(prompt:String,sessionId:String?,exerciseId:String?,selected:String?,generation:Long):String? {
        val historyEpoch=repository.coachHistoryEpoch
        val saved=repository.snapshot()
        if(generation!=dialogueGeneration)return null
        val explicit=saved.conversations.find { it.id==selected }
        val current=explicit?.takeIf { it.sessionId==sessionId && it.exerciseId==exerciseId }
            ?: CoachConversation(selected.takeIf { explicit==null } ?: UUID.randomUUID().toString(),prompt.take(60),System.currentTimeMillis(),exerciseId,sessionId)
        if(saved.conversations.none { it.id==current.id })repository.addConversation(current,historyEpoch)
        if(generation!=dialogueGeneration)return null
        if(conversationId.value!=current.id) {
            val text=draft.value
            saveDraft(selected,"")
            conversationId.value=current.id
            saveDraft(current.id,text)
        }
        return current.id
    }
    private suspend fun clearSavedDraft(prompt:String,generation:Long,source:String?) = withContext(Dispatchers.Main.immediate) {
        // Callbacks originate in the repository's IO work. All selection/draft
        // generations are owned by Main, and newer text always survives.
        if(generation==dialogueGeneration && draft.value==prompt) {
            updateDraft("")
            val owner=conversationId.value
            draftMutex.withLock {
                if(generation==dialogueGeneration && draft.value.isEmpty())repository.saveCoachDraft("",owner)
                if(source!=owner && drafts[source].isNullOrEmpty())repository.saveCoachDraft("",source)
            }
        }
    }
    fun send(prompt: String, sessionId: String?, exerciseId: String?) {
        val generation=dialogueGeneration
        val selected=conversationId.value
        if(sendingGeneration.value!=null || assistant.state.value.busy || clearingChats.value)return
        sendingGeneration.value=generation
        action {
            try {
                val id=prepareConversation(prompt,sessionId,exerciseId,selected,generation) ?: return@action
                if(generation==dialogueGeneration)assistant.chat(prompt,sessionId,exerciseId,id,
                    onQuestionSaved={clearSavedDraft(prompt,generation,selected)})
            } finally { if(sendingGeneration.value==generation)sendingGeneration.value=null }
        }
    }
    fun conversation(id: String?) {
        // Expanding the same conversation into a full screen is not navigation
        // to a different request and must keep its stream alive.
        if(conversationId.value==id)return
        dialogueGeneration++
        assistant.resetUi()
        conversationId.value=id
        draft.value=drafts[id].orEmpty()
    }
    fun newConversation(sessionId: String? = null, exerciseId: String? = null) {
        if(clearingChats.value)return
        val id = UUID.randomUUID().toString()
        val historyEpoch=repository.coachHistoryEpoch
        conversation(id)
        action {
            repository.addConversation(CoachConversation(id, "Новый диалог", System.currentTimeMillis(), exerciseId = exerciseId, sessionId = sessionId),historyEpoch)
            drafts[id]?.let { saveDraft(id,it) }
        }
    }
    fun cancelAi() { dialogueGeneration++;assistant.cancel() }
    fun insight(kind: String, sessionId: String?, force: Boolean = false, onDone: (String) -> Unit) {
        if(sendingGeneration.value!=null || assistant.state.value.busy || clearingChats.value)return
        val generation=dialogueGeneration
        sendingGeneration.value=generation
        action {
            try {
                assistant.insight(kind,sessionId,force)
                if(generation==dialogueGeneration && assistant.state.value.phase in setOf("done","cached")) {
                    repository.snapshot().insights.filter { it.type==kind && (sessionId==null || sessionId in it.evidenceIds) }.maxByOrNull { it.createdAt }?.let { onDone(it.id) }
                }
            } finally { if(sendingGeneration.value==generation)sendingGeneration.value=null }
        }
    }
    fun propose(prompt: String, sessionId: String?, onDone: (String) -> Unit) {
        val generation=dialogueGeneration
        val selected=conversationId.value
        if(sendingGeneration.value!=null || assistant.state.value.busy || clearingChats.value)return
        sendingGeneration.value=generation
        action {
            try { proposeNow(prompt,sessionId,selected,generation,onDone) }
            finally { if(sendingGeneration.value==generation)sendingGeneration.value=null }
        }
    }
    fun proposeForToday(prompt: String, onDone: (String) -> Unit) {
        if(sendingGeneration.value!=null || assistant.state.value.busy || clearingChats.value)return
        conversation(null)
        val generation=dialogueGeneration
        sendingGeneration.value=generation
        action {
            try {
                val existing=repository.snapshot().sessions.firstOrNull { it.status=="active" }
                val sessionId=repository.startWorkout()
                if(existing==null)selectedExercise.value=0
                if(generation==dialogueGeneration)proposeNow(prompt,sessionId,null,generation,onDone)
            } finally { if(sendingGeneration.value==generation)sendingGeneration.value=null }
        }
    }
    private suspend fun proposeNow(prompt:String,sessionId:String?,selected:String?,generation:Long,onDone:(String)->Unit) {
        val exerciseId=repository.snapshot().conversations.find { it.id==selected && it.sessionId==sessionId }?.exerciseId
        val id=prepareConversation(prompt,sessionId,exerciseId,selected,generation) ?: return
        if(generation!=dialogueGeneration)return
        assistant.propose(prompt,sessionId,id,onQuestionSaved={clearSavedDraft(prompt,generation,selected)})
        if(generation==dialogueGeneration && assistant.state.value.phase=="done") {
            assistant.state.value.proposalId?.let(onDone)
        }
    }
    fun clearChats() {
        if(clearingChats.value)return
        clearingChats.value=true
        dialogueGeneration++
        assistant.resetUi()
        conversationId.value=null
        draftEpoch++
        drafts.clear();draft.value=""
        action {
            // Serialize pending draft writes with chat deletion. This mutation reads
            // the latest workout state inside Room instead of replacing a snapshot.
            try { draftMutex.withLock { repository.clearChats() } }
            finally { clearingChats.value=false }
        }
    }
    fun deleteConversation(id:String) {
        if(clearingChats.value)return
        clearingChats.value=true
        if(conversationId.value==id)conversation(null)
        drafts.remove(id)
        action {
            try { draftMutex.withLock { repository.deleteConversation(id) } }
            finally { clearingChats.value=false }
        }
    }
}
