# Interior AR — verified build and source acceptance (2026-10-18)

## Confirmed

- Original full-resolution design PNG: `reference/source/scandinavian_kitchen_reference.png` — **3,058,988 bytes**, SHA-256 `0a4b0390a077ec57c60467f58160e7e3a12c052230feb3593e141883f3c0fe54`.
- Original 17.898-second, 720×1280 home video: `reference/source/room_walkthrough_original.mp4` — **6,682,968 bytes**, SHA-256 `b2c45336564b79e9db51fc835b290bbd3517b9bf0274e63caf3240047507f2c3`.
- GitHub restored and committed the original video from byte-preserving shards, [successful restore run](https://github.com/MakSoS1/interior/actions/runs/39136096379).
- Android baseline: [successful APK workflow](https://github.com/MakSoS1/interior/actions/runs/39134648299) and [successful Android 16 emulator smoke](https://github.com/MakSoS1/interior/actions/runs/39134643491) for baseline commit `7ab328be`. Later documentation/media commits must pass their own follow-up CI.
- The bundled reference GLB has 236 distinct named nodes, textures inside the GLB, approximate scene bounds 3.89×2.62×3.227 m; the fridge is a separate 3D model with separate AR floor placement.
- The provided offline overlay demonstrates uncalibrated frame correspondence on a short portion of the original home video. It is **not** a real ARCore capture or measured physical camera pose.
- A repeatable Blender-compatible GLB generation script and strict source verification are present in Git.

## Not confirmed

- Pixel 10 Android 17 and realme 14 Pro+ Android 16 **physical installation and ARCore availability**.
- Correct 1:1 placement error, anchor drift, HDR lighting reaction, depth occlusion, floor shadows and GPU frame time on either phone.
- Automatic reconstruction of exact room dimensions or full 6-DOF camera trajectory from original monocular recording.
- Existing furniture removal from live camera video; none implemented.
- Restoration of ARCore room anchors after restart without manually marking reference points.

## Real device acceptance

Install the APK from the latest successful Android workflow, open the bundled reference kitchen in 3D, then enter AR if the device supports ARCore. Mark two tracked floor points at least 0.5 m apart under the conventional sink window and place the kitchen assembly. Add the refrigerator by marking two new floor points at the opposite solid wall. Verify a 0.6 m cabinet with a physical ruler, circle the island, check door clearance, switch real lights and inspect shadows/occlusion. Record pass/fail, device build, AR service version and FPS under `docs/device-testing.md`.

**Status:** CI-verified Android source, asset fidelity and model generation; final live AR behavior remains device-unverified.
