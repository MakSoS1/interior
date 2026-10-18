# Interior AR Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Выпустить Android-приложение, которое импортирует Blender GLB-кухню с размерами в метрах, показывает её в обычном 3D и в масштабе 1:1 через ARCore, адаптирует изображение к живому освещению, сохраняет проекты и собирает APK в GitHub Actions.

**Architecture:** Нативный Android/Compose с независимым доменом геометрии и хранения; SceneView/Filament — просмотр PBR; ARCore — tracking, plane hit-test, Anchor, lighting и опциональный Depth. Адаптеры AR, освещения и рендеринга отделены от UI и тестируются через fakes; неподдерживаемые устройства получают полноценный 3D-preview.

**Tech Stack:** Kotlin, Jetpack Compose, AndroidX Room, Android Storage Access Framework, SceneView `4.57.0`, ARCore, Filament, AGP `9.3.0`, Gradle `9.5.0`, JDK `17`, `minSdk=29`, `compileSdk=37`, `targetSdk=36`, Blender Python + glTF 2.0 GLB, JUnit4/Robolectric, GitHub Actions.

**Spec:** [`docs/superpowers/specs/2026-10-18-interior-ar-design.md`](../specs/2026-10-18-interior-ar-design.md).

**Status:** План утверждения ещё не прошёл; ни одна реализационная задача не выполнялась. При реализации каждый пункт требует фактического результата тестов перед отметкой `[x]`.

## Global Constraints

- Платформа: Android 10+; основные устройства — realme 14 Pro+ / Android 16 и Pixel 10 / Android 17; ни одно из них не должно быть жёстко прописано как единственное поддерживаемое устройство.
- **Проверка оборудования на старте обязательна.** realme 14 Pro+ пока не найден под этим названием в официальном списке ARCore: https://developers.google.com/ar/devices . Трекинг на нём не обещается до реального runtime-подтверждения.
- Приложение объявляет AR **optional** (`com.google.ar.core=optional`, `android.hardware.camera.ar required=false`); камера запрашивается только при открытии AR, не для галереи/3D.
- Версии: AGP 9.3.0, Gradle 9.5.0, JDK 17, compileSdk 37, targetSdk 36, minSdk 29, SceneView/ARSceneView 4.57.0. Остальные зависимости фиксируются в `gradle/libs.versions.toml` после первого совместимого CI-прогона и не используют `+` или floating versions.
- `applicationId = "com.maksos.interiorar"`; единственная точка входа — `MainActivity`; интерфейс на русском.
- Blender/glTF: метры, Y-up после экспорта; вправо +X, вверх +Y, от стены в комнату +Z, пол Y=0, начало кухни по нижнему заднему левому углу; **никакого автоматического scaleToUnits**. По умолчанию `scale=1.0`.
- Импорт только GLB через системный Storage Access Framework. Успешная загрузка не должна зависеть от постоянного доступа к оригинальному URI: копия хранится в private files.
- Нет сетевого backend, облачных Anchors, аккаунтов или передачи данных камеры. Google Play Services for AR может потребовать начальной загрузки.
- Один корневой Anchor на кухню. Координаты Anchors из одной сессии не являются долговременной привязкой между запусками; повторная калибровка обязательна.
- ARCore lighting и Depth — обнаруживаемые возможности, а не абсолютные требования. При отсутствии ARCore работает обычный 3D-preview.
- В CI проверяются код, модель и APK. Качество реального трекинга и света **нельзя** подтверждать только CI.
- Работа только в docs на текущем этапе: выполнение следующих задач начинается после явного одобрения этого плана.

## Review Focus

Пять наиболее рискованных входов/сбоев — **обязательные регрессионные тесты**, а не пожелания:

1. **realme без сертифицированного ARCore / устаревший AR service**: экран библиотеки и 3D-просмотр работают, AR-кнопка показывает причину и путь установки/повтора — Task 2: `DeviceCapabilityPolicyTest`.
2. **GLB с ошибочной единицей, бесконечным transform, неправильно вращёнными node, внешним URI или повреждённым BIN**: импорт отказывается молча изменять масштаб, не повреждает проект — Task 4: `GlbImportValidationTest`.
3. **Две точки почти совпадают, лежат не на отслеживаемом полу либо имеют перепутанные стороны стены**: Anchor не создаётся, UI помогает повторить выбор — Task 7/8: `PlacementFrameTest`, `ArPlacementControllerTest`.
4. **Невозможность HDR, Depth, изменение яркости/потеря tracking**: рендер не мерцает и не падает, выбранное качество снижается, информация точна — Tasks 10–12: `LightingSmootherTest`, `RenderFeaturesTest`, `QualityPolicyTest`.
5. **Прерывание копирования GLB, убийство процесса, миграция Room, повторное открытие**: никакой повреждённой записи и фальшивого Anchor; повторная калибровка — Tasks 4/5/9: `AssetStoreTest`, `ProjectRepositoryTest`, `ResumePlacementTest`.

---

## Proposed repository map

