package ru.dmitriy.workouts.domain

import java.time.LocalDate

/** Validate the entire graph before entering the database transaction. No partial import. */
object ImportValidator {
    fun validate(s:LocalState) {
        fun ids(values:List<String>) { require(values.size==values.distinct().size && values.all { it.isNotBlank() && it.length<=200 }) { "Файл содержит повторяющиеся или неполные записи" } }
        require(s.profile.age in 1..120 && s.profile.heightCm in 80..250 && s.profile.goal.length<=200 && s.profile.preferences.length<=2000) { "Неверные параметры профиля" }
        require(s.sessions.size<=10_000 && s.messages.size<=30_000 && s.equipment.size<=200 && s.insights.size<=5_000 && s.proposals.size<=5_000 && s.changeLogs.size<=10_000 && s.revisions.size<=10_000) { "Резервная копия превышает лимит записей" }
        ids(s.exercises.map { it.id });ids(s.equipment.map { it.id });ids(s.sessions.map { it.id });ids(s.bodyWeights.map { it.id });ids(s.checkIns.map { it.id });ids(s.conversations.map { it.id });ids(s.messages.map { it.id });ids(s.insights.map { it.id });ids(s.proposals.map { it.proposalId });ids(s.changeLogs.map { it.id });ids(s.revisions.map { it.id })
        require(s.exercises.toSet()==ExerciseCatalog.exercises.toSet()) { "Каталог техники должен совпадать с проверенным каталогом приложения" }
        val exercises=s.exercises.associateBy { it.id };val equipment=s.equipment.associateBy { it.id };val sessions=s.sessions.associateBy { it.id }
        s.equipment.forEach { require(it.exerciseId in exercises && it.stepGrams in 1..100_000 && it.name.isNotBlank() && it.name.length<=200 && it.settings.length<=2000) { "Неверное оборудование" } }
        fun program(p:Program) {
            ProgramRules.validate(p);require(p.version>0 && p.id.isNotBlank())
            require(p.stages==ExerciseCatalog.initialProgram().stages) { "Неизвестные правила этапов программы" }
            if(p.nextDate.isNotEmpty())LocalDate.parse(p.nextDate)
            require(p.targets.all { it.exerciseId in exercises && equipment[it.equipmentId]?.exerciseId==it.exerciseId }) { "Неверные связи программы" }
            require(p.targets.none { exercises[it.exerciseId]?.tracking in listOf("reps_per_side","bodyweight_reps") && it.weightGrams!=null }) { "Неподходящие единицы веса" }
        }
        program(s.program)
        val allSetIds=mutableListOf<String>();val allExerciseIds=mutableListOf<String>()
        s.sessions.forEach { session ->
            require(session.status in listOf("active","completed","partial") && session.version>0 && session.programVersion>0 && session.startedAt>0 && session.note.length<=2000 && (session.finishedAt==null || session.finishedAt>=session.startedAt)) { "Неверное занятие" }
            require(session.status=="active" || session.finishedAt!=null);program(session.assignedSnapshot)
            require(session.exercises.size in 1..12 && session.exercises.map { it.exerciseId }.distinct().size==session.exercises.size)
            session.exercises.forEach { e ->
                allExerciseIds+=e.id;require(e.exerciseId in exercises && equipment[e.equipmentId]?.exerciseId==e.exerciseId && e.target.exerciseId==e.exerciseId && e.target.equipmentId==e.equipmentId && e.note.length<=2000)
                ProgramRules.validate(s.program.copy(targets=listOf(e.target)))
                if(exercises[e.exerciseId]?.tracking in listOf("reps_per_side","bodyweight_reps"))require(e.target.weightGrams==null)
                require(e.sets.size<=15 && e.sets.map { it.index to it.warmup }.distinct().size==e.sets.size)
                e.sets.forEach { set ->
                    allSetIds+=set.id
                    require(set.index>=0 && set.index<(if(set.warmup)10 else e.target.sets) && (set.weightGrams==null || set.weightGrams in 0..1_000_000) && (set.reps==null || set.reps in 0..100) && (set.rir==null || set.rir in 0..10) && set.technique in listOf("good","unknown","poor")) { "Неверный подход" }
                    if(set.completed)require(set.reps!=null && (exercises[e.exerciseId]?.tracking !in listOf("weight_reps","dumbbell_each") || set.weightGrams!=null))
                    if(exercises[e.exerciseId]?.tracking in listOf("reps_per_side","bodyweight_reps"))require(set.weightGrams==null)
                }
            }
            if(session.status=="completed")require(session.isComplete()) { "Завершённое занятие содержит невыполненные подходы" }
        }
        ids(allSetIds);ids(allExerciseIds);require(s.sessions.count { it.status=="active" }<=1)
        s.bodyWeights.forEach { LocalDate.parse(it.date);require(it.grams in 20_000..400_000) }
        s.checkIns.forEach { LocalDate.parse(it.date);require((it.fatigue==null || it.fatigue in 1..5)&&(it.availableMinutes==null || it.availableMinutes in 5..240)) }
        val conversationIds=s.conversations.map { it.id }.toSet()
        s.conversations.forEach { require(it.title.length<=300 && (it.exerciseId==null || it.exerciseId in exercises) && (it.sessionId==null || it.sessionId in sessions)) }
        s.messages.forEach { require(it.conversationId in conversationIds && it.role in listOf("user","assistant","system") && it.content.length<=16_000 && it.state in listOf("draft","saved","sending","streaming","sent","cancelled","error","offline","receiving")) { "Неверное сообщение" } }
        s.insights.forEach { require(it.text.length<=16_000 && it.evidenceIds.all { id -> id in sessions } && it.sourceIds.all { id -> ExerciseCatalog.sources.any { source -> source.id==id } } && it.fingerprint.length in 1..128) }
        s.proposals.forEach { p -> require(p.expectedVersion>0 && p.scope in listOf("session","program") && p.status in listOf("proposed","applied","rejected","stale","reverted") && p.operations.size in 1..12 && p.evidenceIds.all { it in sessions } && (p.sessionId==null || p.sessionId in sessions));p.operations.forEach { require(it.type in listOf("reorder","replace","reduceSets","weight","reps","rest","stage","schedule") && it.previous.length<=2000 && it.value.length<=2000) } }
        s.changeLogs.forEach { log ->
            require(s.proposals.any { it.proposalId==log.proposalId } && log.appliedVersion>0 && (log.scope=="program" && log.beforeProgram!=null && log.beforeSession==null || log.scope=="session" && log.beforeSession!=null && log.beforeProgram==null))
            log.beforeProgram?.let { program(it) };require(log.sessionId==null || log.sessionId in sessions)
            log.beforeSession?.let { before ->
                require(before.id==log.sessionId)
                validate(s.copy(sessions=listOf(before),bodyWeights=emptyList(),checkIns=emptyList(),conversations=emptyList(),messages=emptyList(),insights=emptyList(),proposals=emptyList(),changeLogs=emptyList(),revisions=emptyList()))
            }
        }
        s.revisions.forEach { program(it.snapshot);require(it.version>0 && it.programId==it.snapshot.id) }
    }
}
