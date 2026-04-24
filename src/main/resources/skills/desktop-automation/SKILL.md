---
name: desktop-automation
description: 当用户要在 Windows 桌面上做 UI 自动化——窗口管理、鼠标点击、键盘模拟、桌面截屏、弹窗处理、应用控件定位时使用。关键词：桌面自动化、操作桌面应用、鼠标点击、键盘模拟、窗口管理、截屏、pyautogui、pywinauto。网页自动化用 browser-automation，仅 Windows 平台。
version: 2.0.0
metadata:
  zhiwei:
    category: external-integration
    priority: normal
    tags:
      - desktop
      - ui-automation
      - windows
      - pyautogui
      - pywinauto
    suggested_tools:
      - shell.exec
      - code.execute
      - file.read
      - file.write
    requires:
      os:
        - windows
---

# 桌面自动化指南

通过 Python 脚本控制 Windows 桌面应用，完成 UI 自动化任务。仅支持 Windows。

## 适用场景

- Windows 应用 UI 自动化操作
- 窗口管理（查找、激活、调整大小）
- 对话框和弹窗处理
- 键盘和鼠标模拟
- 屏幕截图和 UI 元素定位
- 重复性桌面操作批量执行

## 不适用场景

- 网页自动化 → 用 browser-automation
- 命令行操作 → 直接用 `shell.exec`
- Linux / macOS 桌面 → 当前不支持

## 工作流

### 工具依赖

```bash
shell.exec(command="pip install pyautogui pywinauto pillow")
```

| 库 | 用途 |
|----|------|
| `pyautogui` | 键鼠模拟、截图、图像定位 |
| `pywinauto` | Windows UI 元素控制、窗口管理 |
| `pillow` | 图像处理 |

### 截图分析当前状态

```python
code.execute(language="python", code="
import pyautogui
screenshot = pyautogui.screenshot()
screenshot.save('current_screen.png')
print(f'屏幕分辨率: {pyautogui.size()}')
")
```

### 定位目标窗口

```python
code.execute(language="python", code="
from pywinauto import Desktop
desktop = Desktop(backend='uia')
for w in desktop.windows():
    print(f'{w.window_text()} - {w.class_name()}')
")
```

### 操作应用

```python
code.execute(language="python", code="
from pywinauto.application import Application
app = Application(backend='uia').connect(title='记事本')
dlg = app.window(title_re='.*记事本')
dlg.Edit.type_keys('Hello World', with_spaces=True)
")
```

### 键鼠模拟

```python
code.execute(language="python", code="
import pyautogui, time
pyautogui.moveTo(100, 200, duration=0.5)
pyautogui.click()
pyautogui.typewrite('hello', interval=0.05)
pyautogui.hotkey('ctrl', 's')
")
```

### 脚本保存复用

```
file.write(path="scripts/auto_task.py", content="脚本内容")
```

## 规则

- 每次操作前截图确认页面状态，不盲操作
- 操作间加入适当延迟（`time.sleep`），等待 UI 响应
- 设置安全区域：`pyautogui.FAILSAFE = True`
- 破坏性操作（删除文件、关闭未保存文档）需用户确认
- 优先使用 `pywinauto` 控件定位，比坐标点击更可靠

## 常见错误处理

- **窗口未找到** → 检查窗口标题、确认应用已启动
- **元素定位失败** → 使用 `print_control_identifiers()` 查看控件树
- **权限不足** → 某些系统对话框需管理员权限
- **分辨率差异** → 图像定位依赖分辨率，优先用控件定位
