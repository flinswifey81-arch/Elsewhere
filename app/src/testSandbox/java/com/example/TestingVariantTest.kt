package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TestingVariantTest {
    @Test
    fun testingVariantHasSeparateIdentityAndProductionBackupImportCompatibility() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals("com.aistudio.elsewhere.abwzy.v15.test", BuildConfig.APPLICATION_ID)
        assertEquals("Elsewhere Test", context.getString(R.string.app_name))
        assertEquals(
            "com.aistudio.elsewhere.abwzy.v15",
            BuildConfig.BACKUP_IMPORT_SOURCE_APPLICATION_ID
        )
    }
}
