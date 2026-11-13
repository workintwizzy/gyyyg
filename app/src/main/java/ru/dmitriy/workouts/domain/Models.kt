package ru.dmitriy.workouts.domain

import kotlinx.serialization.Serializable

@Serializable data class Profile(val age: Int = 19, val heightCm: Int = 175, val goal: String = "Сила и мышцы", val preferences: String = "", val onboarded: Boolean = false)
@Serializable data class Technique(val setup: String, val start: String, val steps: List<String>, val breathing: String, val mistakes: List<String>, val sourceIds: List<String>)
@Serializable data class Exercise(val id: String, val name: String, val muscles: String, val equipment: String, val tracking: String = "weight_reps", val technique: Technique, val alternatives: List<String> = emptyList())
@Serializable data class ExerciseSource(val id: String, val title: String, val url: String)
@Serializable data class EquipmentProfile(val id: String, val exerciseId: String, val name: String, val settings: String = "", val stepGrams: Int = 2500)
@Serializable data class ProgramStage(val weekFrom: Int, val weekTo: Int, val sessionsPerWeek: Int, val setsOverride: Int? = null)
@Serializable data class ExerciseTarget(val exerciseId: String, val equipmentId: String, val sets: Int, val minReps: Int, val maxReps: Int, val restSeconds: Int, val weightGrams: Int? = null)
@Serializable data class Program(val id: String = "beginner-fullbody", val name: String = "Всё тело · 8 недель", val version: Long = 1, val week: Int = 1, val weekdays: List<Int> = listOf(1,5), val nextDate: String = "", val targets: List<ExerciseTarget> = emptyList(), val stages: List<ProgramStage> = listOf(ProgramStage(1,1,2,1),ProgramStage(2,2,2,2),ProgramStage(3,4,3,2),ProgramStage(5,8,3,null)))
@Serializable data class ProgramRevision(val id: String, val programId: String, val version: Long, val createdAt: Long, val snapshot: Program)
@Serializable data class SetEntry(val id: String, val index: Int, val weightGrams: Int? = null, val reps: Int? = null, val rir: Int? = null, val technique: String = "unknown", val warmup: Boolean = false, val completed: Boolean = false, val recordedAt: Long = 0)
@Serializable data class SessionExercise(val id: String, val exerciseId: String, val equipmentId: String, val target: ExerciseTarget, val sets: List<SetEntry> = emptyList(), val note: String = "")
@Serializable data class WorkoutSession(val id: String, val status: String = "active", val startedAt: Long, val finishedAt: Long? = null, val note: String = "", val version: Long = 1, val programVersion: Long, val assignedSnapshot: Program, val exercises: List<SessionExercise>)
@Serializable data class BodyWeightEntry(val id: String, val date: String, val grams: Int)
@Serializable data class ReadinessCheckIn(val id: String, val date: String, val fatigue: Int? = null, val availableMinutes: Int? = null)
@Serializable data class CoachConversation(val id: String, val title: String, val createdAt: Long, val exerciseId: String? = null, val sessionId: String? = null, val period: String? = null)
@Serializable data class CoachMessage(val id: String, val conversationId: String, val role: String, val content: String, val createdAt: Long, val state: String = "saved", val requestId: String? = null, val proposalId: String? = null)
@Serializable data class AiInsight(val id: String, val type: String, val text: String, val createdAt: Long, val period: String, val evidenceIds: List<String>, val fingerprint: String, val sourceIds: List<String> = emptyList())
@Serializable data class ProposalOperation(val type: String, val exerciseId: String = "", val previous: String, val value: String)
@Serializable data class PlanProposal(val proposalId: String, val summary: String, val reason: String, val evidenceIds: List<String>, val expectedVersion: Long, val scope: String, val expiresAt: Long, val operations: List<ProposalOperation>, val status: String = "proposed", val sessionId: String? = null)
@Serializable data class PlanChangeLog(val id: String, val proposalId: String, val scope: String, val sessionId: String?, val createdAt: Long, val beforeProgram: Program? = null, val beforeSession: WorkoutSession? = null, val appliedVersion: Long, val rolledBack: Boolean = false)
@Serializable data class LocalState(val profile: Profile = Profile(), val program: Program = Program(), val exercises: List<Exercise> = emptyList(), val equipment: List<EquipmentProfile> = emptyList(), val sessions: List<WorkoutSession> = emptyList(), val bodyWeights: List<BodyWeightEntry> = emptyList(), val checkIns: List<ReadinessCheckIn> = emptyList(), val conversations: List<CoachConversation> = emptyList(), val messages: List<CoachMessage> = emptyList(), val insights: List<AiInsight> = emptyList(), val proposals: List<PlanProposal> = emptyList(), val changeLogs: List<PlanChangeLog> = emptyList(), val revisions: List<ProgramRevision> = emptyList())

fun WorkoutSession.completedWorkSets() = exercises.sumOf { e -> e.sets.count { it.completed && !it.warmup } }
fun WorkoutSession.assignedWorkSets() = exercises.sumOf { it.target.sets }
fun WorkoutSession.isComplete() = exercises.all { e -> (0 until e.target.sets).all { index -> e.sets.any { it.index == index && !it.warmup && it.completed } } }
