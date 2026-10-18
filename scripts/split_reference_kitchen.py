#!/usr/bin/env python3
"""Export the reference kitchen into independently anchorable GLB assemblies.

The full original photo-inspired scene remains untouched for offline preview.
AR-only exports have local floor origin at the back-left of their group.
Wall assembly +X right, +Y up, +Z outward into the room.
Island anchor origin is the back-left FLOOR corner (x=island_x,z=island_z).
"""
import argparse
import json
from pathlib import Path

import numpy as np
import trimesh
from build_reference_kitchen import DIMS, scene

def is_island(name: str) -> bool:
    return (name.startswith(("island_", "stool_", "pendant_", "induction_", "counter_lemon", "counter_coffee_tray"))
            or any(name.startswith(p) for p in (
                "ceramic_planter_0", "ceramic_planter_1",
                "herb_stem_0_", "herb_stem_1_", "herb_leaf_0_", "herb_leaf_1_"
            )))

def extract_scene(group: str) -> trimesh.Scene:
    out = trimesh.Scene()
    shift = np.eye(4)
    if group == "island":
        shift[:3, 3] = (-DIMS["island_x"], 0, -DIMS["island_z"])
    for node in sorted(scene.graph.nodes_geometry):
        mat, geom = scene.graph[node]
        if is_island(node) != (group == "island"):
            continue
        out.add_geometry(scene.geometry[geom].copy(),
                         geom_name=node, node_name=node, transform=shift @ mat)
    return out

def export(output_dir: Path):
    output_dir.mkdir(parents=True, exist_ok=True)
    details = {"scale":"metres","axes":{"x":"wall to right","y":"vertical up","z":"from wall into room"},
               "source_scene":"reference kitchen: geometry only, not surveyed"}
    for name in ("wall", "island"):
        group = extract_scene(name)
        path = output_dir / f"reference_{name}.glb"
        path.write_bytes(group.export(file_type="glb"))
        bounds = group.bounds
        details[name] = {
            "file":path.name,
            "count":len(group.graph.nodes_geometry),
            "minimum":bounds[0].round(5).tolist(),
            "maximum":bounds[1].round(5).tolist(),
            "size":(bounds[1]-bounds[0]).round(5).tolist(),
        }
        print(f"{name}: {details[name]['count']} objects, bounds {details[name]['size']}")
    return details

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", default="app/src/main/assets")
    parser.add_argument("--manifest", default="assets/placement_groups_manifest.json")
    args = parser.parse_args()
    manifest = export(Path(args.output_dir))
    target = Path(args.manifest)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+"\n", encoding="utf8")
