package com.example

import com.example.domain.generation.ReplyNotificationPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyNotificationPolicyTest {
    @Test fun onlySavedRepliesWhenChatIsNotVisibleCanAlert() {
        assertTrue(ReplyNotificationPolicy.shouldAlert(
            saved = true, chatVisible = false, appVisible = true, permissionGranted = true))
        assertTrue(ReplyNotificationPolicy.shouldAlert(
            saved = true, chatVisible = true, appVisible = false, permissionGranted = true))
        assertFalse(ReplyNotificationPolicy.shouldAlert(
            saved = true, chatVisible = true, appVisible = true, permissionGranted = true))
        assertFalse(ReplyNotificationPolicy.shouldAlert(
            saved = false, chatVisible = false, appVisible = false, permissionGranted = true))
        assertFalse(ReplyNotificationPolicy.shouldAlert(
            saved = true, chatVisible = false, appVisible = false, permissionGranted = false))
    }
}