```text
.
├── README.md
├── .editorconfig
├── .gitignore
├── .github/workflows/android-ci.yml
├── .github/workflows/android-emulator-smoke.yml
├── gradlew, gradlew.bat, gradle/wrapper/*
├── build.gradle.kts, settings.gradle.kts, gradle.properties
├── gradle/libs.versions.toml
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/demo_kitchen.glb
│       │   └── java/com/maksos/interiorar/
│       │       ├── MainActivity.kt
│       │       ├── ui/InteriorApp.kt
│       │       ├── ui/projects/ProjectsScreen.kt
│       │       ├── ui/preview/PreviewScreen.kt
│       │       ├── ui/ar/ArScreen.kt
│       │       ├── ui/ar/ArViewModel.kt
│       │       ├── domain/model/Project.kt
│       │       ├── domain/geometry/Vec3.kt
│       │       ├── domain/geometry/Transform3.kt
│       │       ├── domain/geometry/PlacementFrame.kt
│       │       ├── data/projects/ProjectDao.kt
│       │       ├── data/projects/InteriorDatabase.kt
│       │       ├── data/projects/ProjectRepository.kt
│       │       ├── data/assets/PrivateAssetStore.kt
│       │       ├── data/assets/GlbInspector.kt
│       │       ├── data/assets/GlbImporter.kt
│       │       ├── ar/DeviceCapabilities.kt
│       │       ├── ar/ArCoreCapabilityProbe.kt
│       │       ├── ar/ArPlacementController.kt
│       │       ├── ar/AnchorAdapter.kt
│       │       ├── ar/lighting/LightingSample.kt
│       │       ├── ar/lighting/LightingSmoother.kt
│       │       ├── ar/lighting/ArLightAdapter.kt
│       │       ├── ar/render/RenderFeaturePolicy.kt
│       │       └── ar/render/QualityController.kt
│       ├── test/java/com/maksos/interiorar/...
│       └── androidTest/java/com/maksos/interiorar/...
├── assets/blender/generate_demo_kitchen.py
├── assets/blender/demo_manifest.json
├── scripts/validate_demo_glb.py
├── scripts/check_glb.mjs
├── scripts/package_report.py
├── package.json
├── package-lock.json
├── requirements-ci.txt
└── docs/
    ├── superpowers/specs/2026-10-18-interior-ar-design.md
    ├── superpowers/plans/2026-10-18-interior-ar-implementation.md
    ├── device-testing.md
    ├── blender-export.md
    └── android-compatibility.md
```

Точки входа адаптеров — сознательные границы. Не превращать каждый UI-элемент в отдельный класс и не делать обобщённый 3D-движок вместо приложения.

## Milestones

| Milestone | Tasks | Independently testable result |
|---|---|---|
| M0: foundational APK | 1–2 | APK запускается на поддерживаемой ОС и показывает честную проверку ARCore, без обязательной камеры |
| M1: usable 3D project | 3–6 | Демомодель или свой GLB открывается в 3D, проверяется масштаб, проект сохраняется |
| M2: room placement | 7–9 | Две точки, один Anchor, свободный обход, сохранение относительной позы и повторная калибровка |
| M3: light and appearance | 10–12 | Адаптивный свет, feature-gated depth/тени/отражения, качество под GPU |
| M4: delivery | 13–15 | Тесты, документация, отчёт, APK из GitHub Actions и реальный список испытаний |

---

