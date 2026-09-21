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
# 用户按「补充特征」批次单独放的 logo，和上面两批分开。
SUPP = os.path.join(ROOT, "test", "应用logo", "补充特征")
SUPP1 = os.path.join(ROOT, "test", "应用logo", "补充图标1")

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
    "mijia": (OFFICIAL, "米家.png"),
    # 认不出来的应用一律用这张：显式打包，别让它靠「碰巧同名」生效。
    "unknown": (OFFICIAL, "未识别应用图标.png"),
    # 特征库有 10-5-2-0 红果免费短剧，logo 早就在这堆文件里，只是没人加映射。
    "hongguo-shortdrama": (OFFICIAL, "红果免费短剧.png"),
    # 本轮 test/应用logo 新增：这些应用在 309 款目录里，但一直没有映射。
    "bank-of-china": (NEW, "中国银行.png"),
    "xuexitong": (NEW, "学习通.png"),
    "sohu-video": (NEW, "搜狐视频.png"),
    "baidu-translate": (NEW, "百度翻译.png"),
    "tencent-weishi": (NEW, "腾讯微视.png"),
    "lol-mobile": (NEW, "英雄联盟手游.png"),
    # 文件名写着「酷狗音乐#酷我音乐」，图只有一张蓝色 K —— 那是酷狗的标。
    # 只挂给酷狗：一张图盖两个品牌，就是又一次「腾讯会议显示成今日头条」。
    "kugou-music": (NEW, "酷狗音乐#酷我音乐.png"),
    # 第二轮新增。文件名和特征库名不一致的（YY语音 -> YY、番茄小说 -> 番茄免费
    # 小说）在这里显式对上，不靠猜。
    "duolingo": (NEW, "多邻国.png"),
    "youdao-dict": (NEW, "有道词典.png"),
    "yy": (NEW, "YY语音.png"),
    "global-net-test": (NEW, "全球网测.png"),
    # 「补充特征」那一批：UU远程 + 360 两个 + 亲宝宝，WPS 换清晰版。
    "uu-remote": (SUPP, "UU远程.jpg"),
    "qihoo-kids-watch": (SUPP, "360儿童卫士.png"),
    "qihoo-smart-life": (SUPP, "360智慧生活.png"),
    "qinbaobao": (SUPP, "亲宝宝.jpg"),
    "wps-office": (SUPP, "WPS Office.png"),
    # 「补充图标1」这一批：11 张全部命中目录里的应用，且此前一张图都没有。
    "taptap": (SUPP1, "TapTap.PNG"),
    "abc-bank": (SUPP1, "中国农业银行.png"),
    "icbc": (SUPP1, "中国工商银行.jpg"),
    "renren-video": (SUPP1, "人人视频.PNG"),
    "call-of-duty-mobile": (SUPP1, "使命召唤手游.PNG"),
    "tantan": (SUPP1, "探探.jpg"),
    "huajiao-live": (SUPP1, "花椒直播.png"),
    "suning": (SUPP1, "苏宁易购.PNG"),
    "huya-live": (SUPP1, "虎牙直播.jpg"),
    "momo": (SUPP1, "陌陌.png"),
    "meizu-app-store": (SUPP1, "魅族应用商店.PNG"),
    # 应用宝换新版四色风车标。以前没进 PLAN，图标是早先手工放进去的，
    # 所以源图换了也不会生效 —— 登记进来才改得动。
    "yyb": (NEW, "应用宝.png"),
    # 补充图标1 的第二批（含替换掉的农业银行）。
    "95meinvxiu": (SUPP1, "95美女秀.png"),
    "pp-video": (SUPP1, "PP视频.PNG"),
    "huawei-video": (SUPP1, "华为视频.PNG"),
    "pumpkin-film": (SUPP1, "南瓜电影.PNG"),
    "zumu": (SUPP1, "瞩目.png"),
}

SIZE = (192, 192)


def flatten_to_white(im: "Image.Image") -> "Image.Image":
    """透明图统一压到不透明白底。

    这些图标会出现在白色卡片上，也会出现在带底色的位置（特征库、列表行选中态），
    透明 PNG 在那儿会露出底色，一排图标就深浅不齐。已经不透明白底的不重压。
    """
    if im.getchannel("A").getextrema()[0] >= 250:
        return im.convert("RGB")
    flat = Image.new("RGBA", im.size, (255, 255, 255, 255))
    flat.alpha_composite(im)
    return flat.convert("RGB")


def convert(src: str, dst: str) -> str:
    with Image.open(src) as im:
        im = im.convert("RGBA")
        if im.size != SIZE:
            im = im.resize(SIZE, Image.LANCZOS)
        im = flatten_to_white(im)
        im.save(dst, "PNG", optimize=True)
    return f"{os.path.basename(dst)}  {im.size} {im.mode}"


def normalize_existing() -> int:
    """把目录里历史遗留的透明图一起压白 —— 它们没有登记在 PLAN 里，不补这一步就还是花的。"""
    changed = 0
    for name in sorted(os.listdir(OUT)):
        if not name.lower().endswith(".png"):
            continue
        path = os.path.join(OUT, name)
        with Image.open(path) as im:
            rgba = im.convert("RGBA")
            if rgba.getchannel("A").getextrema()[0] >= 250 and im.mode == "RGB":
                continue
            flat = flatten_to_white(rgba)
            before = os.path.getsize(path)
            flat.save(path, "PNG", optimize=True)
        changed += 1
        print(f"normalized {name}  {before} -> {os.path.getsize(path)} B")
    return changed


def resolve_source(folder: str, name: str) -> str | None:
    """按大小写无关找源文件 —— 这批 logo 的扩展名 .PNG/.png/.jpg 混着用，
    用户替换一张图顺手改了大小写就会让整个批次卡在「缺文件」上。"""
    exact = os.path.join(folder, name)
    if os.path.exists(exact):
        return exact
    wanted = name.lower()
    for candidate in sorted(os.listdir(folder)) if os.path.isdir(folder) else []:
        if candidate.lower() == wanted:
            return os.path.join(folder, candidate)
    return None


def main() -> int:
    missing = []
    for key, (folder, name) in PLAN.items():
        src = resolve_source(folder, name)
        if src is None:
            missing.append(name)
            print(f"MISSING SOURCE: {name}")
            continue
        print(convert(src, os.path.join(OUT, f"{key}.png")))
    if missing:
        return 1
    print(f"\n{len(PLAN)} icons written to appicons/")
    print(f"normalize {normalize_existing()} legacy icons onto white")
    return 0


if __name__ == "__main__":
    sys.exit(main())
