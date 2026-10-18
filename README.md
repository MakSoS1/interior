# Interior AR

Native Android app for placing a Blender-made kitchen into a real room at **metric scale**.

- **Android 10+**, targeted tests: realme 14 Pro+ (Android 16) / Pixel 10 (Android 17). AR requires a device actually supported by Google Play Services for AR; unsupported devices can open models in the 3D preview.
- Import .glb via Android file picker. Blender's glTF binary exporter converts Blender Z-up to glTF Y-up; set real dimensions in meters.
- Live AR tracking is an approximation, not a substitute for construction measurements.

## Build

GitHub Actions workflow **Android APK** generates a bundled multi-object kitchen model, runs model/unit tests, compiles a debug APK and uploads `interior-ar-debug-apk`.

Download APK at [Actions](../../actions/workflows/android-ci.yml). No release-signing keys are needed.

## Source

- `scripts/generate_demo.py`: procedural, self-authored sample kitchen (2.60 m wide).
- `app/`: Kotlin, Compose, ARCore and SceneView/Filament.
- `docs/superpowers/`: design specification and implementation plan.

The app makes no network requests itself and does not upload your camera images. It saves imported files privately on-device.
