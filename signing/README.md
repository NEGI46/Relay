# Local release signing

`relay-local-release.jks` is a development-only keystore generated locally to validate the Android release packaging pipeline. It is not an organizational production key and must not be used for public deployment or copied into the user distribution folder.

When the organization's real keystore is available, sign `distribution/Relay-User-Package/Android/Relay-release-unsigned.apk` with that key and replace the local signed artifact.
