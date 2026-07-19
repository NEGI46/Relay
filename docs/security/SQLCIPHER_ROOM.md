# SQLCipher Room migration contract

The Android Room database contains encrypted rescue envelopes and delivery
metadata. Production builds must use the maintained `sqlcipher-android`
artifact through Room's `openHelperFactory`, with the passphrase derived from
an Android Keystore-protected secret. Do not store the passphrase in a
resource, SharedPreferences, logs, or backup metadata.

Migration acceptance criteria:

- a fresh database cannot be opened with the plaintext SQLite driver;
- process restart reopens the database with the same Keystore-derived key;
- missing or invalid key material fails closed and leaves the encrypted DB;
- an in-memory Room test remains available for repository unit tests;
- backups use the Litestream + age flow and never copy the live DB directly.

The current repository constructs the database in
`app/src/main/.../RelayApplication.kt` through
`PlaintextDatabaseMigration` and `SupportOpenHelperFactory`. A pre-SQLCipher
database is opened read-only, copied into a new encrypted Room database, and
removed only after the encrypted file has been installed at the original path.
The migration contract is covered by
`app/src/androidTest/.../PlaintextDatabaseMigrationTest.kt`; the device run
must still be executed on a sufficiently provisioned emulator or phone.
