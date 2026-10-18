#!/usr/bin/env python3
"""Reproducible parametric reconstruction of the *design* reference (not measured room).
Outputs Blender-importable glTF GLB in meters; Y-up, +X along window wall, +Z into room.
All dimensions are assumptions and can be edited via this Python script.
"""
import argparse, math, json
from pathlib import Path
from collections import Counter
import numpy as np
import trimesh
from PIL import Image
from trimesh.visual.material import PBRMaterial
from trimesh.visual.texture import TextureVisuals

SEED=18026
rng=np.random.default_rng(SEED)

# metres. Furniture x-coordinate runs from 0 to 4.05, the island is in front of the run.
DIMS={
 'base_run_x': 0.10, 'base_run_width': 2.55, 'base_depth': .62, 'base_height': .875,
 'tall_unit_x': 2.75, 'tall_unit_width': .62, 'tall_unit_depth': .65, 'tall_unit_height': 2.43,
 'open_shelf_x':3.42,'open_shelf_width':.58,'open_shelf_depth':.45,
 'island_x':.76,'island_z':1.60,'island_width':2.55,'island_depth':1.05,'island_height':.91,
 'counter_thickness':.038,'ceiling_height':2.62,'stool_z':3.01
}
materials={}

def texture(kind,n=256):
    xx,yy=np.meshgrid(np.arange(n)/n,np.arange(n)/n)
    if kind=='oak':
        grain=(np.sin(xx*180 + 2.3*np.sin(yy*12) + 1.2*np.sin(yy*39))+ .25*np.sin(xx*460+yy*9)+.2*rng.normal(size=(n,n)))
        gain=(grain*10+6*np.sin(xx*38+yy*3))
        rgb=np.stack([174+gain,125+gain*.78,78+gain*.47],axis=-1)
    elif kind=='stone':
        veins=np.sin(xx*9+yy*13+7*np.sin(xx*5-yy*7))*np.cos(xx*15+yy*25)
        shade=np.maximum(veins-.73,0)*95+rng.normal(0,2.0,(n,n))
        rgb=np.stack([235-shade,230-shade*.93,220-shade*.83],axis=-1)
    elif kind=='cloth':
        lines=np.sin(xx*210)*2+np.sin(yy*210)*2+rng.normal(0,1,(n,n))
        rgb=np.stack([223+lines,212+lines,188+lines],axis=-1)
    elif kind=='wood_floor':
        grain=np.sin(xx*130+np.sin(yy*25)) * 7+rng.normal(0,2,(n,n))
        rgb=np.stack([176+grain,148+grain,112+grain],axis=-1)
    else: raise ValueError(kind)
    return Image.fromarray(np.clip(rgb,0,255).astype('uint8'),'RGB')

spec={
 'oak': dict(roughnessFactor=.68,metallicFactor=0,baseColorTexture=texture('oak')),
 'light_oak':dict(roughnessFactor=.72,metallicFactor=0,baseColorTexture=texture('oak')),
 'quartz':dict(roughnessFactor=.34,metallicFactor=.02,baseColorTexture=texture('stone')),
 'cream':dict(roughnessFactor=.36,metallicFactor=.0,baseColorFactor=[232,225,210,255]),
 'satin_cream':dict(roughnessFactor=.53,metallicFactor=.0,baseColorFactor=[226,219,204,255]),
 'stainless':dict(roughnessFactor=.29,metallicFactor=.82,baseColorFactor=[176,185,183,255]),
 'brushed_gold':dict(roughnessFactor=.25,metallicFactor=.85,baseColorFactor=[176,132,62,255]),
 'black':dict(roughnessFactor=.30,metallicFactor=.28,baseColorFactor=[24,27,29,255]),
 'hob_glass':dict(roughnessFactor=.16,metallicFactor=.36,baseColorFactor=[26,31,32,255]),
 'warm_white':dict(roughnessFactor=.66,metallicFactor=.0,baseColorFactor=[251,235,198,255]),
 'plant':dict(roughnessFactor=.81,metallicFactor=.0,baseColorFactor=[58,102,56,255]),
 'plant_light':dict(roughnessFactor=.84,metallicFactor=0,baseColorFactor=[105,130,69,255]),
 'terracotta':dict(roughnessFactor=.79,metallicFactor=0,baseColorFactor=[188,169,143,255]),
 'linen':dict(roughnessFactor=.83,metallicFactor=0,baseColorTexture=texture('cloth')),
 'lemon':dict(roughnessFactor=.63,metallicFactor=0,baseColorFactor=[221,175,39,255]),
 'fruit_green':dict(roughnessFactor=.7,metallicFactor=0,baseColorFactor=[117,148,63,255]),
 'glass':dict(roughnessFactor=.14,metallicFactor=.10,baseColorFactor=[189,198,184,255]),
}
for k,v in spec.items(): materials[k]=PBRMaterial(name=k,**v)

