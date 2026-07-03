---
name: desktop-automation
description: 当用户要在 Windows 桌面上做 UI 自动化——窗口管理、鼠标点击、键盘模拟、桌面截屏、弹窗处理、应用控件定位时使用。
version: 2.1.2
metadata:
  zhiwei:
    tags:
      - desktop
      - ui-automation
      - windows
      - pyautogui
      - pywinauto
    suggested_tools:
      - shell.exec
      - file.read
      - file.write
    outputs:
      - text
      - file
    requires:
      os:
        - windows
---

# 桌面自动化指南

`pyautogui` + `pywinauto` 控制 Windows 桌面应用。**仅 Windows，且必须用 `shell.exec` 走宿主 Python 进程**（`code` 是沙箱，访问不到真实屏幕和窗口）。**核心约束：操作前必须截图确认状态，不盲操作。**

## 触发判断
- Windows 应用 UI 自动化
- 窗口管理（查找 / 激活 / 调整大小）
- 对话框 / 弹窗处理
- 键鼠模拟 / 控件操作
- 屏幕截图 / UI 元素定位
- 重复性桌面操作批量执行

不要触发：

- 网页自动化 → browser-automation
- 命令行操作 → `shell.exec`
- Linux / macOS 桌面 → 当前不支持

## 决策路径

1. **依赖检查**：`shell.exec(command="python -c \"import pyautogui, pywinauto\"")`，缺则提示用户 `pip install pyautogui pywinauto pillow`
2. **写脚本到文件**：操作步骤写到 cwd 下脚本文件（路径取自工具返回的 workingDirectory），再 `shell.exec(command="python <脚本路径>")` 执行；不用 `python -c` 拼复杂多行
3. **截图先**：操作前必须截图看到当前状态，不盲操作
4. **优先 pywinauto 控件定位**：通过控件树拿元素（标题 / class / 自动化 ID），比坐标点击稳得多（窗口移动 / 分辨率变都不会失效）
5. **加等待**：每个键鼠操作之间 `time.sleep(0.3-1.0)` 等 UI 响应；点完按钮等加载完再下一步
6. **FAILSAFE 必开**：脚本顶部 `pyautogui.FAILSAFE = True`，鼠标移到屏幕角即终止脚本（紧急退出）
7. **破坏性操作要确认**：删文件 / 关闭未保存窗口 / 提交表单等，必须用户授权后才动手
8. **失败回退**：连续 2 次定位不到控件 → 重新截图比对，可能是窗口状态变了


## 输出标准

- 输出截图路径、识别到的窗口/控件、执行动作和最终状态。
- 自动化脚本落盘时说明脚本路径、运行方式和已验证步骤。
- 对用户可见的 UI 变化要写明操作前后差异。


## 失败策略

- 未能截图、找不到窗口或控件不稳定时暂停并要求用户调整界面。
- 涉及不可逆桌面操作时先给预览和确认。
- 非 Windows 或缺少桌面权限时说明限制并给替代方案。

## 详细参考
- pyautogui + pywinauto 脚本片段（截图 / 定位 / 操作 / 键鼠组合）：`{skill_dir}/references/pyautogui-recipes.md`
