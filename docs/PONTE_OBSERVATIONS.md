# PONTE observations and CSV scope

Staff with OPERATOR role may create observations; VIEWER can list only the minimum metadata. Subject tokens must be opaque identifiers, not names. Details are intentionally not persisted by this pilot endpoint. A device observation is never a person confirmation.

The staff screen supplies CSV preview and import for training-only baseline subjects, observations, and support profiles. UTF-8 with or without BOM is accepted up to 256 KiB / 5,000 rows. Required columns, row widths, enum values, times, opaque subject tokens, and duplicates are checked before import. Unknown columns are ignored; they are never treated as signature evidence. An import with any rejected row makes no changes. CSV observations are stored as `CSV_IMPORT / UNVERIFIED` even if a CSV cell claims another assurance.
