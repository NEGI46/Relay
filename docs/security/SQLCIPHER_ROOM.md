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

The current repository keeps database construction in
`app/src/main/.../RelayApplication.kt`; the SQLCipher factory must be inserted
there as one isolated change after the exact dependency/API version is pinned.
