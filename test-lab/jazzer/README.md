# Jazzer lane

The JVM Jazzer fuzz targets live in the `:fuzz-jvm` Gradle module
(`fuzz-jvm/src/test/kotlin/com/example/relay/fuzz`). Each target is a real
`@FuzzTest` (Jazzer JUnit5 integration) that calls the same production decoder
used by shipping code and asserts that malformed input is rejected without an
unchecked crash:

- `EnvelopeDecoderFuzzTest` -> `EncryptedRescueEnvelope` JSON decode + `validate()` (`:shared`)
- `GatewayDtoFuzzTest` -> `GatewayMessage` JSON decode (`:relay-protocol`)
- `QrFrameFuzzTest` -> `QrTransferCodec.decodeFrame` + `assemble` (`:shared`)

Run the deterministic regression lane (each committed seed once, plus the empty
input); this is cross-platform and needs no libFuzzer driver:

    ./gradlew :fuzz-jvm:test

`scripts/run-jazzer.ps1` runs this lane after the Python decoder/BLE regression,
and CI runs it in the `fuzz-regression` job.

Continuous, coverage-guided fuzzing is the explicit opt-in: set `JAZZER_FUZZ=1`
before invoking `scripts/run-jazzer.ps1`. The Jazzer libFuzzer driver only
supports Linux/macOS, so `JAZZER_FUZZ=1` is refused on Windows (the deterministic
regression lane above still runs there). Availability must never be represented
as coverage when the continuous lane is not run.

The BLE fragment decoder (`RescueBleFrameCodec`) lives in the Android `:app`
module and cannot be a plain-JVM fuzz dependency; its boundaries are covered by
the deterministic corpus under `test-lab/fuzz` and the BLE virtual transport
regression until an Android-instrumented fuzz lane is provisioned.
