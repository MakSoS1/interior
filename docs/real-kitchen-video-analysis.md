# Real-room video and reference image — evidence-based reconstruction

**Source date:** 2026-10-18. **Video:** `2026-10-18 15.40.42.mp4`, 720×1280 portrait, 17.898 s, H.264, approximately 25.59 fps average (458 decoded frames). **Design image:** `ChatGPT Image Oct 18, 2026, 04_08_56 PM.png`.

**Source confidentiality:** the user explicitly authorized publishing both original media in this public GitHub repository on 2026-10-18. Media transfer status must be verified separately; these are the desired target paths: `reference/source/room_walkthrough_original.mp4` and `reference/source/scandinavian_kitchen_reference.png`. Do not claim the binaries are in Git until GitHub confirms their blob SHA and URLs. The photo is a design reference, not evidence of the room's actual geometry.

## 1. What is visible in the actual video

| Video time | Camera/view | Directly supported observation | Modeling consequence |
|---|---|---|---|
| 0.0–2.0 s | Wide starting angle | Freestanding dining table and chairs in foreground; kitchen run on the left; broad window above the sink; tall shelving/cabinet near the corner | Replace the table area with the island; retain left-wall worktop under the smaller regular window |
| 2.0–6.0 s | Camera pans toward outside | At least one large floor-to-ceiling glazed opening with door profile and dark frame, curtain in pink/lilac tones, threshold and outdoor patio | **Keep this façade entirely clear**. Do not put the range, solid cupboards, refrigerator, or decorative wall behind its glass |
| 6.0–7.5 s | Camera returns toward work zone | Glazed door and tall cabinet/vertical shelves are next to each other; temporary table at foreground | Treat vertical cabinets as a corner element only where opaque wall exists |
| 8.0–10.0 s | Head-on shot of worktop | Stainless dishwasher, oven, round inset sink, existing counter under the conventional horizontal window | Sink/dishwasher belong to the wall with the ordinary window; induction hob may move to the island as in the design photo |
| 10.0–12.5 s | Close-up of oven, sink, temporary hob | Portable two-burner hob on a narrow separate support at the corner; *not* an existing permanent cooktop installation | Remove temporary hob/table from future 3D furniture; check power/extraction requirements for proposed island separately |
| 12.5–14.0 s | Camera passes glazed door and temporary table | Second clear angle on near-full-height glazing and direct exterior light; dining table is in front of the glass | Preserve panoramic light and avoid modeling these panels as a kitchen appliance wall |
| 14.0–16.0 s | Camera rotates to opposite side | Blue two-door tall refrigerator on a predominantly opaque wall, not in the glass opening | Place refrigerator **on the wall opposite the floor-height glazing**, not within the base cabinet run adjacent to the ordinary window |
| 16.0–17.9 s | Further turn toward adjacent small prep area | Existing small worktop and bare wall edge near refrigerator | Model separate refrigerator zone rather than assuming all units follow one straight wall |

Confidence: **high** for relative adjacency and the distinction between ordinary window / panoramic opening / opposite solid refrigerator wall. **Medium** for approximate distances and island orientation. **Low** for exact length/width, window reveals, plumbing endpoints, lens intrinsics, wall squareness and floor level because the video is an uncalibrated handheld sweep.

## 2. Target design from supplied still

Photo shows a Scandinavian/warm-Japandi concept: off-white slab fronts; light oak cabinetry, vertical island oak cladding; light warm quartz/stone worktops; black flush induction hob on island; around three oak/linen stools; two cream pendant shades; recessed lit open shelf; sink below ordinary window; narrow tall cabinetry; greenery and decor.

**Do not copy photograph walls/windows blindly:** the image composition is illustrative and does not establish where the existing refrigerator actually stands. Keep the transparent glass in its video-confirmed location and the refrigerator on the opposite solid wall.

## 3. Layout coordinate convention and components

Modeling coordinates in metres; +Y is up, +X is to the right along the sink/base-run wall as seen head-on; +Z points away from the sink wall into the room.

**Phase A — existing reference model** (`scripts/build_reference_kitchen.py`):
- Straight base run under conventional window, approximate length 2.55 m (NOT measured)
- Worktop with sink/dishwasher; tall pantry/corner shelves
- Island with estimated 2.55×1.05 m footprint, induction hob, three bar stools and two pendants
- Current complete model bounding box approximately 3.89 × 2.62 × 3.227 m; this is **model bounds, not room dimensions**
- Individual elements remain named in generated GLB to permit changes in Blender

**Phase B — geometry-to-room registration**, not reliably solved from this video alone:
- A: floor/wall corner under the ordinary sink window; B: measured point along that same wall
- Place base run on A→B
- Move island to measured offset forward with usable circulation around both long sides
- Place refrigerator against opaque wall **opposite** full-height glass, at an independently measured reference
- Exclude all background walls/window geometry from GLB: real video supplies them
- Confirm clearance to the glass door and swings before any cabinetry purchase

**Critical limitation:** the current Android AR view uses a **single rigid root anchor** per imported GLB. Consequently, if a model combines opposite-wall refrigerator and island with guessed relative separation, it will not automatically align both in the real room. The defensible workflow is to (a) measure room and use a correct common model frame, or (b) implement multiple independent modular anchors. Do not interpret current two-point placement as room-mapping.

## 4. What can be tested

Automated:
- Deterministic GLB export, embedded PBR maps and materials
- Realistic dimensions of authored objects and world-space mesh bounding box
- Model loading in SceneView, Android build, basic project persistence
- Robustness of projection on moving frames where enough image features exist

Not established without calibrated footage / real AR device:
- Correct metric world scale (a homography is only a 2D planar transform)
- Room depth reconstruction or camera 6-DOF positions from this particular video
- Correct occlusion by existing table and appliances, accurate reflections, actual lamp position
- Robust anchor persistence and live ARCore placement on realme 14 Pro+ or Pixel 10
- Whether same photographs would prove ARCore functionality; they do not

## 5. Manual measurements that would resolve uncertainties

Before claiming construction-ready positioning, measure at least the usable sink-wall length; adjacent wall-to-panorama distance; true distance of the solid fridge wall from the glazing; glazing width and door opening direction; sink position along its wall; kitchen finished ceiling height; island utility outlets/pipes near former dining table; minimum circulation clearance around island.

## 6. Testing the original video

The offline video overlay is a *visual proof of concept* that tracks 2D features and projects a guessed 3D model. The existing example covers ~3.1 s of the ~17.9 s source and should always be captioned "illustrative uncalibrated overlay". It is neither a real ARCore camera capture nor a metrically recovered room. In the full video, separate wall views require true 6-DOF tracking or segmented calibration; a single initial-image homography cannot explain the entire walkthrough.

## 7. Media access

Publication target files are `reference/source/room_walkthrough_original.mp4` and `reference/source/scandinavian_kitchen_reference.png`. Raw private-stage files remain attached to the ChatGPT conversation until GitHub binary upload is confirmed. A named target path is not proof of upload.

**Original photo copyright/attribution:** supplied by user as intended design reference; do not state that it is a photograph of the actual room. The room recording was shot by the user.
