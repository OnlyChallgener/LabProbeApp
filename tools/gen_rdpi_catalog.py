"""Generate the child-guard app catalog from the router's live RDPI signature DB.

Why this exists: the picker used to be a hand-written list of ~45 names, so apps the
router can actually classify (抖音系列, QQ音乐, 米家, ...) never appeared and could not
be allowed/blocked. The router DB is the only source that knows what is enforceable,
so the list is generated from it instead of curated by hand.

Usage:
    python tools/gen_rdpi_catalog.py <db.default.json> [out.kt]

The input is a dump of the router's /usr/share/ndpi/db.default.json. Re-run it after
firmware updates or after pushing new custom signatures, then commit the diff.
"""
import collections
import io
import json
import re
import sys

DEFAULT_OUT = r"app/src/main/kotlin/com/labprobe/app/RdpiCatalogGenerated.kt"

# The DB has no category field. Its first index segment groups apps well enough to
# trust, so we take that as the default and override only where the vendor's own
# picker clearly disagrees (music lives in the 7-* block, meeting tools in 8-*).
PREFIX_CATEGORY = {
    "4": "games",
    "7": "social",
    "8": "education",
    "10": "media",
    "18": "shopping",
    "19": "stores",
    "20": "tools",
}

NAME_CATEGORY = {
    # 7-* block: audio and cloud storage are not "social".
    "QQ音乐": "media", "网易云音乐": "media", "酷狗音乐": "media",
    "百度网盘": "tools", "阿里云盘": "tools", "百度": "tools", "baiduAPP": "tools",
    # 8-* block: office / meeting software is not a child's study app.
    "腾讯会议": "office", "企业微信": "office", "钉钉": "office", "飞书": "office",
    "WPS Office": "office", "瞩目": "office",
    "喜马拉雅": "media",
    # 9-* is our own custom range, so the prefix carries no meaning at all.
    "豆包": "ainews", "DeepSeek": "ainews", "今日头条": "ainews", "夸克": "tools",
    "唯品会": "shopping", "饿了么": "shopping", "山姆会员商店": "shopping",
    "菜鸟": "shopping", "顺丰速运": "shopping", "顺丰速递": "shopping",
    "西瓜视频": "media", "番茄免费小说": "media", "醒图": "tools",
    "三角洲行动": "games",
    # UU远程是远程协助，360 那两款管的是手表/摄像头这类硬件，亲宝宝是全家相册，
    # 留在一号段默认的「其他」里家长翻不到。
    "UU远程": "tools", "360儿童卫士": "smart", "360智慧生活": "smart", "亲宝宝": "social",
    # 19-* 里混着两个游戏平台：TapTap 是游戏社区+下载，4399游戏盒是游戏启动器，
    # 按编号段划进「应用商店」的话，家长想放开游戏会找不到它们。
    "TapTap": "games", "4399游戏盒": "games",
    "海尔智家": "smart", "美的美居": "smart", "米家": "smart",
    "小爱同学": "smart", "TP-LINK物联": "smart", "阿里CDN": "other",
}

CATEGORIES = [
    ("education", "学习/教育"),
    ("media", "视频/音频"),
    ("games", "游戏"),
    ("social", "社交"),
    ("ainews", "AI/资讯"),
    ("tools", "工具"),
    ("office", "办公协作"),
    ("smart", "智能家居"),
    ("shopping", "支付/购物"),
    ("stores", "应用商店"),
    ("other", "其他"),
]

# 王者荣耀_login / QQ_chat / 快手_weak_relation are extra matchers for the same app,
# not separate apps -- the vendor picker folds them the same way ("含抖音/抖音极速版/
# ..."). The reliable key is the index family (first three segments): 百度 7-3-2-0 and
# baiduAPP_homePage 7-3-2-1 share it, which is exactly the merge the Hub's display
# alias does -- while name-based folding would split 快手系列 from 快手_weak_relation.
VARIANT_SUFFIX = re.compile(r"_[A-Za-z一-鿿].*$")


def family(index: str) -> str:
    parts = index.split("-")
    return "-".join(parts[:3]) if len(parts) >= 3 else index


def index_key(index: str):
    return [int(p) if p.isdigit() else p for p in index.split("-")]


