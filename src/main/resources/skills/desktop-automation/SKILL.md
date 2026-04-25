---
name: desktop-automation
description: 当用户要在 Windows 桌面上做 UI 自动化——窗口管理、鼠标点击、键盘模拟、桌面截屏、弹窗处理、应用控件定位时使用。关键词：桌面自动化、操作桌面应用、鼠标点击、键盘模拟、窗口管理、截屏、pyautogui、pywinauto。网页自动化用 browser-automation，仅 Windows 平台。
version: 2.0.0
metadata:
  zhiwei:
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

通过 Python 脚本（`pyautogui` + `pywinauto`）控制 Windows 桌面应用，完成 UI 自动化任务。仅 Windows。

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

1. **准备依赖**：`pip install pyautogui pywinauto pillow`
2. **截图确认状态**：操作前必须截图，不盲操作
3. **定位窗口 / 控件**：优先 `pywinauto` 控件定位，比坐标点击更可靠
4. **执行操作**：键鼠模拟 / 控件操作，加入 `time.sleep` 等待 UI 响应
5. **设 FAILSAFE**：`pyautogui.FAILSAFE = True`，留出紧急退出路径
6. **破坏性操作**（删文件、关闭未保存）须用户确认

## 详细参考

- pyautogui + pywinauto 脚本片段（截图 / 定位 / 操作 / 键鼠）：`{skill_dir}/references/pyautogui-recipes.md`
</content>
</invoke>