# Parser fuzz regression lane

The four named test classes mirror the report's required decoder boundaries:
`EnvelopeDecoderFuzzTest`, `GatewayDtoFuzzTest`, `BleFragmentFuzzTest`, and
`QrFrameFuzzTest`. Seed inputs live below `corpus/` and include malformed,
oversized, negative-index, unknown-schema, and time-boundary values. The host
lane is deterministic and fail-closed.

The JVM Jazzer targets that bind three of these boundaries to the real
production decoders (`EnvelopeDecoderFuzzTest`, `GatewayDtoFuzzTest`,
`QrFrameFuzzTest`) live in the `:fuzz-jvm` module; see `test-lab/jazzer/README.md`.
The BLE fragment boundary stays in this deterministic Python lane because its
codec (`RescueBleFrameCodec`) is in the Android `:app` module.
