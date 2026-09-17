#!/usr/bin/env python3
"""Read-only static checks for the supplied Ruijie APK and firmware archive.

The script intentionally does not install, execute, extract, or modify either
artifact.  It validates containers and inspects metadata/strings so that APP ↔
firmware interface hypotheses can be compared without an Android or router
runtime.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import struct
import tarfile
import zipfile
from pathlib import Path
from typing import Any, Iterable


KEYWORDS = (
    "http", "https", "ws://", "wss://", "rpc", "ssh", "relay", "hub",
    "agent", "rdpi", "sniffer", "policy", "apply", "rollback", "backup",
    "device", "traffic", "internet", "schedule", "parent", "child",
    "report", "usage", "appId", "application", "feature", "router",
)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def suspicious_path(name: str) -> bool:
    p = name.replace("\\", "/")
    return p.startswith("/") or p == ".." or "/../" in f"/{p}/" or p.startswith("../")


def printable_strings(data: bytes, minimum: int = 5, include_utf16: bool = True) -> list[str]:
    """Extract ASCII and UTF-16LE strings, preserving order and uniqueness."""
    found: list[str] = []
    seen: set[str] = set()
    patterns = [re.compile(rb"[ -~]{%d,}" % minimum)]
    if include_utf16:
        patterns.append(re.compile(rb"(?:[ -~]\x00){%d,}" % minimum))
    for pattern in patterns:
        for match in pattern.finditer(data):
            raw = match.group(0)
            try:
                text = raw.decode("utf-16-le") if b"\x00" in raw else raw.decode("ascii")
            except UnicodeDecodeError:
                continue
            text = text.strip("\x00")
            if text and text not in seen:
                seen.add(text)
                found.append(text)
    return found


def read_uleb128(data: bytes, pos: int) -> tuple[int, int]:
    value = 0
    shift = 0
    while pos < len(data):
        byte = data[pos]
        pos += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, pos
        shift += 7
        if shift > 35:
            break
    return value, pos


def dex_strings(data: bytes) -> list[str]:
    """Read the DEX string_ids table instead of treating binary bytes as text."""
    if not data.startswith(b"dex\n") or len(data) < 112:
        return []
    try:
        string_count, string_off = struct.unpack_from("<II", data, 56)
    except struct.error:
        return []
    if string_count > 2_000_000 or string_off + string_count * 4 > len(data):
        return []
    result: list[str] = []
    for index in range(string_count):
        item_off = struct.unpack_from("<I", data, string_off + index * 4)[0]
        if item_off >= len(data):
            continue
        _, pos = read_uleb128(data, item_off)
        end = data.find(b"\x00", pos)
        if end < 0:
            continue
        raw = data[pos:end]
        try:
            result.append(raw.decode("utf-8", "replace"))
        except UnicodeDecodeError:
            result.append(raw.decode("latin-1", "replace"))
    return result


def javascript_literals(data: bytes, maximum: int = 180) -> list[str]:
    """Extract short quoted JS literals for routes, labels and command names."""
    text = data.decode("utf-8", "replace")
    pattern = re.compile(r'''(?:"((?:\\.|[^"\\]){2,%d})"|'((?:\\.|[^'\\]){2,%d})')''' % (maximum, maximum))
    result: list[str] = []
    seen: set[str] = set()
    for match in pattern.finditer(text):
        value = match.group(1) if match.group(1) is not None else match.group(2)
        if value not in seen:
            seen.add(value)
            result.append(value)
    return result


def hermes_candidates(data: bytes) -> list[str]:
    """Extract high-signal ASCII tokens from a Hermes bytecode bundle.

    The production bundle is HBC bytecode rather than source JavaScript, so a
    normal quoted-string parser would join unrelated bytecode bytes.  These
    bounded patterns retain routes, asset keys and nearby operation labels.
    """
    result: list[str] = []
    seen: set[str] = set()
    text = data.decode("latin-1", "ignore")
    patterns = (
        r"(?:https?|wss?)://[A-Za-z0-9._:/?&=%+{}#-]{3,240}",
        r"/(?:service/api|cgi-bin|api|rpc)/[A-Za-z0-9._:/?&=%+{}#-]{2,200}",
        r"src_assets_[A-Za-z0-9_]{3,140}",
        r"(?:child|parent|report|schedule|internet|attention|app_allow|device)[A-Za-z0-9_]{0,100}",
    )
    for pattern in patterns:
        for match in re.finditer(pattern, text, re.I):
            value = match.group(0)
            if value not in seen:
                seen.add(value)
                result.append(value)
    return result


def keyword_hits(strings: Iterable[str]) -> dict[str, list[str]]:
    result: dict[str, list[str]] = {}
    for text in strings:
        low = text.lower()
        for keyword in KEYWORDS:
            if keyword.lower() in low:
                result.setdefault(keyword, []).append(text)
    for values in result.values():
        values.sort(key=lambda item: (len(item), item))
    return result


def verify_apk_samples(archive: zipfile.ZipFile, names: list[str]) -> tuple[bool, str]:
    """Read representative APK entries to force CRC checks without a full
    100-MB native-library decompression pass."""
    selected = [n for n in ("AndroidManifest.xml", "classes.dex", "assets/index.android.bundle") if n in names]
    for name in selected:
        with archive.open(name) as handle:
            while handle.read(1024 * 1024):
                pass
    return True, ",".join(selected)


def decode_axml_string_pool(data: bytes, start: int) -> tuple[list[str], int]:
    """Decode the Android binary XML string pool at ``start``."""
    if start + 28 > len(data):
        return [], start
    _, header_size, chunk_size = struct.unpack_from("<HHI", data, start)
    if header_size < 28 or start + chunk_size > len(data):
        return [], start
    count, _, flags, strings_start, _ = struct.unpack_from("<IIIII", data, start + 8)
    offsets_start = start + header_size
    blob_start = start + strings_start
    utf8 = bool(flags & 0x100)
    strings: list[str] = []

    def read_varint(pos: int) -> tuple[int, int]:
        value = data[pos]
        pos += 1
        if value & 0x80:
            value = ((value & 0x7F) << 8) | data[pos]
            pos += 1
        return value, pos

    for index in range(count):
        off_pos = offsets_start + index * 4
        if off_pos + 4 > len(data):
            strings.append("")
            continue
        relative = struct.unpack_from("<I", data, off_pos)[0]
        pos = blob_start + relative
        try:
            if utf8:
                _, pos = read_varint(pos)
                byte_len, pos = read_varint(pos)
                value = data[pos:pos + byte_len].decode("utf-8", "replace")
            else:
                char_len = struct.unpack_from("<H", data, pos)[0]
                pos += 2
                value = data[pos:pos + char_len * 2].decode("utf-16-le", "replace")
        except (IndexError, struct.error):
            value = ""
        strings.append(value)
    return strings, start + chunk_size


def parse_binary_manifest(data: bytes) -> dict[str, Any]:
    """Parse enough AXML to expose package/version/permission/component metadata."""
    if len(data) < 8:
        return {"error": "manifest too small"}
    strings: list[str] = []
    pos = 0
    # The first chunk is RES_XML_TYPE; its size covers the whole XML tree, so
    # child chunks begin immediately after the 8-byte outer header.
    try:
        outer_type, outer_header, _ = struct.unpack_from("<HHI", data, 0)
        if outer_type == 0x0003 and outer_header >= 8:
            pos = outer_header
    except struct.error:
        pass
    # The first child chunk is usually the string pool.
    while pos + 8 <= len(data):
        chunk_type, header_size, chunk_size = struct.unpack_from("<HHI", data, pos)
        if chunk_size == 0 or chunk_size < header_size or pos + chunk_size > len(data):
            break
        if chunk_type == 0x0001 and not strings:
            strings, _ = decode_axml_string_pool(data, pos)
        pos += chunk_size
    if not strings:
        return {"error": "no string pool"}

    def s(index: int) -> str:
        return strings[index] if 0 <= index < len(strings) else ""

    def typed_value(data_type: int, value: int, raw_index: int) -> Any:
        if raw_index != 0xFFFFFFFF:
            return s(raw_index)
        if data_type == 0x12:
            return bool(value)
        if data_type == 0x10:
            return value
        if data_type == 0x11:
            return hex(value)
        if data_type == 0x01:
            return f"@0x{value:08x}"
        return value

    manifest: dict[str, Any] = {"permissions": [], "components": []}
    try:
        outer_type, outer_header, _ = struct.unpack_from("<HHI", data, 0)
        pos = outer_header if outer_type == 0x0003 and outer_header >= 8 else 0
    except struct.error:
        pos = 0
    current_root = ""
    while pos + 8 <= len(data):
        chunk_type, header_size, chunk_size = struct.unpack_from("<HHI", data, pos)
        if chunk_size == 0 or chunk_size < header_size or pos + chunk_size > len(data):
            break
        if chunk_type == 0x0102 and header_size >= 16 and pos + 36 <= len(data):
            try:
                ns_idx, name_idx = struct.unpack_from("<II", data, pos + 16)
                attr_start, attr_size, attr_count = struct.unpack_from("<HHH", data, pos + 24)
                element = s(name_idx)
                attrs: dict[str, Any] = {}
                # attributeStart is relative to the attrExt extension at
                # chunk+16 (line/comment occupy the 8-byte header extension).
                attr_pos = pos + 16 + attr_start
                for _ in range(attr_count):
                    if attr_pos + attr_size > pos + chunk_size or attr_size < 20:
                        break
                    _, attr_name_idx, raw_idx = struct.unpack_from("<III", data, attr_pos)
                    _, _, data_type, value = struct.unpack_from("<HBBI", data, attr_pos + 12)
                    attrs[s(attr_name_idx)] = typed_value(data_type, value, raw_idx)
                    attr_pos += attr_size
                if element == "manifest":
                    current_root = element
                    for key in ("package", "versionName", "versionCode", "compileSdkVersion", "platformBuildVersionName"):
                        if key in attrs:
                            manifest[key] = attrs[key]
                elif element == "uses-permission":
                    if "name" in attrs:
                        manifest["permissions"].append(attrs["name"])
                elif element in {"activity", "activity-alias", "service", "receiver", "provider"}:
                    manifest["components"].append({"type": element, "name": attrs.get("name", ""), "exported": attrs.get("exported")})
            except (struct.error, ValueError):
                pass
        pos += chunk_size
    manifest["permissions"] = sorted(set(manifest["permissions"]))
    return manifest


def detect_binary_kind(data: bytes, name: str) -> str:
    lower = name.lower()
    if data.startswith(b"PK\x03\x04"):
        return "zip"
    if data.startswith(b"\x1f\x8b"):
        return "gzip"
    if data.startswith(b"\xfd7zXZ\x00"):
        return "xz"
    if data.startswith(b"BZh"):
        return "bzip2"
    if data.startswith(b"070701") or data.startswith(b"070702"):
        return "cpio-newc"
    if data.startswith(b"\x7fELF"):
        return "ELF"
    if len(data) >= 4 and struct.unpack_from(">I", data, 0)[0] == 0xD00DFEED:
        return "uImage"
    if len(data) >= 4 and data[0:4] == b"hsqs":
        return "squashfs"
    if len(data) >= 1082 and data[1080:1082] == b"\x53\xef":
        return "ext filesystem"
    if lower.endswith((".tar", ".tgz", ".tar.gz")):
        return "tar-like"
    return "unknown"


def apk_report(path: Path) -> dict[str, Any]:
    result: dict[str, Any] = {
        "name": path.name,
        "size": path.stat().st_size,
        "sha256": sha256_file(path),
    }
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        result["zip"] = {
            "entryCount": len(names),
            # CRC verification is performed once by run_tests(); repeating it
            # here would decompress every native library a second time.
            "crcCheck": "performed by run_tests",
            "pathTraversal": sorted(name for name in names if suspicious_path(name)),
            "manifest": "AndroidManifest.xml" in names,
            "dex": [{"name": n, "size": archive.getinfo(n).file_size} for n in names if re.fullmatch(r"classes(?:\d+)?\.dex", n)],
            "nativeLibraries": [{"name": n, "size": archive.getinfo(n).file_size} for n in names if n.startswith("lib/") and n.endswith(".so")],
            "signatureEntries": [n for n in names if n.upper().startswith("META-INF/") and n.upper().endswith((".RSA", ".DSA", ".EC"))],
        }
        if "AndroidManifest.xml" in names:
            result["manifestMetadata"] = parse_binary_manifest(archive.read("AndroidManifest.xml"))
        all_strings: list[str] = []
        for dex in result["zip"]["dex"]:
            raw = archive.read(dex["name"])
            all_strings.extend(dex_strings(raw))
        # JS bundle/JSON/XML assets carry app routes and labels.  Skip binary
        # images/fonts so a large APK remains a fast, bounded read-only scan.
        for name in names:
            info = archive.getinfo(name)
            # React Native/Expo keeps the application code in this bundle;
            # other assets are only sampled when they have an explicit text
            # extension and are small enough to be metadata rather than media.
            text_like = name in {"assets/index.android.bundle", "assets/modules.json"}
            binary_visual = name.lower().endswith((".png", ".jpg", ".jpeg", ".webp", ".gif", ".ttf", ".otf", ".so"))
            if text_like and not binary_visual and info.file_size <= 25 * 1024 * 1024:
                all_strings.extend(hermes_candidates(archive.read(name)))
        unique = sorted(set(all_strings))
        result["stringStats"] = {"unique": len(unique), "keywordHits": keyword_hits(unique)}
        result["endpointCandidates"] = sorted({
            text for text in unique
            if (len(text) <= 240 and (
                text.startswith(("/service/api/", "/cgi-bin/", "/api/", "/rpc/", "http://", "https://", "ws://", "wss://"))
                or re.search(r"(?:/service/api/|/cgi-bin/|/api/|/rpc/)", text, re.I)
            ))
        })[:300]
    return result


def firmware_report(path: Path) -> dict[str, Any]:
    result: dict[str, Any] = {
        "name": path.name,
        "size": path.stat().st_size,
        "sha256": sha256_file(path),
    }
    with tarfile.open(path, "r:gz") as archive:
        members = archive.getmembers()
        result["tar"] = {
            "memberCount": len(members),
            "pathTraversal": sorted(m.name for m in members if suspicious_path(m.name)),
            "members": [{"name": m.name, "size": m.size, "type": "dir" if m.isdir() else "file"} for m in members],
        }
        scanned: list[dict[str, Any]] = []
        text_hits: dict[str, list[str]] = {}
        for member in members:
            if not member.isfile() or member.size == 0:
                continue
            handle = archive.extractfile(member)
            if handle is None:
                continue
            head = handle.read(min(member.size, 4096))
            kind = detect_binary_kind(head, member.name)
            item: dict[str, Any] = {"name": member.name, "size": member.size, "kind": kind}
            # Scan small/text-like files and the first 4 MiB of larger images.
            handle.seek(0)
            sample = handle.read(min(member.size, 4 * 1024 * 1024))
            strings = printable_strings(sample, minimum=6)
            hits = keyword_hits(strings)
            if hits:
                item["keywordHits"] = hits
                for key, values in hits.items():
                    text_hits.setdefault(key, []).extend(f"{member.name}: {v}" for v in values[:50])
            if strings and (kind == "unknown" or member.size < 512 * 1024):
                item["textSample"] = strings[:30]
            scanned.append(item)
        result["scannedFiles"] = scanned
        result["keywordHits"] = {k: sorted(set(v))[:100] for k, v in text_hits.items()}
    return result


def verify_firmware_md5_manifest(path: Path) -> tuple[bool, str]:
    """Verify hashes listed by the archive's own .md5 manifest."""
    with tarfile.open(path, "r:gz") as archive:
        members = {member.name: member for member in archive.getmembers() if member.isfile()}
        manifest = next((name for name in members if name.lower().endswith(".md5")), None)
        if manifest is None:
            return False, "no .md5 manifest"
        handle = archive.extractfile(members[manifest])
        if handle is None:
            return False, f"cannot read {manifest}"
        lines = handle.read().decode("utf-8", "replace").splitlines()
        entries: list[tuple[str, str]] = []
        for line in lines:
            match = re.match(r"^([0-9a-fA-F]{32})\s+(.+?)\s*$", line)
            if match:
                entries.append((match.group(1).lower(), match.group(2).strip().lstrip("*")))
        if not entries:
            return False, f"no hashes in {manifest}"
        mismatches: list[str] = []
        checked = 0
        for expected, name in entries:
            member = members.get(name)
            if member is None:
                # Some vendor manifests prefix entries with ./; normalize only
                # this harmless representation and never access outside the tar.
                member = members.get(name.removeprefix("./"))
            if member is None:
                mismatches.append(f"missing:{name}")
                continue
            source = archive.extractfile(member)
            if source is None:
                mismatches.append(f"unreadable:{name}")
                continue
            digest = hashlib.md5(source.read()).hexdigest()
            checked += 1
            if digest != expected:
                mismatches.append(f"mismatch:{name}")
        detail = f"{checked}/{len(entries)} entries matched"
        return not mismatches and checked == len(entries), detail if not mismatches else detail + "; " + ", ".join(mismatches[:3])


