# Accuracy and reliability audit

Audited 12 November 2026. This change improves conservative failure handling; it does not certify any phone's compass accuracy worldwide.

Repository baseline: `1c7a8f0d85bb94c6f67745ebb91b49bcd33bcff1`.

This records the original compass reliability changes. Version 1.6 subsequently adds optional network-based app updates and installation permission; see [UPDATES.md](UPDATES.md) for that feature and its validation.

## Location and permissions

Previously, an accurate early fix could reject all subsequent lower-accuracy fixes indefinitely, and GPS updates stopped after a <=20 m fix. The app could therefore keep directing from the wrong city after travel. Updates now continue only while resumed. An accuracy preference lasts 30 seconds; a newer, valid fix may then replace it. Out-of-order, future, >10 minute, missing-accuracy and non-finite fixes are rejected using elapsed realtime. Providers are handled independently so a missing/restricted provider cannot skip another. Coarse-only permission uses the network provider, without requiring Google Play Services. GPS requires fine permission.

Denied permission no longer starts another request from its callback. Recovery is through Android app settings; the app retries access on resume. A saved coordinate is displayed as last known, but cannot enable the live compass until a fresh platform location arrives. Coordinates now retain Double precision, with migration from older Float preferences. No background location permission or internet permission is added. Platform mock locations are not rejected; development/testing locations can drive the app.

Location accuracy is an Android uncertainty estimate, not a guaranteed bound. The direction is suppressed within max(20 m, 10 x reported accuracy) of the Kaaba or antipode and within 0.1 degrees of a geographic pole. This conservative heuristic also protects coarse location near Makkah. A future enhancement could show a full bearing interval instead of suppressing the arrow. Providers disabled while the screen is open leave the last fresh fix usable until its 10-minute expiry; coordinates and source remain visible in Detailed view.

## Bearing and geodesy

The existing spherical initial great-circle bearing is retained, using Kaaba coordinates 21.422487 N, 39.826206 E and mean Earth radius 6,371,008.8 m. It is clockwise from true north; no rhumb-line or map-projection bearing is used. Date-line crossing already works through trigonometric periodicity. Invalid coordinates, exact geographic poles, coincidence and the exact antipode now return NaN rather than an arbitrary north arrow. The UI guards singular/uncertain positions before displaying direction.

Spherical and WGS84 ellipsoidal geodesics are different conventions. Neither the UI nor this change silently switches between them. Near the antipode, even a small location or model change can radically change the initial bearing. An ellipsoidal option would require an explicit convention, a robust antipodal solver and independent reference tests; a simple Vincenty loop is unsuitable as a universal replacement.

## Sensors, axes and lifecycle

Priority: Android rotation vector, then geomagnetic rotation vector, then accelerometer + calibrated magnetometer via getRotationMatrix. Never use game rotation vector as an absolute north source. The optional game rotation vector supplies smooth relative motion only after an absolute reference has been acquired. The fallback rejects strong acceleration and filters gravity; it is less resistant to motion than fused rotation-vector hardware.

Heading projects the **visible screen's top edge** onto the horizontal plane. All four display rotations are accounted for, including portrait on a naturally landscape tablet. The previous switch from top-edge heading to rear-camera heading when upright has been removed. The screen must face up within 45 degrees of horizontal; otherwise the arrow is hidden with instructions. This avoids a discontinuous change of the aiming axis.

Resume clears gyro offsets, field averages, timestamps, smoothing and room-averaging accumulators. A display rotation or a gyro event gap also invalidates the old reference. Delayed/non-finite events are ignored. Fusion requires both orientation streams to be recent (100 ms); UI sensor data expires after 1.5 seconds. All interval timers use elapsed realtime; wall time is reserved for magnetic-model date and solar calculations. A gyro-only reference expires after 30 seconds, then needs reacquisition. The 30-second limit is a conservative product policy, not a measured drift bound for every device.

## True north, declination and magnetic environment

