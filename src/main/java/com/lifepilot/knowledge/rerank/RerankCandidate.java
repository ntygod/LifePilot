package com.lifepilot.knowledge.rerank;

/**
 * 通用精排候选项 — 与具体检索结果类型解耦的精排输入。
 *
 * @param id      候选项唯一标识
 * @param content 用于精排评分的文本内容
 * @param score   初始检索分数
 * @author zsg
 * @since 2026-03-15
 */
public record RerankCandidate(String id, String content, double score) {}
