"""Package the user-supplied app logos into the APK icon pack.

Convention already in force in app/src/main/assets/appicons: 192x192 RGBA PNG,
kebab-case ASCII filename. Sources come from test/应用logo (the batch the user
dropped today) and test/logo/logo (the official pack).
"""
import os
import sys

from PIL import Image

ROOT = r"D:\Github\LabProbeApp"
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "appicons")
NEW = os.path.join(ROOT, "test", "应用logo")
OFFICIAL = os.path.join(ROOT, "test", "logo", "logo")

# target key -> (source folder, source filename)
PLAN = {
    # 官方特征库里有应用、我们一直没有画图的：以前这些 key 会掉到 CDN 上，
    # 拿到什么图全看第三方仓库，「腾讯会议显示成今日头条」就是这么来的。
    "tencent-meeting": (NEW, "腾讯会议.png"),
    "wecom": (NEW, "企业微信.png"),
    "bilibili": (OFFICIAL, "哔哩哔哩.png"),
    # 用户今天补的 logo。
    "baidu": (NEW, "百度.PNG"),
    "wechat-pay": (NEW, "微信支付.png"),
    "safety-education": (NEW, "安全教育平台.PNG"),
    "delta-force": (NEW, "三角洲行动.png"),
    "sams-club": (NEW, "山姆会员商店.png"),
    "tp-link-iot": (NEW, "TP-LINK物联.png"),
    "haier-smart-home": (NEW, "海尔智家.png"),
    "midea-meiju": (NEW, "美的美居.png"),
    "xigua-video": (NEW, "西瓜视频.png"),
    "xingtu": (NEW, "醒图.png"),
    "xiaoai": (NEW, "小爱同学.png"),
    "eleme": (NEW, "饿了么.png"),
    "fanqie-novel": (NEW, "番茄小说.png"),
    "dingtalk": (NEW, "钉钉.png"),
    "weibo": (NEW, "微博.png"),
    "kuaishou": (NEW, "快手.png"),
    "qq-music": (NEW, "QQ音乐.png"),
    # 夸克（浏览器和网盘共用一个入口图标）与菜鸟都做成独立特征，图配套。
    "quark": (NEW, "夸克.png"),
    "cainiao": (NEW, "菜鸟.png"),
}

SIZE = (192, 192)


def convert(src: str, dst: str) -> str:
    with Image.open(src) as im:
        im = im.convert("RGBA")
        if im.size != SIZE:
            im = im.resize(SIZE, Image.LANCZOS)
        im.save(dst, "PNG", optimize=True)
    return f"{os.path.basename(dst)}  {im.size} {im.mode}"


def main() -> int:
    missing = []
    for key, (folder, name) in PLAN.items():
        src = os.path.join(folder, name)
        if not os.path.exists(src):
            missing.append(name)
            print(f"MISSING SOURCE: {name}")
            continue
        print(convert(src, os.path.join(OUT, f"{key}.png")))
    if missing:
        return 1
    print(f"\n{len(PLAN)} icons written to appicons/")
    return 0


if __name__ == "__main__":
    sys.exit(main())
