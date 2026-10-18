# Interior AR — design specification (v1.0)

**Status:** design approved in conversation on 2026-10-18, corrected for Android 17/16; implementation is NOT started.  
**Repository:** \`MakSoS1/interior\`  
**Source of truth:** this file. Detailed execution steps: [../plans/2026-10-18-interior-ar-implementation.md](../plans/2026-10-18-interior-ar-implementation.md).

## 1. Outcome and target users

Interior AR is a local-first Android application to import a Blender-created kitchen as one GLB scene, verify its physical dimensions, place it along a real wall at 1:1 scale, and walk around it through the phone camera. Lighting should react to current actual room illumination, not just the clock.

The user needs accurate perceived layout and realistic visualization. The application is **not** a construction-grade measurement instrument, a room reconstruction system, or a mechanism for erasing already installed furniture.

### Platforms

- **Primary tests:** realme 14 Pro+ with Android 16; Pixel 10 with Android 17 as a secondary/reference test. Android version **does not imply ARCore certification**.
- **Supported installation range:** Android 10+ (minSdk 29). Build with compileSdk 37, targetSdk 36 for v1.0; support current system permissions and edge-to-edge behavior.
- Use **AR Optional**: the app runs in normal 3D-viewer mode on devices without ARCore. AR placement is enabled only after a successful runtime ARCore capability and service check.
- Depth, Environmental HDR and GPU performance are detected individually at runtime and have fallbacks. Never infer capabilities from brand/model or Android version.
- **Known certification risk (2026-10-18):** Google’s public ARCore-device list shows realme 14 Pro, but does not explicitly list realme 14 Pro+. No claim that the Pro+ supports ARCore until verified on-device with official API/Google Play Services for AR.

References: https://developers.google.com/ar/devices ; https://developers.google.com/ar/develop/java/enable-arcore ; https://developer.android.com/build/releases/agp-9-3-0-release-notes

## 2. Stack and constraints

- Native Kotlin + Jetpack Compose, SceneView 4.57.0 (Filament renderer), ARCore via SceneView/official ARCore SDK.
- Android Gradle Plugin 9.3.0, Gradle 9.5.0, JDK 17, compilation SDK API 37. Use stable compatible versions of Kotlin/Compose and pin them in \`gradle/libs.versions.toml\`; AGP 9 uses built-in Kotlin by default — do not double-apply an incompatible Android Kotlin plugin.
- One Android app module \`:app\`; isolate pure math, model metadata and persistence from AR and Filament.
- Entire user flow works **offline** after Google Play Services for AR has been installed and downloaded its device profile. The application does not collect images, transmit camera frames, or require any backend.
- App ID: \`com.maksos.interiorar\`; user-facing app name: **Interior AR**. UI language Russian for v1.0; accessible controls and reasonable dynamic font sizing.
- Debug APK must be built and uploaded by GitHub Actions for each PR and \`main\` push.

## 3. Import contract: Blender → GLB

- Input \`.glb\` conforming to glTF 2.0, opened through Android Storage Access Framework (\`ACTION_OPEN_DOCUMENT\`) and copied to private app storage; no broad-storage permission.
- Blender scene models authored in **meters**. glTF uses **Y-up**, Blender works **Z-up**; Blender glTF exporter performs axis conversion. Use a documented origin convention: floor level \`Y=0\`, kitchen local back at \`Z=0\`, right along \`+X\`, forward out from wall along \`+Z\`. Positive depth goes into the room.
- Preserve authored size at scene root scale **1.0**. Never invoke SceneView helpers that automatically normalize geometry to one unit (e.g. \`scaleToUnits(1f)\`), or silently fix wrong models. If dimensions appear wrong, offer explicit manual import-scale correction with persistent warning.
- Calculate actual world-space bounds by traversing scene nodes and transforms; rotate-only transforms do not change linear measurements, but nonuniform scale does. Show meters/mm in preview, and warn on suspicious dimensions.
- Require finite positions/scales and valid non-empty mesh data; provide actionable error if GLB malformed or unsupported. Suggested import cap 200 MiB on-device and warnings for complex geometry/textures; limits must be user-visible.
- Persist local copy and SHA-256 to deduplicate. Undo import errors without damaging existing projects.
- GLB embedded PBR materials, textures, normals and tangents should render where supported; unsupported material extensions degrade gracefully.

## 4. Sample kitchen

Generate a deterministic, original Blender scene from a Python script and export \`demo_kitchen.glb\`:
- Base drawer cabinet: 600×600×870 mm.
- Sink cabinet: 800×600×870 mm.
- Cooktop cabinet: 600×600×870 mm.
- Refrigerator: 600×650×2050 mm.
- Two wall cabinets: each 800×350×720 mm.
- Continuous countertop: 2000×635×38 mm (covers the base units only).
- Additional distinct materials and primitives: wood panels, matte/metal handles, white sink, dark hob, faucet, glass/metal refrigerator.
- Total horizontal width: **2600 mm** (600+800+600+600); no automatic scale change.
- Blender script writes both GLB and simple manifest of intended dimensions; validation checks glTF correctness and the computed assembled scene bounds, allowing a documented small tolerance. No third-party sample licensing necessary.

## 5. Functional UX

1. **Projects** — create/import, open past projects, open bundled sample, delete with confirmation.
2. **3D preview** — orbit and zoom camera, see model, real physical bounds, unit/orientation warnings, open AR if available.
3. **AR placement** — request camera permission; check ARCore service; detect horizontal floor; tap two floor points: point A is the starting floor reference and point B defines the +X direction along the wall. Place kitchen root relative to A and AB at scale 1.0. Floor/bounds indicator and clear preview before final confirmation.
4. **Fine adjustment** — constrained translations in centimeters, rotation in degrees; explicitly save adjusted local root pose. Reset to original and undo last adjustment.
5. **AR walk-through** — fix one root Anchor containing the whole kitchen. Camera moves; kitchen remains stationary. Display tracking state, low-feature/low-light feedback, recenter/re-place controls.
6. **Save / reopen** — store imported GLB, project and relative placement transform. ARCore session-local Anchors **cannot** be blindly serialized: next launch asks user to realign point A and AB, then restores saved offset. No automatic persistent spatial relocalization in v1.
7. **Settings** — live lighting toggle, quality tier, depth occlusion if supported, contact shadow option, FPS overlay; diagnostics shareable as user-initiated text (no camera data).

No editor for individual cabinetry geometry in v1. The model is authored in Blender and placed as one rigid assembly.

## 6. AR tracking, world coordinates and alignment

- ARCore provides metric poses, camera tracking, hit tests, horizontal plane recognition and Anchors.
- Avoid anchoring modules independently. Create one Anchor on valid, tracking floor plane, and parent the single model root to it.
- Pose math: world_model = world_anchor × anchor_to_model × model_to_object.
- Project local right direction comes from floor A→B projected to the horizontal plane, rejecting short/invalid points (distance < 0.5 m) and non-tracking plane states. Forward/up derived as an orthonormal right-handed frame and verified with known test poses. Provide 180° flip if wrong side of wall.
- ARCore may adjust its spatial estimate over time: the UI must not promise centimeter absolute accuracy. Report any anchor loss and allow replacement.
- Touch points are approximate camera/plane observations, not actual dimensions of the physical wall: the app does not claim to measure the room.

## 7. Real-time lighting and realistic integration

**Mandatory:** live environmental light from ARCore when available:
- dominant main light direction, intensity and color;
- ambient spherical harmonics;
- environmental HDR cube map where exposed and supported;
- light-change smoothing that avoids visual pumping and recovers from large genuine changes (e.g., switching on a lamp);
- PBR Filament materials plus exposure/white-balance tuning without misleadingly claiming absolute calibration.

**Progressive effects and truthful fallbacks:**
- Dynamic reflections: assess actual SceneView/Filament environmental-probe support and cubemap update cost on both device families; fallback to ambient IBL when not feasible.
- Depth occlusion: enable only when supported, accept reduced edge quality; hide toggle with explanation otherwise.
- Shadows: first implement constrained fake/contact shadows on virtual/recognized floor; proper transparent camera-composited shadow receiver is an advanced stage requiring shader/multipass feasibility test. Do not promise physically correct room shadows from ARCore's single estimated light.
- Old cupboards are not digitally removed from camera view. Real depth of existing furniture may obscure replacement kitchen.

**Quality tiers:** Auto, Balanced, High; downgrade shadows, reflection update frequency, depth resolution/occlusion based on measured frame timing, without changing physical scale.

## 8. Persistence & privacy

- Persist with Room metadata / JSON transform record and managed \`.glb\` files in app-private storage, atomic writes and schema migrations.
- Stable project ID, model asset checksum/path, original/display name, imported dimensions, explicit import-scale, relative position/rotation, placement orientation flag, material/lighting settings, timestamps.
- User deleting a project removes its model copy only when no other project references it; prevent path traversal in imported display names.
- No tracking analytics, cloud assets, raw camera persistence, mandatory accounts, internet permission, or broad filesystem access.

## 9. Error handling and tests

Graceful errors: camera permission denied, ARCore unsupported/outdated, phone lacks depth/HDR, tracking paused, invalid raycast, malformed/huge GLB, renderer context loss, out-of-memory loading, interrupted import, app kill and project migration.

Test levels:
- JVM unit tests: geometry, orthonormal alignment, scale, model validation metadata, repository/persistence.
- Local Android/Robolectric/UI tests: routing, SAF importer controller, permission/state machine, persistence migrations.
- GLB validation with Khronos glTF Validator and independent bbox/manifest check.
- Automated build and static analysis on GitHub-hosted runners.
- Android instrumented smoke with emulator where practical; **real hardware acceptance mandatory** for tracking, lighting, occlusion and FPS.
- Acceptance benchmark: 30 FPS median and 95th-percentile frame time ≤ 50 ms on sample scene, when ARCore is available; sample placement deviation target ≤ 5 cm at 2 m in textured, well-lit room, measured by user/device. These are goals requiring measurements, not claims.

## 10. Scope boundaries and delivery

Release 1.0 is considered **feature complete in code** only when imported/sample GLB preview, 1:1 anchored placement, live lighting (with explicit feature fallbacks), project persistence and CI APK are implemented. **Device-tested** status requires realme/Pixel hardware smoke evidence. CI alone is never evidence of correct physical AR anchoring.

Out of scope: creating a kitchen in-app; auto room mesh/reconstruction, deleting existing furniture, cloud anchors, cross-device shared placement, path tracing, multiple-room mapping, and sunshine prediction from time/geolocation.

## 11. Architecture boundaries

\`\`\`text
Compose UI / ViewModels
  ├── ProjectCatalog → RoomDatabase + PrivateAssetStore
  ├── ImportPipeline → GlbInspector + AssetStore → ModelPreview
  └── ArExperienceController
       ├── Capabilities → ARCore runtime probes
       ├── PlacementMath → pure math / metric transforms
       ├── AnchorAdapter → SceneView/ARCore
       ├── LightingController → ARCore light estimate → Filament
       └── QualityController → dynamic rendering feature gates
\`\`\`

Every bridge to ARCore, SceneView or Filament must live behind an interface so deterministic behavior is unit-testable without camera hardware. The first implementation task must prove a compatible 4.57.0 SceneView/ARCore build on AGP 9.3.0 and confirm the runtime API surface before specialized lighting code is written.

## References

- Google ARCore: https://developers.google.com/ar/develop/java/enable-arcore
- Certified devices: https://developers.google.com/ar/devices
- ARCore Environmental HDR: https://developers.google.com/ar/develop/java/light-estimation
- SceneView: https://sceneview.github.io/docs/ and https://github.com/sceneview/sceneview
- Android Gradle Plugin 9.3: https://developer.android.com/build/releases/agp-9-3-0-release-notes
- Khronos glTF Validator: https://github.com/KhronosGroup/glTF-Validator
