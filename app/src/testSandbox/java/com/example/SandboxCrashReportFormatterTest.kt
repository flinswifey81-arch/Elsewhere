package com.example

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxCrashReportFormatterTest {
    @Test
    fun reportContainsOnlyExceptionTypesAndCodeLocations() {
        val privateText = "PRIVATE prompt api-key roleplay message"
        val error = IllegalStateException(privateText).apply {
            stackTrace = arrayOf(StackTraceElement("com.example.ChatDetailViewModel", "sendMessage", "ChatDetailViewModel.kt", 812))
            initCause(IllegalArgumentException("PRIVATE character data"))
        }

        val report = SandboxCrashReportFormatter.format(error)

        assertTrue(report.contains("java.lang.IllegalStateException"))
        assertTrue(report.contains("ChatDetailViewModel.kt:812"))
        assertTrue(report.contains("java.lang.IllegalArgumentException"))
        assertFalse(report.contains("PRIVATE"))
        assertFalse(report.contains("api-key"))
    }
}
