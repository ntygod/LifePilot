package com.lifepilot.eval.web;

import org.springframework.lang.Nullable;

/**
 * 反馈请求 DTO。
 *
 * @param feedbackType 反馈类型：agree / disagree / golden_answer
 * @param comment      评论
 * @param goldenAnswer 标注答案
 * @author zsg
 * @since 2026-03-22
 */
public record FeedbackRequest(
        String feedbackType,
        @Nullable String comment,
        @Nullable String goldenAnswer
) {}
