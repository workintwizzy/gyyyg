package ru.dmitriy.workouts.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="profile") data class ProfileRow(@PrimaryKey val id:String="profile", val json:String)
@Entity(tableName="exercise") data class ExerciseRow(@PrimaryKey val id:String, val json:String)
@Entity(tableName="equipment") data class EquipmentRow(@PrimaryKey val id:String, val exerciseId:String, val json:String)
@Entity(tableName="program") data class ProgramRow(@PrimaryKey val id:String, val version:Long, val json:String)
@Entity(tableName="program_stage") data class StageRow(@PrimaryKey val id:String, val programId:String, val json:String)
@Entity(tableName="program_revision") data class RevisionRow(@PrimaryKey val id:String, val json:String)
@Entity(tableName="workout_session") data class SessionRow(@PrimaryKey val id:String, val status:String, val startedAt:Long, val json:String)
@Entity(tableName="session_exercise", foreignKeys=[ForeignKey(entity=SessionRow::class,parentColumns=["id"],childColumns=["sessionId"],onDelete=ForeignKey.CASCADE)],indices=[Index("sessionId")])
data class SessionExerciseRow(@PrimaryKey val id:String, val sessionId:String, val position:Int, val json:String)
@Entity(tableName="set_entry", foreignKeys=[ForeignKey(entity=SessionExerciseRow::class,parentColumns=["id"],childColumns=["sessionExerciseId"],onDelete=ForeignKey.CASCADE)],indices=[Index("sessionExerciseId"),Index(value=["sessionExerciseId","setIndex","warmup"],unique=true)])
data class SetRow(@PrimaryKey val id:String, val sessionExerciseId:String, val setIndex:Int, val warmup:Boolean, val completed:Boolean, val json:String)
@Entity(tableName="body_weight") data class BodyWeightRow(@PrimaryKey val id:String,val json:String)
@Entity(tableName="readiness") data class ReadinessRow(@PrimaryKey val id:String,val json:String)
@Entity(tableName="coach_conversation") data class ConversationRow(@PrimaryKey val id:String,val json:String)
@Entity(tableName="coach_message") data class MessageRow(@PrimaryKey val id:String,val conversationId:String,val json:String)
@Entity(tableName="ai_insight",indices=[Index(value=["type","fingerprint"],unique=true)]) data class InsightRow(@PrimaryKey val id:String,val type:String,val fingerprint:String,val json:String)
@Entity(tableName="plan_proposal") data class ProposalRow(@PrimaryKey val id:String,val json:String)
@Entity(tableName="plan_change_log") data class ChangeLogRow(@PrimaryKey val id:String,val json:String)
@Entity(tableName="database_epoch") data class EpochRow(@PrimaryKey val id:Int=1,val value:Long=0)

@Dao interface WorkoutDao {
    @Query("SELECT * FROM database_epoch WHERE id=1") fun observeEpoch():Flow<EpochRow?>
    @Query("SELECT * FROM profile LIMIT 1") suspend fun profile():ProfileRow?
    @Query("SELECT * FROM exercise") suspend fun exercises():List<ExerciseRow>
    @Query("SELECT * FROM equipment") suspend fun equipment():List<EquipmentRow>
    @Query("SELECT * FROM program LIMIT 1") suspend fun program():ProgramRow?
    @Query("SELECT * FROM program_revision") suspend fun revisions():List<RevisionRow>
    @Query("SELECT * FROM workout_session ORDER BY startedAt DESC") suspend fun sessions():List<SessionRow>
    @Query("SELECT * FROM session_exercise ORDER BY position") suspend fun sessionExercises():List<SessionExerciseRow>
    @Query("SELECT * FROM set_entry ORDER BY setIndex") suspend fun sets():List<SetRow>
    @Query("SELECT * FROM body_weight") suspend fun weights():List<BodyWeightRow>
    @Query("SELECT * FROM readiness") suspend fun readiness():List<ReadinessRow>
    @Query("SELECT * FROM coach_conversation") suspend fun conversations():List<ConversationRow>
    @Query("SELECT * FROM coach_message") suspend fun messages():List<MessageRow>
    @Query("SELECT * FROM ai_insight") suspend fun insights():List<InsightRow>
    @Query("SELECT * FROM plan_proposal") suspend fun proposals():List<ProposalRow>
    @Query("SELECT * FROM plan_change_log") suspend fun logs():List<ChangeLogRow>
    @Upsert suspend fun putProfile(row:ProfileRow)
    @Upsert suspend fun putExercises(rows:List<ExerciseRow>)
    @Upsert suspend fun putEquipment(rows:List<EquipmentRow>)
    @Upsert suspend fun putProgram(row:ProgramRow)
    @Upsert suspend fun putStages(rows:List<StageRow>)
    @Upsert suspend fun putRevisions(rows:List<RevisionRow>)
    @Upsert suspend fun putSessions(rows:List<SessionRow>)
    @Upsert suspend fun putSessionExercises(rows:List<SessionExerciseRow>)
    @Upsert suspend fun putSets(rows:List<SetRow>)
    @Upsert suspend fun putWeights(rows:List<BodyWeightRow>)
    @Upsert suspend fun putReadiness(rows:List<ReadinessRow>)
    @Upsert suspend fun putConversations(rows:List<ConversationRow>)
    @Upsert suspend fun putMessages(rows:List<MessageRow>)
    @Upsert suspend fun putInsights(rows:List<InsightRow>)
    @Upsert suspend fun putProposals(rows:List<ProposalRow>)
    @Upsert suspend fun putLogs(rows:List<ChangeLogRow>)
    @Upsert suspend fun putEpoch(row:EpochRow)
    @Query("UPDATE database_epoch SET value=value+1 WHERE id=1") suspend fun bumpEpoch()
    @Query("DELETE FROM profile") suspend fun clearProfile()
    @Query("DELETE FROM exercise") suspend fun clearExercises()
    @Query("DELETE FROM equipment") suspend fun clearEquipment()
    @Query("DELETE FROM program") suspend fun clearProgram()
    @Query("DELETE FROM program_stage") suspend fun clearStages()
    @Query("DELETE FROM program_revision") suspend fun clearRevisions()
    @Query("DELETE FROM workout_session") suspend fun clearSessions()
    @Query("DELETE FROM body_weight") suspend fun clearWeights()
    @Query("DELETE FROM readiness") suspend fun clearReadiness()
    @Query("DELETE FROM coach_conversation") suspend fun clearConversations()
    @Query("DELETE FROM coach_message") suspend fun clearMessages()
    @Query("DELETE FROM ai_insight") suspend fun clearInsights()
    @Query("DELETE FROM plan_proposal") suspend fun clearProposals()
    @Query("DELETE FROM plan_change_log") suspend fun clearLogs()
}

@Database(entities=[ProfileRow::class,ExerciseRow::class,EquipmentRow::class,ProgramRow::class,StageRow::class,RevisionRow::class,SessionRow::class,SessionExerciseRow::class,SetRow::class,BodyWeightRow::class,ReadinessRow::class,ConversationRow::class,MessageRow::class,InsightRow::class,ProposalRow::class,ChangeLogRow::class,EpochRow::class],version=1,exportSchema=true)
abstract class WorkoutDatabase:RoomDatabase() { abstract fun dao():WorkoutDao }
