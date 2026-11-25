"""Prepare user-supplied device and round manufacturer assets for review."""
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw

SOURCE = Path('C:/Users/admin/Pictures/Saved Pictures/设备')
DEST = Path(__file__).resolve().parent / 'assets' / 'hub-brandicons'
BRANDS = {'360':'360', '海思视讯':'hisilicon_video', 'Aqara':'aqara',
          'GIGABYTE':'gigabyte', 'GOOGLE':'google', 'IKUAI':'ikuai', 'IQOO':'iqoo',
          'LENOVO':'lenovo', 'LITTLEGENIUS':'littlegenius', 'NETGEAR':'netgear',
          'TENDA':'tenda', 'XIANGRIKUI':'xiangrikui', 'ZTE':'zte'}
DEVICES = {'智能插座':'smartplug', '交换机':'netswitch', '触屏音箱':'touchspeaker'}


def fit(im, size, bound):
    im = im.convert('RGBA')
    bbox = im.getchannel('A').getbbox()
    if not bbox:
        raise ValueError('empty image')
    im = im.crop(bbox)
    scale = bound / max(im.width, im.height)
    im = im.resize((max(1, round(im.width * scale)), max(1, round(im.height * scale))), Image.Resampling.LANCZOS)
    canvas = Image.new('RGBA', (size, size))
    canvas.alpha_composite(im, ((size-im.width)//2, (size-im.height)//2))
    return canvas


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    for filename, key in BRANDS.items():
        image = Image.open(SOURCE / (filename + '.png')).convert('RGBA')
        # A white circular badge keeps the supplied mark intact and makes the
        # transparent wordmarks legible against the original UI background.
        circle = Image.new('L', (256,256), 0)
        ImageDraw.Draw(circle).ellipse((1,1,254,254), fill=255)
        badge = Image.new('RGBA', (256,256), (255,255,255,255))
        badge.putalpha(circle)
        badge.alpha_composite(fit(image, 256, 188))
        badge.putalpha(ImageChops.multiply(badge.getchannel('A'), circle))
        badge.save(DEST / (key + '.png'))
    for filename, key in DEVICES.items():
        fit(Image.open(SOURCE / (filename + '.png')), 256, 220).save(DEST / ('device_' + key + '.png'))
    # The supplied baseline's existing "other" foreground sits in the lower
    # left of an oversized transparent canvas. Correct only that one resource.
    original = (Path(__file__).resolve().parents[2] / 'test/_h3b/1793170771/decoded/res/drawable-mdpi'
                / 'src_assets_image_common_icon_statype_other.png')
    foreground = Image.open(original).convert('RGBA')
    foreground = foreground.crop(foreground.getchannel('A').getbbox())
    scale = 500 / max(foreground.width, foreground.height)
    foreground = foreground.resize((round(foreground.width * scale), round(foreground.height * scale)), Image.Resampling.LANCZOS)
    corrected = Image.new('RGBA', (576, 600))
    corrected.alpha_composite(foreground, ((576-foreground.width)//2, (600-foreground.height)//2))
    corrected.save(DEST / 'device_other_centered.png')
    print('Prepared', len(BRANDS), 'brand and', len(DEVICES), 'device assets')


if __name__ == '__main__':
    main()
