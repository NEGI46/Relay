"""Schema checks for the checked-in emulator configuration."""

import json
import pathlib
import unittest


CONFIG = pathlib.Path(__file__).with_name("configs") / "relay_emulator.yaml"


class MoblyConfigContractTest(unittest.TestCase):
    def test_emulator_config_is_json_compatible_yaml_with_three_roles(self) -> None:
        # JSON is a strict YAML 1.2 subset, so this validation has no PyYAML
        # dependency and catches malformed checked-in configuration in CI.
        config = json.loads(CONFIG.read_text(encoding="utf-8"))
        self.assertEqual(config["test_bed"], "relay_emulators")
        self.assertEqual(len(config["devices"]), 3)
        self.assertEqual(
            {device["role"] for device in config["devices"]},
            {"origin", "relay", "destination"},
        )
        self.assertEqual(config["debug_package"], "com.example.relay")
        self.assertTrue(config["prerequisites"]["debug_apk_installed"])
        self.assertEqual(config["prerequisites"]["snippet_bridge"], "relay_test")


if __name__ == "__main__":
    unittest.main()
