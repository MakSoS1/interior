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


## Reference kitchen reconstructed from image (2026-10-18)

The **“Кухня из твоего референса · остров + 3 стула”** button loads a separate
model from the supplied kitchen-design photograph. It contains **236 individual
3D scene nodes**, including the oak-clad island, three upholstered stools, twin
pendants, the induction hob, sink, tall cabinets, open shelves and small decor.
The original procedural scene remains available under **“Тестовая кухня”**.

**Dimensions are intentionally approximate:** assembled GLB bounds
3.89 × 2.62 × 3.227 metres (X × Y × Z), island 2.55 × 1.05 m. Neither the
single design photograph nor the home video provide an accurately measured
floor plan; before fitting the furniture to the real kitchen, manually
measure the wall length, window/door offsets, clearances and island position.

The generative source is `scripts/build_reference_kitchen.py`; SceneView
and Filament load its self-contained `reference_kitchen.glb` as a bundled
Android asset generated in CI. All material textures are synthetic; there
is no hidden dependency on the reference photo.

### Rebuild the reference model

```bash
python3 -m pip install -r scripts/requirements-reference.txt
python3 scripts/build_reference_kitchen.py \
  --output app/src/main/assets/reference_kitchen.glb \
  --manifest assets/reference_kitchen_manifest.json
python3 -m unittest discover -s scripts -p 'test_*.py' -v
```

To inspect/edit in Blender: File → Import → glTF 2.0 (.glb), select the
generated file, modify individual named nodes and export as GLB with metric
dimensions preserved. Model coordinates are in metres; Y-up, +X along the
wall, +Z from wall into the room. The island and pendants are part of the
same single AR-root assembly and use a single Anchor.

The CI `Android APK` workflow publishes two artifacts:
**`interior-ar-debug-apk`** for installation and
**`interior-reference-kitchen-glb`** for Blender.

### Offline video projection — not on-device AR

For a local proof of concept using the original handheld phone video, run:

```bash
python3 -m pip install -r scripts/requirements-reference.txt opencv-python==4.13.0.92
python3 scripts/offline_video_overlay.py \
  --input room-video.mp4 \
  --glb app/src/main/assets/reference_kitchen.glb \
  --output offline_projection.mp4 \
  --seconds 3.1 --maxfps 12
```

The overlay uses a **manually assumed** camera orientation and SIFT feature
correspondences/homographies to follow a pan. It is explicitly labeled an
illustrative reconstruction, **not** metrically accurate camera calibration
or a real ARCore acceptance test. Rendering does not remove the original
table and cabinets. Real tracking, scale, depth occlusion and live lighting
must be evaluated with the APK on an ARCore-supported phone; see
`docs/device-testing.md`.

**Privacy:** The reference photo and original home video are deliberately
**not committed to this public repository**. The procedural model, source
code and reproducible tests are committed; an offline overlay can be produced
privately from local inputs.
