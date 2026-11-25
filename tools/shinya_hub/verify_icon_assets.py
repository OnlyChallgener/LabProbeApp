"""Inspect actual exported icon pixels; never transforms source images."""
from pathlib import Path
from PIL import Image
from patch_device_catalog import BRANDS,TYPES
ROOT=Path(__file__).resolve().parents[2]

def check():
 for kind,items in [('brand',BRANDS),('device',TYPES)]:
  for key,_ in items:
   path=ROOT/'tools/shinya_hub/assets/hub-brandicons'/((key if kind=='brand' else 'device_'+key)+'.png')
   with Image.open(path) as im:
    assert im.mode=='RGBA',(key,'requires RGBA')
    w,h=im.size;alpha=im.getchannel('A');assert alpha.getbbox(),(key,'empty')
    for xy in ((0,0),(w-1,0),(0,h-1),(w-1,h-1)):assert alpha.getpixel(xy)==0,(key,'opaque corners')
    if kind=='brand':
     assert (w,h)==(256,256),(key,'canonical badge canvas')
     assert alpha.getpixel((128,128))>0,(key,'missing round backing')
     for y in range(h):
      for x in range(w):
       if ((x-127.5)**2+(y-127.5)**2)>129**2:assert alpha.getpixel((x,y))==0,(key,'outside circular badge')
 print('PASS',len(BRANDS),'circular brand assets and',len(TYPES),'transparent nonempty device assets; real RGBA pixels inspected')
if __name__=='__main__':check()
