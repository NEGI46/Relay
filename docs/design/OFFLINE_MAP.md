# Signed offline disaster map

> This document describes the Compose Multiplatform signed-map boundary. The PC v1 pilot uses a separate bounded cache described below.

## PC v1 Fuchu pilot

`pc-gateway/GsiTileCache` stores only the Fuchu Town bounding box at zoom 13–15 from the Geospatial Information Authority of Japan standard-tile endpoint. The operator starts the bounded background download from the initial-settings tab. Requests outside the prepared tile set are rejected, preventing the public local tile route from becoming an arbitrary proxy or unbounded disk writer. The UI always displays GSI attribution.

The PC map keeps independent center/zoom state for the rescue and full-map views. It supports control buttons, mouse wheel, double click, keyboard, pointer dragging, and touch pinch. Zoom 16–18 is an offline overzoom of the cached zoom-15 tiles: it improves close-range marker inspection without requesting uncached tiles or turning the route into a network proxy. Panning is clamped to the pilot municipality bounds.

Before real deployment, the operator must confirm the current GSI content terms and whether the planned use requires a Survey Act procedure. This cache is separate from the signed PMTiles contract below and must not be presented as a cryptographically signed map pack.

`composeApp` has a verification boundary in `OfflineMapPackVerifier`. A local
asset loader supplies the bytes and detached signature; only
`VerifiedOfflineMapPack` crosses into MapLibre. It checks style JSON shape,
PMTiles magic header, both SHA-256 values, local-only URIs (`asset://`,
`file://`, or `content://`), and a signature over both hashes and both URIs.

The screen uses MapLibre Compose 0.13.0's
`MaplibreMap(baseStyle = BaseStyle.Uri(...))`. The verified style is expected
to reference the verified PMTiles URI; no online style URL or download is used.

MapLibre Compose integration must not depend on online style URLs or offline
pack downloads. The first supported pack is a versioned style JSON plus one
PMTiles file in the app's local resources. A missing or invalid pack falls back
to the existing text/location UI and never shows an unverified map.

Acceptance checks:

- valid signed style + PMTiles opens with airplane mode enabled;
- changed bytes are rejected before MapLibre is initialized;
- missing pack produces a user-visible, actionable fallback;
- map labels do not expose encrypted rescue payload contents.

## Verification status

- Pure tests cover success, changed PMTiles bytes, and remote style rejection.
- Android/iOS asset copying, PMTiles protocol registration, and physical-device
  MapLibre rendering remain unverified.
- The default `RelaySharedApp` supplies no pack, so it shows an actionable
  fallback and does not initialize MapLibre. A platform loader must call
  `OfflineMapPackVerifier.verify` first.
