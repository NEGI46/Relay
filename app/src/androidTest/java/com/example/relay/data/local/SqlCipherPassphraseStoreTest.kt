package com.example.relay.data.local

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertContentEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqlCipherPassphraseStoreTest {
    @Test
    fun passphraseSurvivesStoreRecreation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val first = SqlCipherPassphraseStore(context).loadOrCreate()
        val second = SqlCipherPassphraseStore(context).loadOrCreate()
        assertContentEquals(first, second)
    }
}
