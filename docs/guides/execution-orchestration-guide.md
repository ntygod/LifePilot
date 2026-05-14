# 知微执行工具编排指南

> 本文档面向需要组合使用 `shell.exec`、`shell.process`、`code` 三个执行工具完成复杂任务的 Skill 作者。
> 如果你的 Skill 只用到单个工具的单次调用，阅读对应的单工具指南即可。本文档聚焦于**多工具协作、多步编排、高阶场景**。

---

## 目录

- [三工具定位与协作关系](#三工具定位与协作关系)
- [场景一：外部编码 Agent 编排](#场景一外部编码-agent-编排)
- [场景二：数据管线（采集→清洗→分析→可视化）](#场景二数据管线采集清洗分析可视化)
- [场景三：并行任务分发与汇总](#场景三并行任务分发与汇总)
- [场景四：构建-测试-部署流水线](#场景四构建-测试-部署流水线)
- [场景五：交互式环境探索](#场景五交互式环境探索)
- [编排模式参考](#编排模式参考)
- [轮询策略指南](#轮询策略指南)
- [错误处理决策树](#错误处理决策树)
- [Skill 编写模板](#skill-编写模板)

---

## 三工具定位与协作关系

```
用户请求
  │
  ├─ 系统操作（CLI / 文件系统 / 进程管理）
  │     └─ shell.exec + shell.process
  │
  ├─ 计算与数据处理（分析 / 图表 / 格式转换）
  │     └─ code
  │
  └─ 复杂编排（多步 / 多工具 / 多 Agent）
        └─ shell.exec + shell.process + code 组合
```

### 各工具的不可替代能力

| 能力 | shell.exec | shell.process | code |
|------|-----------|--------------|------|
| 后台长跑进程 | ✅（background） | ✅（管理） | ❌ |
| 进程间通信（stdin/signal） | ❌ | ✅ | ❌ |
| 跨调用变量保持 | ❌ | ❌ | ✅（kernelId） |
| 预装数据科学栈 | ❌ | ❌ | ✅ |
| 代码预检 + 审计 | ❌ | ❌ | ✅ |
| 环境变量注入 | ✅ | ❌ | ❌ |
| 工作目录控制 | ✅ | ❌ | ❌（固定沙箱） |
| yieldMs 自适应 | ✅ | ❌ | ❌ |
| tmux 持久会话 | ❌ | ✅ | ❌ |

### 协作原则

1. **shell.exec 负责启动，shell.process 负责管理** — 启动后的交互全走 process
2. **code 负责计算，shell.exec 负责 I/O** — 数据文件的移动/下载用 shell，分析用 code
3. **不要用 code 做 shell 的事** — `os.system()`、`subprocess` 会被预检拦截
4. **不要用 shell 做 code 的事** — `python -c "..."` 没有预装库、没有审计、没有内核

---

## 场景一：外部编码 Agent 编排

最复杂的编排场景：用 Claude Code / Codex / Gemini CLI 作为子 Agent 执行编码任务。

### 单任务生命周期

```
步骤 1: 启动 CLI
  shell.exec(
    command='claude -p "修复 src/auth.ts 的类型错误" --output-format stream-json --permission-mode acceptEdits',
    workingDirectory="/path/to/project",
    background=true,
    env={"ANTHROPIC_API_KEY": "sk-..."}
  )
  → { sessionId: "a3f8c1b2" }

步骤 2: 验证启动（等 5 秒后首次检查）
  shell.process(action=output, sessionId="a3f8c1b2")
  → 看到 {"type":"init"} 即成功
  → state=FAILED 读 stderr 排查
  → 无输出 → 再等 5 秒（CLI 预热）

步骤 3: 轮询监控（每 10 秒）
  shell.process(action=output, sessionId="a3f8c1b2")
  → lastResult 有值 → 任务完成，跳到步骤 4
  → state=RUNNING + 有输出 → 继续等
  → state=RUNNING + 连续 3 轮无输出 → 可能卡住，kill 重试

步骤 4: 收集结果
  IF lastResult.is_error == false:
    shell.exec(command="git diff --stat", workingDirectory="/path/to/project")
    → 汇总改动报告给用户
  ELSE:
    读 output 里的错误信息，报告给用户

步骤 5: 清理
  shell.process(action=kill, sessionId="a3f8c1b2")  // 幂等，已完成也安全
```

### 串行编排（实现→审查）

```
阶段 A: 实现
  shell.exec(command='claude -p "实现用户注册功能" ...', background=true)
  → sessionId: "impl-01"
  轮询直到完成...

阶段 B: 审查（用不同 CLI 减少同模型盲区）
  shell.exec(command='codex exec "审查 src/auth/ 目录的新代码，检查安全漏洞" --json', background=true)
  → sessionId: "review-01"
  轮询直到完成...

汇总: 合并两阶段结果报告给用户
```

**关键约束**：
- 阶段 B 必须等阶段 A 的 exitCode=0 才启动
- 不要把 A 的 stream-json 塞进 B 的 prompt（太长且无意义）
- B 直接读 worktree 里的文件即可

### 并行编排（多 issue）

```
同时启动:
  shell.exec(command='claude -p "修复 issue #42" ...', workingDirectory="/worktree-42", background=true)
  → sessionId: "fix-42"

  shell.exec(command='claude -p "修复 issue #43" ...', workingDirectory="/worktree-43", background=true)
  → sessionId: "fix-43"

轮询（交替检查）:
  shell.process(action=output, sessionId="fix-42")
  shell.process(action=output, sessionId="fix-43")
  ...直到两个都完成

汇总: 分别报告每个 issue 的修复结果
```

**关键约束**：
- 并行任务**必须**用不同的 worktree / 工作目录，否则 git 冲突
- 注意后台进程并发上限（默认 5），别一次起太多

### 反馈环（审查→修→再审查）

```
轮次 1:
  审查: codex exec "审查代码" → 发现 3 个问题
  修复: claude -p "修复以下问题: ..." → 修复完成

轮次 2:
  审查: codex exec "再次审查" → 发现 1 个问题
  修复: claude -p "修复: ..." → 修复完成

硬上限: ≤2 轮。超过 2 轮停下来问用户。
```

---

## 场景二：数据管线（采集→清洗→分析→可视化）

shell.exec 和 code 的典型协作场景。

### 完整流程

```
步骤 1: 数据采集（shell.exec）
  shell.exec(command="curl -o /tmp/sales.csv https://api.example.com/export/sales")
  → exitCode=0, 文件已下载

步骤 2: 数据探索（code + kernelId）
  code(action=exec, kernelId="sales-pipeline", code="""
    import pandas as pd
    df = pd.read_csv('/tmp/sales.csv')
    print(f"行数: {len(df)}, 列: {list(df.columns)}")
    print(df.dtypes)
    print(df.isnull().sum())
  """)
  → 了解数据结构和质量

步骤 3: 数据清洗（code，同一内核）
  code(action=exec, kernelId="sales-pipeline", code="""
    df['date'] = pd.to_datetime(df['date'])
    df = df.dropna(subset=['amount'])
    df['amount'] = df['amount'].astype(float)
    print(f"清洗后行数: {len(df)}")
  """)

步骤 4: 分析（code，同一内核）
  code(action=exec, kernelId="sales-pipeline", code="""
    monthly = df.groupby(df['date'].dt.to_period('M'))['amount'].agg(['sum', 'mean', 'count'])
    print(monthly.to_markdown())
  """)

步骤 5: 可视化（code，同一内核）
  code(action=exec, kernelId="sales-pipeline", code="""
    import matplotlib.pyplot as plt
    fig, ax = plt.subplots(figsize=(12, 6))
    monthly['sum'].plot(kind='bar', ax=ax)
    ax.set_title('月度销售额')
    ax.set_ylabel('金额')
    plt.tight_layout()
    plt.savefig('/tmp/sales_chart.png', dpi=150)
    print('图表已保存: /tmp/sales_chart.png')
  """)

步骤 6: 结果交付（shell.exec）
  shell.exec(command="cp /tmp/sales_chart.png /Users/me/Desktop/")
  → 告诉用户图表在桌面
```

### 为什么这样分工

| 步骤 | 用 shell.exec | 用 code | 理由 |
|------|--------------|---------|------|
| curl 下载 | ✅ | ❌ | 系统 CLI 操作 |
| pandas 分析 | ❌ | ✅ | 预装库 + 内核保持 |
| matplotlib 画图 | ❌ | ✅ | 预装库 + 需要 df 变量 |
| 复制文件到桌面 | ✅ | ❌ | 文件系统操作 |

---

## 场景三：并行任务分发与汇总

### 并行测试（前端 + 后端 + E2E）

```
启动阶段（3 个 background 进程）:
  shell.exec(command="npm run test:unit", workingDirectory="/project/frontend", yieldMs=30000)
  shell.exec(command="mvn test -pl backend-core", workingDirectory="/project", yieldMs=60000)
  shell.exec(command="npm run test:e2e", workingDirectory="/project/e2e", background=true)

收集结果:
  前两个如果 yieldMs 内完成 → 直接拿同步结果
  第三个（E2E 通常慢）→ 轮询 shell.process(action=output)

汇总:
  code(action=exec, code="""
    results = {
      'unit': {'passed': 42, 'failed': 0},
      'backend': {'passed': 128, 'failed': 2},
      'e2e': {'passed': 15, 'failed': 1}
    }
    total_failed = sum(r['failed'] for r in results.values())
    print(f"总计: {total_failed} 个失败测试")
    for name, r in results.items():
        status = '✅' if r['failed'] == 0 else '❌'
        print(f"  {status} {name}: {r['passed']} passed, {r['failed']} failed")
  """)
```

### 并行方案探索

```
同时用两种方案解决同一问题:
  shell.exec(command='claude -p "用 Redis 实现分布式锁" ...', workingDirectory="/worktree-redis", background=true)
  shell.exec(command='claude -p "用 ZooKeeper 实现分布式锁" ...', workingDirectory="/worktree-zk", background=true)

两个都完成后:
  shell.exec(command="diff -r /worktree-redis/src /worktree-zk/src")
  → 对比两种实现的差异，报告给用户选择
```

---

## 场景四：构建-测试-部署流水线

### 典型 CI 流程

```
步骤 1: 构建（yieldMs，通常 10-30 秒）
  shell.exec(command="mvn clean package -DskipTests", yieldMs=30000)
  → 快则同步拿结果
  → 慢则转后台，轮询等待

步骤 2: 测试（yieldMs，可能 1-5 分钟）
  shell.exec(command="mvn test", yieldMs=60000)
  → 同上

步骤 3: 部署（background，不确定耗时）
  shell.exec(command="./deploy.sh production", background=true)
  → sessionId: "deploy-01"

步骤 4: 监控部署
  循环:
    shell.process(action=output, sessionId="deploy-01")
    → 看到 "Deployment successful" → 完成
    → 看到 "ERROR" / "FAILED" → 报告失败
    → state=COMPLETED + exitCode=0 → 完成
    → state=FAILED → 报告失败
    间隔 5 秒

步骤 5: 验证（部署后健康检查）
  shell.exec(command="curl -s http://localhost:8080/health", yieldMs=5000)
  → 检查返回 200
```

### 失败回滚

```
IF 部署失败:
  shell.exec(command="./rollback.sh", yieldMs=10000)
  → 报告回滚结果
```

---

## 场景五：交互式环境探索

### SSH 远程调试（持久会话）

```
步骤 1: 创建持久会话
  shell.process(action=session-create, name="remote-debug")
  → sessionId: "b7c2d4e1"

步骤 2: SSH 连接
  shell.process(action=session-exec, sessionId="b7c2d4e1", command="ssh user@server")
  → 等待连接...

步骤 3: 远程操作
  shell.process(action=session-exec, sessionId="b7c2d4e1", command="docker logs app --tail 50")
  shell.process(action=session-exec, sessionId="b7c2d4e1", command="top -bn1 | head -20")

步骤 4: 结束
  shell.process(action=session-exec, sessionId="b7c2d4e1", command="exit")
  shell.process(action=session-close, sessionId="b7c2d4e1")
```

### Python REPL + 数据探索（持久会话 vs 持久内核）

**用持久内核**（推荐，有预检 + 审计）：
```
code(action=exec, kernelId="explore", code="import pandas as pd; df = pd.read_csv('data.csv')")
code(action=exec, kernelId="explore", code="df.describe()")
code(action=exec, kernelId="explore", code="df[df['score'] > 90].head()")
```

**用持久会话**（需要 conda/venv 等 shell 环境时）：
```
shell.process(action=session-create, name="conda-env")
shell.process(action=session-exec, command="conda activate ml-env")
shell.process(action=session-exec, command="python")
shell.process(action=session-write, input="import torch; print(torch.cuda.is_available())\n")
shell.process(action=session-read)
```

---

## 编排模式参考

### 模式一：顺序管线

```
A → B → C
每步依赖前步的输出
失败时整条管线中止
```

适用：构建→测试→部署、采集→清洗→分析

### 模式二：并行扇出 + 汇总

```
    ┌─ A ─┐
入口 ─┼─ B ─┼─ 汇总
    └─ C ─┘
所有分支独立执行，全部完成后汇总
```

适用：多 issue 修复、多方案探索、并行测试

### 模式三：反馈环

```
A → B → 判断 → (不满足) → A → B → 判断 → (满足) → 结束
硬上限 ≤2 轮
```

适用：实现→审查→修复、生成→验证→调整

### 模式四：探测 + 分支

```
探测（yieldMs）
  ├─ 快速完成 → 直接用结果
  └─ 转后台 → 切换到轮询模式
```

适用：不确定耗时的命令

### 模式五：主进程 + 辅助计算

```
shell.exec(background) → 长跑主任务
  同时:
  code(kernelId) → 分析主任务的中间输出
  shell.exec → 辅助操作（下载依赖、准备环境）
```

适用：训练任务 + 实时指标分析

---

## 轮询策略指南

### 轮询间隔选择

| 任务类型 | 建议间隔 | 最大轮询次数 | 超限处理 |
|---------|---------|------------|---------|
| 编译/lint | 3 秒 | 40（~2 分钟） | 报告超时 |
| 单元测试 | 5 秒 | 60（~5 分钟） | 报告超时 |
| 部署脚本 | 5 秒 | 120（~10 分钟） | 报告超时 |
| 外部 CLI Agent | 10 秒 | 60（~10 分钟） | kill + 报告 |
| 训练任务 | 30 秒 | 无上限 | 用户主动停止 |

### "卡住了"的判断启发式

```
连续 N 次 output 增量为空 + state 仍为 RUNNING
  N=3（短任务，间隔 3-5 秒）→ 可能卡住
  N=5（长任务，间隔 10-30 秒）→ 大概率卡住

对策:
  1. 再等一轮确认
  2. kill + 重启（如果任务幂等）
  3. 报告给用户（如果任务不幂等）
```

### 轮询代码模板（Skill prompt 里的伪代码）

```
sessionId = shell.exec(command=..., background=true).sessionId
attempts = 0
maxAttempts = 60

LOOP:
  wait(interval)
  result = shell.process(action=output, sessionId=sessionId)

  IF result.lastResult exists:
    → 任务完成，处理 lastResult
    BREAK

  IF result.state in [COMPLETED, FAILED, KILLED]:
    → 进程已退出，处理最终输出
    BREAK

  IF result.output is empty:
    attempts += 1
    IF attempts >= 3:
      → 可能卡住，决定 kill 还是继续等
  ELSE:
    attempts = 0  // 有输出，重置计数

  IF 总轮询次数 > maxAttempts:
    → 超时，kill + 报告
    BREAK
```

---

## 错误处理决策树

### shell.exec 错误

```
收到错误响应
├─ "命令被安全策略拒绝" / "命令被永久阻断"
│   └─ 不可重试。换方案或告知用户
├─ "命令被拒绝执行（危险操作）"
│   └─ 不可重试（除非 yolo 模式）。换方案或告知用户
├─ "工作目录不存在"
│   └─ 修正路径重试
├─ "命令执行超时"
│   └─ 改用 background 或拉高 timeout
├─ "后台进程数已达上限"
│   └─ shell.process(action=list) 清理空闲进程后重试
├─ exitCode != 0（命令本身失败）
│   └─ 读 stderr 判断原因，修正命令重试
└─ "命令执行被中断"（transientError）
    └─ 可以直接重试
```

### code 错误

```
收到错误响应
├─ "代码执行环境未启用"
│   └─ 不可重试。引导用户去设置页
├─ "代码预检未通过"
│   └─ 不可重试当前代码。换写法（不要绕过检测）或改用 shell.exec
├─ "此命令被永久阻断" / "此命令被拒绝执行"
│   └─ 不可重试。换方案
├─ "持久内核数已达上限"
│   └─ 等待空闲内核超时，或复用已有 kernelId
├─ "沙箱实例获取失败"
│   └─ 可能是资源不足，稍后重试
├─ "内核执行失败"（kernelId 模式）
│   └─ kernel_inspect 查看状态，kernel_reset 后重试
├─ exitCode != 0（代码运行时错误）
│   └─ 读 stderr（通常是 Python traceback），修正代码重试
└─ "不支持的语言"
    └─ 检查 language 参数拼写
```

### shell.process 错误

```
收到错误响应
├─ "后台进程不存在: sessionId=xxx"
│   └─ 进程已被清理。重新启动
├─ "进程已结束，无法写入"
│   └─ 正常情况，进程已退出。读 output 获取最终结果
└─ 其他 IO 错误
    └─ 可以重试一次
```

---

## Skill 编写模板

### 模板一：外部 Agent 编排 Skill

```markdown
---
name: code-task-runner
description: 使用外部编码 CLI 执行复杂编码任务
suggested_tools:
  - shell_exec
  - shell_process
---

# 编码任务执行指南

## 工具选择
- 启动 CLI: shell.exec(background=true)
- 监控进程: shell.process(action=output)
- 终止进程: shell.process(action=kill)
- 查看改动: shell.exec(command="git diff --stat")

## 执行流程
1. 确认 CLI 可用（shell.exec "claude --version"）
2. 启动任务（background=true + env 注入 API key）
3. 5 秒后首次检查启动状态
4. 每 10 秒轮询 output，检查 lastResult
5. 完成后 git diff --stat 汇总改动
6. kill 清理进程

## 错误处理
- 启动秒退: 读 stderr，通常是 API key 过期或参数错
- 连续 3 轮无输出: kill 后重启
- 后台进程达上限: list 后清理空闲进程
- 反馈环超 2 轮: 停下来问用户
```

### 模板二：数据分析 Skill

```markdown
---
name: data-analyst
description: 数据分析与可视化
suggested_tools:
  - code
  - shell_exec
  - file_read
---

# 数据分析指南

## 工具分工
- 下载/移动数据文件: shell.exec
- 数据加载/清洗/分析/可视化: code(kernelId=...)
- 读取小文件内容: file_read

## 执行流程
1. 确认数据文件位置（file_read 或 shell.exec "ls"）
2. 创建分析内核: code(kernelId="analysis-{任务名}")
3. 加载数据 + print(df.shape, df.dtypes)
4. 按用户需求逐步分析（每步一次 exec）
5. 生成图表 savefig 到用户可访问路径
6. 汇总发现报告给用户

## 关键规则
- 文件路径必须用绝对路径
- 大数据先 df.head() 预览
- 图表用 plt.savefig()，不要 plt.show()
- 超过 30 秒的计算显式传 timeoutSeconds
```

### 模板三：构建部署 Skill

```markdown
---
name: build-deploy
description: 项目构建、测试和部署
suggested_tools:
  - shell_exec
  - shell_process
---

# 构建部署指南

## 模式选择
- 编译/lint: 同步（默认 120s 够用）
- 测试: yieldMs=60000（快则同步，慢则后台）
- 部署: background=true（耗时不可预测）
- 健康检查: yieldMs=5000

## 执行流程
1. 构建: shell.exec("mvn clean package -DskipTests")
2. 测试: shell.exec("mvn test", yieldMs=60000)
3. 部署: shell.exec("./deploy.sh", background=true)
4. 监控: shell.process(action=output) 每 5 秒
5. 验证: shell.exec("curl health-endpoint", yieldMs=5000)

## 失败处理
- 构建失败: 读 stderr，报告编译错误
- 测试失败: 读 stdout 找失败用例
- 部署失败: 执行 rollback.sh
- 健康检查失败: 等 10 秒重试，3 次失败则回滚
```

---

## 附录：工具能力速查表

| 需求 | 工具 | 参数要点 |
|------|------|---------|
| 秒级命令 | shell.exec | 默认同步 |
| 不确定耗时 | shell.exec | yieldMs=3000~10000 |
| 永不退出的服务 | shell.exec | background=true |
| 读后台进程输出 | shell.process | action=output, sessionId |
| 终止后台进程 | shell.process | action=kill, sessionId |
| 向进程发信号 | shell.process | action=session-signal（需 tmux） |
| 一次性计算 | code | action=exec |
| 多步分析 | code | action=exec, kernelId="..." |
| 查看内核变量 | code | action=kernel_inspect, kernelId |
| 重置内核 | code | action=kernel_reset, kernelId |
| 下载文件 | shell.exec | command="curl -o ..." |
| 数据分析 | code | kernelId + pandas |
| 生成图表 | code | kernelId + matplotlib + savefig |
| 文件格式转换 | code | python-docx / openpyxl / pypdf |
| 外部 Agent | shell.exec + shell.process | background + output 轮询 |
| 交互式 REPL | shell.process | session-create + session-exec |

---

## 相关文档

- [Shell 工具使用指南](./shell-tool-guide.md) — shell.exec 和 shell.process 的完整参数与行为
- [Code 工具使用指南](./code-tool-guide.md) — code 工具的完整参数与行为
- `src/main/resources/skills/code-assistant/SKILL.md` — 编码代理 Skill 定义
- `src/main/resources/skills/code-assistant/references/agent-lifecycle.md` — Agent 生命周期参考
- `src/main/resources/skills/code-assistant/references/orchestration.md` — CLI 速查与编排参考
