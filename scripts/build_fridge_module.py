#!/usr/bin/env python3
"""An independent metric refrigerator component for a different opaque wall.

Design dimensions are assumptions. The standalone GLB is aligned and anchored on
the real wall opposite the panoramic glass, rather than rigidly positioned next
to the sink-run window based on guessed floor-plan dimensions.
"""
import argparse
import json
from pathlib import Path
import trimesh
from trimesh.visual.material import PBRMaterial

WIDTH=1.10
DEPTH=.72
HEIGHT=2.37

MATERIALS={
 "cream":PBRMaterial(name="warm_off_white",baseColorFactor=[232,226,212,255],roughnessFactor=.48,metallicFactor=0),
 "oak":PBRMaterial(name="oak_side",baseColorFactor=[183,137,90,255],roughnessFactor=.67,metallicFactor=0),
 "stainless":PBRMaterial(name="brushed_stainless",baseColorFactor=[155,166,166,255],roughnessFactor=.32,metallicFactor=.82),
 "black":PBRMaterial(name="dark_trim",baseColorFactor=[35,35,35,255],roughnessFactor=.38,metallicFactor=.24)
}

def build(output,manifest):
    scene=trimesh.Scene()
    names=[]
    def box(name,centre,dimensions,material):
        mesh=trimesh.creation.box(extents=dimensions)
        mesh.apply_translation(centre)
        mesh.visual.material=MATERIALS[material]
        scene.add_geometry(mesh,geom_name=name,node_name=name)
        names.append(name)

    # +X along wall, +Y vertical, +Z into room
    box("rear_frame",(WIDTH/2,HEIGHT/2,.018),(WIDTH,HEIGHT,.036),"oak")
    box("left_side",(.022,HEIGHT/2,DEPTH/2),(.044,HEIGHT,DEPTH),"oak")
    box("right_side",(WIDTH-.022,HEIGHT/2,DEPTH/2),(.044,HEIGHT,DEPTH),"oak")
    box("upper_cap",(WIDTH/2,HEIGHT-.018,DEPTH/2),(WIDTH,.036,DEPTH),"oak")
    box("left_integrated_door",(.28,1.18,DEPTH-.026),(.505,2.20,.033),"cream")
    box("right_integrated_door",(.82,1.18,DEPTH-.026),(.505,2.20,.033),"cream")
    box("centre_gap",(WIDTH/2,1.17,DEPTH-.003),(.015,2.22,.002),"black")
    box("oak_toe_kick",(WIDTH/2,.055,DEPTH-.08),(1.01,.105,.035),"oak")
    box("left_handle",(.518,1.20,DEPTH+.015),(.016,.45,.035),"stainless")
    box("right_handle",(.582,1.20,DEPTH+.015),(.016,.45,.035),"stainless")
    box("side_finish_return",(WIDTH+.024,HEIGHT/2,DEPTH/2),(.048,HEIGHT,.70),"oak")

    output=Path(output)
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_bytes(scene.export(file_type="glb"))
    data={
        "name":"opposite-wall-fridge",
        "component":"independently placed unit",
        "version":1,
        "dimensions_assumed_not_measured":True,
        "axes":{"x":"along wall","y":"up","z":"toward room"},
        "design_dimensions_m":[WIDTH,HEIGHT,DEPTH],
        "mesh_bounds_m":(scene.bounds[1]-scene.bounds[0]).round(4).tolist(),
        "nodes":names,
        "required_wall":"opaque wall opposite full-height glazing",
        "why_separate":"The video shows refrigerator against a different wall; a single unmeasured rigid anchor must not guess the separation."
    }
    if manifest:
        p=Path(manifest)
        p.parent.mkdir(parents=True,exist_ok=True)
        p.write_text(json.dumps(data,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"parts":len(names),"bounds_m":data["mesh_bounds_m"]}))

if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--output",default="app/src/main/assets/fridge_opposite_wall.glb")
    parser.add_argument("--manifest",default="assets/fridge_module_manifest.json")
    args=parser.parse_args()
    build(args.output,args.manifest)
