package ru.dmitriy.workouts.core.timer

data class RestDeadline(val deadline:Long,val boot:Int,val generation:Long,val paused:Boolean=false,val delivered:Boolean=false) {
    fun remaining(now:Long,currentBoot:Int):Int = if(boot!=currentBoot || deadline<=0)0 else ((deadline-now+999)/1000).toInt().coerceAtLeast(0)
    fun canFire(now:Long,currentBoot:Int,eventGeneration:Long)=boot==currentBoot && generation==eventGeneration && !paused && !delivered && deadline>0 && now>=deadline
}
