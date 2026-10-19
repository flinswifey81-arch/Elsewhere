package com.example.data

data class ProfileDeleteOutcome(
    val existed: Boolean,
    val affectedChatCount: Int = 0,
    val historyOnlyChatCount: Int = 0
)