# Make a box with 6 planar-UV faces, so procedural textures are deterministic in glTF.
def box_mesh(size):
    template=trimesh.creation.box(extents=size)
    p=template.vertices[template.faces].reshape(-1,3)
    mesh=trimesh.Trimesh(vertices=p,faces=np.arange(len(p)).reshape(-1,3),process=False)
    uv=[]
    for tri in range(0,len(p),3):
        pts=p[tri:tri+3]
        n=np.cross(pts[1]-pts[0],pts[2]-pts[0]);axis=int(np.argmax(abs(n)))
        ix=([1,2] if axis==0 else [0,2] if axis==1 else [0,1])
        side=pts[:,ix]
        lens=np.ptp(side,axis=0)
        lens=np.where(lens<1e-8,1.,lens)
        vals=(side-side.min(axis=0))/lens
        uv.extend(vals.tolist())
    return mesh,np.array(uv)

scene=trimesh.Scene(); part_counts=Counter(); items=[]
def register(name,mesh,mat,center=None,uv=None):
    if center is not None: mesh.apply_translation(center)
    if uv is None:
        # planar projection for curved objects: no texture required except boxes
        if spec[mat].get('baseColorTexture') is not None:
            mat='cream'
        mesh.visual.material=materials[mat]
    else:mesh.visual=TextureVisuals(uv=np.array(uv), material=materials[mat])
    scene.add_geometry(mesh,geom_name=name,node_name=name)
    part_counts[mat]+=1;items.append(name)

def box(name,c,size,mat):
    m,u=box_mesh(size)
    register(name,m,mat,center=c,uv=u)

def tube(name,origin,target,r,mat,sections=10):
    o=np.asarray(origin,float);t=np.asarray(target,float);delta=t-o
    if np.linalg.norm(delta)<1e-5:return
    m=trimesh.creation.cylinder(radius=r,height=np.linalg.norm(delta),sections=sections)
    m.apply_transform(trimesh.geometry.align_vectors([0,0,1], delta))
    register(name,m,mat,center=(o+t)/2)

def sphere(name,c,r,mat,subdivisions=1):
    m=trimesh.creation.icosphere(subdivisions=subdivisions,radius=r)
    register(name,m,mat,center=c)

def dome(name,c,top_radius=.052,bottom_radius=.23,height=.24,material='cream'):
    # lathed pendant bell profile Y-up (radial bulge)
    segments=28;rings=10;verts=[];faces=[]
    for k in range(rings):
        h=k/(rings-1);radius=top_radius+(bottom_radius-top_radius)*math.sin(h*math.pi/2)**.78
        for i in range(segments):
            th=2*math.pi*i/segments
            verts.append([radius*math.cos(th), -height*h,radius*math.sin(th)])
    for k in range(rings-1):
        for i in range(segments):
            a=k*segments+i;b=k*segments+(i+1)%segments;d=(k+1)*segments+i;e=(k+1)*segments+(i+1)%segments
            faces.extend([[a,b,e],[a,e,d]])
    m=trimesh.Trimesh(vertices=verts,faces=faces,process=False)
    register(name,m,material,center=c)

def disk(name,c,r,height,mat,sections=24):
    register(name,trimesh.creation.cylinder(radius=r,height=height,sections=sections),mat,center=c)

# Wall kitchen beneath window: real window geometry is not modelled because camera provides it.
run_x=DIMS['base_run_x'];back=.0
for i,(w,kind) in enumerate([(.59,'dishwasher'),(.77,'sink'),(.58,'drawer'),(.61,'drawer2')]):
    x=run_x+sum([.59,.77,.58,.61][:i]);cx=x+w/2
    box(f'wall_{kind}_cabinet_body',(cx,.436,.31),(w-.018,.872,.60),'oak')
    if kind=='dishwasher':
        box('dishwasher_stainless_face',(cx,.438,.624),(w-.037,.815,.011),'stainless')
        box('dishwasher_control_band',(cx,.818,.632),(w-.068,.058,.008),'black')
    else:
        for side in ([0] if kind=='sink' else [-1,1]):
            fy=.445+(side*.205 if side else 0)
            fh=.813 if side==0 else .398
            box(f'{kind}_cream_front_{side}',(cx,fy,.626),(w-.035,fh,.014),'cream')
            box(f'{kind}_oak_pencil_pull_{side}',(cx,fy+.13 if side else fy+.30,.636),(w*.52,.012,.017),'oak')
