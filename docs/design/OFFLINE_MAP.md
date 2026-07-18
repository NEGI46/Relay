# Signed offline disaster map

The app should render a locally packaged MapLibre style and PMTiles file using
an `asset://` URI. The map pack is an opaque distribution artifact: download
or copy it only after validating its TUF target metadata and SHA-256 hash.

MapLibre Compose integration must not depend on online style URLs or offline
pack downloads. The first supported pack is a versioned style JSON plus one
PMTiles file in the app's local resources. A missing or invalid pack falls back
to the existing text/location UI and never shows an unverified map.

Acceptance checks:

- valid signed style + PMTiles opens with airplane mode enabled;
- changed bytes are rejected before MapLibre is initialized;
- missing pack produces a user-visible, actionable fallback;
- map labels do not expose encrypted rescue payload contents.
