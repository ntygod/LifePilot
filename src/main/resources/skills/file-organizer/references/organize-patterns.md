# 文件整理参考

所有破坏性操作走 **现状 → 预览 → 用户确认 → 执行 → 验证** 五步。

## 整理策略

| 分类依据 | 适用 | 目录形态示例 |
|----------|------|-------------|
| 文件类型 | 混杂下载 | `images/` `docs/` `archives/` `code/` |
| 日期 | 照片、日志 | `2026/04/`（按 mtime 取年月） |
| 项目名 | 工作文件 | `<projectA>/` `<projectB>/` |
| 文件大小 | 磁盘清理 | `large-files/` 单独归类（>100MB） |

先按主依据分大类，每类内按次依据细分。

## 批量重命名

1. file.read(list) 找匹配文件 → 列旧名
2. 按规则计算新名 → 输出旧→新映射预览
3. 用户确认 → 循环 file.manage(action="move") 改名
4. file.write 落 rename-map.json 到目录内备查

## 重复文件检测

1. file.read(list) 列文件清单
2. file.read(info) 拿 size，同 size 的进入下一步
3. shell.exec 算 md5（Windows: `certutil -hashfile <path> MD5`，Linux: `md5sum <path>`）
4. 同 hash 归组 → 推荐保留最早/路径最规范的
5. 用户决定 → file.manage(move) 挪多余到 trash

## 旧文件清理（按 mtime）

1. file.read(info) 收集 lastModified
2. 筛 lastModified < 当前-30 天的文件 → 列预览
3. 用户确认 → file.manage(move) 到 .trash/yyyy-MM-dd/（保留 7-30 天再清）

## 常见错误

| 现象 | 处理 |
|------|------|
| 权限不足 | 提示管理员模式，不静默跳过 |
| 目标已存在 | 默认 overwrite=false，加序号后缀 `(1)` |
| 路径过长（Windows） | 缩短目录层级或用 `\\?\` 前缀 |
| 文件被占用 | 提示关程序后重试 |
| 用户反悔 | 按 rename-map.json 反向 file.manage(move) 回滚 |

## 可用脚本

以下脚本位于 `{skill_scripts_dir}/`，用 `shell.exec(command="python <path> <args>")` 调用：

- `dedup-by-hash.py <目录>` — 按文件 hash 检测重复，输出 JSON 分组。比 LLM 手动写循环更快且不易出错
- `stale-files.py <目录> --days N` — 扫描超过 N 天未修改的文件，输出路径/大小/时间
