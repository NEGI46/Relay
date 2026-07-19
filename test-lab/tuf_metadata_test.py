import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "scripts" / "verify-tuf-metadata-chain.py"


class TestTufMetadataContract(unittest.TestCase):
    def make_set(self):
        directory = Path(tempfile.mkdtemp())
        artifact = directory / "relay.zip"
        artifact.write_bytes(b"relay test artifact")
        future = (datetime.now(timezone.utc) + timedelta(days=2)).isoformat().replace("+00:00", "Z")

        def envelope(role, version, **extra):
            value = {"_type": role, "spec_version": "1.0.31", "version": version, "expires": future}
            value.update(extra)
            return {"signatures": [], "signed": value}

        root = envelope("root", 1, keys={"k": {"keytype": "rsa", "scheme": "rsassa-pss-sha256", "keyval": {"public": "UNSIGNED_MANIFEST_ONLY"}}}, roles={role: {"keyids": ["k"], "threshold": 1} for role in ("root", "targets", "snapshot", "timestamp")})
        targets = envelope("targets", 1, targets={"relay.zip": {"length": artifact.stat().st_size, "hashes": {"sha256": hashlib.sha256(artifact.read_bytes()).hexdigest()}}})
        (directory / "root.json").write_text(json.dumps(root, separators=(",", ":")), encoding="utf-8")
        (directory / "targets.json").write_text(json.dumps(targets, separators=(",", ":")), encoding="utf-8")

        def link(name):
            raw = (directory / name).read_bytes()
            return {"version": json.loads(raw)["signed"]["version"], "length": len(raw), "hashes": {"sha256": hashlib.sha256(raw).hexdigest()}}

        snapshot = envelope("snapshot", 1, meta={"root.json": link("root.json"), "targets.json": link("targets.json")})
        (directory / "snapshot.json").write_text(json.dumps(snapshot, separators=(",", ":")), encoding="utf-8")
        timestamp = envelope("timestamp", 1, meta={"snapshot.json": link("snapshot.json")})
        (directory / "timestamp.json").write_text(json.dumps(timestamp, separators=(",", ":")), encoding="utf-8")
        return directory, artifact

    def run_command(self, directory, artifact=None, *extra):
        command = [sys.executable, str(VERIFIER), str(directory), *extra]
        if artifact:
            command += ["--artifact", str(artifact)]
        return subprocess.run(command, capture_output=True, text=True)

    def test_valid_chain_and_artifact(self):
        directory, artifact = self.make_set()
        result = self.run_command(directory, artifact)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_detects_target_hash_tampering(self):
        directory, artifact = self.make_set()
        artifact.write_bytes(b"tampered")
        result = self.run_command(directory, artifact)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("artifact", result.stderr)

    def test_rejects_path_traversal(self):
        directory, _ = self.make_set()
        path = directory / "targets.json"
        value = json.loads(path.read_text(encoding="utf-8"))
        target = value["signed"]["targets"].pop("relay.zip")
        value["signed"]["targets"]["../relay.zip"] = target
        path.write_text(json.dumps(value, separators=(",", ":")), encoding="utf-8")
        result = self.run_command(directory)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("escapes", result.stderr)

    def test_detects_snapshot_link_mismatch(self):
        directory, _ = self.make_set()
        path = directory / "timestamp.json"
        value = json.loads(path.read_text(encoding="utf-8"))
        value["signed"]["meta"]["snapshot.json"]["version"] = 99
        path.write_text(json.dumps(value, separators=(",", ":")), encoding="utf-8")
        result = self.run_command(directory)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("timestamp snapshot version", result.stderr)

    def test_rejects_role_rollback_from_state(self):
        directory, _ = self.make_set()
        state = directory / "accepted-versions.json"
        state.write_text(json.dumps({"schema": 1, "versions": {role: 2 for role in ("root", "targets", "snapshot", "timestamp")}}), encoding="utf-8")
        result = self.run_command(directory, None, "--state-path", str(state))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("rollback detected", result.stderr)


if __name__ == "__main__":
    unittest.main()
