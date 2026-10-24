"""Code-authored line icons matching the original router tool row."""
from pathlib import Path
from PIL import Image,ImageDraw
out=Path(__file__).with_name('assets')/'hub-ui';out.mkdir(exist_ok=True)
scale=4
def draw_icon(kind):
    image=Image.new('RGBA',(96*scale,96*scale));d=ImageDraw.Draw(image)
    def points(values):return [(int(x*scale),int(y*scale)) for x,y in values]
    def line(values):d.line(points(values),fill='#3b3d40',width=5*scale,joint='curve')
    def ellipse(box):d.ellipse(tuple(int(v*scale) for v in box),outline='#3b3d40',width=5*scale)
    if kind=='ddns':
        ellipse((13,13,76,76));ellipse((30,13,59,76));line([(14,45),(75,45)])
        d.rounded_rectangle((48*scale,55*scale,88*scale,86*scale),radius=6*scale,fill='white',outline='#3b3d40',width=5*scale)
        line([(57,65),(65,65)]);line([(72,76),(80,76)])
    else:
        line([(47,11),(76,22),(76,44),(71,61),(61,73),(47,84),(33,73),(23,61),(18,44),(18,22),(47,11)])
        # Three staggered bricks inside the shield; no central vertical bar.
        line([(31,35),(44,35)]);line([(53,35),(65,35)])
        line([(30,47),(64,47)]);line([(35,59),(56,59)])
    image.resize((96,96),Image.Resampling.LANCZOS).save(out/(kind+'.png'))
for kind in ('ddns','firewall'):draw_icon(kind)
