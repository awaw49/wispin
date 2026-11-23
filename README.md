# Wispin

[中文](README.zh.md) · **English**

An iOS location changer for Android. Wispin makes your Android phone act as an
MFi GPS accessory and pushes NMEA location fixes to your iPhone over Bluetooth —
no jailbreak, no cable, no desktop computer.

> [!IMPORTANT]
> **iOS 26 and later**: Apple changed location fusion and caching. After
> connecting, you must [refresh the location cache](#refresh-the-location-cache-on-ios)
> once for the iPhone to adopt the external source.

## Features

- **Point mode** — pick a spot on the map or type coordinates; the iPhone jumps there.
- **Path mode** — draw a polyline on the map or import a GPX file; the location
  moves along it at a configurable speed and can loop as a closed circuit.
- **GPX timestamp replay** — tracks with `<time>` are replayed at the recorded
  pace, including stops and speed changes.
- **Live retargeting** — pick a new point or redraw the path while a session is
  running; the next position updates take effect immediately.
- **Map** — OpenStreetMap (WGS-84) or Amap (GCJ-02) tiles with automatic
  coordinate conversion, plus saved favourite locations.
- **NMEA tuning** — GGA/RMC sentences with configurable quality, satellite
  count, speed, bearing, altitude and accuracy.
- **Randomisation** — optional position jitter, accuracy and altitude ranges.
- **Accessory identity** — rotate the advertised accessory name/serial per
  session.

## Requirements

- Android 8.0+ phone with Bluetooth.
- An iPhone paired with the Android phone in system Bluetooth settings.
- MFi accessory identity files (see below). Without them the link connects but
  authentication cannot complete and no location is pushed.

## Quick start

1. **Install** the APK from Releases (or build from source).
2. **Import MFi identity**: open the app, load `identity.pk8` and
   `certificate.p7b` once.
3. **Pair** the iPhone with the Android phone in the iPhone's
   *Settings → Bluetooth*.
4. **Pick a target**: choose **单点** or **路径**, tap the map to place points,
   then press the start button.
5. Watch the status row — *Authenticated* then a rising frame counter means the
   iPhone is subscribed and receiving fixes.

## Refresh the location cache on iOS

On iOS 26+, location services adopt the external source at fusion-refresh
moments. If the blue dot does not move after connecting:

1. Turn **off** Wi-Fi, cellular data and Location Services. Wait ten seconds,
   turn Location Services back on — leave Wi-Fi off for now.
2. Open **Maps** and confirm the blue dot lands on the target coordinates.
3. Turn Wi-Fi on; wait for the fix to stabilise, then turn cellular data back on.

If it still refuses, restart the iPhone with radios off, then repeat from step 2.

## Path mode notes

- **Loop** treats the route as a closed circuit: the position returns to the
  start along the implicit closing leg (drawn on the map), so movement is
  continuous with no wrap-around jump.
- The **travelled counter** shows distance delivered while the iPhone is
  actually subscribed, so it only advances when the phone is receiving fixes.

## MFi identity

MFi authentication requires accessory credentials obtained legitimately. This
repository ships no private keys or certificates:

```text
identity.pk8       # PKCS#8 DER P-256 private key
certificate.p7b    # Accessory certificate chain
```

**Never commit keys, certificates, or device-identifying logs.**

## Build

```bash
./gradlew assembleDebug        # debug APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # unit + protocol integration tests
```

Or open the project in Android Studio and run on a device.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| iPhone not listed | System Bluetooth pairing state; iPhone Bluetooth on |
| Connect fails / drops | SDP advertises the iAP2 UUID; re-pair the device |
| Authenticated but no frames | Wait for iOS `0xFFFA` subscription; keep Maps in foreground |
| Blue dot not moving | Do the [cache refresh](#refresh-the-location-cache-on-ios) |
| Dot stuck at route end | Enable **循环** so the route closes geometrically |
| MFi auth fails | Wrong or missing `identity.pk8` / `certificate.p7b` |

## License

[GPL-3.0](LICENSE). Use only on devices you own, for protocol research and
compliance testing.
