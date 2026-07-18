# Parser fuzz regression lane

The four named test classes mirror the report's required decoder boundaries:
`EnvelopeDecoderFuzzTest`, `GatewayDtoFuzzTest`, `BleFragmentFuzzTest`, and
`QrFrameFuzzTest`. Seed inputs live below `corpus/` and include malformed,
oversized, negative-index, unknown-schema, and time-boundary values. The host
lane is deterministic and fail-closed; a JVM Jazzer target may bind to the same
boundary functions when the dedicated fuzz dependency is provisioned.
