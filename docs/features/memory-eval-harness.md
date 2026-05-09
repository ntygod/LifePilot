# 记忆评估 Harness — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.memory.eval`（新建）
> **最后更新**：2026-05-09
> **面向读者**：知微开发者、CI 维护者
> **上位 gap**：`docs/planned/memory-and-proactive-evolution-gaps.md` §1 M-P0-1
> **不面向**：终端用户（开发期工具，产品包中不暴露）

---

## 1. 功能概述与价值

知微的记忆系统已包含 L0-L4 七层、质量字段、项目空间隔离、overlay、投影 outbox、候选流水线、MaRS 遗忘策略等大量机制。每次新增一个 listener、调整一个阈值、改一条遗忘权重，工程师都会问同一个问题：**"这次改完整体是变好了还是变差了？"**

Memory Eval Harness 提供离线回归通道，解决这个问题：

- **客观**：基于开源公共基准（LoCoMo、LongMemEval），答案有标准参考
- **可重复**：隔离 SQLite 环境，每次跑批结果可对比
- **有基线**：历史基线落盘 git，变差自动 fail
- **低打扰**：默认不跑，仅在 `-Pmemory-eval` profile 下触发

**不服务用户，只服务迭代**。

---

## 2. 核心特性

### 2.1 开源数据集即用

**支持 benchmark**：

- `LoCoMo`（multi-session conversational memory，arXiv:2402.09727）
- `LongMemEval`（ICLR 2025，500 题，5 类能力评估）
- 预留扩展口，后续可加 BEAM、PerLTQA 等

**首次使用**：

```bash
./scripts/download-eval-datasets.sh
# 默认落到 ~/.zhiwei/eval-cache/
#   locomo/
#     conversations.json    (10 conversations)
#   longmemeval/
#     longmemeval_s.json    (500 questions)
```

### 2.2 四维指标

| 维度 | 指标 |
|---|---|
| 召回质量 | LLM-Score (0/1) / F1 / BLEU / ExactMatch |
| 效率 | p50 / p95 / p99 检索延迟 |
| 成本 | 每次召回 token 数 / 整 session 累计 LLM 调用数 |
| 稳定性 | N 次重跑方差 |

### 2.3 基线对比与回归检测

- 每次 merge 前跑 harness，输出 `docs/memory-eval/baseline.json`
- 再次跑批时对比 baseline，指标退化超阈值 → 自动 fail
- 阈值（可配置）：
  - LLM-Score 下降 > 3%
  - p95 延迟上升 > 20%
  - token 成本上升 > 30%

### 2.4 LLM-as-judge 可选

- 默认关闭，只用规则 judge（ExactMatch / F1 / BLEU）
- 开启：`lifepilot.memory.eval.judge.llm-enabled=true` 并配置 LLM Router
- 关闭时，复杂问答题标记为 `skipped_judge_required`，不计入整体得分

### 2.5 隔离评估环境

- 每个用例独立 SQLite 文件（`tmp/zhiwei-eval-<uuid>.db`）
- 跑完自动清理
- 不碰开发者本机的真实记忆数据库

### 2.6 两种跑批模式

| 模式 | 触发方式 | 耗时 | 场景 |
|---|---|---|---|
| 快速 | `mvn test -Pmemory-eval-quick` | ~5 分钟 | 本地改动后快速验证，只跑 LongMemEval 前 50 题 + LoCoMo 前 2 对话 |
| 完整 | `mvn test -Pmemory-eval-full` | ~60-120 分钟 | nightly CI / pre-merge，LoCoMo 全量 + LongMemEval 全量 |

---

## 3. 使用场景

### 场景 A：修改记忆读写链路前后对比

```bash
# 1. 保存基线
mvn test -Pmemory-eval-full
# → target/memory-eval/<timestamp>.md
# → target/memory-eval/<timestamp>.json

# 2. 修改代码（例：调整 ForgettingPolicy 权重）

# 3. 再次跑
mvn test -Pmemory-eval-full
# → RegressionDetector 对比 baseline.json 与新结果
# → 控制台输出退化/改进明细
```

### 场景 B：CI 拦截退化 PR

在 `.github/workflows/ci.yml` 中加入：

```yaml
- name: Memory Eval (quick)
  run: mvn test -Pmemory-eval-quick
```

