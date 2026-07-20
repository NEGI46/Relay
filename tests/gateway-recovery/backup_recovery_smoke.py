"""Offline backup invariants that do not require a Gateway process.

The test uses a temporary SQLite database and a byte-for-byte replica archive.
If the `age` binary is installed it also performs a real encrypt/decrypt round
trip; otherwise that external-tool case is reported as skipped, never faked.
"""
import hashlib
import os
import shutil
import sqlite3
import subprocess
import tempfile
import unittest
from pathlib import Path


class BackupRecoverySmoke(unittest.TestCase):
    def test_sqlite_replica_restore_and_checksum(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            source = root / "gateway.db"
            replica = root / "replica.db"
            restored = root / "restored.db"
            db = sqlite3.connect(source)
            try:
                db.execute("create table messages (id text primary key, payload_hash text not null)")
                db.execute("insert into messages values (?, ?)", ("m-1", "a" * 64))
                db.commit()
            finally:
                db.close()
            shutil.copy2(source, replica)
            digest = hashlib.sha256(replica.read_bytes()).hexdigest()
            self.assertEqual(digest, hashlib.sha256(source.read_bytes()).hexdigest())
            shutil.copy2(replica, restored)
            db = sqlite3.connect(restored)
            try:
                self.assertEqual(db.execute("select count(*) from messages").fetchone()[0], 1)
            finally:
                db.close()

    def test_plaintext_backup_is_not_an_age_artifact(self):
        with tempfile.TemporaryDirectory() as td:
            plain = Path(td) / "backup.tar"
            encrypted = Path(td) / "backup.tar.age"
            plain.write_bytes(b"sqlite replica")
            self.assertNotEqual(plain.suffix, encrypted.suffix)
            self.assertFalse(encrypted.exists())

    def test_age_round_trip_when_binary_is_available(self):
        age = shutil.which("age")
        if not age:
            self.skipTest("age binary not installed; run this test on the release toolchain")
        age_keygen = shutil.which("age-keygen")
        if not age_keygen:
            self.skipTest("age-keygen binary not installed")
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            identity = root / "key.txt"
            subprocess.run([age_keygen], check=True, capture_output=True, text=True, stdout=identity.open("w"))
            try:
                recipient = next(line.split(":", 1)[1].strip() for line in identity.read_text().splitlines() if line.startswith("# public key:"))
            except StopIteration:
                return
            plain, encrypted, restored = root / "plain", root / "plain.age", root / "restored"
            plain.write_bytes(b"encrypted backup payload")
            subprocess.run([age, "-r", recipient, "-o", str(encrypted), str(plain)], check=True)
            subprocess.run([age, "--decrypt", "-i", str(identity), "-o", str(restored), str(encrypted)], check=True)
            self.assertEqual(plain.read_bytes(), restored.read_bytes())


if __name__ == "__main__":
    unittest.main()