### Task 1: Project bootstrap, deterministic Android build and CI

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat`, `.gitignore`, `.editorconfig`
- Create: `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/maksos/interiorar/MainActivity.kt`, `app/src/main/java/com/maksos/interiorar/ui/InteriorApp.kt`, `app/src/test/java/com/maksos/interiorar/AppSmokeTest.kt`
- Create: `.github/workflows/android-ci.yml`, `README.md`

**Interfaces:**
- Produces: one installable `com.maksos.interiorar` app and a lightweight Compose routing host.
- Requires: JDK 17, Android SDK 37, release-matched Gradle wrapper, AGP 9.3.0, SceneView 4.57.0.

- [ ] **Step 1.** Commit Android Gradle skeleton with `minSdk=29`, `compileSdk=37`, `targetSdk=36`, one `MainActivity`, Material/Compose app title and empty projects page. Keep Android app and test dependencies version-pinned, follow AGP 9.3 built-in Kotlin integration.
- [ ] **Step 2.** Add failing `AppSmokeTest.appStartsOnProjectsRoute`: initial route equals `projects`, app name is `Interior AR`, and the view model initializes without camera permission.
- [ ] **Step 3.** Run `./gradlew :app:testDebugUnitTest --tests '*AppSmokeTest*'`; verify RED first, implement route initialization, then GREEN.
- [ ] **Step 4.** Add `android-ci.yml` for push/PR/manual triggers: checkout, `actions/setup-java` Temurin 17, Gradle caching, Android SDK 37 setup/licensing, `./gradlew --no-daemon :app:lintDebug :app:testDebugUnitTest :app:assembleDebug`, upload `app/build/outputs/apk/debug/app-debug.apk` as `interior-ar-debug-apk`, retention 14 days.
- [ ] **Step 5.** On a fresh runner verify build success, artifacts exist and APK includes `com.maksos.interiorar` manifest. Capture action run URL in PR notes.
- [ ] **Step 6.** Commit: `chore(android): bootstrap reproducible Compose APK and CI`.

### Task 2: AR Optional hardware/service capability gate

**Files:**
- Create: `app/src/main/java/com/maksos/interiorar/ar/DeviceCapabilities.kt`, `ar/ArCoreCapabilityProbe.kt`, `ui/projects/ArCapabilityBanner.kt`
- Test: `app/src/test/java/com/maksos/interiorar/ar/DeviceCapabilityPolicyTest.kt`
- Modify: `AndroidManifest.xml`, `InteriorApp.kt`

**Interfaces:**
- `enum class ArAvailability { SUPPORTED_READY, NEEDS_INSTALL, CHECKING, UNSUPPORTED, ERROR }`
- `data class DeviceCapabilities(val ar: ArAvailability, val depth: Boolean?, val hdr: Boolean?)` (`null` = unknown before session)
- `interface ArCapabilityProbe { suspend fun probe(): DeviceCapabilities }`
- `fun canEnterAr(capabilities: DeviceCapabilities): Boolean`.

- [ ] **Step 1.** Tests: `UNSUPPORTED` allows preview but disables AR; `NEEDS_INSTALL` produces install/retry action; `CHECKING` does not create session; `SUPPORTED_READY` permits AR even if `depth=false`. RED.
- [ ] **Step 2.** Implement policy and Android wrapper around `ArCoreApk.checkAvailability` and `requestInstall` with Activity lifecycle; request Camera permission only when starting AR. Use `com.google.ar.core=optional`, optional AR camera feature.
- [ ] **Step 3.** Run `./gradlew :app:testDebugUnitTest --tests '*DeviceCapabilityPolicyTest*' :app:assembleDebug`; GREEN. Manually confirm fallback UI without permission.
- [ ] **Step 4.** Commit: `feat: gate AR by runtime capability and preserve 3D fallback`.

### Task 3: Reproducible Blender kitchen and GLB validation

**Files:**
- Create: `assets/blender/generate_demo_kitchen.py`, `assets/blender/demo_manifest.json`, `app/src/main/assets/demo_kitchen.glb`
- Create: `scripts/validate_demo_glb.py`, `scripts/check_glb.mjs`, `package.json`, `package-lock.json`, `requirements-ci.txt`
- Modify: `.github/workflows/android-ci.yml`, `README.md`

**Interfaces:**
- Blender command: `blender -b --factory-startup --python assets/blender/generate_demo_kitchen.py -- --output app/src/main/assets/demo_kitchen.glb`.
- Validator command: `python scripts/validate_demo_glb.py --model app/src/main/assets/demo_kitchen.glb --manifest assets/blender/demo_manifest.json`.
- Result: manifest with metric dimensions and named objects; stdout reports measured total width/depth/height. Khronos glTF validator issues are structured errors, not ignored.

- [ ] **Step 1.** Write failing Python scene-manifest test checking 2600 mm overall width, refrigerator height 2050 mm, three base cabinets, two wall cabinets and countertop 2000×635×38 mm. Add GLB malformed-file fixture test. RED via `python -m unittest discover -s scripts -p 'test_*.py'`.
- [ ] **Step 2.** Write Blender Python generator using primitives plus custom normals/bevels and noncopyright materials (wood, matte white, black hob, steel sink/tap, fridge). Export glTF Binary using Blender exporter with consistent Y-up axis and scene root. Ensure wall units do not accidentally contribute to demo geometry.
- [ ] **Step 3.** Build independent `trimesh`-based bbox checker, verify glTF 2.0 header/assets with Khronos `gltf-validator` npm package. Run both against actual GLB, not only source script.
- [ ] **Step 4.** CI validates checked-in demo GLB on each PR; reproducible Blender generator is tested separately in its own job (document Blender executable acquisition/version; do not install mutable system Blender implicitly).
- [ ] **Step 5.** Confirm GLB has correct orientation, PBR materials and approximately 2.6 m width by inspecting through a separate GLB viewer or instrumentation; no `scaleToUnits` auto-rescale.
- [ ] **Step 6.** Commit: `feat(assets): generate and validate original kitchen model`.

### Task 4: Import pipeline, immutable private copy and validation

**Files:**
- Create: `data/assets/GlbInspector.kt`, `data/assets/GlbImporter.kt`, `data/assets/PrivateAssetStore.kt`
- Test: `app/src/test/java/com/maksos/interiorar/data/assets/GlbImportValidationTest.kt`, `AssetStoreTest.kt`

**Interfaces:**
- `data class GlbMetadata(val boundsMeters: Bounds3, val meshCount: Int, val bytes: Long, val warnings: List<String>)`
- `sealed interface ImportResult { data class Success(val assetId: String, val metadata: GlbMetadata) : ImportResult; data class Failure(val reason: ImportFailure) : ImportResult }`
- `interface GlbImporter { suspend fun import(uri: Uri): ImportResult }`
- `interface PrivateAssetStore { suspend fun copyValidated(input: InputStream, maxBytes: Long): StoredAsset; suspend fun deleteIfUnreferenced(assetId: String) }`
- `GlbInspector.inspect(file: File): GlbMetadata`.
- `MAX_GLB_BYTES = 200L * 1024 * 1024`.

- [ ] **Step 1.** Fail tests for corrupted header, non-finite transforms, GLB >200 MiB, empty mesh, unsupported/external resource references, incomplete stream and duplicate SHA-256; verify no partial final asset and correct actionable error. RED.
- [ ] **Step 2.** Add `ACTION_OPEN_DOCUMENT` launcher, MIME `model/gltf-binary` with file-extension fallback; read via ContentResolver. Copy stream to private temporary file, size check while streaming, flush, inspect, atomic rename, SHA-256 checksum; never trust display name as path.
- [ ] **Step 3.** Implement GLB JSON + binary bounds inspection respecting node hierarchy, matrix/TRS transforms and glTF accessor positions; reject NaN/Infinity. Treat `accessor.min/max` as preliminary metadata, not proof all vertices/renderers are valid. Renderer load errors stay catchable.
- [ ] **Step 4.** Run `./gradlew :app:testDebugUnitTest --tests '*GlbImportValidationTest*' --tests '*AssetStoreTest*'`; GREEN and large-file memory bounded.
- [ ] **Step 5.** Commit: `feat(import): validate GLB and store atomic local copies`.

### Task 5: Project repository and durable local saves

**Files:**
- Create: `domain/model/Project.kt`, `data/projects/ProjectDao.kt`, `data/projects/InteriorDatabase.kt`, `data/projects/ProjectRepository.kt`
- Test: `app/src/test/java/com/maksos/interiorar/data/projects/ProjectRepositoryTest.kt`, `ProjectMigrationTest.kt`

**Interfaces:**
- `data class LocalPlacement(val xMeters: Float, val yMeters: Float, val zMeters: Float, val yawDegrees: Float, val importScale: Float, val reversedWallSide: Boolean)`
- `data class InteriorProject(val id: String, val title: String, val assetId: String, val placement: LocalPlacement, val createdAtEpochMs: Long, val modifiedAtEpochMs: Long)`
- `interface ProjectRepository { fun observeProjects(): Flow<List<InteriorProject>>; suspend fun create(assetId: String, title: String): InteriorProject; suspend fun get(id: String): InteriorProject?; suspend fun updatePlacement(id: String, placement: LocalPlacement); suspend fun delete(id: String) }`.

- [ ] **Step 1.** RED tests: project survives close/reopen of Room DB, updates placement atomically, title uniqueness is not required, deletion does not remove shared asset, migration preserves `importScale=1.0` and does not store ARCore world pose.
- [ ] **Step 2.** Implement entities and DAO, schema version 1, migrations/export schemas. Use Room transactional updates and private asset reference counting; store relative placement and rendering options only.
- [ ] **Step 3.** Run `./gradlew :app:testDebugUnitTest --tests '*ProjectRepositoryTest*' --tests '*ProjectMigrationTest*'`; GREEN.
- [ ] **Step 4.** Commit: `feat(projects): persist local projects and relative model transforms`.

### Task 6: Projects catalog and non-AR Filament preview

**Files:**
- Create: `ui/projects/ProjectsScreen.kt`, `ui/preview/PreviewScreen.kt`, `ui/preview/PreviewViewModel.kt`
- Modify: `InteriorApp.kt`
- Test: `app/src/test/java/com/maksos/interiorar/ui/PreviewViewModelTest.kt`

**Interfaces:**
- `data class PreviewUiState(val project: InteriorProject?, val metadata: GlbMetadata?, val loading: Boolean, val error: String?)`
- `PreviewViewModel.openProject(id: String)`, `PreviewViewModel.importProject(uri: Uri)`.
- `PreviewScreen` consumes immutable `PreviewUiState`, exposes preview-orbit/zoom callbacks and action to AR only if supported.

- [ ] **Step 1.** RED tests: demo project visible on first launch, import creates project and opens preview, reopening projects works, invalid model displays localized error and existing project persists, AR-disabled device has view-only experience.
- [ ] **Step 2.** Compose projects catalog, import FAB, built-in demo, view/delete dialogs, loading and empty states; 3D `SceneView` renders ModelNode from private file path/bytes supported by 4.57.0. Filament loading on engine/main thread; asynchronous file I/O on worker dispatcher.
- [ ] **Step 3.** Disable all normalization/scaling helpers. Show `width × depth × height` in meters and import-scale warning; orbit camera, pinch zoom, reset view, neutral PBR lighting in preview.
- [ ] **Step 4.** Run `./gradlew :app:testDebugUnitTest --tests '*PreviewViewModelTest*' :app:assembleDebug`; GREEN; test screen manually/emulator.
- [ ] **Step 5.** Commit: `feat(ui): add project library and 1-to-1 model preview`.

### Task 7: Pure metric placement frame and correction math

**Files:**
- Create: `domain/geometry/Vec3.kt`, `domain/geometry/Transform3.kt`, `domain/geometry/PlacementFrame.kt`
- Test: `app/src/test/java/com/maksos/interiorar/domain/geometry/PlacementFrameTest.kt`

**Interfaces:**
- `data class Vec3(val x: Double, val y: Double, val z: Double)`
- `data class PlacementFrame(val origin: Vec3, val right: Vec3, val up: Vec3, val forward: Vec3)`
- `sealed interface PlacementMathResult { data class Valid(val frame: PlacementFrame) : PlacementMathResult; data class Invalid(val reason: String) : PlacementMathResult }`
- `fun makePlacementFrame(pointA: Vec3, pointB: Vec3, floorUp: Vec3 = Vec3(0.0, 1.0, 0.0), reverse: Boolean = false): PlacementMathResult`.
- `fun applyLocalAdjustment(frame: PlacementFrame, local: LocalPlacement): Transform3`.

- [ ] **Step 1.** RED tests: for A=(0,0,0), B=(2,0,0), right=(1,0,0), up=(0,1,0), forward=(0,0,1); reverse flips model's facing without reflection; 0.49 m distance rejected, near-vertical vector rejected, nonfinite input rejected, perpendicular basis normalized, yaw=90° rotates within horizontal plane.
- [ ] **Step 2.** Implement horizontal projection of B−A, basis `forward = right × up` in right-handed coordinates, validated local translation and yaw; `Transform3` composition is pure and double-precision until AR renderer conversion.
- [ ] **Step 3.** Run `./gradlew :app:testDebugUnitTest --tests '*PlacementFrameTest*'`; GREEN (including property/invariant checks over random valid rays).
- [ ] **Step 4.** Commit: `feat(geometry): calculate wall-aligned metric placement frames`.

### Task 8: AR scene, floor picking, one Anchor and tracking feedback

**Files:**
- Create: `ar/AnchorAdapter.kt`, `ar/ArPlacementController.kt`, `ui/ar/ArScreen.kt`, `ui/ar/ArViewModel.kt`
- Test: `app/src/test/java/com/maksos/interiorar/ar/ArPlacementControllerTest.kt`
- Modify: `InteriorApp.kt`, `AndroidManifest.xml`

**Interfaces:**
- `enum class PlacementPhase { SCANNING, SELECT_START, SELECT_DIRECTION, ADJUSTING, ANCHORED, TRACKING_PAUSED }`
- `interface AnchorAdapter { fun anchorAtFloorPose(worldPose: Transform3): AnchorHandle?; fun setKitchenRoot(handle: AnchorHandle, localPose: Transform3); fun detach(handle: AnchorHandle) }` (opaque `AnchorHandle` adapter type).
- `ArPlacementController.onFloorTap(hit: FloorHit)`, `onTrackingChanged(isTracking: Boolean)`, `confirm(): Boolean`, `reset()`.
- `data class FloorHit(val position: Vec3, val normal: Vec3, val planeTracking: Boolean, val withinPolygon: Boolean)`.

- [ ] **Step 1.** RED tests: invalid/vertical/nontracking hits ignored; two valid floor hits enter ADJUSTING; no Anchor before confirm; confirm creates **exactly one** Anchor; reset detaches previous Anchor; tracking pause doesn't move last known kitchen root or create new anchors.
- [ ] **Step 2.** Integrate `ARSceneView` 4.57.0 camera feed, horizontal-plane detection and ARCore hit testing. Convert hit into `FloorHit` using tracking/plane polygon checks; place translucent floor footprint during preview; rotate kitchen using Task 7 frame.
- [ ] **Step 3.** Attach one root node per project to single ARCore Anchor; track lifecycle and renderer unload; show “Сканируйте пол”, “Выберите угол”, “Укажите направление”, “Подтвердите”, “Трекинг потерян”.
- [ ] **Step 4.** Run `./gradlew :app:testDebugUnitTest --tests '*ArPlacementControllerTest*' :app:assembleDebug`; GREEN. Real-device integration check remains open until compatible device available.
- [ ] **Step 5.** Commit: `feat(ar): anchor full kitchen after two-point floor placement`.

### Task 9: Fine adjustment, local save and re-alignment on reopening

**Files:**
- Modify: `ui/ar/ArScreen.kt`, `ui/ar/ArViewModel.kt`, `ar/ArPlacementController.kt`, `data/projects/ProjectRepository.kt`
- Test: `app/src/test/java/com/maksos/interiorar/ar/ResumePlacementTest.kt`

**Interfaces:**
- `ArViewModel.adjustTranslation(dxMeters: Float, dzMeters: Float)`
- `ArViewModel.adjustYaw(deltaDegrees: Float)`
- `ArViewModel.flipWallSide()`, `resetFineAdjustment()`, `savePlacement()`
- `ArPlacementController.restoreAfterCalibration(project: InteriorProject, newFrame: PlacementFrame)`.

- [ ] **Step 1.** RED tests: +0.01 m translates exactly 1 cm, yaw input wrapped to normalized angle, preview reset restores default, reopening requires new points A/B and never resurrects stale world-coordinate Anchor, restored relative offset/pivot matches saved project.
- [ ] **Step 2.** Add 1/5/10 cm adjustment buttons and ±1°/5° rotation, 180° flip, visible dimensions/floor outline and confirm/reset actions.
- [ ] **Step 3.** Persist `LocalPlacement` after confirmation; on reopened project show “Повторно укажите угол и направление стены”, recalibrate and apply local transform only after new Anchor.
- [ ] **Step 4.** Run `./gradlew :app:testDebugUnitTest --tests '*ResumePlacementTest*' :app:assembleDebug`; GREEN.
- [ ] **Step 5.** Commit: `feat(ar): save relative placement and re-calibrate between sessions`.

### Task 10: Live Environmental HDR → Filament light bridge

**Files:**
- Create: `ar/lighting/LightingSample.kt`, `ar/lighting/LightingSmoother.kt`, `ar/lighting/ArLightAdapter.kt`
- Test: `app/src/test/java/com/maksos/interiorar/ar/lighting/LightingSmootherTest.kt`, `ArLightAdapterTest.kt`
- Modify: `ui/ar/ArScreen.kt`

**Interfaces:**
- `data class LightingSample(val timestampNanos: Long, val mainDirection: Vec3?, val mainColorLinear: Vec3?, val mainIntensity: Float?, val ambientSh27: FloatArray?, val cubemapAvailable: Boolean)`
- `interface LightingInput { fun current(): LightingSample? }`
- `interface SceneLightingOutput { fun apply(sample: LightingSample); fun useNeutralFallback() }`
- `class LightingSmoother(private val timeConstantSeconds: Float) { fun update(sample: LightingSample, deltaSeconds: Float): LightingSample }`.

- [ ] **Step 1.** RED tests: missing light estimates preserve last accepted sample up to a bounded duration and then fallback; intensity spike smoothed over frames; true sustained change (e.g. switched on lamp) converges; zero/negative/NaN values rejected; camera orientation changes do not rotate world-space light spuriously; app pause/resume resets stale timestamps.
- [ ] **Step 2.** At session config request `Config.LightEstimationMode.ENVIRONMENTAL_HDR` where available; extract ARCore light estimate main direction, color/intensity and SH to `LightingSample`, transforming directions into renderer coordinates exactly once; note 27 SH coefficients and color-space contract.
- [ ] **Step 3.** Drive Filament main directional light and ambient lighting via `SceneLightingOutput` at bounded update frequency (e.g. ≤15 Hz); keep camera render at full FPS. No linear/sRGB double conversion; check native SceneView 4.57.0 engine API in its source before compiling.
- [ ] **Step 4.** Render `Lighting unavailable — neutral` on unsupported ARCore; configurable Live Lighting on/off. Avoid unsupported assertion that HDR cubemap necessarily changes in each frame.
- [ ] **Step 5.** Run `./gradlew :app:testDebugUnitTest --tests '*LightingSmootherTest*' --tests '*ArLightAdapterTest*' :app:assembleDebug`; GREEN. Hardware comparison day/lamp-light remains gated.
- [ ] **Step 6.** Commit: `feat(lighting): adapt kitchen lighting to ARCore environmental HDR`.

### Task 11: Dynamic reflections, depth occlusion and shadow fallback

**Files:**
- Create: `ar/render/RenderFeaturePolicy.kt`, `ar/render/FilamentEffectsAdapter.kt`
- Test: `app/src/test/java/com/maksos/interiorar/ar/render/RenderFeaturesTest.kt`
- Modify: `ui/ar/ArScreen.kt`

**Interfaces:**
- `data class RenderCapabilities(val supportsDepth: Boolean, val supportsHdr: Boolean, val supportsEnvironmentCubemap: Boolean, val supportsShadowReceiver: Boolean)`
- `data class RenderOptions(val occlusion: Boolean, val reflections: Boolean, val contactShadows: Boolean)`
- `fun resolveRenderOptions(requested: RenderOptions, capability: RenderCapabilities): RenderOptions`
- `interface FilamentEffectsAdapter { fun setDepthOcclusion(enabled: Boolean); fun updateEnvironmentMap(sample: LightingSample); fun setFloorContactShadow(enabled: Boolean) }`.

- [ ] **Step 1.** RED tests: on non-Depth device occlusion=false with explanation; when cubemap unavailable keep constant neutral/reflection IBL; when shadow receiver unsupported disable it and keep material shading; toggles survive project reload.
- [ ] **Step 2.** Add depth-mode check via `Session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)`; if supported, choose supported depth texture and camera/virtual depth comparison; verify near/far occlusion orientation on camera tests. Disabled state must not access depth buffer.
- [ ] **Step 3.** Add Environment HDR cubemap → Filament indirect light/reflection mapping only if SceneView renderer provides safe cubemap update; rate-limit/reuse textures, release old native resources. Fallback to fixed IBL in unsupported path.
- [ ] **Step 4.** Add inexpensive floor contact shadow using tuned translucent footprint only after floor detection. Evaluate custom shadow-catching render pass separately: enable only after demonstrated correct blending with camera image on hardware; a failed shader experiment must not block safe baseline.
- [ ] **Step 5.** Run `./gradlew :app:testDebugUnitTest --tests '*RenderFeaturesTest*' :app:assembleDebug`; GREEN; capture pixel screenshots for comparison during device testing.
- [ ] **Step 6.** Commit: `feat(render): gate realistic reflections depth and contact shadows`.

### Task 12: Adaptive performance profiles and safe renderer lifecycle

**Files:**
- Create: `ar/render/QualityController.kt`
- Test: `app/src/test/java/com/maksos/interiorar/ar/render/QualityPolicyTest.kt`
- Modify: `ui/ar/ArScreen.kt`, `ui/preview/PreviewScreen.kt`

**Interfaces:**
- `enum class QualityTier { AUTO, BALANCED, HIGH }`
- `data class FrameMetrics(val cpuFrameMs: Float, val gpuFrameMs: Float?, val tracking: Boolean)`
- `data class RenderBudget(val cubemapRefreshHz: Float, val depthEnabled: Boolean, val shadowQuality: Int)`
- `class QualityController { fun update(tier: QualityTier, metrics: FrameMetrics, capabilities: RenderCapabilities): RenderBudget }`.

- [ ] **Step 1.** RED tests: prolonged slow frames reduce expensive effects; short spikes do not oscillate tiers; depth must remain off on unsupported devices regardless of `HIGH`; pausing Activity frees session/texture listeners; project geometry scale never changes.
- [ ] **Step 2.** Implement FPS/frame-time sampling, thermal/low-memory notifications, hysteresis, rate-limited cubemap updates; fix leaks of scene nodes, Filament buffers and ARCore Anchors across navigation.
- [ ] **Step 3.** Run `./gradlew :app:testDebugUnitTest --tests '*QualityPolicyTest*' :app:assembleDebug`; GREEN; record instrumented heap and FPS on actual devices.
- [ ] **Step 4.** Commit: `perf(ar): adaptive rendering with stable quality controls`.

### Task 13: Finish mobile UX, localization, recovery and privacy

**Files:**
- Modify: `ui/InteriorApp.kt`, `ui/projects/ProjectsScreen.kt`, `ui/preview/PreviewScreen.kt`, `ui/ar/ArScreen.kt`
- Create: `app/src/main/res/values/strings.xml`, `docs/blender-export.md`, `docs/android-compatibility.md`
- Test: `app/src/test/java/com/maksos/interiorar/ui/NavigationAndRecoveryTest.kt`

**Interfaces:**
- Full navigation route set `projects`, `preview/{id}`, `ar/{id}`, `settings`; AR errors return user to preview without clearing project.
- All user-visible strings from Android string resources.

- [ ] **Step 1.** RED tests: hardware back from AR returns preview, from preview returns projects, denied camera permission doesn't lose imported file, invalid/deleted project route shows recovery action, deleting project requires confirmation; app restart retains history.
- [ ] **Step 2.** Add structured Russian onboarding for camera scanning, exact dimension labels, warnings of measurement uncertainty, lighting/occlusion controls, model origin guide, accessibility labels and loading progress.
- [ ] **Step 3.** Document Blender export recipe: Scene Units Metric/1.0, floor/back/left origin, axes after glTF export, transforms applying cautiously, GLB material workflow; document AR service checks and fallback for unlisted realme hardware.
- [ ] **Step 4.** Run `./gradlew :app:testDebugUnitTest --tests '*NavigationAndRecoveryTest*' :app:lintDebug`; GREEN, zero fatal accessibility lint warnings.
- [ ] **Step 5.** Commit: `feat(ux): complete project navigation onboarding and recovery`.

### Task 14: Continuous integration, emulator smoke and APK provenance

**Files:**
- Modify: `.github/workflows/android-ci.yml`
- Create: `.github/workflows/android-emulator-smoke.yml`, `scripts/package_report.py`
- Test: `app/src/androidTest/java/com/maksos/interiorar/AndroidSmokeTest.kt`

**Interfaces:**
- Output `app/build/outputs/apk/debug/app-debug.apk` and `build/reports/interior-build-report.json` with commit SHA, Android target/compile APIs, model integrity status, unit/lint result, APK SHA256.
- Actions artifact name `interior-ar-debug-apk` and second artifact `interior-ar-test-reports`.

- [ ] **Step 1.** RED `AndroidSmokeTest`: launch without camera permission; project library displays sample; open sample 3D preview; AR unsupported status does not crash; return to projects. Keep camera-dependent scenarios on physical device, not Android emulator claims.
- [ ] **Step 2.** Add emulator workflow on `workflow_dispatch` and nightly schedule with x86_64 API 36 AVD; run `./gradlew :app:connectedDebugAndroidTest` on emulator runner with software graphics fallback and report artifacts. If VM acceleration unavailable, workflow records `infrastructure-limited`, does not fabricate pass.
- [ ] **Step 3.** Harden primary workflow: lock Node/Python validator dependencies, build and lint, no repository secrets needed, use least-permission `permissions: contents: read`, upload aapt/aab metadata, report and APK SHA256; limit CI network to dependency fetches.
- [ ] **Step 4.** Confirm download of APK artifact from a successful run, `aapt dump badging` shows package, `minSdk=29`, `targetSdk=36`, and APK signature is Android debug signature. Do not claim release signing or Play Store publish.
- [ ] **Step 5.** Commit: `ci: publish verified debug APK and emulator smoke reports`.

### Task 15: Hardware acceptance, regression report and v1 handoff

**Files:**
- Create: `docs/device-testing.md`
- Modify: `README.md`, `docs/android-compatibility.md`
- Test evidence: screenshots/logcat/FPS measurements **only when observed on real devices**, stored as opt-in, no private room photos committed without permission.

**Interfaces:**
- `docs/device-testing.md` includes device model, firmware/build, Android API, `ArCoreApk.checkAvailability`, installed AR Services version, depth/HDR status, scene FPS median/p95, placement error measurement and result per test.
- Outcome statuses: `CI verified`, `device verified`, `blocked by unsupported hardware` — never collapse these into a single “everything works”.

- [ ] **Step 1.** Create physical protocol: on realme 14 Pro+ Android 16, check Play Services for AR availability, Android permission, 3D preview always; if AR unsupported, capture reason and mark AR scenario **blocked**, not passed.
- [ ] **Step 2.** On an ARCore-supported phone (Pixel 10 Android 17 if available), measure 2 m tape check, walk 3 m and return, yaw stability, tracking loss and regain, reopen/recalibrate.
- [ ] **Step 3.** Run day/night/lamp toggle, bright-window turn, depth occlusion with real chair/person crossing, glossy material reflection and old-furniture overlap; compare default versus Auto/Balanced/High, record limitations.
- [ ] **Step 4.** Execute full CI `./gradlew --no-daemon :app:lintDebug :app:testDebugUnitTest :app:assembleDebug` and Python/Node GLB validation, verify Actions artifacts and SHA256. Any red/unknown remains open in checklist.
- [ ] **Step 5.** Finish README with preview/device install walkthrough, GLB asset guide, compatibility matrix, screenshots only with consent, exact APK artifact retrieval. Document known issues, permissions and platform limits.
- [ ] **Step 6.** Commit: `docs(release): report CI and on-device AR acceptance`.

---

## Explicit quality gates and expected evidence

| Gate | Required evidence | May be automated? |
|---|---|---|
| G0 | JDK/Gradle/AGP/SceneView resolved; app debug APK builds | Yes, GitHub Actions |
| G1 | Valid generated GLB, measured kitchen width 2.6 m, Khronos validator clean | Yes |
| G2 | Sample/custom import, duplicate/invalid/large handling, no ghost files | Yes (unit + emulator) |
| G3 | Deterministic 1:1 placement math and **one** AR Anchor | Math yes; physical anchor no |
| G4 | Restart requires recalibration; previous project and local edits survive | Yes + device smoke |
| G5 | Changing current light updates virtual scene without visible oscillation | Math yes; visuals no |
| G6 | HDR/depth/shadows gracefully downgrade, memory released on exit | Policy yes; rendering on device |
| G7 | Installable APK + reports downloaded from Actions | Yes |
| G8 | Realme-specific capability result and Pixel/another certified phone physical acceptance | **Only real hardware** |

## Scope and dependency decisions that must NOT be silently changed

1. First working deliverable is a **real Android APK**; do not substitute a website or video mockup.
2. `AR Optional` is deliberate because realme 14 Pro+ certification cannot be assumed; if AR unsupported, user still gets a useful 3D application.
3. `.blend` is **not** imported on the phone; Blender exports GLB. The checked-in sample is generated by an original script, without internet asset-license ambiguity.
4. Imports are in physical meters with `scale=1`. Do not force fit or `scaleToUnits(1f)` to make rendering easy.
5. Lighting is taken from the **actual current camera**, not weather and not a scripted morning/evening slider. Current lighting estimation is approximate and cannot guarantee all cast shadows.
6. A new ARCore session does not restore coordinates through saved plain floats. v1 restores a project using manual two-point recalibration.
7. Do not enable Cloud Anchors, Google API keys, GitHub secrets, user accounts or telemetry in v1.
8. Keep the GLB + project store usable in ordinary offline 3D mode even on an uncertified Android 16 device.

## Verification commands at completion

```bash
# Repository root, with Android SDK 37/JDK 17 configured.
./gradlew --no-daemon :app:lintDebug :app:testDebugUnitTest :app:assembleDebug

