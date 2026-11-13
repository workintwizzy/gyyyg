package ru.dmitriy.workouts.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.dmitriy.workouts.data.local.WorkoutDatabase
import ru.dmitriy.workouts.domain.*

@RunWith(AndroidJUnit4::class)
class WorkoutRoomTest {
    private lateinit var db:WorkoutDatabase
    private lateinit var repo:WorkoutRepository
    @Before fun before()=runBlocking { db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),WorkoutDatabase::class.java).build();repo=WorkoutRepository(db);repo.seed() }
    @After fun after(){ db.close() }
    @Test fun startSaveRestoreUndoAndPartialFinish()=runBlocking {
        val id=repo.startWorkout();assertEquals(id,repo.startWorkout())
        repo.saveSet(id,"leg-press",0,20_000,12,3,"good")
        repo.saveSet(id,"leg-press",0,20_000,12,3,"good")
        val session=repo.snapshot().sessions.single();assertEquals(1,session.completedWorkSets());assertEquals(1,db.dao().sets().size)
        val restored=WorkoutRepository(db).snapshot().sessions.single();assertEquals(session,restored)
        assertTrue(runCatching { repo.finishWorkout(id,false) }.isFailure)
        repo.unmarkSet(session.exercises.first().sets.single().id);assertEquals(0,repo.snapshot().sessions.single().completedWorkSets())
        repo.finishWorkout(id,true);assertEquals("partial",repo.snapshot().sessions.single().status)
    }
    @Test fun warmupSeparateFromWorkAndSeedIdempotent()=runBlocking {
        repo.seed();assertEquals(10,repo.snapshot().exercises.size)
        val id=repo.startWorkout();repo.saveSet(id,"leg-press",0,5000,10,4,"good",true);repo.saveSet(id,"leg-press",0,20000,12,3,"good")
        val session=repo.snapshot().sessions.single();assertEquals(1,session.completedWorkSets());assertEquals(2,db.dao().sets().size)
    }
    @Test fun restDefaultsChangeWithoutRewritingRecordedSetsOrAssignment()=runBlocking {
        val id=repo.startWorkout();repo.saveSet(id,"leg-press",0,20_000,12,3,"good")
        val before=repo.snapshot().sessions.single()
        repo.updateSessionRest(id,"leg-press",90,true)
        val after=repo.snapshot()
        assertEquals(before.exercises.first().sets,after.sessions.single().exercises.first().sets)
        assertEquals(150,after.sessions.single().assignedSnapshot.targets.first().restSeconds)
        assertEquals(90,after.sessions.single().exercises.first().target.restSeconds)
        assertEquals(90,after.program.targets.first().restSeconds)
        assertTrue(after.sessions.single().version>before.version)
    }
    @Test fun templatesAndManualCorrectionPreserveSnapshot()=runBlocking {
        val id=repo.startWorkout();repo.saveSet(id,"leg-press",0,20_000,12,3,"good")
        val initial=repo.snapshot();repo.updateProgram(initial.program.copy(targets=initial.program.targets.map { it.copy(sets=3) }))
        assertEquals(1,repo.snapshot().sessions.single().assignedSnapshot.week)
        assertEquals(1,repo.snapshot().sessions.single().assignedSnapshot.targets.first().sets)
        assertEquals(1,repo.snapshot().sessions.single().exercises.first().target.sets)
        assertEquals(3,repo.snapshot().program.targets.first().sets)
        repo.saveSet(id,"leg-press",0,22_500,10,3,"good")
        assertEquals(1,db.dao().sets().size);assertEquals(22_500,repo.snapshot().sessions.single().exercises.first().sets.single().weightGrams)
    }
    private suspend fun proposedRest(id:String):PlanProposal {
        val s=repo.snapshot().sessions.first { it.id==id }
        return PlanProposal("proposal","Отдых","Осталось мало времени",emptyList(),s.version,"session",System.currentTimeMillis()+60_000,listOf(ProposalOperation("rest","leg-press","150","120")),sessionId=id)
    }
    @Test fun proposalApplicationIsAtomicIdempotentAndRollbackVersioned()=runBlocking {
        val id=repo.startWorkout();val p=proposedRest(id);repo.saveProposal(p);repo.applyProposal(p.proposalId);repo.applyProposal(p.proposalId)
        var state=repo.snapshot();assertEquals(120,state.sessions.single().exercises.first().target.restSeconds);assertEquals(1,state.changeLogs.size)
        repo.rollback(state.changeLogs.single().id);state=repo.snapshot();assertEquals(150,state.sessions.single().exercises.first().target.restSeconds)
        assertTrue(state.changeLogs.single().rolledBack)
    }
    @Test fun rollbackCannotOverwriteLaterManualWorkAndStaleApplyDoesNothing()=runBlocking {
        val id=repo.startWorkout();val p=proposedRest(id);repo.saveProposal(p);repo.saveSet(id,"leg-press",0,20_000,12,3,"good")
        assertTrue(runCatching { repo.applyProposal(p.proposalId) }.isFailure);assertEquals(150,repo.snapshot().sessions.single().exercises.first().target.restSeconds)
        val p2=proposedRest(id).copy(proposalId="p2");repo.saveProposal(p2);repo.applyProposal("p2");val log=repo.snapshot().changeLogs.single()
        repo.saveSet(id,"chest-press",0,10_000,10,3,"good")
        assertTrue(runCatching { repo.rollback(log.id) }.isFailure);assertEquals(120,repo.snapshot().sessions.single().exercises.first().target.restSeconds)
    }
    @Test fun rejectedProposalAndBrokenImportPreserveDatabase()=runBlocking {
        val id=repo.startWorkout();val p=proposedRest(id);repo.saveProposal(p);repo.rejectProposal(p.proposalId)
        assertTrue(runCatching { repo.applyProposal(p.proposalId) }.isFailure)
        val before=repo.snapshot();assertTrue(runCatching { repo.replaceAll(before.copy(equipment=emptyList())) }.isFailure);assertEquals(before,repo.snapshot())
    }
    @Test fun stageCannotSkipUnfinishedWeekAndEquipmentNeverCarriesWeight()=runBlocking {
        val old=repo.snapshot().program
        assertTrue(runCatching { repo.updateProgram(ProgramRules.forWeek(old,5)) }.isFailure)
        assertTrue(runCatching { repo.updateProgram(ProgramRules.forWeek(old,2)) }.isFailure)
        val id=repo.startWorkout();val custom=EquipmentProfile("second-leg-press","leg-press","Второй жим ногами",stepGrams=5000)
        repo.saveEquipment(custom);repo.selectSessionEquipment(id,"leg-press",custom.id)
        val e=repo.snapshot().sessions.single().exercises.first();assertEquals(custom.id,e.equipmentId);assertNull(e.target.weightGrams)
        repo.saveSet(id,"leg-press",0,15_000,10,3,"good")
        assertTrue(runCatching { repo.selectSessionEquipment(id,"leg-press","eq-leg-press") }.isFailure)
    }
}
