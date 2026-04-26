# pyautogui / pywinauto 脚本参考

**关键约束**：

- 仅 Windows，且必须本机进程（沙箱无图形会话，跑不了 pyautogui）。
- 用 `shell.exec(command="python <脚本>")` 走宿主 Python，**不用** `code.execute`（在沙箱里）。
- 操作步骤写到 `~/.zhiwei/workspace/desktop-<task>.py`，再 `shell.exec` 执行；不用 `python -c` 拼复杂多行。

## 本机依赖检查

```
shell.exec(command="python -c \"import pyautogui, pywinauto, PIL\"")
```

任一报 `ModuleNotFoundError` → 提示用户安装：

```
pip install pyautogui pywinauto pillow
```

| 库 | 用途 |
|---|---|
| `pyautogui` | 键鼠模拟、截图、图像定位 |
| `pywinauto` | Windows UI 控件树、窗口管理（**优先用，比坐标稳**） |
| `pillow` | 图像处理（`pyautogui.screenshot()` 依赖） |

## 工作流模板

### 1. 写脚本

`file.write(path="~/.zhiwei/workspace/desktop-<task>.py", content=...)` 写入完整脚本。

### 2. 执行

```
shell.exec(command="python <脚本路径>")
```

### 3. 取截图

脚本里 `pyautogui.screenshot().save("<workspace>/screen-<step>.png")`，再用 `file.read` 给用户看路径或直接放截图。

## 脚本片段

### 截图分析当前状态

```python
import pyautogui
pyautogui.FAILSAFE = True
shot = pyautogui.screenshot()
shot.save(r"<workspace>\\screen.png")
print(f"屏幕分辨率: {pyautogui.size()}")
```

### 列出当前所有窗口（pywinauto）

```python
from pywinauto import Desktop
for w in Desktop(backend='uia').windows():
    print(f"{w.window_text()} | class={w.class_name()}")
```

### 连接窗口并操作控件

```python
from pywinauto.application import Application
import pyautogui, time

pyautogui.FAILSAFE = True
app = Application(backend='uia').connect(title_re='.*<窗口标题正则>.*')
dlg = app.window(title_re='.*<窗口标题正则>.*')

# 用控件树看元素，便于第一次写脚本
# dlg.print_control_identifiers()

dlg.set_focus()
dlg.child_window(title="<按钮文本>", control_type="Button").click()
time.sleep(0.5)
dlg.Edit.type_keys("<要输入的文本>", with_spaces=True)
```

### 键鼠模拟（pyautogui）

```python
import pyautogui, time
pyautogui.FAILSAFE = True
pyautogui.PAUSE = 0.3   # 每次操作之间默认等 300ms

pyautogui.moveTo(<x>, <y>, duration=0.5)
pyautogui.click()
pyautogui.doubleClick()
pyautogui.rightClick()

pyautogui.typewrite("hello", interval=0.05)
pyautogui.hotkey("ctrl", "s")
pyautogui.hotkey("alt", "tab")
pyautogui.press("enter")
```

### 图像定位（兜底，不如控件稳）

```python
import pyautogui
loc = pyautogui.locateOnScreen(r"<workspace>\\template.png", confidence=0.8)
if loc:
    pyautogui.click(pyautogui.center(loc))
else:
    print("未找到模板图")
```

> 图像定位依赖分辨率和 DPI 缩放，优先用 pywinauto 控件树定位。

### FAILSAFE 紧急退出

脚本顶部必加：

```python
import pyautogui
pyautogui.FAILSAFE = True   # 鼠标移到屏幕左上角立即抛 FailSafeException 终止
```

## 错误处理

| 现象 | 处理 |
|---|---|
| `ModuleNotFoundError` | 提示用户 `pip install pyautogui pywinauto pillow`，确认后再跑 |
| `Could not connect to an instance of an application` | 用 `Desktop().windows()` 看真实窗口列表；标题用正则容错（`.*xxx.*`）；可能应用未启动 |
| 控件定位失败 | 在脚本里加 `dlg.print_control_identifiers()` 输出控件树，照着改 child_window 参数 |
| 操作没生效但无报错 | 多半是窗口未聚焦，先 `dlg.set_focus()` 或 `pyautogui.click()` 激活窗口 |
| 权限不足（系统对话框） | 提示用户用管理员模式启动 ZhiWei 后端进程，普通权限点不到 UAC / 系统提权弹窗 |
| 分辨率 / DPI 缩放导致坐标偏 | 改用 pywinauto 控件定位；图像匹配加 `confidence` 容差 |
| 用户中途要停 | 鼠标拖到屏幕左上角触发 FAILSAFE，或 Ctrl+C 终止 `shell.exec` |
