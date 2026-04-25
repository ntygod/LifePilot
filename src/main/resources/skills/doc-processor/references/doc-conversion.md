# 文档处理命令速查

## 依赖检查与安装提示

```
shell.exec(command="pandoc --version")
shell.exec(command="pdftotext -v")
code.execute(language="python", code="import docx; print(docx.__version__)")
```

不存在时给用户对应平台命令,**不自行安装**:

| 工具 | Windows | macOS | Linux |
|---|---|---|---|
| pandoc | `choco install pandoc` | `brew install pandoc` | apt / yum 包管理 |
| pdftotext | poppler-utils | `brew install poppler` | `apt install poppler-utils` |
| wkhtmltopdf | `choco install wkhtmltopdf` | `brew install wkhtmltopdf` | apt / yum |
| Python 库 | `pip install python-docx openpyxl python-pptx pypdf` | 同 | 同 |

## 格式转换(pandoc)

各种互转一行命令:

```
shell.exec(command="pandoc <input.md> -o <output.html> --standalone")
shell.exec(command="pandoc <input.md> -o <output.docx>")
shell.exec(command="pandoc <input.md> -o <output.pdf> --pdf-engine=wkhtmltopdf")
shell.exec(command="pandoc <input.html> -t markdown -o <output.md>")
shell.exec(command="pandoc <part1.md> <part2.md> -o <combined.pdf>")  # 多输入合并
```

## PDF 处理

```
shell.exec(command="pdftotext <input.pdf> <output.txt>")        # 文本提取
shell.exec(command="pdfinfo <input.pdf>")                       # 元数据
shell.exec(command="qpdf --decrypt --password=<pwd> <in.pdf> <out.pdf>")  # 解密
```

Python 路径(库已装且需要更精细控制):

```python
code.execute(language="python", code="""
from pypdf import PdfReader
reader = PdfReader('<input.pdf>')
for page in reader.pages:
    print(page.extract_text())
""")
```

## 生成 Word

```python
code.execute(language="python", code="""
from docx import Document
doc = Document()
doc.add_heading('<标题>', 0)
doc.add_paragraph('<正文>')
doc.add_heading('<二级标题>', 1)
for item in <要点列表>:
    doc.add_paragraph(item, style='List Bullet')
doc.save('<output.docx>')
""")
```

## 生成 Excel

```python
code.execute(language="python", code="""
import openpyxl
wb = openpyxl.Workbook()
ws = wb.active
ws.title = '<sheet 名>'
ws.append([<列1>, <列2>, <列3>])
for row in <数据列表>:
    ws.append(row)
wb.save('<output.xlsx>')
""")
```

读取 Excel:

```python
code.execute(language="python", code="""
import openpyxl
wb = openpyxl.load_workbook('<data.xlsx>')
ws = wb.active
for row in ws.iter_rows(values_only=True):
    print(row)
""")
```

## 生成 PowerPoint

```python
code.execute(language="python", code="""
from pptx import Presentation
prs = Presentation()

# 标题页
slide = prs.slides.add_slide(prs.slide_layouts[0])
slide.shapes.title.text = '<主标题>'
slide.placeholders[1].text = '<副标题>'

# 内容页
slide = prs.slides.add_slide(prs.slide_layouts[1])
slide.shapes.title.text = '<页标题>'
tf = slide.placeholders[1].text_frame
for point in <要点列表>:
    p = tf.add_paragraph()
    p.text = point

prs.save('<output.pptx>')
""")
```

## 批量转换

shell 循环单文件转换:

```
shell.exec(command="for f in <docs>/*.md; do pandoc \"$f\" -o \"${f%.md}.html\" --standalone; done")
```

复杂批量(分目录、改名规则)用 Python 调子进程更可控。

## 结果验证

生成后必读取确认格式正常:

```
file.read(path="<output.html>", maxChars=5000)
```

## 错误处理表

| 症状 | 对策 |
|---|---|
| 工具未安装 | 给对应平台安装命令,等用户装好 |
| `Cannot decode byte` 编码错 | pandoc 加 `--from markdown+utf8`,Python 读 Excel 加 `encoding='utf-8'` 或 `'gbk'` |
| PDF 加密读取失败 | 让用户给密码,或 `qpdf --decrypt` 预处理 |
| 复杂格式转换样式丢失 | 先转一页预览,确认可接受再批量 |
| `exitCode=0` 但输出异常 | 库版本兼容性问题;`file.read` 抽样 + 换库版本 |
