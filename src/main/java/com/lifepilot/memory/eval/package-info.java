/**
 * 记忆评估 Harness —— 开发期离线回归基线。
 *
 * <p><b>职责</b>：基于公开基准（LoCoMo / LongMemEval）度量知微记忆系统的召回质量、
 * 延迟、token 成本与稳定性，并对比历史基线检测回归。每次改动记忆读写链路后可跑一次，
 * 客观回答"整体是否变好了"。</p>
 *
 * <p><b>非目标</b>：</p>
 * <ul>
 *     <li>不面向终端用户，不暴露在产品 UI。</li>
 *     <li>不替代 Phase 5 的 Agentic Evals（模块 20.5），后者面向 Agent 轨迹评估，
 *         本模块面向记忆子系统质量。</li>
 *     <li>不在线上运行；所有测量只在开发机 / CI 上离线执行。</li>
 *     <li>不自建数据集，仅复用社区已有标注数据。</li>
 * </ul>
 *
 * <p><b>激活方式</b>：</p>
 * <ul>
 *     <li>{@code lifepilot.memory.eval.enabled=true}（默认关闭）</li>
 *     <li>Maven profile {@code memory-eval-quick}（快速模式，~5 分钟）</li>
 *     <li>Maven profile {@code memory-eval-full}（完整模式，~60-120 分钟）</li>
 * </ul>
 *
 * <p>上位文档：</p>
 * <ul>
 *     <li>{@code docs/architecture/memory-eval-harness.md}</li>
 *     <li>{@code docs/features/memory-eval-harness.md}</li>
 *     <li>{@code docs/planned/memory-and-proactive-evolution-gaps.md} §1 M-P0-1</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-05-09
 */
package com.lifepilot.memory.eval;
