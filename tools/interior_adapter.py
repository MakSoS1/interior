#!/usr/bin/env python3
"""Package self-contained GLB files into independently placeable Interior AR groups.

Python 3.10+, standard library only. No network, Blender or Android SDK required.
A GLB can contain one object or an entire rigid group. Use a different GLB for
anything you need to move independently. Blender exporter is provided separately.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import math
import struct
import zipfile
from pathlib import Path, PurePosixPath
from typing import Iterable

MAX_GLB=200*1024*1024
MAX_PACKAGE=512*1024*1024


def inspect_glb(path: Path) -> dict:
    size=path.stat().st_size
    if not 28 <= size <= MAX_GLB:
        raise ValueError(f'{path.name}: GLB size must be 28 bytes–200 MiB')
    with path.open('rb') as src:
        magic,version,total,length,kind=struct.unpack('<IIIII',src.read(20))
        if (magic,version,total,kind)!=(0x46546C67,2,size,0x4E4F534A) or not 2<=length<=8*1024*1024 or length+20>=size:
            raise ValueError(f'{path.name}: invalid binary glTF 2.0 header')
        doc=json.loads(src.read(length))
    if doc.get('asset',{}).get('version')!='2.0' or not doc.get('meshes'):
        raise ValueError(f'{path.name}: no glTF 2.0 mesh')
    if any('uri' in item for field in ('buffers','images') for item in doc.get(field,[])):
        raise ValueError(f'{path.name}: embed all resources in GLB first')
    if not doc.get('scenes') or not doc.get('nodes'):
        raise ValueError(f'{path.name}: no scene')
    return doc


def digest(path: Path) -> str:
    value=hashlib.sha256()
    with path.open('rb') as src:
        for block in iter(lambda:src.read(65536),b''):
            value.update(block)
    return value.hexdigest()


def pack(models: Iterable[Path], output: Path, name: str='Interior project', units: str='m', authored_pivot: bool=False) -> dict:
    models=[Path(p) for p in models]
    if not 1<=len(models)<=32 or not name.strip() or len(name)>160:
        raise ValueError('Use 1–32 groups and a title of 1–160 characters')
    scale={'m':1.0,'cm':0.01,'mm':0.001}[units]
    for model in models:inspect_glb(model)
    if sum(p.stat().st_size for p in models)>MAX_PACKAGE:
        raise ValueError('Package exceeds 512 MiB')
    if output.resolve() in {p.resolve() for p in models}:
        raise ValueError('Output must not overwrite an input model')
    manifest={'format':'interior-ar','schemaVersion':1,'name':name,'groups':[],
              'lighting':{'natural':True,'exposureStops':0.0,'lock':False}}
    output.parent.mkdir(parents=True,exist_ok=True)
    temporary=output.with_name(output.name+'.tmp')
    try:
        with zipfile.ZipFile(temporary,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
            written=set()
            for index,model in enumerate(models):
                sha=digest(model); asset=f'models/{sha}.glb'
                entry={'id':f'group_{index}','name':model.stem[:160],'asset':asset,'sha256':sha,'metresPerUnit':scale,
                       'transform':{'position':[0,0,0],'yawDegrees':0,'scale':1}}
                if authored_pivot:entry['pivot']=[0,0,0]
                manifest['groups'].append(entry)
                if asset not in written:
                    archive.write(model,asset);written.add(asset)
            archive.writestr('interior.json',json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    return manifest


def validate(path: Path) -> dict:
    with zipfile.ZipFile(path) as z:
        entries=z.infolist()
        if len(entries)>128 or sum(e.file_size for e in entries)>MAX_PACKAGE:
            raise ValueError('Oversized archive')
        names=set()
        for e in entries:
            p=PurePosixPath(e.filename)
            if p.is_absolute() or '..' in p.parts or '\\' in e.filename or ':' in e.filename or e.filename.lower() in names:
                raise ValueError('Unsafe or duplicate archive entry')
            names.add(e.filename.lower())
        if z.getinfo('interior.json').file_size>1024*1024:
            raise ValueError('Manifest exceeds 1 MiB')
        data=json.loads(z.read('interior.json'))
        if data.get('format')!='interior-ar' or data.get('schemaVersion')!=1:
            raise ValueError('Unsupported schema')
        groups=data.get('groups',[])
        if not 1<=len(groups)<=32 or len({g['id'] for g in groups})!=len(groups):
            raise ValueError('Invalid group count or duplicate IDs')
        for g in groups:
            s=g.get('metresPerUnit',1)
            if not isinstance(s,(float,int)) or not math.isfinite(s) or not 1e-6<=s<=1000:
                raise ValueError('Invalid unit scale')
            info=z.getinfo(g['asset'])
            if not g['asset'].endswith('.glb') or info.file_size>MAX_GLB:
                raise ValueError('Invalid model asset')
            md=hashlib.sha256()
            with z.open(info) as src:
                for block in iter(lambda:src.read(65536),b''):md.update(block)
            if g.get('sha256') and g['sha256']!=md.hexdigest():
                raise ValueError('Model checksum mismatch')
        return data


def main() -> None:
    parser=argparse.ArgumentParser(description=__doc__)
    sub=parser.add_subparsers(dest='command',required=True)
    p=sub.add_parser('pack',help='each GLB becomes a separately placeable group')
    p.add_argument('models',type=Path,nargs='+');p.add_argument('--output','-o',type=Path,required=True)
    p.add_argument('--name',default='Interior project');p.add_argument('--units',choices=['m','cm','mm'],default='m')
    p.add_argument('--authored-pivot',action='store_true',help='preserve authored origin instead of automatic floor pivot')
    v=sub.add_parser('validate');v.add_argument('package',type=Path)
    args=parser.parse_args()
    try:
        result=pack(args.models,args.output,args.name,args.units,args.authored_pivot) if args.command=='pack' else validate(args.package)
        print(json.dumps({'name':result['name'],'groups':len(result['groups']),'valid':True},ensure_ascii=False))
    except (ValueError,OSError,KeyError,zipfile.BadZipFile,json.JSONDecodeError) as error:
        parser.exit(2,f'Error: {error}\n')

if __name__=='__main__':main()
