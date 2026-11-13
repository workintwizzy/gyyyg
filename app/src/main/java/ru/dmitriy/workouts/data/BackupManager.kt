package ru.dmitriy.workouts.data

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.dmitriy.workouts.domain.*
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Serializable data class BackupPreferences(val sound:Boolean=true,val vibration:Boolean=true,val includeNotes:Boolean=false)
@Serializable data class BackupEnvelope(val formatVersion:Int=1,val createdAt:Long,val includesChats:Boolean,val data:LocalState,val preferences:BackupPreferences=BackupPreferences())
object BackupCodec {
    private val json=Json { encodeDefaults=true; ignoreUnknownKeys=false }
    fun encode(data:LocalState,includeChats:Boolean,preferences:BackupPreferences=BackupPreferences()):String {
        val clean=if(includeChats)data else data.copy(conversations=emptyList(),messages=emptyList())
        return json.encodeToString(BackupEnvelope(createdAt=System.currentTimeMillis(),includesChats=includeChats,data=clean,preferences=preferences))
    }
    fun decode(text:String):BackupEnvelope {
        require(text.toByteArray().size<=20_000_000) { "Файл больше 20 МБ" }
        val envelope=try { json.decodeFromString<BackupEnvelope>(text) } catch (_: Exception) { error("Не удалось прочитать копию. Файл повреждён или создан другим приложением") }
        require(envelope.formatVersion==1) { "Эта версия резервной копии не поддерживается" }
        require(envelope.createdAt>0)
        require(envelope.includesChats || (envelope.data.conversations.isEmpty() && envelope.data.messages.isEmpty())) { "Состав диалогов не соответствует описанию копии" }
        ImportValidator.validate(envelope.data)
        return envelope
    }
    fun csv(data:LocalState):String {
        fun cell(value:Any?):String {
            val raw=value?.toString().orEmpty()
            val safe=if(raw.startsWith('=') || raw.startsWith('+') || raw.startsWith('-') || raw.startsWith('@')) "'$raw" else raw
            return "\"${safe.replace("\"","\"\"")}\""
        }
        val lines=mutableListOf(listOf("Номер занятия","Дата","Состояние","Упражнение","Оборудование","Подход","Разминка","Выполнен","Вес кг","Повторения","В запасе","Техника").joinToString(",",transform=::cell))
        for(session in data.sessions.sortedBy { it.startedAt }) for(exercise in session.exercises) for(set in exercise.sets) {
            val date=Instant.ofEpochMilli(session.startedAt).atZone(ZoneId.systemDefault()).toLocalDate()
            lines+=listOf(session.id,date,when(session.status){"active"->"Идёт";"completed"->"Завершено";else->"Частично"},data.exercises.find { it.id==exercise.exerciseId }?.name,data.equipment.find { it.id==exercise.equipmentId }?.name,set.index+1,if(set.warmup)"Да" else "Нет",if(set.completed)"Да" else "Нет",set.weightGrams?.let { java.math.BigDecimal(it).movePointLeft(3).stripTrailingZeros().toPlainString() },set.reps,set.rir,when(set.technique){"good"->"Хорошо";"poor"->"Ухудшилась";else->"Не указано"}).joinToString(",",transform=::cell)
        }
        return "\uFEFF"+lines.joinToString("\r\n")
    }
}
@Singleton class BackupManager @Inject constructor(@ApplicationContext private val context:Context,private val repository:WorkoutRepository,private val settings:SettingsRepository) {
    suspend fun exportJson(uri:Uri,includeChats:Boolean)=withContext(Dispatchers.IO) {
        val prefs=settings.settings.first()
        val content=BackupCodec.encode(repository.snapshot(),includeChats,BackupPreferences(prefs.sound,prefs.vibration,prefs.includeNotes))
        context.contentResolver.openOutputStream(uri,"wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) } ?: error("Файл недоступен")
    }
    suspend fun exportCsv(uri:Uri)=withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri,"wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(BackupCodec.csv(repository.snapshot())) } ?: error("Файл недоступен")
    }
    suspend fun inspect(uri:Uri):BackupEnvelope=withContext(Dispatchers.IO) {
        val bytes=context.contentResolver.openInputStream(uri)?.use { input ->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) { val n=input.read(buffer);if(n<0)break;require(out.size()+n<=20_000_000) { "Файл больше 20 МБ" };out.write(buffer,0,n) };out.toByteArray()
        } ?: error("Файл недоступен")
        BackupCodec.decode(bytes.decodeToString(throwOnInvalidSequence=true))
    }
    suspend fun restore(backup:BackupEnvelope) {
        ImportValidator.validate(backup.data)
        repository.replaceAll(backup.data)
        settings.update { it.copy(sound=backup.preferences.sound,vibration=backup.preferences.vibration,includeNotes=backup.preferences.includeNotes,onboarded=backup.data.profile.onboarded) }
    }
}
