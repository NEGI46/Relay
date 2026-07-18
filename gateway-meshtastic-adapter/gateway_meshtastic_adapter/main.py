"""JSONL CLI for the out-of-process Meshtastic adapter."""

import argparse
import sys
import time

from .protocol import decode_message, encode_message


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mock", action="store_true", help="accept and validate JSONL without a radio")
    parser.add_argument("--mesh-port", help="reserved for the optional meshtastic backend")
    args = parser.parse_args()
    if not args.mock and not args.mesh_port:
        parser.error("choose --mock or --mesh-port")
    for line in sys.stdin:
        try:
            message = decode_message(line.encode(), int(time.time()))
            sys.stdout.buffer.write(encode_message(message, int(time.time())) + b"\n")
            sys.stdout.flush()
        except ValueError as exc:
            sys.stderr.write(f"rejected: {exc}\n")


if __name__ == "__main__":
    main()
