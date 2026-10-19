"""Install this file as a Blender add-on, or run it with Blender --background.
Each top-level scene collection becomes one independent AR group. Child objects
stay together, preserve PBR materials and internal transforms. Source is not moved.
"""
bl_info={'name':'Interior AR portable export','author':'Interior AR contributors','version':(1,0,0),'blender':(3,6,0),'location':'File > Export','description':'Export top-level collections to .interior.zip','category':'Import-Export'}
import argparse
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import zipfile
import bpy
from bpy.props import StringProperty
from bpy_extras.io_utils import ExportHelper


def export_collections(output: Path, context=None) -> dict:
    context=context or bpy.context
    if abs(context.scene.unit_settings.scale_length-1.0)>1e-7:
        raise ValueError('Set Scene Units / Unit Scale to 1.0 and author metric dimensions before export')
    groups=[c for c in context.scene.collection.children if any(o.type=='MESH' for o in c.all_objects)]
    if not 1<=len(groups)<=32:
        raise ValueError('Create 1–32 top-level collections containing meshes; each collection is one AR group')
    previous=list(context.selected_objects);active=context.view_layer.objects.active
    output=Path(output);output.parent.mkdir(parents=True,exist_ok=True)
    temporary=output.with_name(output.name+'.tmp')
    manifest={'format':'interior-ar','schemaVersion':1,'name':output.name.removesuffix('.interior.zip')[:160],
              'groups':[],'lighting':{'natural':True,'exposureStops':0.0,'lock':False}}
    try:
        if context.mode!='OBJECT':bpy.ops.object.mode_set(mode='OBJECT')
        with tempfile.TemporaryDirectory(prefix='interior-ar-') as directory:
            files=[]
            for index,collection in enumerate(groups):
                bpy.ops.object.select_all(action='DESELECT')
                candidates=[o for o in collection.all_objects if o.name in context.view_layer.objects and o.visible_get()]
                for o in candidates:o.select_set(True)
                if not any(o.type=='MESH' for o in candidates):
                    raise ValueError(f'Collection {collection.name} has no visible meshes')
                path=Path(directory)/f'group_{index}.glb'
                result=bpy.ops.export_scene.gltf(filepath=str(path),export_format='GLB',use_selection=True,
                    export_yup=True,export_animations=False,export_apply=True,export_extras=True,
                    export_cameras=False,export_lights=False)
                if 'FINISHED' not in result or not path.is_file():raise RuntimeError('glTF export failed')
                sha=hashlib.sha256(path.read_bytes()).hexdigest()
                asset=f'models/group_{index}.glb'
                manifest['groups'].append({'id':f'group_{index}','name':collection.name[:160],'asset':asset,
                    'sha256':sha,'metresPerUnit':1.0,'transform':{'position':[0,0,0],'yawDegrees':0,'scale':1}})
                files.append((path,asset))
            with zipfile.ZipFile(temporary,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
                for path,name in files:archive.write(path,name)
                archive.writestr('interior.json',json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
            temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
        bpy.ops.object.select_all(action='DESELECT')
        for o in previous:
            if o.name in context.view_layer.objects:o.select_set(True)
        if active and active.name in context.view_layer.objects:context.view_layer.objects.active=active
    return manifest


class EXPORT_SCENE_OT_interior_ar(bpy.types.Operator,ExportHelper):
    bl_idname='export_scene.interior_ar'
    bl_label='Interior AR (.interior.zip)'
    bl_options={'PRESET'}
    filename_ext='.interior.zip'
    filter_glob:StringProperty(default='*.interior.zip',options={'HIDDEN'})
    def execute(self,context):
        try:
            data=export_collections(Path(self.filepath),context)
            self.report({'INFO'},'Exported '+str(len(data['groups']))+' AR groups')
            return {'FINISHED'}
        except Exception as e:
            self.report({'ERROR'},str(e));return {'CANCELLED'}


def menu_export(self,context):self.layout.operator(EXPORT_SCENE_OT_interior_ar.bl_idname,text='Interior AR (.interior.zip)')
def register():
    bpy.utils.register_class(EXPORT_SCENE_OT_interior_ar)
    bpy.types.TOPBAR_MT_file_export.append(menu_export)
def unregister():
    bpy.types.TOPBAR_MT_file_export.remove(menu_export)
    bpy.utils.unregister_class(EXPORT_SCENE_OT_interior_ar)

if __name__=='__main__':
    if '--' in sys.argv:
        parser=argparse.ArgumentParser();parser.add_argument('--output',required=True,type=Path)
        args=parser.parse_args(sys.argv[sys.argv.index('--')+1:]);print(json.dumps(export_collections(args.output),ensure_ascii=False))
    else:register()
