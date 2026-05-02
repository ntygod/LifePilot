---
name: desktop-automation
description: 当用户要在 Windows 桌面上做 UI 自动化——窗口管理、鼠标点击、键盘模拟、桌面截屏、弹窗处理、应用控件定位时使用。
version: 2.1.0
metadata:
  zhiwei:
    tags:
      - desktop
      - ui-automation
      - windows
      - pyautogui
      - pywinauto
    suggested_tools:
      - shell_exec
      - file_read
      - file_write
    requires:
      os:
        - windows
---

# 桌面自动化指南

`pyautogui` + `pywinauto` 控制 Windows 桌面应用。**仅 Windows，且必须用 `shell_exec` 走宿主 Python 进程**（`code` 是沙箱，访问不到真实屏幕和窗口）。**核心约束：操作前必须截图确认状态，不盲操作。**

## 适用场景

- Windows 应用 UI 自动化
- 窗口管理（查找 / 激活 / 调整大小）
- 对话框 / 弹窗处理
- 键鼠模拟 / 控件操作
- 屏幕截图 / UI 元素定位
- 重复性桌面操作批量执行

## 不适用场景

- 网页自动化 → browser-automation
- 命令行操作 → `shell_exec`
- Linux / macOS 桌面 → 当前不支持

## 工作流

1. **依赖检查**：`shell_exec(command="python -c \"import pyautogui, pywinauto\"")`，缺则提示用户 `pip install pyautogui pywinauto pillow`
2. **写脚本到文件**：操作步骤写到 cwd 下脚本文件（路径取自工具返回的 workingDirectory），再 `shell_exec(command="python <脚本路径>")` 执行；不用 `python -c` 拼复杂多行
3. **截图先**：操作前必须截图看到当前状态，不盲操作
4. **优先 pywinauto 控件定位**：通过控件树拿元素（标题 / class / 自动化 ID），比坐标点击稳得多（窗口移动 / 分辨率变都不会失效）
5. **加等待**：每个键鼠操作之间 `time.sleep(0.3-1.0)` 等 UI 响应；点完按钮等加载完再下一步
6. **FAILSAFE 必开**：脚本顶部 `pyautogui.FAILSAFE = True`，鼠标移到屏幕角即终止脚本（紧急退出）
7. **破坏性操作要确认**：删文件 / 关闭未保存窗口 / 提交表单等，必须用户授权后才动手
8. **失败回退**：连续 2 次定位不到控件 → 重新截图比对，可能是窗口状态变了

## 详细参考

- pyautogui + pywinauto 脚本片段（截图 / 定位 / 操作 / 键鼠组合）：`{skill_dir}/references/pyautogui-recipes.md`
