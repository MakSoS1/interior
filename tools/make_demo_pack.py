#!/usr/bin/env python3
"""Build the exact portable package used by Android instrumented import tests."""
from pathlib import Path
import shutil
import trimesh
from trimesh.visual.material import PBRMaterial
from interior_adapter import pack, validate

ROOT=Path(__file__).resolve().parents[1]

def generate(output: Path) -> Path:
    output.mkdir(parents=True,exist_ok=True)
    wood=PBRMaterial(name='oak',baseColorFactor=[177,143,100,255],roughnessFactor=.72,metallicFactor=0)
    cream=PBRMaterial(name='cream',baseColorFactor=[218,215,203,255],roughnessFactor=.64,metallicFactor=0)
    def box(scene,name,center,size,material):
        mesh=trimesh.creation.box(extents=size);mesh.apply_translation(center);mesh.visual.material=material
        scene.add_geometry(mesh,node_name=name,geom_name=name)
    cabinet=trimesh.Scene()
    # Intentionally off-origin to verify the Android floor-pivot adapter.
    box(cabinet,'Cabinet body',(4.3,2.4,-1.7),(.6,.8,.6),wood)
    box(cabinet,'Door',(4.3,2.4,-1.39),(.56,.75,.02),cream)
    cabinet_path=output/'Cabinet_60cm.glb';cabinet.export(cabinet_path)
    chairs=trimesh.Scene()
    for n,x in enumerate([0.0,.70]):
        box(chairs,f'chair_{n}_seat',(x,.45,0),(.44,.05,.44),cream)
        for dx in [-.17,.17]:
            for dz in [-.17,.17]:box(chairs,f'chair_{n}_leg_{dx}_{dz}',(x+dx,.225,dz),(.035,.45,.035),wood)
        box(chairs,f'chair_{n}_back',(x,.72,.20),(.44,.48,.04),wood)
    chairs_path=output/'Two_chairs.glb';chairs.export(chairs_path)
    package=output/'adapter_demo.interior.zip'
    pack([cabinet_path,chairs_path],package,'Пример адаптера · тумба и стулья')
    validate(package)
    return package

if __name__=='__main__':
    output=ROOT/'dist';package=generate(output)
    assets=ROOT/'app/src/main/assets';assets.mkdir(parents=True,exist_ok=True)
    shutil.copy2(package,assets/package.name)
    models=[assets/n for n in ['reference_wall.glb','reference_island.glb','fridge_opposite_wall.glb']]
    if all(p.exists() for p in models):
        pack(models,output/'kitchen_groups.interior.zip','Кухня · независимые группы')
    print('Portable demo:',package)
