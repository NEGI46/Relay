# Jazzer lane

The parser fuzz target should call the same Relay packet/envelope decoder used
by production code and assert that malformed input is rejected without a
process crash. Until a dedicated Gradle fuzz target is declared, CI keeps the
Jazzer job report-only and the script exits with an explicit skip. This avoids
claiming fuzz coverage that does not exist.
