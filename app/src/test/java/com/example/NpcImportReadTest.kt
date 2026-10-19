package com.example

import com.example.ui.screens.readNpcJsonBounded
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NpcImportReadTest {
    @Test
    fun boundedReaderAcceptsExactLimitAndRejectsOneByteOver() {
        assertEquals("12345", readNpcJsonBounded(ByteArrayInputStream("12345".toByteArray()), 5))

        val oversized = runCatching {
            readNpcJsonBounded(ByteArrayInputStream("123456".toByteArray()), 5)
        }
        assertTrue(oversized.isFailure)
        assertTrue(oversized.exceptionOrNull()?.message.orEmpty().contains("too large"))
    }
}
