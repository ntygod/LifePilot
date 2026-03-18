package com.lifepilot.agent.model;

import com.lifepilot.agent.config.AgentConfigProperties;
import net.jqwik.api.*;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Budget 属性测试 — 验证三维预算 record 的核心不变量。
 *
 * @author zsg
 * @since 2026-03-18
 */
class Budget属性测试 {

    // ===== 属性 1: exceeded/exceedReason 一致性 =====

    @Property(tries = 200)
    void exceeded为true时_exceedReason不为预算未超限(
            @ForAll @IntRange(min = 1, max = 100000) int maxTokens,
            @ForAll @IntRange(min = 1, max = 100) int maxSteps,
            @ForAll @IntRange(min = 1, max = 600) int maxDurationSec,
            @ForAll @IntRange(min = 0, max = 200000) int tokensUsed,
            @ForAll @IntRange(min = 0, max = 200) int stepsUsed,
            @ForAll @IntRange(min = 0, max = 1200) int elapsedSec) {

        var budget = Budget.builder()
                .maxTokens(maxTokens).tokensUsed(tokensUsed).tokensReserved(0)
                .maxSteps(maxSteps).stepsUsed(stepsUsed)
                .maxDuration(Duration.ofSeconds(maxDurationSec))
                .elapsed(Duration.ofSeconds(elapsedSec))
                .build();

        if (budget.exceeded()) {
            assertNotEquals("预算未超限", budget.exceedReason(),
                    "exceeded()=true 时 exceedReason 不应为 '预算未超限'");
        } else {
            assertEquals("预算未超限", budget.exceedReason(),
                    "exceeded()=false 时 exceedReason 应为 '预算未超限'");
        }
    }

    // ===== 属性 2: incrementStep 不变量 =====

    @Property(tries = 200)
    void incrementStep_步骤加一且其他字段不变(
            @ForAll @IntRange(min = 0, max = 100) int stepsUsed,
            @ForAll @IntRange(min = 1, max = 100000) int maxTokens,
            @ForAll @IntRange(min = 0, max = 100000) int tokensUsed) {

        var budget = Budget.builder()
                .maxTokens(maxTokens).tokensUsed(tokensUsed).tokensReserved(0)
                .maxSteps(50).stepsUsed(stepsUsed)
                .maxDuration(Duration.ofSeconds(120))
                .elapsed(Duration.ZERO)
                .build();

        var next = budget.incrementStep();

        assertEquals(stepsUsed + 1, next.stepsUsed(), "stepsUsed 应加 1");
        assertEquals(budget.tokensUsed(), next.tokensUsed(), "tokensUsed 不应变化");
        assertEquals(budget.maxTokens(), next.maxTokens(), "maxTokens 不应变化");
        assertEquals(budget.maxSteps(), next.maxSteps(), "maxSteps 不应变化");
        assertEquals(budget.elapsed(), next.elapsed(), "elapsed 不应变化");
    }

    // ===== 属性 3: allocateForSubAgent 步骤初始化 =====

    @Property(tries = 100)
    void allocateForSubAgent_子预算stepsUsed始终为零(
            @ForAll @DoubleRange(min = 0.1, max = 0.9) double ratio) {

        var parent = Budget.builder()
                .maxTokens(32000).tokensUsed(5000).tokensReserved(0)
                .maxSteps(20).stepsUsed(8)
                .maxDuration(Duration.ofSeconds(120))
                .elapsed(Duration.ofSeconds(30))
                .build();

        var sub = parent.allocateForSubAgent(ratio);

        assertEquals(0, sub.stepsUsed(), "子预算 stepsUsed 应为 0");
        assertEquals(0, sub.tokensUsed(), "子预算 tokensUsed 应为 0");
        assertTrue(sub.maxSteps() > 0, "子预算 maxSteps 应大于 0");
        assertTrue(sub.maxTokens() > 0, "子预算 maxTokens 应大于 0");
        assertEquals(Duration.ZERO, sub.elapsed(), "子预算 elapsed 应为 ZERO");
    }

    // ===== 属性 4: fromConfig 配置映射 =====

    @Property(tries = 50)
    void fromConfig_配置值正确映射到Budget字段(
            @ForAll @IntRange(min = 1000, max = 100000) int maxTokens,
            @ForAll @IntRange(min = 5, max = 50) int maxSteps,
            @ForAll @IntRange(min = 30, max = 600) int maxDurationSec) {

        var config = new AgentConfigProperties.BudgetConfig();
        config.setDefaultMaxTokens(maxTokens);
        config.setDefaultMaxSteps(maxSteps);
        config.setDefaultMaxDurationSeconds(maxDurationSec);

        var budget = Budget.fromConfig(config);

        assertEquals(maxTokens, budget.maxTokens(), "maxTokens 应等于配置值");
        assertEquals(maxSteps, budget.maxSteps(), "maxSteps 应等于配置值");
        assertEquals(Duration.ofSeconds(maxDurationSec), budget.maxDuration(), "maxDuration 应等于配置值");
        assertEquals(0, budget.tokensUsed(), "tokensUsed 应初始化为 0");
        assertEquals(0, budget.stepsUsed(), "stepsUsed 应初始化为 0");
        assertEquals(0, budget.tokensReserved(), "tokensReserved 应初始化为 0");
        assertEquals(Duration.ZERO, budget.elapsed(), "elapsed 应初始化为 ZERO");
    }
}
