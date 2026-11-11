# True Qibla — Android

A Qibla compass that checks itself.

- **Gyroscope** (Android's game rotation vector, no magnet) keeps the arrow smooth and steady.
- **Compass** gives the absolute direction, converted to **true north** with Android's built-in World Magnetic Model.
- **Interference check**: the app measures the magnetic field's **strength** and **dip angle** and compares them with what Earth's field should be at your exact GPS position.
  - **Green**: the field matches, so the arrow is accurate.
  - **Red**: something nearby (steel, cars, speakers, laptops) is bending the field. The compass is ignored, the gyroscope holds the last accurate direction, and the app tells you to move. A live bar shows when you reach a clean spot.

No internet needed after install. No external libraries.

## Build the APK — choose one

### A) In the cloud with GitHub (no install on your PC)
1. Create a free GitHub account, then make a **new repository**. It can be private.
2. Upload all files from this folder. Keep the `.github` folder.
3. Open the **Actions** tab and wait about 5 minutes for "Build APK" to finish.
4. Open the finished run and download **TrueQibla-apk**. Unzip it to get `app-debug.apk`.
5. Copy it to your phone, open it, and allow "Install unknown apps" when asked.

### B) With Android Studio on your PC
1. Install Android Studio, then **File → Open** this folder.
2. Wait for the Gradle sync to finish. It downloads what it needs.
3. Plug in your phone with USB debugging on and press ▶ Run. Or use **Build → Build APK(s)**.

## Use
1. Open the app, allow Location, and hold the phone flat.
2. Turn until it says **✓ You are facing the Qibla**. The phone vibrates.
3. If it says **Interference here**, walk slowly and watch the bar until it's green.
