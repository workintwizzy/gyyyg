package ru.dmitriy.workouts.domain

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class TrainingRulesTest {
    private val engine=ProgressionEngine()
    private val equipment=ExerciseCatalog.equipment.first { it.exerciseId=="leg-press" }
    private fun program(sets:Int=2)=ExerciseCatalog.initialProgram().copy(targets=listOf(ExerciseCatalog.initialProgram().targets.first().copy(sets=sets)))
    private fun session(id:String,whenMs:Long=System.currentTimeMillis(),status:String="completed",sets:Int=2,weight:Int=20_000,rir:Int?=2,technique:String="good",reps:Int=15,equipmentId:String=equipment.id):WorkoutSession {
        val target=program(sets).targets.first().copy(equipmentId=equipmentId)
        return WorkoutSession(id,status,whenMs-1,if(status=="active")null else whenMs,programVersion=1,assignedSnapshot=program(sets),exercises=listOf(SessionExercise("$id:leg-press","leg-press",equipmentId,target,(0 until sets).map { SetEntry("$id:$it",it,weight,reps,rir,technique,completed=true,recordedAt=whenMs) })))
    }
    private fun state(sessions:List<WorkoutSession> = emptyList())=LocalState(program=program(),exercises=ExerciseCatalog.exercises,equipment=ExerciseCatalog.equipment,sessions=sessions)
    @Test fun stageVolumesAndFrequencyMatchEightWeeks() {
        val p=ExerciseCatalog.initialProgram()
        assertTrue(ProgramRules.forWeek(p,1).targets.all { it.sets==1 })
        assertTrue(ProgramRules.forWeek(p,2).targets.all { it.sets==2 })
        assertEquals(listOf(1,3,5),ProgramRules.forWeek(p,3).weekdays)
        assertEquals(listOf(3,3,3,2,2,2,2),ProgramRules.forWeek(p,5).targets.map { it.sets })
        assertEquals(1,p.week)
    }
    @Test fun twoCompleteComparableSessionsPermitOneEquipmentStep() {
        val result=engine.evaluate("leg-press",equipment,listOf(session("a",100),session("b",200)))
        assertTrue(result.allowed);assertEquals(22_500,result.nextWeightGrams);assertEquals(listOf("b","a"),result.evidenceIds)
    }
    @Test fun oneSessionNeverPermitsIncrease() { assertFalse(engine.evaluate("leg-press",equipment,listOf(session("a"))).allowed) }
    @Test fun changedVolumeResetsSequence() { assertFalse(engine.evaluate("leg-press",equipment,listOf(session("a",100),session("b",200,sets=3))).allowed) }
    @Test fun changedCurrentTargetCannotReuseSuccessfulOldVolume() {
        val history=listOf(session("a",100),session("b",200))
        val changed=program(3).targets.single().copy(weightGrams=20_000)
        assertFalse(engine.evaluate("leg-press",equipment,history,changed).allowed)
        val s=state(history).copy(program=program(3).copy(targets=listOf(changed)))
        val p=PlanProposal("p","Прибавить","Два занятия",listOf("a","b"),1,"program",System.currentTimeMillis()+60_000,listOf(ProposalOperation("weight","leg-press","20000","22500")))
        assertFalse(ProposalValidator(engine).validate(p,s).isEmpty())
        assertFalse(engine.evaluate("leg-press",equipment,history,changed.copy(sets=2,minReps=8,maxReps=12)).allowed)
        assertFalse(engine.evaluate("leg-press",equipment,history,changed.copy(sets=2,weightGrams=22_500)).allowed)
    }
    @Test fun differingMachinesAndLoadsCannotBeMixed() {
        assertFalse(engine.evaluate("leg-press",equipment,listOf(session("a",100),session("b",200,equipmentId="different"))).allowed)
        assertFalse(engine.evaluate("leg-press",equipment,listOf(session("a",100),session("b",200,weight=25_000))).allowed)
    }
    @Test fun weightCannotShareBatchWithChangedVolumeOrRepRangeButRestIsAllowed() {
        val history=listOf(session("a",100),session("b",200))
        val input=state(history).copy(program=program().copy(targets=program().targets.map { it.copy(weightGrams=20_000) }))
        val weight=ProposalOperation("weight","leg-press","20000","22500")
        val p=PlanProposal("p","Прибавка","Условия выполнены",listOf("a","b"),1,"program",System.currentTimeMillis()+60_000,listOf(weight))
        val validator=ProposalValidator(engine)
        assertTrue(validator.validate(p,input).isEmpty())
        for(other in listOf(ProposalOperation("reduceSets","leg-press","2","1"),ProposalOperation("reps","leg-press","10-15","12-15"))) {
            assertFalse(validator.validate(p.copy(operations=listOf(weight,other)),input).isEmpty())
            assertFalse(validator.validate(p.copy(operations=listOf(other,weight)),input).isEmpty())
        }
        assertTrue(validator.validate(p.copy(operations=listOf(weight,ProposalOperation("rest","leg-press","150","120"))),input).isEmpty())
    }
    @Test fun unknownEffortOrTechniqueDoesNotPermitIncrease() {
        assertFalse(engine.evaluate("leg-press",equipment,listOf(session("a",100),session("b",200,rir=null))).allowed)
        assertFalse(engine.evaluate("leg-press",equipment,listOf(session("a",100),session("b",200,technique="unknown"))).allowed)
        assertFalse(engine.evaluate("leg-press",equipment,listOf(session("a",100),session("b",200,rir=1))).allowed)
    }
    @Test fun latestFailedCompleteSessionPreventsSkippingBackToOldSuccesses() { assertFalse(engine.evaluate("leg-press",equipment,listOf(session("a",100),session("b",200),session("c",300,reps=12))).allowed) }
    @Test fun partialAndWarmupCannotCreateSuccess() {
        assertFalse(engine.evaluate("leg-press",equipment,listOf(session("a",100),session("b",200,status="partial"))).allowed)
        val a=session("a",100);val warmup=a.exercises.first().sets.first().copy(id="warmup",warmup=true,weightGrams=5000,rir=0,technique="poor")
        val withWarmup=a.copy(exercises=a.exercises.map { it.copy(sets=it.sets+warmup) })
        assertTrue(engine.evaluate("leg-press",equipment,listOf(withWarmup,session("b",200))).allowed)
        assertEquals(2,withWarmup.completedWorkSets())
    }
    @Test fun contextOmitsNotesAndContainsOnlyBoundedRelevantData() {
        val sessions=(0..15).map { session("session-$it",System.currentTimeMillis()-it*86_400_000L).copy(note="PRIVATE_NOTE") }
        val input=state(sessions).copy(profile=Profile(preferences="PRIVATE_PREFERENCE"),equipment=ExerciseCatalog.equipment.map { it.copy(settings="PRIVATE_MACHINE_NOTE") })
        val result=ContextBuilder(engine).build(input,"period",exerciseId="leg-press")
        assertEquals(12,result.evidenceIds.size)
        assertFalse(result.payload.toString().contains("PRIVATE_"))
        assertFalse(result.payload.toString().contains("accessToken"))
        assertEquals(64,result.fingerprint.length)
        assertEquals(result.fingerprint,ContextBuilder(engine).build(input,"period",exerciseId="leg-press").fingerprint)
        assertTrue(ContextBuilder(engine).build(input,"period",exerciseId="leg-press",includeNotes=true).payload.toString().contains("PRIVATE_NOTE"))
    }
    @Test fun chosenWorkoutKeepsOwnWeekAfterFuturePlanChanges() {
        val old = session("earlier", status="active")
        val future = ProgramRules.forWeek(program(),5)
        val input = state(listOf(old)).copy(program=future)
        val selected = ContextBuilder(engine).build(input,"chat",sessionId=old.id)
        assertEquals("1",selected.payload.getValue("week").toString())
        assertEquals(listOf("1","5"),selected.payload.getValue("weekdays").jsonArray.map { it.toString() })
        assertEquals("2",selected.payload.getValue("targets").jsonArray.first().jsonObject.getValue("sets").toString())
        val upcoming = ContextBuilder(engine).build(input,"chat")
        assertEquals("5",upcoming.payload.getValue("week").toString())
        assertEquals(listOf("1","3","5"),upcoming.payload.getValue("weekdays").jsonArray.map { it.toString() })
    }
    private fun proposal(session:WorkoutSession,op:ProposalOperation)=PlanProposal("p","Сократить","Причина",emptyList(),session.version,"session",System.currentTimeMillis()+60_000,listOf(op),sessionId=session.id)
    @Test fun proposalRejectsStaleVersionAndUnknownExercise() {
        val s=session("a",status="active");val v=ProposalValidator(engine)
        val p=proposal(s,ProposalOperation("rest","leg-press","150","120"))
        assertTrue(v.validate(p,state(listOf(s))).isEmpty())
        assertFalse(v.validate(p.copy(expectedVersion=0),state(listOf(s))).isEmpty())
        assertFalse(v.validate(p.copy(operations=listOf(ProposalOperation("rest","unknown","150","120"))),state(listOf(s))).isEmpty())
    }
    @Test fun proposalRejectsReplacingStartedExerciseAndCuttingCompletedSets() {
        val s=session("a",status="active");val v=ProposalValidator(engine)
        assertFalse(v.validate(proposal(s,ProposalOperation("replace","leg-press","leg-press","seated-curl")),state(listOf(s))).isEmpty())
        assertFalse(v.validate(proposal(s,ProposalOperation("reduceSets","leg-press","2","1")),state(listOf(s))).isEmpty())
    }
    @Test fun proposalRejectsFabricatedWeightIncreaseAndExpiredRequests() {
        val s=session("a",status="active").copy(exercises=session("a",status="active").exercises.map { it.copy(sets=emptyList()) })
        val v=ProposalValidator(engine)
        assertFalse(v.validate(proposal(s,ProposalOperation("weight","leg-press","null","50000")),state(listOf(s))).isEmpty())
        assertFalse(v.validate(proposal(s,ProposalOperation("rest","leg-press","150","120")).copy(expiresAt=1),state(listOf(s))).isEmpty())
    }
    @Test fun manualTemplateEditCannotMutateExistingSnapshot() {
        val original=program();val saved=session("a").copy(assignedSnapshot=original)
        val changed=original.copy(targets=original.targets.map { it.copy(sets=3) },version=2)
        assertEquals(2,saved.assignedSnapshot.targets.first().sets);assertEquals(3,changed.targets.first().sets)
    }
    @Test fun importRejectsBrokenReferencesUnknownCatalogAndInvalidNumbers() {
        val valid=state(listOf(session("a")))
        ImportValidator.validate(valid)
        fun rejects(s:LocalState) { assertTrue(runCatching { ImportValidator.validate(s) }.isFailure) }
        rejects(valid.copy(equipment=emptyList()))
        rejects(valid.copy(exercises=valid.exercises.drop(1)))
        rejects(valid.copy(profile=Profile(heightCm=-1)))
        rejects(valid.copy(sessions=valid.sessions+valid.sessions))
        rejects(valid.copy(bodyWeights=listOf(BodyWeightEntry("body","2026-13-44",65000))))
    }
    @Test fun restDaysIncludeWrapAroundSundayMonday() { assertTrue(ProgramRules.restDayWarning(listOf(7,1)));assertFalse(ProgramRules.restDayWarning(listOf(1,3,5))) }
}
