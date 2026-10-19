#!/usr/bin/env python3
"""Package self-contained GLB objects/groups for Interior AR; Python 3.11+, no dependencies."""
from __future__ import annotations
import argparse, hashlib, json, os, struct, tempfile, zipfile
from pathlib import Path

MAX_FILE=200*1024*1024
MAX_TOTAL=256*1024*1024
MAX_ASSETS=24

def check_glb(path:Path)->None:
    if path.suffix.lower()!='.glb' or not path.is_file():
        raise ValueError(f'Expected an existing .glb file: {path}')
    size=path.stat().st_size
    if not 28<=size<=MAX_FILE: raise ValueError(f'GLB must be 28 bytes..200 MiB: {path.name}')
    with path.open('rb') as f:
        magic,version,declared,count,kind=struct.unpack('<IIIII',f.read(20))
        if (magic,version,declared,kind)!=(0x46546c67,2,size,0x4e4f534a):
            raise ValueError(f'Invalid GLB 2.0: {path.name}')
        if count>8*1024*1024 or count<=0 or 20+count>size: raise ValueError('Invalid GLB JSON size')
        data=json.loads(f.read(count))
        for key in ('buffers','images'):
            if any('uri' in x for x in data.get(key,[])):
                raise ValueError('Embed every buffer and texture into GLB before exporting')

def package_glbs(output:Path,models:list[Path],name:str='Furniture')->Path:
    output=Path(output);models=[Path(p) for p in models]
    if not 1<=len(models)<=MAX_ASSETS: raise ValueError('Choose 1..24 GLB objects/groups')
    for p in models: check_glb(p)
    if sum(p.stat().st_size for p in models)>MAX_TOTAL: raise ValueError('Package expanded size exceeds 256 MiB')
    output.parent.mkdir(parents=True,exist_ok=True)
    manifest={'format':'interior-ar','version':1,'units':'m','name':str(name)[:160],'assets':[]}
    handle,tmp=tempfile.mkstemp(prefix='.interior-',suffix='.zip',dir=output.parent);os.close(handle)
    try:
        with zipfile.ZipFile(tmp,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
            for i,path in enumerate(models):
                file=f'models/asset-{i:03d}.glb'
                with path.open('rb') as stream: digest=hashlib.file_digest(stream,'sha256').hexdigest()
                manifest['assets'].append({'id':f'asset-{i:03d}','name':path.stem[:160],
                    'file':file,'sha256':digest,'origin':'floor'})
                z.write(path,file)
            z.writestr('manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
        os.replace(tmp,output)
    finally:
        Path(tmp).unlink(missing_ok=True)
    return output

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('models',nargs='+',type=Path)
    parser.add_argument('-o','--output',required=True,type=Path)
    parser.add_argument('--name',default='Furniture')
    args=parser.parse_args()
    try: print(package_glbs(args.output,args.models,args.name))
    except (ValueError,OSError) as e: parser.exit(2,str(e)+'\n')
