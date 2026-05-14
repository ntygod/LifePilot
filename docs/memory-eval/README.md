# Memory Eval Harness — 基线目录

本目录用于存放 Memory Eval Harness 的历史基线。

## 文件

- `baseline.json` — 最新基线（纳入 git，merge 到 develop 前由开发者手动更新）

## 基线更新流程

1. 确认改动合理且已通过代码审查
2. 本地跑一次完整评估，确认输出符合预期：
   ```bash
   mvn test -Pmemory-eval-full
   ```
3. 更新基线：
   ```bash
   mvn test -Pmemory-eval-full -Dlifepilot.memory.eval.regression.update-baseline=true
   ```
4. 在 commit message 中说明基线变化的原因（例：改进了某项指标，或接受某项轻微退化换取其他价值）

## 当前状态

基线尚未生成。首次运行完整评估后会生成 `baseline.json`。

## 相关文档

- 架构设计：`docs/architecture/memory-system.md` §8.3
- 特性说明：`docs/features/memory-system.md` §3.4
- Spec：`.kiro/specs/memory-eval-harness/`
