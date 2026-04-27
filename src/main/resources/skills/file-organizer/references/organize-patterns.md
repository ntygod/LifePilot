# 文件整理参考

按 SKILL body 表格的 5 条路径展开命令模板。所有破坏性操作走"**现状 → 预览 → 用户确认 → 执行 → 验证**"五步。

## 通用步骤模板

### 1. 了解现状

```
file.list(action="list", path="<目标目录>", maxDepth=3)            # 看目录树
file.list(action="list", path="<目标目录>", pattern="*.pdf")        # glob 过滤
file.list(action="info", path="<某文件>")                           # 看大小/修改时间
file.list(action="search", path="<目标目录>", filePattern="*.log",
          pattern="<内容正则>")                                      # 按内容找
```

### 2. 预览（必做）

整理前向用户输出明文表格：

```
共扫描到 87 个文件 / 12 个目录，计划如下：

| 来源 | 目标 | 动作 |
|---|---|---|
| D:\Downloads\IMG_*.jpg (32 个) | D:\Downloads\images\2026\ | move |
| D:\Downloads\report-*.pdf (8 个) | D:\Downloads\docs\reports\ | move |
| D:\Downloads\duplicate.zip | <操作目录>/.trash/<yyyy-MM-dd>/ | move（去重）|

确认执行？
```

### 3. 用户确认 → 执行

跨平台优先 `file.manage`：

```
file.manage(action="mkdir", path="<新建目录>")
file.manage(action="move",
            source="<原路径>",
            destination="<新路径>",
            overwrite=false)
file.manage(action="copy",
            source="<原路径>",
            destination="<备份路径>",
            recursive=true)
file.manage(action="delete",
            path="<要删的路径>",
            recursive=true)
```

仅当 `file.manage` 不能表达（如 robocopy 增量、xcopy 特殊参数）才用 `shell.exec`，并按平台拆分：

```
shell.exec(command="<windows 命令>", shell="cmd")      # 例: dir / robocopy
shell.exec(command="<linux/mac 命令>", shell="bash")   # 例: rsync
```

### 4. 验证结果

```
file.list(action="list", path="<目标目录>", maxDepth=2)
```

跟用户口述本次实际改了多少 / 跳过多少。

## 路径专属模板

### 整理目录（按类型/日期/项目分类归档）

| 分类依据 | 适用 | 目录形态示例 |
|---|---|---|
| 文件类型 | 混杂下载 | `images/`、`docs/`、`archives/`、`code/` |
| 日期 | 照片、日志 | `2026/04/`（按 mtime 取年月） |
| 项目名 | 工作文件 | `<projectA>/`、`<projectB>/` |
| 文件大小 | 磁盘清理 | `large-files/` 单独归类（>100MB） |

策略：先按主依据分大类，每类内可再按次依据细分。

### 批量重命名

```
# 步骤
1. file.list 找匹配文件 → 列出旧名
2. 按规则计算新名 → 输出旧→新映射给用户对
3. 用户确认 → 循环 file.manage(action="move") 改名
4. file.write 落 rename-map.json 到目录内备查
```

新名规则示例：序号补零 (`IMG_{0001..n}.jpg`)、加日期前缀 (`<yyyy-MM-dd>_<原名>`)、按 EXIF 拍摄日。

### 重复文件检测

```
# 步骤
1. file.list(action="list", path="<目录>", maxDepth=N) → 列文件清单
2. file.list(action="info", path=...) 拿 size，size 相同的进入下一步
3. 用 shell.exec 算 md5/sha1：
   shell.exec(command="certutil -hashfile <path> MD5", shell="cmd")  # Windows
   shell.exec(command="md5sum <path>", shell="bash")                  # Linux/Mac
4. 同 hash 归一组 → 输出每组保留哪份的建议（最早 / 路径最规范的）
5. 用户决定 → file.manage(action="move") 把多余的挪到 trash
```

### 磁盘空间分析

```
# Windows
shell.exec(command="dir <path> /s /-c | findstr 个文件", shell="cmd")
# Linux/Mac
shell.exec(command="du -sh <path>/*", shell="bash")
```

按大小降序输出 Top N 给用户看，再问要不要清理。

### 旧文件清理（按 mtime）

```
1. file.list(action="info", path=...) 收集 lastModified
2. 筛 lastModified < 当前 - 30 天 的文件 → 列预览
3. 用户确认 → file.manage(action="move") 到
   <操作目录>/.trash/<yyyy-MM-dd>/ （保留 7-30 天再清）
```

## 错误处理

| 现象 | 处理 |
|---|---|
| `权限不足` / `Access denied` | 提示用户用管理员模式重启，或手动 chmod；不要静默跳过 |
| `目标已存在` | 默认 `overwrite=false`；按需改名加序号后缀 `<name>(1).<ext>` 或换目标路径 |
| `路径过长`（Windows MAX_PATH） | 缩短目录层级或用 `\\?\` 长路径前缀 |
| `文件被占用` | 提示用户关闭占用程序后重试，不强删 |
| `磁盘空间不足` | 移动操作改成同盘内 rename；跨盘换成先 copy 再 delete 不可行时停下问 |
| 用户中途反悔 | 按 `rename-map.json` 反向 `file.manage(action="move")` 回滚 |
