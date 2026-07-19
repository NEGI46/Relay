# ADR-001: Make the Relay peer trust mode explicit

Status: Proposed
Date: 2026-07-19

## Context

`NearbyConnectionsTransport` receives Nearby authentication digits but automatically accepts an incoming connection once digits are available. This supports a zero-operation disaster flow, but the code does not establish that a user compared the digits or that the endpoint maps to a previously trusted peer.

## Decision

Keep the current behavior only as an explicitly labelled **OPEN** mode. Design a **TRUSTED** mode before changing the wire protocol:

- OPEN: auto-connect, keep all incoming data unverified until application-level signatures/receipts prove more; no delivery claim from transport completion.
- TRUSTED: establish or scan a peer/gateway public-key fingerprint, bind it to a stable identity, and require a match before automatic data exchange.
- UI copy must say which mode is active and never turn transport completion into remote-persistence confirmation.

## Consequences

This avoids silently changing disaster usability or breaking current peers. It also leaves a real P1 risk open in OPEN mode, so production deployment needs an explicit product/security decision, a protocol compatibility plan, and tests for downgrade, rotation, and lost-device recovery.
