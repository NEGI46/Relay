# Signed offline disaster map

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
