# BPv7 export boundary

`gateway-bp7-export` is an export-only adapter. It maps Relay's envelope to a
deterministic BPv7-shaped JSON fixture:

| Relay field | Export field |
|---|---|
| `messageId` | `extensionBlocks[0].data.messageId` |
| `ttlSeconds` | `extensionBlocks[0].data.ttl` |
| `payloadHash` | `extensionBlocks[0].data.payloadHash` |
| `priority` | `extensionBlocks[0].data.priority` |

The Relay core keeps its own TTL, receipt, and store-carry-forward semantics;
it is not replaced by BPv7. Import is intentionally unsupported until an RFC
9171-compatible external interop fixture is available. The checked-in golden
fixture and invalid-input tests prove mapping and fail-closed behavior, not
interoperability with a DTN daemon.