Android rotation-vector and magnetometer headings are magnetic. The code **adds** east-positive GeomagneticField declination once, then compares against the true-north Qibla bearing. It uses location altitude when available, zero otherwise, and the current date. Units remain nT for model strength and microtesla for sensor strength (conversion /1000).

Android's magnetic model depends on the OS version; old devices may carry an old WMM. Passing field checks cannot validate that model. This version does not bundle WMM2025 coefficients. A separately validated current magnetic-model implementation remains follow-up work, especially for older OS versions and high magnetic latitudes. Large horizontal declination error cannot be ruled out by scalar strength/dip checks.

Calibration now requires at least MEDIUM accuracy from both magnetic and absolute orientation streams. Matching strength and inclination no longer overrides UNRELIABLE/LOW calibration flags. An optional rotation-vector azimuth error >15 degrees also disables calibration acceptance. Devices that never report usable accuracy may conservatively show calibration guidance. Compass guidance is suppressed where expected horizontal field is <5,000 nT; this is an app policy for weak-field regions, not a physical guarantee above the threshold.

Field strength/dip mismatch can detect some interference, but **matching strength and dip cannot prove correct azimuth**: rotating the horizontal field preserves both. Calibration is not a correction for nearby steel or magnets. Figure-eight guidance is retained, along with advice to move away from interference. Haptics and green alignment are disabled for disturbed, uncalibrated and approximate room-average directions. Gyro hold is explicitly described as subject to drift.

## Room averages and UI confidence

Shared magnetic bias does not disappear by averaging. Small standard error measures agreement, not absolute accuracy. Room results are now described as approximate; unsupported +/- accuracy and guaranteed-accuracy claims were removed in English and Arabic. Motion groups are heuristic (shaking can look like steps), not independent surveyed positions. Without the linear acceleration sensor, changing magnetic readings no longer fabricate new positions. Old buckets without new usable samples cannot refresh reference age. Room estimates are never eligible for alignment haptics.

## Automated validation

`gradle testDebugUnitTest lintDebug assembleDebug --no-daemon` (Java 17, Gradle 8.7, Android SDK 34).

JUnit tests cover 629 nonsingular positions on a global grid using an independent 3D tangent-plane reference, known/cardinal bearings, date-line equivalence, spherical distances, poles/coincidence/antipode, non-finite inputs, angular wrap, east/west declination, coarse-location ambiguity, location replacement and timestamp limits, all display rotations, tilted/face-down matrices. These are deterministic calculations and policy tests, not physical sensor accuracy tests.

The verification workflow runs on pull requests and main pushes. The existing release workflow now runs tests and lint before assembling/publishing its release. No workflow has been dispatched and no release has been published as part of this local change.

## Device validation still required

- Android 8 and current Android; devices with/without gyro, rotation vector and network provider; naturally landscape tablet.
- Grant precise/approximate location; deny, deny permanently, revoke in Settings; turn providers off and on; operate offline without Play Services.
- Walk/drive after an early accurate fix; resume after travel; verify the new position wins over an old precise fix. Check near Makkah with coarse permission.
- Screen up/tilted/upright/face down; turn 359 -> 0 degrees; screen rotation, pause/resume, gyro reset and sensor delivery gaps.
- Compare with a surveyed true-north line in open ground in both hemispheres and across east/west declination regions. Repeat away from a magnetic phone case, vehicle, steel building and power equipment.
- Introduce magnetic interference, watch orange hold behavior, wait >30 seconds, then reacquire outdoors. Verify neither room averages nor field-match percentages promise an angular accuracy.
- Near geographic/magnetic poles and the Kaaba antipode, confirm unavailable guidance rather than an arbitrary arrow. Emulator location/sensor playback cannot replace this field testing.

## References

- [Android position sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_position)
- [Android sensor coordinate system](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview#sensors-coords)
- [GeomagneticField API](https://developer.android.com/reference/android/hardware/GeomagneticField)
- [Location API: elapsed time and accuracy](https://developer.android.com/reference/android/location/Location)
- [Runtime precise/approximate location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime)
