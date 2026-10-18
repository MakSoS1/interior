#!/usr/bin/env python3
"""Generate an original, deterministic, meter-scaled GLB sample kitchen; no external assets."""
import argparse
import json
import struct
from pathlib import Path

COLORS = {
    "oak": ([0.48, 0.30, 0.16, 1], .72, 0.02),
    "cream": ([0.83, 0.81, 0.74, 1], .46, 0.03),
    "quartz": ([0.79, 0.79, 0.77, 1], .26, 0.05),
    "steel": ([0.37, 0.42, 0.46, 1], .22, 0.76),
    "black": ([0.035, 0.037, 0.043, 1], .18, 0.32),
    "glass": ([0.085, 0.105, 0.12, 1], .15, 0.12),
}
# Standard cube, 24 unique corners so every side is flat shaded.
FACES = [
    ((1,0,0), [(1,-1,-1),(1,1,-1),(1,1,1),(1,-1,1)]),
    ((-1,0,0), [(-1,-1,1),(-1,1,1),(-1,1,-1),(-1,-1,-1)]),
    ((0,1,0), [(-1,1,-1),(-1,1,1),(1,1,1),(1,1,-1)]),
    ((0,-1,0), [(-1,-1,1),(-1,-1,-1),(1,-1,-1),(1,-1,1)]),
    ((0,0,1), [(1,-1,1),(1,1,1),(-1,1,1),(-1,-1,1)]),
    ((0,0,-1), [(-1,-1,-1),(-1,1,-1),(1,1,-1),(1,-1,-1)]),
]
def make_glb(path):
    boxes = []
    def box(name, xyz, size, material):
        boxes.append(dict(name=name, xyz=xyz, size=size, material=material))
    # Project local origin: left-back floor, right +X, up +Y, front into room +Z
    units = [("drawer", 0.0, .6),("sink", .6, .8),("hob", 1.4, .6)]
    for name, x, width in units:
        box(name+"_carcass", (x+width/2, .445, .305), (width,.85,.59), "oak")
        box(name+"_front", (x+width/2, .45, .604), (width-.025,.81,.021), "cream")
        box(name+"_handle", (x+width/2, .69, .62), (min(.24,width-.08),.016,.026), "steel")
        box(name+"_legs", (x+width/2,.05,.31), (width-.1,.1,.5), "black")
    box("continuous_stone_countertop", (1.0,.889,.3175), (2.0,.038,.635), "quartz")
    box("sink_cutout_dark", (1.0,.909,.36), (.52,.006,.39), "black")
    box("sink_bowl", (1.0,.903,.36), (.44,.01,.31), "steel")
    box("sink_tap_vertical", (1.0,1.018,.105), (.025,.24,.03), "steel")
    box("sink_tap_spout", (1.08,1.128,.19), (.17,.021,.025), "steel")
    box("cooktop", (1.70,.913,.32), (.52,.015,.42), "glass")
    for x in [1.56,1.83]:
        for z in [.20,.42]:
            box("burner", (x,.925,z), (.105,.004,.105), "steel")
    box("fridge", (2.3,1.025,.325), (.6,2.05,.65), "steel")
    box("fridge_front", (2.3,1.025,.653), (.58,2.03,.009), "cream")
    box("fridge_handle", (2.05,1.27,.665), (.024,.36,.025), "black")
    for n,x in enumerate([.4,1.3]):
        box(f"upper_{n}_body", (x,1.76,.18), (.8,.72,.35), "oak")
        box(f"upper_{n}_door", (x,1.76,.36), (.77,.69,.013), "cream")
        box(f"upper_{n}_handle", (x,1.44,.377), (.22,.015,.017), "steel")
    positions=[]; normals=[]; indices=[]
    for normal,corners in FACES:
        base=len(positions)//3
        for corner in corners:
            positions.extend([c/2 for c in corner]); normals.extend(normal)
        indices.extend([base,base+1,base+2, base,base+2,base+3])
    raw=bytearray()
    def view(data, fmt, target):
        start=len(raw); raw.extend(struct.pack("<"+fmt*len(data),*data))
        while len(raw)%4: raw.append(0)
        return {"buffer":0,"byteOffset":start,"byteLength":len(raw)-start,"target":target}
    views=[view(positions,"f",34962),view(normals,"f",34962),view(indices,"H",34963)]
    accessors=[
        {"bufferView":0,"componentType":5126,"count":len(positions)//3,"type":"VEC3","min":[-.5,-.5,-.5],"max":[.5,.5,.5]},
        {"bufferView":1,"componentType":5126,"count":len(normals)//3,"type":"VEC3"},
        {"bufferView":2,"componentType":5123,"count":len(indices),"type":"SCALAR"}
    ]
    mats=[]
    for name,(rgba,rough,metallic) in COLORS.items():
        mats.append({"name":name,"pbrMetallicRoughness":{"baseColorFactor":rgba,"roughnessFactor":rough,"metallicFactor":metallic},"doubleSided":False})
    names=list(COLORS)
    meshes=[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1},"indices":2,"material":i}]} for i in range(len(names))]
    nodes=[{"name":x["name"],"mesh":names.index(x["material"]),"translation":list(x["xyz"]),"scale":list(x["size"])} for x in boxes]
    data={"asset":{"version":"2.0","generator":"Interior AR demo builder"},"scene":0,"scenes":[{"nodes":list(range(len(nodes)))}],"nodes":nodes,"meshes":meshes,"materials":mats,"accessors":accessors,"bufferViews":views,"buffers":[{"byteLength":len(raw)}]}
    js=json.dumps(data,separators=(",",":"),ensure_ascii=False).encode("utf8")
    js+=b" " * ((-len(js))%4)
    size=12+8+len(js)+8+len(raw)
    binary=struct.pack("<III",0x46546c67,2,size)+struct.pack("<I4s",len(js),b"JSON")+js+struct.pack("<I4s",len(raw),b"BIN\x00")+raw
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_bytes(binary)
    return len(boxes)

if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--output",default="app/src/main/assets/demo_kitchen.glb")
    args=parser.parse_args()
    count=make_glb(Path(args.output))
    print(f"Wrote demo kitchen: {count} parts, width 2.60m, {args.output}")
