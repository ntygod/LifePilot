# Windows 桌面自动化参考

> pyautogui/pywinauto 命令直接用 code/shell.exec。本文档仅含平台特有约束。

**关键约束**：
- 仅 Windows，必须本机进程（沙箱无图形会话）
- 用 `shell.exec(command="python <脚本>")` 走宿主 Python，不用 `code`（在沙箱里）
- 脚本写到 `<workspace>/desktop-<task>.py`，再 shell.exec 执行
- 依赖检查：`python -c "import pyautogui, pywinauto, PIL"`，缺则 `pip install`

## 定位策略（按优先级）

1. **pywinauto 控件树**（最稳）→ `dlg.child_window(title="...", control_type="Button").click()`
2. **pyautogui 坐标** → `pyautogui.moveTo(x, y)` + `click()`
3. **图像匹配**（兜底，依赖分辨率/DPI）→ `pyautogui.locateOnScreen()` + `confidence=0.8`

定位失败时 `dlg.print_control_identifiers()` 输出控件树排查。

## FAILSAFE

脚本顶部必加：
```python
import pyautogui
pyautogui.FAILSAFE = True  # 鼠标移到左上角立即终止
pyautogui.PAUSE = 0.3       # 操作间隔
```

## 常见错误

| 现象 | 处理 |
|------|------|
| 控件定位失败 | print_control_identifiers() 输出控件树 |
| 操作没生效 | 窗口未聚焦，先 set_focus() 或 click() 激活 |
| 权限不足（系统对话框） | 需管理员模式启动后端 |
| DPI 缩放导致坐标偏 | 改用 pywinauto 控件定位 |
| 用户中途要停 | 鼠标拖到左上角触发 FAILSAFE，或 Ctrl+C |
