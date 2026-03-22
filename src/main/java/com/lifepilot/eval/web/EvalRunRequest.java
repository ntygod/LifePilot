package com.lifepilot.eval.web;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 评估运行请求 DTO。
 *
 * <p>客户端通过 POST /api/eval/runs 提交此请求触发批量评估。
 * scenarioIds 和 tag 均为可选：两者都为空时执行全部场景，
 * 指定 scenarioIds 时按 ID 过滤，指定 tag 时按标签过滤。</p>
 *
 * @param scenarioIds    指定场景 ID 列表（可选）
 * @param tag            按标签过滤（可选）
 * @param baselineRunId  对比基线运行 ID（可选）
 * @param labels         自定义标签（可选）
 * @param smokeTestOnly  只运行 smoke 标签场景
 * @param offlineReeval  离线重评模式
 * @author zsg
 * @since 2026-03-17
 */
public record EvalRunRequest(
        @Nullable List<String> scenarioIds,
        @Nullable String tag,
        @Nullable String baselineRunId,
        @Nullable Map<String, String> labels,
        @Nullable Boolean smokeTestOnly,
        @Nullable Boolean offlineReeval
) {}
