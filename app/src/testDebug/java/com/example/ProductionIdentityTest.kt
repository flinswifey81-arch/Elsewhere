package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProductionIdentityTest {
    @Test
    fun productionVariantUsesV15IdentityAndLegacyBackupBridge() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals("com.aistudio.elsewhere.abwzy.v15", BuildConfig.APPLICATION_ID)
        assertEquals("1.5", BuildConfig.VERSION_NAME)
        assertEquals(1, BuildConfig.VERSION_CODE)
        assertEquals("Elsewhere v1.5", context.getString(R.string.app_name))
        assertEquals(
            "com.aistudio.elsewhere.abwzy",
            BuildConfig.BACKUP_IMPORT_SOURCE_APPLICATION_ID
        )
    }
}