# Validate included GLB and authored Blender dimensions.
python scripts/validate_demo_glb.py \
  --model app/src/main/assets/demo_kitchen.glb \
  --manifest assets/blender/demo_manifest.json
node scripts/check_glb.mjs app/src/main/assets/demo_kitchen.glb

# Emulator smoke is executed in its own GitHub Actions workflow.
./gradlew :app:connectedDebugAndroidTest

# APK artifact for sideloading, produced on successful CI:
# app/build/outputs/apk/debug/app-debug.apk
```

These commands define expected verification **after implementation**; they do not work in the currently documentation-only repository.

## Verification of the plan itself (before implementation)

- Every spec requirement is mapped: compatibility (2), sample (3), input (4), persistence (5), viewer (6), geometry (7), anchoring (8–9), lighting (10), depth/reflect/shadow (11), performance (12), UX/privacy (13), APK/CI (1/14), device acceptance (15).
- Each task has a RED test, a concrete implementation boundary, a GREEN command (or external reality check) and a commit. Real-camera behavior is explicitly tagged as non-CI.
- No pinned guarantee that realme 14 Pro+ can execute ARCore. Device probing is the first release gate; Android 16 alone is insufficient.
- Acceptance requires artifact evidence, not a claim that a workflow succeeded.

## Execution handoff

**Recommended:** native implementation in sequential checkpoints, with a full independent review at the end; use Superpowers `executing-plans`. AR/lighting interfaces depend strongly on the exact SceneView runtime build, so sequential integration is efficient. The alternative is `subagent-driven-development` with reviewer per task if independent task-review fidelity is preferred.

**Next action after plan approval:** run Task 1, create first CI APK; do not start coding solely because this plan document exists.
