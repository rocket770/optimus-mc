"""Rasterize the renderer's simple SVG marks for offline visual inspection."""
from pathlib import Path
import xml.etree.ElementTree as ET
from PIL import Image, ImageDraw, ImageFont
root = Path(__file__).resolve().parent.parent/'diagnostics'
try:
    font = ImageFont.truetype('DejaVuSans.ttf',12)
except OSError:
    font = ImageFont.load_default()
for name in ('parkour-after-preview','parkour-before-preview','parkour-narrow-preview'):
    source=root/(name+'.svg')
    if not source.exists(): continue
    svg=ET.parse(source).getroot()
    _,_,width,height=map(float,svg.attrib['viewBox'].split())
    image=Image.new('RGBA',(int(width),int(height)),'white')
    for mark in svg:
        a=mark.attrib;tag=mark.tag.split('}')[-1];layer=Image.new('RGBA',image.size);draw=ImageDraw.Draw(layer)
        fill=a.get('fill','#20252a');stroke=a.get('stroke');fill=None if fill=='none' else fill
        if tag in ('polygon','polyline'):
            points=[tuple(map(float,p.split(','))) for p in a['points'].split()]
            if tag=='polygon': draw.polygon(points,fill=fill,outline=stroke)
            else: draw.line(points,fill=stroke,width=round(float(a.get('stroke-width',1))),joint='curve')
        elif tag=='circle':
            x,y,r=(float(a[k]) for k in ('cx','cy','r'));draw.ellipse((x-r,y-r,x+r,y+r),fill=fill)
        elif tag=='rect':
            x,y,w,h=(float(a[k]) for k in ('x','y','width','height'));draw.rectangle((x,y,x+w,y+h),fill=fill,outline=stroke)
        elif tag=='text':
            draw.text((float(a['x']),float(a['y'])-12),mark.text or '',fill=fill,font=font)
        opacity=float(a.get('fill-opacity',1))
        if opacity<1: layer.putalpha(layer.getchannel('A').point(lambda v:int(v*opacity)))
        image=Image.alpha_composite(image,layer)
    image.convert('RGB').save(root/(name+'.png'))
print('Rendered available map previews')