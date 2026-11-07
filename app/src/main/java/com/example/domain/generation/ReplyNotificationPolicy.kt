package com.example.domain.generation

/** A reply alert is about a persisted result, never a stream close or partial text. */
object ReplyNotificationPolicy {
    fun shouldAlert(
        saved: Boolean,
        chatVisible: Boolean,
        appVisible: Boolean,
        permissionGranted: Boolean
    ): Boolean = saved && (!chatVisible || !appVisible) && permissionGranted
}
