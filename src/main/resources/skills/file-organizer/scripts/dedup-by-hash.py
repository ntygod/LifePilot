#!/usr/bin/env python3
"""按文件 hash 检测重复文件，输出 JSON 分组结果。

用法:
  python dedup-by-hash.py <目录> [--min-size 0] [--algo md5|sha1] [--json]

输出:
  [
    {"hash": "abc123", "size": 1024, "files": ["/path/a.txt", "/path/b.txt"]},
    ...
  ]

LLM 调用示例:
  shell_exec(command="python {skill_scripts_dir}/dedup-by-hash.py /home/user/downloads --json")
"""

import argparse
import hashlib
import json
import os
import sys
from collections import defaultdict


def hash_file(filepath, algo="md5"):
    h = hashlib.new(algo)
    try:
        with open(filepath, "rb") as f:
            while chunk := f.read(8192):
                h.update(chunk)
        return h.hexdigest()
    except (PermissionError, OSError) as e:
        return None


def scan_dir(root_dir, min_size=0):
    for dirpath, _, filenames in os.walk(root_dir):
        for fn in filenames:
            fp = os.path.join(dirpath, fn)
            try:
                st = os.stat(fp)
                if st.st_size >= min_size:
                    yield fp, st.st_size
            except OSError:
                continue


def main():
    p = argparse.ArgumentParser(description="检测重复文件")
    p.add_argument("directory", help="要扫描的目录")
    p.add_argument("--min-size", type=int, default=0, help="最小文件大小（字节），默认 0")
    p.add_argument("--algo", choices=["md5", "sha1"], default="md5", help="hash 算法")
    p.add_argument("--json", action="store_true", help="输出 JSON")
    args = p.parse_args()

    if not os.path.isdir(args.directory):
        print(f"错误：目录不存在 {args.directory}", file=sys.stderr)
        sys.exit(1)

    # 按 size 分组（同大小才可能是重复）
    by_size = defaultdict(list)
    for fp, sz in scan_dir(args.directory, args.min_size):
        by_size[sz].append(fp)

    # 同 size 组内算 hash
    groups = []
    for sz, files in by_size.items():
        if len(files) < 2:
            continue
        by_hash = defaultdict(list)
        for fp in files:
            h = hash_file(fp, args.algo)
            if h:
                by_hash[h].append(fp)
        for h, dups in by_hash.items():
            if len(dups) > 1:
                groups.append({"hash": h, "size": sz, "files": sorted(dups)})

    groups.sort(key=lambda g: -g["size"])

    if args.json:
        print(json.dumps(groups, indent=2, ensure_ascii=False))
    else:
        if not groups:
            print("未发现重复文件")
        for g in groups:
            print(f"\nhash={g['hash']} size={g['size']} ({len(g['files'])} 个):")
            for f in g["files"]:
                print(f"  {f}")

    print(f"\n共 {len(groups)} 组重复，涉及 {sum(len(g['files']) for g in groups)} 个文件", file=sys.stderr)


if __name__ == "__main__":
    main()
