"""Deterministic retry/idempotency oracle for fault-injection scenarios."""
from enum import Enum

class Fault(str, Enum):
    NONE = "none"
    TIMEOUT = "timeout"
    RESET = "reset"

def run(fault: Fault) -> list[str]:
    stored = set()
    outcomes = []
    for attempt in range(3):
        if attempt == 0 and fault in (Fault.TIMEOUT, Fault.RESET):
            outcomes.append(fault.value)
            continue
        message_id = "msg-fixed-001"
        outcomes.append("duplicate" if message_id in stored else "accepted")
        stored.add(message_id)
    assert stored == {"msg-fixed-001"}
    return outcomes

def main() -> None:
    assert run(Fault.NONE) == ["accepted", "duplicate", "duplicate"]
    assert run(Fault.TIMEOUT) == ["timeout", "accepted", "duplicate"]
    assert run(Fault.RESET) == ["reset", "accepted", "duplicate"]
    for fault in Fault:
        print(f"{fault.value}: {run(fault)}")

if __name__ == "__main__":
    main()
