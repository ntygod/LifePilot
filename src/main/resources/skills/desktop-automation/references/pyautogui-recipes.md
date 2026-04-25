# 桌面自动化脚本速查（pyautogui / pywinauto）

## 依赖安装

```bash
shell.exec(command="pip install pyautogui pywinauto pillow")
```

| 库 | 用途 |
|----|------|
| `pyautogui` | 键鼠模拟、截图、图像定位 |
| `pywinauto` | Windows UI 元素控制、窗口管理 |
| `pillow` | 图像处理 |

## 截图分析当前状态

```python
code.execute(language="python", code="
import pyautogui
screenshot = pyautogui.screenshot()
screenshot.save('current_screen.png')
print(f'屏幕分辨率: {pyautogui.size()}')
")
```

## 定位目标窗口

```python
code.execute(language="python", code="
from pywinauto import Desktop
desktop = Desktop(backend='uia')
for w in desktop.windows():
    print(f'{w.window_text()} - {w.class_name()}')
")
```

## 操作应用（pywinauto 控件）

```python
code.execute(language="python", code="
from pywinauto.application import Application
app = Application(backend='uia').connect(title='记事本')
dlg = app.window(title_re='.*记事本')
dlg.Edit.type_keys('Hello World', with_spaces=True)
")
```

## 键鼠模拟（pyautogui）

```python
code.execute(language="python", code="
import pyautogui, time
pyautogui.moveTo(100, 200, duration=0.5)
pyautogui.click()
pyautogui.typewrite('hello', interval=0.05)
pyautogui.hotkey('ctrl', 's')
")
```

## 保存脚本复用

```
file.write(path="scripts/auto_task.py", content="脚本内容")
```

## 常见错误处理

- **窗口未找到** → 检查窗口标题、确认应用已启动
- **元素定位失败** → 使用 `print_control_identifiers()` 查看控件树
- **权限不足** → 某些系统对话框需管理员权限
- **分辨率差异** → 图像定位依赖分辨率，优先用控件定位
</content>
</invoke>