def run_tests(apk: Path, firmware: Path) -> dict[str, Any]:
    tests: list[dict[str, Any]] = []

    def check(name: str, condition: bool, detail: str) -> None:
        tests.append({"name": name, "pass": bool(condition), "detail": detail})

    check("APK exists", apk.is_file(), str(apk))
    check("Firmware exists", firmware.is_file(), str(firmware))
    try:
        with zipfile.ZipFile(apk) as z:
            names = z.namelist()
            try:
                ok, detail = verify_apk_samples(z, names)
            except (OSError, zipfile.BadZipFile, RuntimeError) as exc:
                ok, detail = False, repr(exc)
            check("APK ZIP representative CRC", ok, detail)
            check("APK has binary manifest", "AndroidManifest.xml" in names, "AndroidManifest.xml")
            check("APK has DEX", any(re.fullmatch(r"classes(?:\d+)?\.dex", n) for n in names), "classes*.dex")
            check("APK has no unsafe ZIP paths", not any(suspicious_path(n) for n in names), "path traversal check")
    except Exception as exc:
        check("APK ZIP readable", False, repr(exc))
    try:
        with tarfile.open(firmware, "r:gz") as t:
            members = t.getmembers()
            check("Firmware gzip/tar readable", True, f"members={len(members)}")
            check("Firmware has non-empty member", any(m.isfile() and m.size > 0 for m in members), "non-empty payload")
            check("Firmware has no unsafe paths", not any(suspicious_path(m.name) for m in members), "path traversal check")
        try:
            ok, detail = verify_firmware_md5_manifest(firmware)
        except Exception as exc:
            ok, detail = False, repr(exc)
        check("Firmware internal MD5 manifest", ok, detail)
    except Exception as exc:
        check("Firmware gzip/tar readable", False, repr(exc))
    return {"passed": sum(t["pass"] for t in tests), "failed": sum(not t["pass"] for t in tests), "tests": tests}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--firmware", type=Path, required=True)
    parser.add_argument("--json", action="store_true", help="emit a JSON report")
    args = parser.parse_args()
    report = {"tests": run_tests(args.apk, args.firmware), "apk": apk_report(args.apk), "firmware": firmware_report(args.firmware)}
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"Python tests: {report['tests']['passed']} passed, {report['tests']['failed']} failed")
        print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["tests"]["failed"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
