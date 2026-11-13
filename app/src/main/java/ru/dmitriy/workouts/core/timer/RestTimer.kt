package ru.dmitriy.workouts.core.timer

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class RestState(val remainingSeconds:Int=0,val running:Boolean=false,val paused:Boolean=false)
/** elapsedRealtime survives process death; BOOT_COUNT prevents stale alarms after a reboot. */
@Singleton class RestTimer @Inject constructor(@ApplicationContext private val context:Context) {
    private val prefs=context.getSharedPreferences("rest_timer",Context.MODE_PRIVATE)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val mutable=MutableStateFlow(RestState())
    val state:StateFlow<RestState> = mutable.asStateFlow()
    private var deadline=0L
    private var generation=0L
    private var ticker:Job?=null
    private fun boot()=Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,0)
    init {
        generation=prefs.getLong("generation",0)
        if(prefs.getInt("boot",-1)==boot()) {
            deadline=prefs.getLong("deadline",0)
            if(prefs.getBoolean("paused",false)) mutable.value=RestState(prefs.getInt("remaining",0),false,true)
            else if(deadline>0) tick()
        } else prefs.edit().clear().apply()
    }
    @Synchronized fun start(seconds:Int) { require(seconds in 1..1800); ticker?.cancel(); generation++;deadline=SystemClock.elapsedRealtime()+seconds*1000L; persist(false,seconds); schedule(); tick() }
    @Synchronized fun pause() { val left=remaining(); ticker?.cancel(); mutable.value=RestState(left,false,true); persist(true,left); cancelAlarm() }
    @Synchronized fun resume() { if(mutable.value.paused && mutable.value.remainingSeconds>0) start(mutable.value.remainingSeconds) }
    @Synchronized fun skip() { ticker?.cancel(); deadline=0; generation++;mutable.value=RestState(); persist(false,0);cancelAlarm() }
    @Synchronized fun add30() { val old=mutable.value; if(old.paused) { mutable.value=old.copy(remainingSeconds=(old.remainingSeconds+30).coerceAtMost(1800)); persist(true,mutable.value.remainingSeconds) } else start((remaining()+30).coerceAtMost(1800)) }
    private fun remaining()=((deadline-SystemClock.elapsedRealtime()+999)/1000).toInt().coerceAtLeast(0)
    private fun persist(paused:Boolean,left:Int) { prefs.edit().putInt("boot",boot()).putLong("deadline",deadline).putLong("generation",generation).putBoolean("delivered",false).putBoolean("paused",paused).putInt("remaining",left).commit() }
    private fun tick() { ticker?.cancel(); val eventGeneration=generation;ticker=scope.launch { while(isActive) { val left=remaining(); mutable.value=RestState(left,left>0,false); if(left==0) { RestSignal.fire(context,eventGeneration);break }; delay(200) } } }
    private fun alarm()=PendingIntent.getBroadcast(context,1,Intent(context,RestReceiver::class.java).putExtra("generation",generation),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun schedule() { (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,deadline,alarm()) }
    private fun cancelAlarm() { (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarm()) }
}

class RestReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val pending=goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { RestSignal.fire(context,intent.getLongExtra("generation",-1)) } finally { pending.finish() }
        }
    }
}

internal object RestSignal {
    private val lock=Any()
    suspend fun fire(context:Context,generation:Long) {
        val claimed=synchronized(lock) {
            val prefs=context.getSharedPreferences("rest_timer",Context.MODE_PRIVATE)
            val timer=RestDeadline(prefs.getLong("deadline",0),prefs.getInt("boot",-1),prefs.getLong("generation",0),prefs.getBoolean("paused",false),prefs.getBoolean("delivered",false))
            if(!timer.canFire(SystemClock.elapsedRealtime(),Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,0),generation))false
            else { prefs.edit().putBoolean("delivered",true).putLong("deadline",0).commit();true }
        }
        if(!claimed)return
        val settings=ru.dmitriy.workouts.data.SettingsRepository(context).settings.first()
        if(context.getSharedPreferences("rest_timer",Context.MODE_PRIVATE).getLong("generation",0)!=generation)return
        val manager=context.getSystemService(NotificationManager::class.java)
        val channelId="rest_${settings.sound}_${settings.vibration}"
        manager.createNotificationChannel(NotificationChannel(channelId,"Отдых",NotificationManager.IMPORTANCE_DEFAULT).apply { enableVibration(settings.vibration); if(!settings.sound) setSound(null,null) })
        val open=PendingIntent.getActivity(context,0,Intent(context,ru.dmitriy.workouts.MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val canNotify=android.os.Build.VERSION.SDK_INT<33 || androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED
        if(canNotify)runCatching { manager.notify(1,NotificationCompat.Builder(context,channelId).setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("Отдых завершён").setContentText("Следующий подход — когда будете готовы").setContentIntent(open).setAutoCancel(true).build()) }
        else {
            if(settings.vibration)runCatching { (context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator).vibrate(android.os.VibrationEffect.createOneShot(150,android.os.VibrationEffect.DEFAULT_AMPLITUDE)) }
            if(settings.sound)runCatching { val tone=android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION,70);tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK,200);delay(250);tone.release() }
        }
    }
}
