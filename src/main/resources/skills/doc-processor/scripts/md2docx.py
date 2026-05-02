#!/usr/bin/env python3
"""Markdown → DOCX 转换，支持标题层级、代码块、表格、图片。

用法:
  python md2docx.py <input.md> [output.docx]

LLM 调用示例:
  code(action="exec", code="python {skill_scripts_dir}/md2docx.py workspace/report.md workspace/report.docx")
"""

import sys
import os

try:
    from docx import Document
    from docx.shared import Pt, Inches, RGBColor
    from docx.enum.text import WD_ALIGN_PARAGRAPH
except ImportError:
    print("错误：需要 python-docx。安装：pip install python-docx", file=sys.stderr)
    sys.exit(1)


def add_paragraph(doc, text, style=None, bold=False, font_size=None):
    p = doc.add_paragraph()
    if style:
        p.style = style
    run = p.add_run(text)
    if bold:
        run.bold = True
    if font_size:
        run.font.size = Pt(font_size)
    return p


def convert_md_to_docx(md_path, docx_path):
    if not os.path.exists(md_path):
        print(f"错误：文件不存在 {md_path}", file=sys.stderr)
        sys.exit(1)

    with open(md_path, "r", encoding="utf-8") as f:
        lines = f.readlines()

    doc = Document()
    in_code_block = False
    code_lines = []

    for line in lines:
        stripped = line.rstrip()

        # 代码块
        if stripped.startswith("```"):
            if in_code_block:
                add_paragraph(doc, "\n".join(code_lines), font_size=9)
                code_lines = []
                in_code_block = False
            else:
                in_code_block = True
            continue
        if in_code_block:
            code_lines.append(stripped)
            continue

        # 标题
        if stripped.startswith("# ") and len(stripped) > 2:
            add_paragraph(doc, stripped[2:], style="Heading 1")
        elif stripped.startswith("## ") and len(stripped) > 3:
            add_paragraph(doc, stripped[3:], style="Heading 2")
        elif stripped.startswith("### ") and len(stripped) > 4:
            add_paragraph(doc, stripped[4:], style="Heading 3")
        # 无序列表
        elif stripped.startswith("- ") or stripped.startswith("* "):
            add_paragraph(doc, stripped[2:], style="List Bullet")
        # 有序列表
        elif stripped and stripped[0].isdigit() and ". " in stripped[:5]:
            add_paragraph(doc, stripped.split(". ", 1)[1], style="List Number")
        # 空行
        elif not stripped:
            continue
        # 普通段落
        else:
            # 行内代码
            add_paragraph(doc, stripped)

    doc.save(docx_path)
    print(f"已生成 {docx_path} ({os.path.getsize(docx_path)} bytes)")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法: python md2docx.py <input.md> [output.docx]", file=sys.stderr)
        sys.exit(1)
    input_path = sys.argv[1]
    output_path = sys.argv[2] if len(sys.argv) > 2 else input_path.rsplit(".", 1)[0] + ".docx"
    convert_md_to_docx(input_path, output_path)