box('back_white_quartz_counter',(run_x+1.275,.896,.315),(2.57,.038,.645),'quartz')
box('sink_recess',(1.15,.917,.345),(.59,.012,.38),'black')
box('sink_stainless_base',(1.15,.922,.345),(.51,.011,.29),'stainless')
tube('sink_faucet_column',(1.43,.93,.11),(1.43,1.15,.11),.012,'stainless')
tube('sink_faucet_arm',(1.43,1.15,.11),(1.32,1.15,.25),.011,'stainless')
tube('sink_faucet_down',(1.32,1.15,.25),(1.32,1.07,.25),.009,'stainless')

# Floor-to-ceiling tall unit and the recessed illuminated shelves beside it.
x=2.75;w=.62
box('pantry_oak_left_side',(x+.013,1.22,.33),(.025,2.44,.65),'oak')
box('pantry_oak_right_side',(x+w-.013,1.22,.33),(.025,2.44,.65),'oak')
box('pantry_upper_cream_door',(x+w/2,1.965,.65),(w-.037,.89,.016),'cream')
box('pantry_lower_cream_door',(x+w/2,.715,.65),(w-.037,1.36,.016),'cream')
box('pantry_vertical_handle',(x+w-.075,1.21,.672),(.013,.23,.02),'brushed_gold')
for i,y in enumerate([.50,1.13,1.75,2.37]):
    box(f'shelving_oak_shelf_{i}',(3.69,y,.255),(.56,.038,.50),'oak')
box('shelving_top',(3.69,2.51,.24),(.56,.035,.50),'oak')
box('shelving_back',(3.69,1.49,.012),(.58,2.0,.018),'satin_cream')
for i in range(3):
    box(f'shelf_led_strip_{i}',(3.69,1.11+i*.62,.495),(.40,.009,.015),'warm_white')

# The island; warm oak cladding has repeated narrow vertical slats.
ix,iz=DIMS['island_x'],DIMS['island_z'];iw,idp=DIMS['island_width'],DIMS['island_depth']
box('island_wood_carcass',(ix+iw/2,.437,iz+idp/2),(iw-.11,.85,idp-.12),'oak')
box('island_rear_flat_oak',(ix+iw/2,.435,iz+.06),(iw-.06,.87,.07),'oak')
box('island_front_wood_panels',(ix+iw/2,.435,iz+idp-.037),(iw-.045,.87,.06),'light_oak')
for i in range(36):
    xx=ix+.06+i*(iw-.12)/35
    box(f'island_vertical_oak_slats_{i:02d}',(xx,.433,iz+idp+.003),(.028,.855,.018),'oak')
box('island_end_wood_panel',(ix+.027,.437,iz+idp/2),(.045,.87,idp-.06),'oak')
box('island_far_end',(ix+iw-.027,.437,iz+idp/2),(.045,.87,idp-.06),'oak')
box('island_quartz_top',(ix+iw/2,.910,iz+idp/2),(iw+.11,.041,idp+.23),'quartz')
box('island_induction_hob',(ix+1.09,.939,iz+.42),(.74,.009,.51),'hob_glass')
for px in [ix+.84,ix+1.36]:
    for zz in [iz+.27, iz+.54]:
        # visible induction-ring highlights
        for k in range(14):
            a=k*2*math.pi/14
            tube('induction_ring_%d_%d_%02d'%(int(px*100),int(zz*100),k),
                 (px+.084*math.cos(a),.946,zz+.084*math.sin(a)),
                 (px+.084*math.cos(a+2*math.pi/14),.946,zz+.084*math.sin(a+2*math.pi/14)),.003,'stainless',6)

