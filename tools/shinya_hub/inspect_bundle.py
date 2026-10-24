"""Export selected function bodies from the exact APK used for the Hub fork."""
import argparse
import json
from pathlib import Path

from patch_bundle import load
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

ROOT = Path(__file__).resolve().parents[2]
ANALYSIS = ROOT / "test/_analysis/oct08-shinya-hub"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("ids", type=int, nargs="+")
    args = parser.parse_args()
    reader = load((ANALYSIS / "index.android.bundle").read_bytes())
    index = json.loads((ANALYSIS / "index.json").read_text(encoding="utf-8"))
    by_id = {row["id"]: row for row in index}
    for fid in args.ids:
        text = "\n".join(str(i) for i in parse_hbc_bytecode(reader.function_headers[fid], reader))
        (ANALYSIS / f"audit-f{fid}.txt").write_text(text + "\n", encoding="utf-8")
        print(json.dumps({"id": fid, "name": by_id[fid]["name"],
                          "strings": [reader.strings[s] for s in by_id[fid]["s"]
                                      if len(reader.strings[s]) < 180],
                          "functions": by_id[fid]["f"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
