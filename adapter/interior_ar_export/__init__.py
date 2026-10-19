"""Blender 4+ add-on: export evaluated static geometry as metric AR groups."""
bl_info={'name':'Interior AR — Objects & Groups','author':'Interior AR project',
    'version':(1,0,0),'blender':(4,0,0),'location':'File > Export > Interior AR',
    'description':'Export selected furniture as one rigid GLB group or separate collection groups in a ZIP',
    'category':'Import-Export'}
import bpy
from bpy_extras.io_utils import ExportHelper
from bpy.props import EnumProperty,StringProperty
from mathutils import Matrix
from pathlib import Path
import hashlib,json,tempfile,zipfile,os

MAX_TOTAL=256*1024*1024

def selection_groups(context,mode):
    selected=set(context.selected_objects)
    if not selected: raise ValueError('Select furniture objects first')
    expanded=set(selected)
    for obj in selected: expanded.update(obj.children_recursive)
    if mode=='ONE': groups=[('Selected furniture',expanded)]
    else:
        groups=[];claimed=set()
        for coll in sorted(context.scene.collection.children,key=lambda c:c.name):
            members=(set(coll.all_objects)&expanded)-claimed
            if members: groups.append((coll.name,members));claimed.update(members)
        for obj in sorted(expanded-claimed,key=lambda x:x.name): groups.append((obj.name,{obj}))
    result=[]
    for name,objs in groups:
        meshes=[o for o in sorted(objs,key=lambda x:x.name) if o.type in {'MESH','CURVE','SURFACE','META','FONT'}]
        if meshes: result.append((name,meshes))
    if not result: raise ValueError('No exportable geometry. Realize collection instances first.')
    if len(result)>24: raise ValueError('Maximum 24 independent groups per package')
    return result

def export_package(context,output,mode='ONE'):
    output=Path(output)
    if not output.name.endswith('.interior.zip'): output=output.with_name(output.name+'.interior.zip')
    groups=selection_groups(context,mode)
    original_selection=list(context.selected_objects);original_active=context.view_layer.objects.active
    original_unit=context.scene.unit_settings.scale_length
    original_mode=context.object.mode if context.object else 'OBJECT'
    collection=bpy.data.collections.new('__INTERIOR_AR_EXPORT_TEMP__');context.scene.collection.children.link(collection)
    clones=[];meshes=[];output.parent.mkdir(parents=True,exist_ok=True)
    try:
        if original_mode!='OBJECT': bpy.ops.object.mode_set(mode='OBJECT')
        depsgraph=context.evaluated_depsgraph_get()
        with tempfile.TemporaryDirectory(prefix='interior-export-') as d:
            base=Path(d);manifest={'format':'interior-ar','version':1,'units':'m','name':output.stem,'assets':[]}
            scale_matrix=Matrix.Scale(float(original_unit),4)
            for i,(title,objects) in enumerate(groups):
                bpy.ops.object.select_all(action='DESELECT');current=[]
                for obj in objects:
                    evaluated=obj.evaluated_get(depsgraph)
                    mesh=bpy.data.meshes.new_from_object(evaluated,preserve_all_data_layers=True,depsgraph=depsgraph)
                    if mesh is None: continue
                    clone=bpy.data.objects.new(obj.name+'_AR',mesh);meshes.append(mesh);clones.append(clone);current.append(clone)
                    collection.objects.link(clone);clone.matrix_world=scale_matrix @ obj.matrix_world;clone.select_set(True)
                if not current: raise ValueError('No mesh in '+title)
                context.view_layer.objects.active=current[0];context.scene.unit_settings.scale_length=1.0
                glb=base/f'asset-{i:03d}.glb'
                status=bpy.ops.export_scene.gltf(filepath=str(glb),export_format='GLB',use_selection=True,
                    export_yup=True,export_apply=False,export_animations=False,export_cameras=False,export_lights=False)
                if 'FINISHED' not in status or not glb.is_file(): raise RuntimeError('glTF exporter failed: '+title)
                if glb.stat().st_size>200*1024*1024: raise ValueError('A group exceeds 200 MiB')
                with glb.open('rb') as f: digest=hashlib.file_digest(f,'sha256').hexdigest()
                manifest['assets'].append({'id':f'asset-{i:03d}','name':title[:160],
                    'file':'models/'+glb.name,'sha256':digest,'origin':'floor'})
                for clone in current: clone.select_set(False)
                context.scene.unit_settings.scale_length=original_unit
            if sum(p.stat().st_size for p in base.glob('*.glb'))>MAX_TOTAL: raise ValueError('Package exceeds 256 MiB')
            archive=base/'result.zip'
            with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
                z.writestr('manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
                for a in manifest['assets']: z.write(base/Path(a['file']).name,a['file'])
            with archive.open('rb') as src,tempfile.NamedTemporaryFile(dir=output.parent,delete=False) as dst:
                tmp=Path(dst.name)
                import shutil
                shutil.copyfileobj(src,dst);dst.flush();os.fsync(dst.fileno())
            try: os.replace(tmp,output)
            finally: tmp.unlink(missing_ok=True)
    finally:
        context.scene.unit_settings.scale_length=original_unit
        for obj in clones:
            if obj.name in bpy.data.objects: bpy.data.objects.remove(obj,do_unlink=True)
        for mesh in meshes:
            if mesh.users==0: bpy.data.meshes.remove(mesh)
        bpy.data.collections.remove(collection)
        bpy.ops.object.select_all(action='DESELECT')
        for obj in original_selection:
            if obj.name in context.view_layer.objects: obj.select_set(True)
        context.view_layer.objects.active=original_active
        if original_active and original_mode!='OBJECT': bpy.ops.object.mode_set(mode=original_mode)
    return str(output)

class INTERIOR_OT_export(bpy.types.Operator,ExportHelper):
    bl_idname='export_scene.interior_ar';bl_label='Export Interior AR package';bl_options={'PRESET'}
    filename_ext='.interior.zip'
    filter_glob:StringProperty(default='*.interior.zip',options={'HIDDEN'})
    grouping:EnumProperty(name='Grouping',items=[
        ('ONE','One rigid group','Keep all selected furniture together'),
        ('COLLECTIONS','Separate collections','Each selected top-level collection can be placed independently')],default='ONE')
    def execute(self,context):
        try:
            output=export_package(context,self.filepath,self.grouping);self.report({'INFO'},'Saved '+output);return {'FINISHED'}
        except Exception as e:
            self.report({'ERROR'},str(e));return {'CANCELLED'}

def menu_export(self,context): self.layout.operator(INTERIOR_OT_export.bl_idname,text='Interior AR (.interior.zip)')
def register():
    bpy.utils.register_class(INTERIOR_OT_export);bpy.types.TOPBAR_MT_file_export.append(menu_export)
def unregister():
    bpy.types.TOPBAR_MT_file_export.remove(menu_export);bpy.utils.unregister_class(INTERIOR_OT_export)
if __name__=='__main__': register()
