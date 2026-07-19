#!/usr/bin/env python3
"""Verify the unsigned integrity relationships in a TUF metadata set.

Cryptographic signature verification remains the responsibility of the TUF/cosign
tooling.  This small, dependency-free verifier makes the metadata graph safe to
consume in CI and in offline recovery environments: it checks role structure,
expiry, snapshot/timestamp hashes and versions, and target path confinement.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any

ROLES = ("root", "targets", "snapshot", "timestamp")
SHA256 = re.compile(r"^[0-9a-f]{64}$")


class MetadataError(ValueError):
    pass


def load(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, json.JSONDecodeError) as exc:
        raise MetadataError(f"cannot read {path.name}: {exc}") from exc
    if not isinstance(value, dict) or not isinstance(value.get("signed"), dict):
        raise MetadataError(f"{path.name} is not a metadata envelope")
    if not isinstance(value.get("signatures"), list):
        raise MetadataError(f"{path.name} signatures must be an array")
    return value


def version(signed: dict[str, Any], role: str) -> int:
    value = signed.get("version")
    if not isinstance(value, int) or isinstance(value, bool) or value < 1:
        raise MetadataError(f"{role}.json has an invalid version")
    return value


def expiry(signed: dict[str, Any], role: str, allow_expired: bool) -> None:
    value = signed.get("expires")
    if not isinstance(value, str):
        raise MetadataError(f"{role}.json has no expiry")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise MetadataError(f"{role}.json has an invalid expiry") from exc
    if parsed.tzinfo is None:
        raise MetadataError(f"{role}.json expiry must include a timezone")
    if not allow_expired and parsed.astimezone(timezone.utc) <= datetime.now(timezone.utc):
        raise MetadataError(f"{role}.json metadata expired")


def meta_entry(value: Any, name: str) -> tuple[int, int, str]:
    if not isinstance(value, dict):
        raise MetadataError(f"{name} metadata entry must be an object")
    v, length, hashes = value.get("version"), value.get("length"), value.get("hashes")
    digest = hashes.get("sha256") if isinstance(hashes, dict) else None
    if not isinstance(v, int) or isinstance(v, bool) or v < 1:
        raise MetadataError(f"{name} metadata entry has an invalid version")
    if not isinstance(length, int) or isinstance(length, bool) or length < 0:
        raise MetadataError(f"{name} metadata entry has an invalid length")
    if not isinstance(digest, str) or not SHA256.fullmatch(digest.lower()):
        raise MetadataError(f"{name} metadata entry has an invalid SHA-256")
    return v, length, digest.lower()


def validate_root(root: dict[str, Any]) -> None:
    signed = root["signed"]
    if signed.get("_type") != "root":
        raise MetadataError("root.json has the wrong role type")
    keys, roles = signed.get("keys"), signed.get("roles")
    if not isinstance(keys, dict) or not isinstance(roles, dict):
        raise MetadataError("root.json must define keys and roles")
    for role in ROLES:
        entry = roles.get(role)
        if not isinstance(entry, dict) or not isinstance(entry.get("keyids"), list):
            raise MetadataError(f"root.json role {role} is malformed")
        threshold = entry.get("threshold")
        if not isinstance(threshold, int) or threshold < 1 or threshold > len(entry["keyids"]):
            raise MetadataError(f"root.json role {role} has an invalid threshold")
        for keyid in entry["keyids"]:
            if not isinstance(keyid, str) or keyid not in keys:
                raise MetadataError(f"root.json role {role} references an unknown key")


def validate_target_names(targets: dict[str, Any]) -> None:
    for name, entry in targets.items():
        if not isinstance(name, str) or not name or "\\" in name:
            raise MetadataError("target names must be non-empty POSIX paths")
        path = PurePosixPath(name)
        if path.is_absolute() or ".." in path.parts or "." in path.parts:
            raise MetadataError(f"target path escapes the package root: {name}")
        if not isinstance(entry, dict):
            raise MetadataError(f"target {name} metadata entry must be an object")
        length, hashes = entry.get("length"), entry.get("hashes")
        digest = hashes.get("sha256") if isinstance(hashes, dict) else None
        if not isinstance(length, int) or isinstance(length, bool) or length < 0:
            raise MetadataError(f"target {name} has an invalid length")
        if not isinstance(digest, str) or not SHA256.fullmatch(digest.lower()):
            raise MetadataError(f"target {name} has an invalid SHA-256")


def check_rollback(docs: dict[str, dict[str, Any]], state_path: Path | None) -> None:
    if state_path is None or not state_path.exists():
        return
    try:
        state = json.loads(state_path.read_text(encoding="utf-8"))
        previous = state.get("versions", {})
    except (OSError, json.JSONDecodeError) as exc:
        raise MetadataError(f"cannot read rollback state: {exc}") from exc
    if not isinstance(previous, dict):
        raise MetadataError("rollback state versions must be an object")
    for role, envelope in docs.items():
        old = previous.get(role)
        if old is not None and (not isinstance(old, int) or version(envelope["signed"], role) < old):
            raise MetadataError(f"rollback detected for {role}.json")


def save_versions(docs: dict[str, dict[str, Any]], state_path: Path | None) -> None:
    if state_path is None:
        return
    state_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = state_path.with_name(f".{state_path.name}.tmp")
    temporary.write_text(
        json.dumps({"schema": 1, "versions": {role: version(doc["signed"], role) for role, doc in docs.items()}}, sort_keys=True),
        encoding="utf-8",
    )
    temporary.replace(state_path)


def verify(metadata_dir: Path, artifact: Path | None, target_name: str | None, allow_expired: bool, state_path: Path | None) -> str:
    docs = {role: load(metadata_dir / f"{role}.json") for role in ROLES}
    for role, envelope in docs.items():
        signed = envelope["signed"]
        if signed.get("_type") != role:
            raise MetadataError(f"{role}.json has the wrong role type")
        version(signed, role)
        expiry(signed, role, allow_expired)
    check_rollback(docs, state_path)
    validate_root(docs["root"])

    targets_signed = docs["targets"]["signed"]
    targets = targets_signed.get("targets")
    if not isinstance(targets, dict):
        raise MetadataError("targets.json must define targets")
    validate_target_names(targets)

    snapshot_meta = docs["snapshot"]["signed"].get("meta")
    timestamp_meta = docs["timestamp"]["signed"].get("meta")
    if not isinstance(snapshot_meta, dict) or not isinstance(timestamp_meta, dict):
        raise MetadataError("snapshot/timestamp metadata must define meta")
    target_meta = snapshot_meta.get("targets.json")
    root_meta = snapshot_meta.get("root.json")
    snapshot_entry = timestamp_meta.get("snapshot.json")
    if target_meta is None or root_meta is None or snapshot_entry is None:
        raise MetadataError("metadata chain is missing root, targets, or snapshot link")

    target_version, target_length, target_hash = meta_entry(target_meta, "targets.json")
    if target_version != version(targets_signed, "targets"):
        raise MetadataError("snapshot targets version does not match targets.json")
    root_version, root_length, root_hash = meta_entry(root_meta, "root.json")
    if root_version != version(docs["root"]["signed"], "root"):
        raise MetadataError("snapshot root version does not match root.json")
    snapshot_version, snapshot_length, snapshot_hash = meta_entry(snapshot_entry, "snapshot.json")
    if snapshot_version != version(docs["snapshot"]["signed"], "snapshot"):
        raise MetadataError("timestamp snapshot version does not match snapshot.json")

    for role, expected_length, expected_hash in (
        ("root", root_length, root_hash),
        ("targets", target_length, target_hash),
        ("snapshot", snapshot_length, snapshot_hash),
    ):
        raw = (metadata_dir / f"{role}.json").read_bytes()
        if len(raw) != expected_length or hashlib.sha256(raw).hexdigest() != expected_hash:
            raise MetadataError(f"{role}.json does not match its parent metadata hash/length")

    if artifact is not None:
        name = (target_name or artifact.name).replace("\\", "/")
        target = targets.get(name)
        if target is None:
            raise MetadataError(f"target not found: {name}")
        target_length = target.get("length")
        target_hash = target.get("hashes", {}).get("sha256")
        raw = artifact.read_bytes()
        if len(raw) != target_length or hashlib.sha256(raw).hexdigest() != target_hash:
            raise MetadataError(f"artifact does not match target metadata: {name}")
    save_versions(docs, state_path)
    return f"TUF metadata chain verified: {len(targets)} target(s)"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("metadata_dir", type=Path)
    parser.add_argument("--artifact", type=Path)
    parser.add_argument("--target-name")
    parser.add_argument("--allow-expired", action="store_true")
    parser.add_argument("--state-path", type=Path, help="persist accepted role versions to reject rollback")
    args = parser.parse_args()
    try:
        print(verify(args.metadata_dir, args.artifact, args.target_name, args.allow_expired, args.state_path))
    except MetadataError as exc:
        print(f"TUF metadata verification failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
