# BathyApp

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![CI](https://github.com/St0n89/BathyApp/actions/workflows/android.yml/badge.svg)](https://github.com/St0n89/BathyApp/actions/workflows/android.yml)
[![GitHub release](https://img.shields.io/github/v/release/St0n89/BathyApp.svg)](https://github.com/St0n89/BathyApp/releases)

Android app for generating **bathymetric profiles and grids** from
[EMODnet Bathymetry](https://emodnet.ec.europa.eu/en/bathymetry) (online, WCS),
**GEBCO 2026** (online, worldwide) or **GEBCO** grids (offline, NetCDF /
GeoTIFF). Pure Kotlin, Jetpack Compose, zero bathymetry libraries: the
NetCDF-3 reader, GeoTIFF reader/writer, WCS client, GEBCO crop client, profiler
and exporters are all hand-written in `:core` (plain Kotlin/JVM, fully
unit-tested).

## Features

- **EMODnet online mode**: downloads a coverage tile for any bounding box via
  the EMODnet Bathymetry WCS 2.0.1 endpoint (`ows.emodnet-bathymetry.eu/wcs`),
  coverages selectable (`emodnet__mean`, 2016/2018/2020/2022 releases).
  Coverage: European waters.
- **GEBCO online mode**: worldwide coverage via the GEBCO 2026 grid
  (15 arc-second). Requests a server-side crop for the entered bounding box
  through `download.gebco.net`, polls until the crop is ready and reads the
  returned NetCDF (CDF-1) with the built-in reader. The crop is cached in the
  app cache dir with a 0.25° margin, so follow-up requests inside the cached
  area need no network. Server limit: 14.400 deg² per request; large areas
  take longer (progress note shown while waiting).
- **Offline mode**: opens local bathymetry files directly on the device:
  - GEBCO NetCDF (**classic NetCDF-3, CDF-1/CDF-2**; NetCDF-4/HDF5 is rejected
    with a clear error message),
  - single- or multi-band float GeoTIFFs (uncompressed, Deflate, LZW, PackBits;
  strips or tiles, either endianness).
- **Interactive map view**: colored bathymetry raster, pinch-zoom and pan,
  tap to set profile points A and B, colorbar with value range.
- **Great-circle depth profile** (1000 samples, slerp interpolation) with a
  chart view, water body shading, zero line and summary statistics.
- **Exports** via the system file picker (SAF):
  - GeoTIFF (float32, georeferenced, values positive-up, north-up),
  - grid CSV (lon/lat/elevation rows, north-first),
  - profile CSV (distance, lon, lat, elevation).
- Grid conventions: rows north-first, columns west-first, bounds are outer
  cell edges, elevations **positive up** (water depth is negative), consistent
  with EMODnet/GEBCO source data.

## Usage

1. Choose *EMODnet (online)*, *GEBCO (online)* or *Datei (offline)*.
2. Enter the bounding box (west/east/south/north in degrees) and the target
   grid width in pixels (2..2048).
3. *Gitter laden* fetches/builds the grid and shows the map. In GEBCO mode the
   first request waits for the server-side crop (status note shown); requests
   inside an already-cached crop load instantly.
4. Tap point A, then point B (further taps move B).
5. *Profil* computes and shows the depth profile.
6. *GeoTIFF / Gitter-CSV / Profil-CSV* export the current result.

UI language is German.

## Installation

- **F-Droid client / IzzyOnDroid repo** (recommended once included):
  add the [IzzyOnDroid repo](https://apt.izzysofts.de/fdroid/repo?fingerprint=3F:0A:20:1C:68:71:D0:DE:BB:1D:B5:54:03:9B:1B:82:3F:DB:17:36:69:F6:0F:8D:C7:9A:CE:66:9B:EE:FA:FB)
  (`https://apt.izzysofts.de/fdroid/repo`) in your F-Droid client and install
  `io.github.st0n89.bathyapp`. Updates then arrive through the F-Droid client.
- **Obtainium / direct download**: the signed release APK is attached to each
  [GitHub release](https://github.com/St0n89/BathyApp/releases).
  [Obtainium](https://github.com/ImranR98/Obtainium) can track the releases page
  and notifies you about updates.
- Official **F-Droid** inclusion is in progress; the build recipe is kept in
  [`fdroid/io.github.st0n89.bathyapp.yml`](fdroid/io.github.st0n89.bathyapp.yml).

## Building

No binary wrapper jar is committed; CI uses a pinned Gradle. Locally:

```bash
gradle :core:test :app:assembleDebug
```

with JDK 17, Gradle 8.9, AGP 8.6.1, Kotlin 2.0.20. The debug APK ends up in
`app/build/outputs/apk/debug/`.

### CI / Releases

`.github/workflows/android.yml` runs unit tests and builds debug + release APKs
on every push and pull request, uploads them as artifacts, and attaches them to
a GitHub Release for tags `v*`.

Release signing: if the repository secrets `BATHY_KEYSTORE_B64` (base64 of the
keystore), `BATHY_KEYSTORE_PASSWORD`, `BATHY_KEY_ALIAS` and
`BATHY_KEY_PASSWORD` are set, the release APK is signed with that keystore;
otherwise CI generates an ephemeral keystore so the APK is still installable.

## Data attribution

- EMODnet Bathymetry: European Marine Observation and Data Network,
  https://emodnet.ec.europa.eu/en/bathymetry
- GEBCO Compilation Grid: General Bathymetric Chart of the Oceans,
  https://www.gebco.net

Both data sets are used under their open licences; see the providers' sites
for attribution requirements.

## License

MIT, see [LICENSE](LICENSE).
