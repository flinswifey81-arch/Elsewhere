package com.example.domain.repository

sealed interface ProfileDeletionResult {
    data class Deleted(
        val affectedChatCount: Int,
        val historyOnlyChatCount: Int
    ) : ProfileDeletionResult
    data object NotFound : ProfileDeletionResult
}

internal fun com.example.data.ProfileDeleteOutcome.toProfileDeletionResult(): ProfileDeletionResult =
    if (!existed) {
        ProfileDeletionResult.NotFound
    } else {
        ProfileDeletionResult.Deleted(
            affectedChatCount = affectedChatCount,
            historyOnlyChatCount = historyOnlyChatCount
        )
    }
