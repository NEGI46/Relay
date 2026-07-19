# ADR-002: Treat UDP gateway discovery as a hint, not an authority

Status: Proposed  
Date: 2026-07-19

## Context

The Android client accepts a UDP announcement after checking service name, versions, gateway ID, port, and the anonymous-ingress path. None of these fields authenticate the announcer, so a LAN attacker can replay or spoof a syntactically valid beacon.

## Decision

Retain UDP only as a discovery hint. Before any trusted gateway action, introduce one of these explicitly designed bootstrap paths:

1. QR/manual operator enrollment containing a gateway public-key fingerprint;
2. TOFU with a conspicuous first-use warning plus key-change quarantine; or
3. a signed discovery announcement validated against an enrolled operator key.

The chosen path must bind the discovered endpoint, gateway identity, and public key, provide rotation/revocation behavior, and preserve an explicit unverified/offline fallback.

## Consequences

This defers a crypto/protocol rewrite until compatibility and operational key ownership are decided. It also means current UDP discovery must not be described as authenticated or as evidence that a gateway is trusted.
