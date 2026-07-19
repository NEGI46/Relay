package com.example.relay.data.local

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.OPEN_READONLY
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.IOException

/**
 * Converts a database created by a pre-SQLCipher build into the current encrypted Room file.
 * The old file is read-only and is removed only after the encrypted copy is installed.
 */
internal object PlaintextDatabaseMigration {
    private val SQLITE_HEADER = "SQLite format 3\u0000".encodeToByteArray()
    private val USER_TABLES = listOf(
        "messages",
        "message_deliveries",
        "delivery_receipts",
        "rescue_envelopes",
    )

    fun migrateIfNeeded(
        context: android.content.Context,
        databaseName: String,
        passphrase: ByteArray,
    ) {
        val plaintextFile = context.getDatabasePath(databaseName)
        if (!hasPlaintextHeader(plaintextFile)) return

        val migrationName = "$databaseName.encrypted-migration"
        val encryptedFile = context.getDatabasePath(migrationName)
        cleanup(encryptedFile)

        val plaintext = SQLiteDatabase.openDatabase(plaintextFile.path, null, OPEN_READONLY)
        val encrypted = Room.databaseBuilder(context, RelayDatabase::class.java, migrationName)
            .openHelperFactory(net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase))
            .build()
        try {
            copyUserTables(plaintext, encrypted.openHelper.writableDatabase)
        } finally {
            encrypted.close()
            plaintext.close()
        }

        cleanup(File("${encryptedFile.path}-wal"))
        cleanup(File("${encryptedFile.path}-shm"))
        installEncryptedFile(plaintextFile, encryptedFile)
    }

    private fun hasPlaintextHeader(file: File): Boolean {
        if (!file.isFile) return false
        val header = ByteArray(SQLITE_HEADER.size)
        return runCatching {
            file.inputStream().use { it.read(header) }
            header.contentEquals(SQLITE_HEADER)
        }.getOrDefault(false)
    }

    private fun copyUserTables(plaintext: SQLiteDatabase, encrypted: SupportSQLiteDatabase) {
        encrypted.beginTransaction()
        try {
            for (table in USER_TABLES) {
                if (!tableExists(plaintext, table)) continue
                val targetColumns = tableColumns(encrypted, table)
                if (targetColumns.isEmpty()) continue
                plaintext.rawQuery("SELECT * FROM `$table`", null).use { cursor ->
                    val columnsToCopy = cursor.columnNames.filter { it in targetColumns }
                    while (cursor.moveToNext()) {
                        val values = ContentValues(columnsToCopy.size)
                        for (column in columnsToCopy) {
                            putCursorValue(values, column, cursor, cursor.getColumnIndexOrThrow(column))
                        }
                        encrypted.insert(table, SQLiteDatabase.CONFLICT_NONE, values)
                    }
                }
            }
            encrypted.setTransactionSuccessful()
        } finally {
            encrypted.endTransaction()
        }
    }

    private fun tableExists(database: SQLiteDatabase, table: String): Boolean =
        database.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(table),
        ).use { it.moveToFirst() }

    private fun tableColumns(database: SupportSQLiteDatabase, table: String): Set<String> =
        database.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }

    private fun putCursorValue(values: ContentValues, column: String, cursor: Cursor, index: Int) {
        when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> values.putNull(column)
            Cursor.FIELD_TYPE_INTEGER -> values.put(column, cursor.getLong(index))
            Cursor.FIELD_TYPE_FLOAT -> values.put(column, cursor.getDouble(index))
            Cursor.FIELD_TYPE_BLOB -> values.put(column, cursor.getBlob(index))
            else -> values.put(column, cursor.getString(index))
        }
    }

    private fun installEncryptedFile(plaintext: File, encrypted: File) {
        val backup = File("${plaintext.path}.plaintext-migration")
        val plaintextWal = File("${plaintext.path}-wal")
        val plaintextShm = File("${plaintext.path}-shm")
        val backupWal = File("${backup.path}-wal")
        val backupShm = File("${backup.path}-shm")
        cleanup(backup); cleanup(backupWal); cleanup(backupShm)
        try {
            rename(plaintext, backup)
            if (plaintextWal.isFile) rename(plaintextWal, backupWal)
            if (plaintextShm.isFile) rename(plaintextShm, backupShm)
            rename(encrypted, plaintext)
            cleanup(backup); cleanup(backupWal); cleanup(backupShm)
        } catch (error: Exception) {
            cleanup(encrypted)
            cleanup(File("${encrypted.path}-wal"))
            cleanup(File("${encrypted.path}-shm"))
            if (!plaintext.exists() && backup.isFile) rename(backup, plaintext)
            if (!plaintextWal.exists() && backupWal.isFile) rename(backupWal, plaintextWal)
            if (!plaintextShm.exists() && backupShm.isFile) rename(backupShm, plaintextShm)
            throw error
        }
    }

    private fun rename(from: File, to: File) {
        if (!from.renameTo(to)) throw IOException("database migration rename failed")
    }

    private fun cleanup(file: File) {
        if (file.exists() && !file.delete()) throw IOException("database migration cleanup failed")
    }
}