任何 PR 如果导致 quick 跑批 fail，CI 拦截。

### 场景 C：记忆相关 spec 设计阶段的证据采集

在 `docs/planned/memory-and-proactive-evolution-gaps.md` 的每条 P1/P2 gap 拆 spec 前，先基于 harness 取得当前指标值，作为 "待解决问题" 的客观证据。

---

## 4. 配置项

全部配置位于 `lifepilot.memory.eval.*`，默认 `enabled=false`：

```yaml
lifepilot:
  memory:
    eval:
      enabled: false                 # 总开关，profile 启用时覆盖
      dataset-cache-dir: ~/.zhiwei/eval-cache
      benchmarks:
        locomo:
          enabled: true
          max-conversations: 10      # quick mode 降级为 2
        long-mem-eval:
          enabled: true
          max-questions: 500         # quick mode 降级为 50
      judge:
        llm-enabled: false           # LLM-as-judge 默认关
        llm-scene: eval              # 走独立场景配置
        exact-match-enabled: true
        f1-enabled: true
      regression:
        enabled: true
        baseline-path: docs/memory-eval/baseline.json
        llm-score-tolerance: 0.03    # 3%
        latency-tolerance: 0.20      # 20%
        token-tolerance: 0.30        # 30%
      isolation:
        use-temp-sqlite: true        # 每个用例独立 SQLite
        keep-db-on-failure: false    # 失败时是否保留 DB 方便调试
      reporting:
        output-dir: target/memory-eval
        markdown: true
        json: true
```

---

## 5. 输出示例

### 5.1 Markdown 报告

```markdown
# Memory Eval Report — 2026-05-10 02:14

| Benchmark | LLM-Score | F1 | p95 latency | avg tokens |
|-----------|-----------|-----|-------------|------------|
| LoCoMo (10 conv) | 0.72 | 0.68 | 840ms | 1,850 |
| LongMemEval (500q) | 0.78 | 0.74 | 620ms | 1,420 |

## 对比基线 (baseline.json @ 2026-05-08)

- LLM-Score: 0.72 → **+0.01** ✓
- p95 latency: 840ms → **-120ms** ✓
- avg tokens: 1,850 → **+180** ⚠️ (+10.8%, 未超阈值)

## 明细

[... 500 题逐题结果 ...]
```

### 5.2 JSON 报告

```json
{
  "timestamp": "2026-05-10T02:14:00Z",
  "git_sha": "abc1234",
  "benchmarks": [
    {
      "name": "locomo",
      "conversations": 10,
      "metrics": {
        "llm_score": 0.72,
        "f1": 0.68,
        "p50_latency_ms": 420,
        "p95_latency_ms": 840,
        "avg_tokens_per_recall": 1850
      }
    }
  ],
  "regression": {
    "against_baseline": "2026-05-08",
    "status": "pass",
    "diffs": {...}
  }
}
```

---

## 6. 限制与扩展方向

### 6.1 当前限制

- 只覆盖**对话记忆**（L0/L2/L3），不覆盖知识库 RAG 召回（已有 `rag-optimization` spec 覆盖）
- LoCoMo/LongMemEval 数据集为英文；中文场景可能表现不同（后续可加自建中文小样本补充，但不作为核心指标）
- LLM-as-judge 有偶发抖动，即使 3 次 median 也无法完全消除；重要决策请人工复核

### 6.2 后续扩展方向

- 接入 BEAM 作为大规模压力测试
- 补充知识库 RAG 专用的评估流程（可能合并或独立）
- 与 Agentic Evals（模块 20.5）在 `common.judge` 层面共享 LLM-as-judge 代码
- 记忆安全评估（prompt injection 注入测试），见 `memory-and-proactive-evolution-gaps.md` M-P2-6
- 中文评估数据集补充（非高优，只有中文特异问题出现时再做）

---

## 7. 与现有模块关系

| 模块 | 关系 |
|---|---|
| `docs/architecture/memory-system.md` | 本模块评估其实现质量 |
| `docs/features/agentic-evals.md` | Agent 轨迹评估，与本模块职责不同但技术栈接近 |
| `docs/planned/memory-and-proactive-evolution-gaps.md` | 记录本模块是后续所有记忆演进 spec 的前置依赖 |
| `docs/architecture/memory-eval-harness.md` | 架构设计，本文档的上位文件 |
