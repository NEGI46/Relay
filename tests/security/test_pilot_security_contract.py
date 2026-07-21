"""Fast source-level safety contracts for the municipal-pilot posture.

These do not replace JVM/Android integration tests. They make accidental reintroduction of known
unsafe defaults visible even on hosts that cannot download the Gradle toolchain or attach devices.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


class PilotSecurityContractTest(unittest.TestCase):
    def test_android_release_and_pilot_release_deny_cleartext(self) -> None:
        manifest = read("app/src/main/AndroidManifest.xml")
        policy = read("app/src/main/res/xml/network_security_config.xml")
        debug_manifest = read("app/src/debug/AndroidManifest.xml")
        build = read("app/build.gradle.kts")

        self.assertIn('android:usesCleartextTraffic="false"', manifest)
        self.assertIn('cleartextTrafficPermitted="false"', policy)
        self.assertIn('android:usesCleartextTraffic="true"', debug_manifest)
        self.assertIn('create("pilotRelease")', build)
        self.assertGreaterEqual(build.count('buildConfigField("boolean", "ALLOW_HTTP_GATEWAY", "false")'), 2)

    def test_gateway_and_broker_production_defaults_are_fail_closed(self) -> None:
        gateway = read("pc-gateway/src/main/kotlin/com/example/relay/pcgateway/GatewayConfig.kt")
        broker = read("broker/src/main/kotlin/com/example/relay/broker/BrokerConfig.kt")
        server = read("pc-gateway/src/main/kotlin/com/example/relay/pcgateway/GatewayServer.kt")

        self.assertIn('null, "", "production", "prod" -> PRODUCTION', gateway)
        self.assertIn('else "127.0.0.1"', gateway)
        self.assertIn('default = profile == GatewayProfile.DEVELOPMENT', gateway)
        self.assertIn('X-Admin-Key compatibility is permitted only in the development profile', gateway)
        self.assertIn('remote management requires RELAY_GATEWAY_LAN_MODE=tls-reverse-proxy', gateway)
        self.assertIn('must bind loopback', broker)
        self.assertIn('gateway_scope_mismatch', read("broker/src/main/kotlin/com/example/relay/broker/BrokerServer.kt"))
        self.assertIn('httpOnly = true', server)
        self.assertIn('gateway_audit_log', read("pc-gateway/src/main/kotlin/com/example/relay/pcgateway/GatewayAccessStore.kt"))

    def test_backup_boundary_excludes_private_key_and_runtime_credentials(self) -> None:
        backup = read("scripts/backup-gateway.ps1").lower()
        for forbidden in ("rescue-keys", "admin.key", "ble-bridge.key", "relay_broker_credential"):
            self.assertNotIn(forbidden, backup)

        access = read("pc-gateway/src/main/kotlin/com/example/relay/pcgateway/GatewayAccessStore.kt")
        self.assertIn('PBKDF2WithHmacSHA256', access)
        self.assertIn('tokenHash(token)', access)
        self.assertIn('Audit only metadata', access)

        key_store = read("pc-gateway/src/main/kotlin/com/example/relay/pcgateway/rescue/RescueKeyStore.kt")
        gateway_main = read("pc-gateway/src/main/kotlin/com/example/relay/pcgateway/Main.kt")
        self.assertIn('verifyOwnerOnly(path)', key_store)
        self.assertIn('automatic generation is disabled outside development', gateway_main)
        self.assertNotIn('error.message', gateway_main)

    def test_formal_release_cannot_publish_debug_or_mutable_actions(self) -> None:
        workflow = read(".github/workflows/publish-release.yml")
        ci = read(".github/workflows/relay-ci.yml")

        self.assertIn(':app:assembleRelease', workflow)
        self.assertNotIn(':app:assembleDebug', workflow)
        self.assertNotIn('Relay-Android-debug.apk', workflow)
        for required in (
            'RELAY_ANDROID_KEYSTORE_BASE64',
            'RELAY_WINDOWS_AUTHENTICODE_PFX_BASE64',
            'signtool.exe sign',
            '-RequireTool',
            '-RequireBundles',
            'SHA256SUMS',
            'resolve-source',
            'git rev-parse HEAD',
            'RELEASE_ARTIFACT_VERIFICATION.md',
        ):
            self.assertIn(required, workflow)

        for name, content in (("release", workflow), ("ci", ci)):
            mutable = re.findall(r"uses:\s+[^\s@]+@(?:v\d+|main|master)\b", content)
            self.assertEqual([], mutable, f"{name} workflow has mutable action refs: {mutable}")


if __name__ == "__main__":
    unittest.main()