def category_for(name: str, index: str) -> str:
    if name in NAME_CATEGORY:
        return NAME_CATEGORY[name]
    return PREFIX_CATEGORY.get(index.split("-")[0], "other")


def common_prefix(names):
    if len(names) == 1:
        return next(iter(names))
    prefix = min(names, key=len)
    while prefix and not all(n.startswith(prefix) for n in names):
        prefix = prefix[:-1]
    return prefix.strip()


def build(db_path: str):
    apps = json.load(io.open(db_path, encoding="utf-8"))["apps"]
    by_index = {str(a.get("index") or "").strip(): a for a in apps}
    grouped = collections.OrderedDict()
    for app in apps:
        index = str(app.get("index") or "").strip()
        raw = str(app.get("name") or "").strip()
        if not index or not raw:
            continue
        grouped.setdefault(family(index), []).append((index, raw))

    entries = []
    for members in grouped.values():
        members.sort(key=lambda t: index_key(t[0]))
        # The `-0` rule is the app itself; the higher suffixes are its variants.
        head_index, head_name = members[0]
        bases = {VARIANT_SUFFIX.sub("", n).strip() for _, n in members}
        if head_index.endswith("-0") or len(bases) == 1:
            name = head_name
        else:
            # 没有 `-0` 主条目又混了不同名字（花瓣测速下行/上行）：取公共前缀，
            # 比随便挑一条的下/上行更像一个应用。前缀太短就退回第一条。
            prefix = common_prefix(bases)
            name = prefix if len(prefix) >= 2 else head_name
        note = str(by_index[head_index].get("note") or "").strip()
        indexes = sorted(i for i, _ in members)
        cat = category_for(VARIANT_SUFFIX.sub("", name).strip(), indexes[0])
        entries.append((name, cat, indexes, note))
    # Audit: a family that holds two clearly different app names would silently merge
    # them into one toggle, so say so instead of shipping it unnoticed.
    for members in grouped.values():
        bases = {VARIANT_SUFFIX.sub("", n).strip() for _, n in members}
        if len(bases) > 1:
            print(f"!! family {members[0][0]} merges {'、'.join(sorted(bases))}", file=sys.stderr)
    entries.sort(key=lambda e: ([c for c, _ in CATEGORIES].index(e[1]), e[0]))
    return entries


def emit(entries, out_path: str) -> None:
    by_cat = collections.Counter(cat for _, cat, _, _ in entries)
    lines = [
        "// GENERATED by tools/gen_rdpi_catalog.py -- do not edit by hand.",
        "// Source: the router's /usr/share/ndpi/db.default.json, folded by index family.",
        "// Re-run the script after a firmware update or new custom signatures.",
        "package com.labprobe.app",
        "",
        "internal data class RdpiCatalogEntry(",
        "    val name: String,",
        "    val categoryId: String,",
        "    val indexes: Set<String>,",
        "    /** Vendor DB note: the sub-apps one signature covers. Empty when absent. */",
        "    val note: String = \"\"",
        ")",
        "",
        "/** Category id -> 界面名, in the order the picker shows them. */",
        "internal val rdpiCatalogCategories: List<Pair<String, String>> = listOf(",
    ]
    lines += [f'    "{cid}" to "{cname}",' for cid, cname in CATEGORIES]
    lines += [")", "", "internal val rdpiCatalogApps: List<RdpiCatalogEntry> = listOf("]
    for name, cat, indexes, note in entries:
        # 每行都带尾逗号：以后重新生成只是追加，不会把上一行也改一遍。
        safe = name.replace('"', '\\"')
        quoted = ", ".join(f'"{j}"' for j in indexes)
        tail = f', "{note.replace(chr(34), "")}"' if note else ""
        lines.append(f'    RdpiCatalogEntry("{safe}", "{cat}", setOf({quoted}){tail}),')
    lines += [")", ""]
    io.open(out_path, "w", encoding="utf-8", newline="\n").write("\n".join(lines))
    print(f"{len(entries)} apps -> {out_path}")
    print("per category: " + ", ".join(f"{c}={n}" for c, n in by_cat.most_common()))


if __name__ == "__main__":
    db = sys.argv[1] if len(sys.argv) > 1 else None
    if not db:
        raise SystemExit(__doc__)
    out = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUT
    emit(build(db), out)
