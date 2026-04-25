# 文档处理命令速查

## 工具选择矩阵

```
需要什么操作？
├── 生成 Word 文档 → code.execute + python-docx
├── 生成 Excel 表格 → code.execute + openpyxl
├── 生成 PowerPoint → code.execute + python-pptx
├── 格式转换（Markdown/HTML/DOCX/PDF）→ shell.exec + pandoc
├── PDF 文本提取 → shell.exec + pdftotext，或 code.execute + pypdf
├── HTML 转 PDF → shell.exec + wkhtmltopdf
└── 批量处理 → shell 脚本循环
```

## 工具可用性检测

```bash
shell.exec(command="pandoc --version")
```

未安装时提示用户安装：

- pandoc：`choco install pandoc` / `brew install pandoc`
- pdftotext：安装 poppler-utils
- wkhtmltopdf：`choco install wkhtmltopdf`
- Python 库：`pip install python-docx openpyxl python-pptx pypdf`

## 格式转换命令

```bash
shell.exec(command="pandoc input.md -o output.html --standalone")
shell.exec(command="pandoc input.md -o output.docx")
shell.exec(command="pandoc input.md -o output.pdf --pdf-engine=wkhtmltopdf")
shell.exec(command="pandoc input.html -t markdown -o output.md")
shell.exec(command="pandoc part1.md part2.md part3.md -o combined.pdf")
```

## PDF 处理

```bash
shell.exec(command="pdftotext input.pdf output.txt")
shell.exec(command="pdfinfo input.pdf")
```

## Excel 读写

```python
code.execute(language="python", code="
import openpyxl
wb = openpyxl.load_workbook('data.xlsx')
ws = wb.active
for row in ws.iter_rows(values_only=True):
    print(row)
")
```

## Word 程序化生成

```python
code.execute(language="python", code="
from docx import Document
doc = Document()
doc.add_heading('标题', 0)
doc.add_paragraph('正文内容')
doc.add_heading('二级标题', 1)
for item in ['要点 A', '要点 B', '要点 C']:
    doc.add_paragraph(item, style='List Bullet')
doc.save('output.docx')
")
```

## PowerPoint 程序化生成

```python
code.execute(language="python", code="
from pptx import Presentation
from pptx.util import Inches
prs = Presentation()
# 标题页
slide = prs.slides.add_slide(prs.slide_layouts[0])
slide.shapes.title.text = '主标题'
slide.placeholders[1].text = '副标题'
# 内容页
slide = prs.slides.add_slide(prs.slide_layouts[1])
slide.shapes.title.text = '要点列表'
tf = slide.placeholders[1].text_frame
for point in ['要点 A', '要点 B', '要点 C']:
    p = tf.add_paragraph()
    p.text = point
prs.save('output.pptx')
")
```

## PDF 文本提取（Python 路径）

```python
code.execute(language="python", code="
from pypdf import PdfReader
reader = PdfReader('input.pdf')
for page in reader.pages:
    print(page.extract_text())
")
```

## 批量转换

```bash
shell.exec(command="for f in docs/*.md; do pandoc \"$f\" -o \"${f%.md}.html\" --standalone; done")
```

## 验证结果

转换后必须读取输出文件验证结果：

```
file.read(path="output.html", maxChars=5000)
```

## 常见错误处理

- **工具未安装** → 给出对应平台的安装命令
- **编码问题** → 指定输入编码 `--from markdown+utf8`
- **PDF 加密** → 提示用户提供密码，或用 `qpdf --decrypt` 解密
- **格式丢失** → 复杂格式转换可能丢失样式，建议先转换单页预览确认
