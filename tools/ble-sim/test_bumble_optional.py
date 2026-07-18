import importlib.util
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))


class OptionalBumbleTests(unittest.TestCase):
    @unittest.skipUnless(
        importlib.util.find_spec("bumble") is not None,
        "optional Bumble integration is unavailable; hardware-free simulator remains authoritative",
    )
    def test_bumble_requires_an_explicit_hardware_lab(self):
        self.skipTest(
            "Bumble package is present, but this repository does not claim a radio-backed integration without a lab adapter"
        )
