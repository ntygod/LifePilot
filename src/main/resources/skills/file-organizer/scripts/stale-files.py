#!/usr/bin/env python3
"""扫描目录中超过指定天数的文件，输出 JSON。

用法:
  python stale-files.py <目录> --days 30 [--json] [--dry-run]

LLM 调用示例:
  shell_exec(command="python {skill_scripts_dir}/stale-files.py /home/user/downloads --days 60 --json")
"""

import argparse
import json
import os
import sys
import time


def main():
    p = argparse.ArgumentParser(description="扫描过期文件")
    p.add_argument("directory", help="目标目录")
    p.add_argument("--days", type=int, required=True, help="天数阈值")
    p.add_argument("--json", action="store_true", help="JSON 输出")
    p.add_argument("--dry-run", action="store_true", help="仅列出，不执行")
    args = p.parse_args()

    if not os.path.isdir(args.directory):
        print(f"错误：目录不存在 {args.directory}", file=sys.stderr)
        sys.exit(1)

    cutoff = time.time() - args.days * 86400
    stale = []

    for dirpath, _, filenames in os.walk(args.directory):
        for fn in filenames:
            fp = os.path.join(dirpath, fn)
            try:
                st = os.stat(fp)
                if st.st_mtime < cutoff:
                    stale.append({
                        "path": fp,
                        "size": st.st_size,
                        "mtime": time.strftime("%Y-%m-%d %H:%M", time.localtime(st.st_mtime)),
                        "age_days": round((time.time() - st.st_mtime) / 86400, 1)
                    })
            except OSError:
                continue

    stale.sort(key=lambda x: -x["age_days"])
    total_size = sum(f["size"] for f in stale)

    if args.json:
        print(json.dumps({"files": stale, "total_count": len(stale), "total_size": total_size},
                         indent=2, ensure_ascii=False))
    else:
        print(f"共 {len(stale)} 个文件超过 {args.days} 天未修改，总计 {total_size / 1024 / 1024:.1f} MB\n")
        for f in stale[:50]:
            print(f"  [{f['age_days']:5.0f}d] {f['size']:>10,} B  {f['mtime']}  {f['path']}")
        if len(stale) > 50:
            print(f"  ... 及其他 {len(stale) - 50} 个文件")


if __name__ == "__main__":
    main()