# 3 bar stools: solid oak legs and frames, lightly upholstered seat, slim curved back.
for n,sx in enumerate([ix+.45,ix+1.29,ix+2.10]):
    sz=DIMS['stool_z']
    box(f'stool_{n}_linen_seat',(sx,.685,sz),(.46,.062,.39),'linen')
    for dx in [-.165,.165]:
        for dz in [-.125,.125]:
            tube(f'stool_{n}_leg_{int((dx+.2)*100)}_{int((dz+.2)*100)}',
                 (sx+dx,.04,sz+dz),(sx+dx*.95,.68,sz+dz*.95),.020,'oak')
    tube(f'stool_{n}_footrest_front',(sx-.158,.245,sz+.118),(sx+.158,.245,sz+.118),.013,'oak')
    tube(f'stool_{n}_footrest_left',(sx-.16,.26,sz-.118),(sx-.16,.26,sz+.118),.011,'oak')
    tube(f'stool_{n}_back_support_l',(sx-.177,.60,sz+.138),(sx-.23,.99,sz+.18),.018,'oak')
    tube(f'stool_{n}_back_support_r',(sx+.177,.60,sz+.138),(sx+.23,.99,sz+.18),.018,'oak')
    box(f'stool_{n}_curved_back',(sx,.985,sz+.184),(.47,.14,.046),'oak')

# Two pendant lamps and low-glare warm interior, fixed ceiling height assumed 2.62m.
for n,sx in enumerate([ix+.72, ix+1.85]):
    tube(f'pendant_{n}_cable',(sx,DIMS['ceiling_height'],iz+.49),(sx,2.285,iz+.49),.003,'black')
    dome(f'pendant_{n}_cream_bell',(sx,2.300,iz+.49),height=.235,material='cream')
    disk(f'pendant_{n}_underside_warm',(sx,2.053,iz+.49),.206,.006,'warm_white')
    disk(f'pendant_{n}_gold_stem',(sx,2.306,iz+.49),.035,.048,'brushed_gold')

# Decorating with ceramic vases, branches, bowls, lemons and pantry jars.
for j,(xx,zz) in enumerate([(ix+1.99,iz+.28),(ix+2.17,iz+.26),(2.25,.31),(3.67,.26)]):
    floor=.943 if j<2 else (.94 if j==2 else 1.15)
    register(f'ceramic_planter_{j}',trimesh.creation.cone(radius=.090,height=.17,sections=18),'terracotta',center=(xx,floor+.085,zz))
    for k in range(7):
        angle=k*2*math.pi/7
        target=(xx+math.cos(angle)*.12,floor+.28+(k%3)*.055,zz+math.sin(angle)*.12)
        tube(f'herb_stem_{j}_{k}',(xx,floor+.17,zz),target,.004,'plant')
        sphere(f'herb_leaf_{j}_{k}',target,.045,'plant_light',1)
for n,sx in enumerate([ix+2.29,ix+2.44]):
    sphere(f'counter_lemon_{n}',(sx,.966,iz+.29),.055,'lemon')
disk('counter_coffee_tray',(ix+2.17,.948,iz+.36),.235,.012,'light_oak')

META={
 'kind':'interior-ar-reference-kitchen', 'status':'approximate_parametric_reconstruction',
 'scene_units':'meters', 'axes':{'x':'along kitchen wall to right','y':'up','z':'from wall into room'},
 'reference':'user supplied design image, 2026-10-18',
 'room_measured':False,'sizes_are_assumptions':True,
 'assumptions_m':DIMS,'main_island_bbox_m':[iw+.11,.931,idp+.23],
 'materials':list(materials),'pieces':len(items),
 'node_names':items,
 'limitations':['Not surveyed; no floor plan or intrinsics supplied','Walls/windows excluded: these remain real through camera','No removal of existing table or cabinetry']
}

def build(output,manifest):
    output=Path(output); output.parent.mkdir(parents=True,exist_ok=True)
    output.write_bytes(scene.export(file_type='glb'))
    bounds=scene.bounds; META['bounding_box_m']=(bounds[1]-bounds[0]).round(4).tolist()
    META['bounding_min_m']=bounds[0].round(4).tolist()
    META['bounding_max_m']=bounds[1].round(4).tolist()
    if manifest:
        m=Path(manifest);m.parent.mkdir(parents=True,exist_ok=True)
        m.write_text(json.dumps(META,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:META[k] for k in ['pieces','bounding_box_m','bounding_min_m','bounding_max_m']},ensure_ascii=False))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--output',default='app/src/main/assets/reference_kitchen.glb');p.add_argument('--manifest',default='assets/reference_kitchen_manifest.json')
    x=p.parse_args();build(x.output,x.manifest)