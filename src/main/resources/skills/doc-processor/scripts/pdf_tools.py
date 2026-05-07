#!/usr/bin/env python3
"""PDF 文本抽取、合并与拆分工具。"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

try:
    from pypdf import PdfReader, PdfWriter
except ImportError:
    print("错误：缺少 pypdf。安装：python -m pip install pypdf", file=sys.stderr)
    sys.exit(1)


def open_reader(path: Path, password: str | None) -> PdfReader:
    reader = PdfReader(str(path))
    if reader.is_encrypted:
        if not password:
            raise ValueError(f"PDF 已加密，需要 --password：{path}")
        if reader.decrypt(password) == 0:
            raise ValueError(f"PDF 密码无效：{path}")
    return reader


def extract_text(input_path: Path, output_path: Path | None, password: str | None) -> None:
    reader = open_reader(input_path, password)
    parts: list[str] = []
    for index, page in enumerate(reader.pages, start=1):
        text = page.extract_text() or ""
        parts.append(f"\n\n--- page {index} ---\n\n{text}".strip())
    result = "\n".join(parts).strip() + "\n"
    if output_path:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(result, encoding="utf-8")
        print(f"已写入 {output_path} ({len(result)} chars)")
    else:
        print(result)


def merge(output_path: Path, inputs: list[Path], password: str | None) -> None:
    writer = PdfWriter()
    for path in inputs:
        reader = open_reader(path, password)
        for page in reader.pages:
            writer.add_page(page)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("wb") as fh:
        writer.write(fh)
    print(f"已合并 {len(inputs)} 个 PDF -> {output_path}")


def split(input_path: Path, output_dir: Path, password: str | None) -> None:
    reader = open_reader(input_path, password)
    output_dir.mkdir(parents=True, exist_ok=True)
    stem = input_path.stem
    for index, page in enumerate(reader.pages, start=1):
        writer = PdfWriter()
        writer.add_page(page)
        target = output_dir / f"{stem}-page-{index:03d}.pdf"
        with target.open("wb") as fh:
            writer.write(fh)
    print(f"已拆分 {len(reader.pages)} 页到 {output_dir}")


def main() -> int:
    parser = argparse.ArgumentParser(description="PDF 处理工具")
    sub = parser.add_subparsers(dest="command", required=True)

    p_extract = sub.add_parser("extract-text", help="提取 PDF 文本")
    p_extract.add_argument("input")
    p_extract.add_argument("output", nargs="?")
    p_extract.add_argument("--password")

    p_merge = sub.add_parser("merge", help="合并多个 PDF")
    p_merge.add_argument("output")
    p_merge.add_argument("inputs", nargs="+")
    p_merge.add_argument("--password")

    p_split = sub.add_parser("split", help="按页拆分 PDF")
    p_split.add_argument("input")
    p_split.add_argument("output_dir")
    p_split.add_argument("--password")

    args = parser.parse_args()
    try:
        if args.command == "extract-text":
            extract_text(Path(args.input), Path(args.output) if args.output else None, args.password)
        elif args.command == "merge":
            merge(Path(args.output), [Path(p) for p in args.inputs], args.password)
        elif args.command == "split":
            split(Path(args.input), Path(args.output_dir), args.password)
        return 0
    except Exception as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
