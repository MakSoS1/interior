#!/usr/bin/env python3
"""Label structural constraints in the original 17.9s room video, with no fake AR claims."""
import argparse
from pathlib import Path
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont
import subprocess

FONT="/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

def segment(t):
    if t<2.5: return ("0–2 s | МОЙКА ПОД ОКНОМ","Остров вместо обеденного стола")
    if t<7.5: return ("3–7 s | ПАНОРАМНЫЕ ОКНА","Оставить стекло и проход свободными")
    if t<12.5: return ("8–12 s | РАБОЧАЯ ЗОНА","ПММ + мойка, плита будет на острове")
    if t<14.5: return ("12–14 s | ПАНОРАМНЫЕ ОКНА","Не размещать шкафы или плиту перед остеклением")
    return ("14–18 s | ХОЛОДИЛЬНИК","На отдельной стене НАПРОТИВ панорамных окон")

def run(src,dst):
    reader=cv2.VideoCapture(str(src))
    assert reader.isOpened(),"Could not decode input"
    fps=reader.get(cv2.CAP_PROP_FPS) or 25.0
    w=int(reader.get(cv2.CAP_PROP_FRAME_WIDTH))
    h=int(reader.get(cv2.CAP_PROP_FRAME_HEIGHT))
    tmp=dst.with_suffix(".silent.mp4")
    writer=cv2.VideoWriter(str(tmp),cv2.VideoWriter_fourcc(*"mp4v"),fps,(w,h))
    assert writer.isOpened(),"Could not create output"
    small=ImageFont.truetype(FONT,18)
    large=ImageFont.truetype(FONT,25)
    n=0
    while True:
        ok,frame=reader.read()
        if not ok:break
        title,subtitle=segment(n/fps)
        canvas=Image.fromarray(cv2.cvtColor(frame,cv2.COLOR_BGR2RGB)).convert("RGBA")
        layer=Image.new("RGBA",(w,h),(0,0,0,0))
        painter=ImageDraw.Draw(layer)
        painter.rounded_rectangle((12,14,w-12,135),radius=12,fill=(0,0,0,185))
        painter.text((24,27),"INTERIOR AR • АНАЛИЗ РЕАЛЬНОЙ КОМНАТЫ",font=small,fill=(255,220,153,255))
        painter.text((24,57),title,font=large,fill=(255,255,255,255))
        painter.text((24,100),subtitle,font=small,fill=(250,250,250,255))
        painter.rounded_rectangle((10,h-73,w-10,h-8),radius=10,fill=(0,0,0,185))
        painter.text((22,h-57),"НЕ ARCore | Неизмеренная геометрия",font=small,fill=(255,255,255,255))
        painter.text((22,h-32),f"Видео: {n/fps:.1f} s | Проверить масштаб рулеткой",font=small,fill=(255,221,153,255))
        output=Image.alpha_composite(canvas,layer).convert("RGB")
        writer.write(cv2.cvtColor(np.asarray(output),cv2.COLOR_RGB2BGR))
        n+=1
    reader.release()
    writer.release()
    proc=subprocess.run(["ffmpeg","-y","-loglevel","error","-i",str(tmp),"-i",str(src),
       "-map","0:v:0","-map","1:a:0?","-c:v","libx264","-preset","veryfast",
       "-crf","23","-pix_fmt","yuv420p","-c:a","aac","-b:a","96k","-shortest",str(dst)],capture_output=True,text=True)
    if proc.returncode:raise RuntimeError(proc.stderr)
    tmp.unlink(missing_ok=True)
    print("Frames:",n,"duration:",n/fps)

if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--input",required=True)
    parser.add_argument("--output",required=True)
    args=parser.parse_args()
    run(Path(args.input),Path(args.output))
