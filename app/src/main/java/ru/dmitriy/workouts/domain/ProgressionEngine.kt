package ru.dmitriy.workouts.domain

import javax.inject.Inject

data class ProgressionDecision(val allowed:Boolean,val reason:String,val nextWeightGrams:Int?=null,val evidenceIds:List<String> = emptyList())
class ProgressionEngine @Inject constructor() {
    fun evaluate(exerciseId:String,equipment:EquipmentProfile,sessions:List<WorkoutSession>,currentTarget:ExerciseTarget?=null):ProgressionDecision {
        val comparable = sessions.filter { it.status=="completed" && it.isComplete() }
            .sortedByDescending { it.finishedAt ?: it.startedAt }
            .mapNotNull { s -> s.exercises.find { it.exerciseId==exerciseId && it.equipmentId==equipment.id }?.let { s to it } }.take(2)
        if(comparable.size<2) return ProgressionDecision(false,"Нужны два завершённых занятия на этом оборудовании.")
        val evidence=comparable.map { it.first.id }
        val a=comparable[0].second; val b=comparable[1].second
        if(a.target.sets!=b.target.sets || a.target.minReps!=b.target.minReps || a.target.maxReps!=b.target.maxReps) return ProgressionDecision(false,"Объём или диапазон изменился: отсчёт начинается заново.",evidenceIds=evidence)
        if(currentTarget!=null && (currentTarget.equipmentId!=equipment.id || currentTarget.sets!=a.target.sets || currentTarget.minReps!=a.target.minReps || currentTarget.maxReps!=a.target.maxReps)) return ProgressionDecision(false,"Текущий объём или диапазон отличается от истории: нужны два занятия с новой целью.",evidenceIds=evidence)
        val working=comparable.map { (_,e) -> e.sets.filter { it.completed && !it.warmup }.sortedBy { it.index } }
        if(working.any { it.size!=a.target.sets }) return ProgressionDecision(false,"Все назначенные рабочие подходы должны быть выполнены.",evidenceIds=evidence)
        if(working.flatten().any { it.rir==null || it.technique=="unknown" }) return ProgressionDecision(false,"Укажите запас повторений и оценку техники.",evidenceIds=evidence)
        if(working.flatten().any { (it.rir ?: 0)<2 || it.technique!="good" || (it.reps ?: 0)<a.target.maxReps }) return ProgressionDecision(false,"В двух занятиях нужны верхняя граница повторений, запас от 2 и хорошая техника.",evidenceIds=evidence)
        val loads=working.flatten().map { it.weightGrams }.distinct()
        if(loads.size!=1 || loads.single()==null) return ProgressionDecision(false,"Рабочий вес должен совпадать во всех подходах обоих занятий.",evidenceIds=evidence)
        val weight=loads.single()!!
        if(currentTarget?.weightGrams!=null && currentTarget.weightGrams!=weight) return ProgressionDecision(false,"Текущий назначенный вес отличается от выполненного: нужны два занятия с новой целью.",evidenceIds=evidence)
        return ProgressionDecision(true,"Условия выполнены в двух последовательных занятиях. Можно предложить один шаг оборудования.",weight+equipment.stepGrams,evidence)
    }
}

object ProgramRules {
    fun stage(week:Int)=ExerciseCatalog.initialProgram().stages.first { week in it.weekFrom..it.weekTo }
    fun forWeek(program:Program,week:Int):Program {
        require(week in 1..8) { "Неделя должна быть от 1 до 8" }
        val stage=stage(week)
        val baseSets=mapOf("leg-press" to 3,"chest-press" to 3,"lat-pulldown" to 3)
        return program.copy(week=week,weekdays=if(stage.sessionsPerWeek==2) listOf(1,5) else listOf(1,3,5),targets=program.targets.map { it.copy(sets=stage.setsOverride ?: baseSets.getOrDefault(it.exerciseId,2)) })
    }
    fun validate(program:Program) {
        require(program.week in 1..8 && program.weekdays.size in 2..3 && program.weekdays.distinct().size==program.weekdays.size && program.weekdays.all { it in 1..7 }) { "Выберите 2–3 разных дня недели" }
        require(program.targets.isNotEmpty() && program.targets.size<=12 && program.targets.map { it.exerciseId }.distinct().size==program.targets.size) { "Некорректный состав программы" }
        program.targets.forEach { t ->
            require(t.sets in 1..5 && t.minReps in 1..30 && t.maxReps in t.minReps..30 && t.restSeconds in 30..600 && (t.weightGrams==null || t.weightGrams in 0..1_000_000)) { "Недопустимая цель упражнения" }
        }
    }
    fun restDayWarning(days:List<Int>):Boolean = days.any { day -> days.any { other -> day!=other && ((day-other+7)%7==1 || (other-day+7)%7==1) } }
}
