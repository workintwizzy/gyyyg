package ru.dmitriy.workouts.domain

import ru.dmitriy.workouts.data.WorkoutRepository
import javax.inject.Inject

class ApplyProposalUseCase @Inject constructor(private val repository:WorkoutRepository) {
    suspend operator fun invoke(proposalId:String)=repository.applyProposal(proposalId)
    suspend fun rollback(changeLogId:String)=repository.rollback(changeLogId)
}
