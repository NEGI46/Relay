package com.example.relay.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SqlCipherPassphraseStoreTest {
    @Test
    fun passphraseSurvivesStoreRecreation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val first = SqlCipherPassphraseStore(context).loadOrCreate()
        val second = SqlCipherPassphraseStore(context).loadOrCreate()
        assertArrayEquals(first, second)
    }

    @Test
    fun roomDatabaseDoesNotExposePlaintextSqliteHeader() {
        System.loadLibrary("sqlcipher")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "sqlcipher-contract-${UUID.randomUUID()}.db"
        val databaseFile = context.getDatabasePath(name)
        val passphrase = ByteArray(32) { (it + 1).toByte() }
        val database = Room.databaseBuilder(context, RelayDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .build()
        try {
            database.openHelper.writableDatabase
            val header = ByteArray(16)
            databaseFile.inputStream().use { it.read(header) }
            assertFalse(header.contentEquals("SQLite format 3\u0000".encodeToByteArray()))
        } finally {
            database.close()
            File(databaseFile.path).delete()
            File(databaseFile.path + "-shm").delete()
            File(databaseFile.path + "-wal").delete()
        }
    }
}
