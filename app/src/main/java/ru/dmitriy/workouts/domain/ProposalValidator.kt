package ru.dmitriy.workouts.domain

import javax.inject.Inject

class ProposalValidator @Inject constructor(private val progression:ProgressionEngine) {
    fun validate(proposal:PlanProposal,state:LocalState,now:Long=System.currentTimeMillis()):List<String> = runCatching { check(proposal,state,now); emptyList<String>() }.getOrElse { listOf(it.message ?: "Предложение отклонено") }
    fun requireValid(proposal:PlanProposal,state:LocalState,now:Long=System.currentTimeMillis()) { check(proposal,state,now) }
    private fun check(p:PlanProposal,s:LocalState,now:Long) {
        require(p.status=="proposed") { "Предложение уже обработано" }
        require(p.expiresAt>now) { "Срок предложения истёк" }
        require(p.scope in listOf("session","program") && p.operations.size in 1..12) { "Не удалось проверить предложенные изменения" }
        require(p.evidenceIds.all { id -> s.sessions.any { it.id==id } }) { "Неизвестные исходные занятия" }
        val session=if(p.scope=="session") s.sessions.find { it.id==p.sessionId } ?: error("Занятие не найдено") else null
        require((session?.version ?: s.program.version)==p.expectedVersion) { "Записи изменились. Попросите новое предложение" }
        require(session==null || session.status=="active") { "Завершённое занятие не изменяется" }
        val targets=session?.exercises?.map { it.target } ?: s.program.targets
        require(p.operations.map { it.type to it.exerciseId }.distinct().size==p.operations.size) { "Одно изменение предложено несколько раз" }
        require(p.operations.none { op -> op.type=="replace" && p.operations.count { it.exerciseId==op.exerciseId }>1 }) { "Замена должна быть отдельным изменением упражнения" }
        require(p.operations.none { op -> op.type=="weight" && p.operations.any { it.exerciseId==op.exerciseId && it.type in listOf("reduceSets","reps") } }) { "Вес и количество подходов нужно менять отдельно, с проверкой последних результатов" }
        require(p.operations.none { it.type=="stage" } || p.operations.size==1) { "Переход этапа подтверждается отдельно" }
        p.operations.forEach { op ->
            val target=targets.find { it.exerciseId==op.exerciseId }
            val actual=session?.exercises?.find { it.exerciseId==op.exerciseId }
            if(op.type !in listOf("stage","schedule","reorder")) require(target!=null) { "Неизвестное упражнение" }
            val started=actual?.sets?.any { it.completed }==true
            when(op.type) {
                "reorder" -> {
                    require(session!=null) { "Меняется порядок только оставшейся части занятия" }
                    val remaining=session.exercises.filter { e -> e.sets.none { it.completed } }.map { it.exerciseId }
                    require(op.previous==remaining.joinToString(",") && op.value.split(',').size==remaining.size && op.value.split(',').toSet()==remaining.toSet()) { "Порядок не соответствует оставшимся упражнениям" }
                }
                "replace" -> {
                    require(!started) { "Начатое упражнение нельзя заменить" }
                    require(op.previous==op.exerciseId) { "Прежнее упражнение изменилось" }
                    val source=s.exercises.find { it.id==op.exerciseId } ?: error("Неизвестное упражнение")
                    require(op.value in source.alternatives && s.exercises.any { it.id==op.value }) { "Замены нет в проверенном каталоге" }
                    require(targets.none { it.exerciseId==op.value }) { "Упражнение уже есть в занятии" }
                    require(s.equipment.any { it.exerciseId==op.value }) { "Оборудование замены не выбрано" }
                }
                "reduceSets" -> {
                    val value=op.value.toIntOrNull() ?: error("Неверное число подходов")
                    require(op.previous==target!!.sets.toString() && value in 1..target.sets) { "Можно только сократить оставшийся объём" }
                    require(actual?.sets?.none { !it.warmup && it.completed && it.index>=value }!=false) { "Выполненные подходы сохраняются" }
                }
                "weight" -> {
                    require(!started) { "Цель начатого упражнения не изменяется" }
                    val value=op.value.toIntOrNull() ?: error("Не удалось проверить предложенный вес")
                    require(op.previous==(target!!.weightGrams?.toString() ?: "null") && value in 0..1_000_000) { "Прежняя цель веса изменилась" }
                    val exercise=s.exercises.first { it.id==op.exerciseId }
                    require(exercise.tracking in listOf("weight_reps","dumbbell_each")) { "У этого упражнения нет внешнего веса" }
                    val equipment=s.equipment.first { it.id==target.equipmentId }
                    val lastWeight=s.sessions.filter { it.status=="completed" }.sortedByDescending { it.startedAt }.flatMap { it.exercises }.firstOrNull { it.exerciseId==op.exerciseId && it.equipmentId==target.equipmentId }?.sets?.firstOrNull { it.completed && !it.warmup }?.weightGrams
                    val old=target.weightGrams ?: lastWeight
                    require(old!=null) { "Стартовый вес подбирается на месте" }
                    if(value>old) { val decision=progression.evaluate(op.exerciseId,equipment,s.sessions,target); require(decision.allowed && decision.nextWeightGrams==value) { decision.reason } }
                }
                "reps" -> {
                    require(!started) { "Цель начатого упражнения не изменяется" }
                    val parts=op.value.split('-').map { it.toIntOrNull() ?: error("Неверный диапазон") }
                    require(op.previous=="${target!!.minReps}-${target.maxReps}" && parts.size==2 && parts[0] in 1..30 && parts[1] in parts[0]..30) { "Неверный диапазон повторений" }
                }
                "rest" -> require(op.previous==target!!.restSeconds.toString() && (op.value.toIntOrNull() ?: 0) in 30..600) { "Отдых должен быть от 30 до 600 секунд" }
                "stage" -> {
                    require(p.scope=="program" && op.previous==s.program.week.toString() && op.value.toIntOrNull()==s.program.week+1 && s.program.week<8) { "Возможен только следующий этап" }
                    val count=s.sessions.count { it.status=="completed" && it.isComplete() && it.assignedSnapshot.week==s.program.week }
                    require(count>=s.program.weekdays.size) { "Сначала завершите занятия текущей недели" }
                }
                "schedule" -> {
                    require(p.scope=="program" && op.previous==s.program.weekdays.joinToString(",")) { "Расписание изменилось" }
                    ProgramRules.validate(s.program.copy(weekdays=op.value.split(',').map { it.toIntOrNull() ?: error("Неверный день") }))
                    require(!ProgramRules.restDayWarning(op.value.split(',').map { it.toInt() })) { "Оставьте полный день между занятиями" }
                }
                else -> error("Такое изменение не поддерживается")
            }
        }
    }
}
