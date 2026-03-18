package com.lifepilot.memory.compression;

import com.lifepilot.llm.LlmRequest;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.episodic.CompressionLevel;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.episodic.MessageRecord;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话压缩服务 — 使用 LLM 将 L2 情景记忆中的对话压缩为摘要 / 要点。
 *
 * <p>职责：
 * <ul>
 *     <li>根据目标压缩层级构建提示词</li>
 *     <li>调用 LLM 执行压缩</li>
 *     <li>将压缩结果回写到 {@link EpisodicMemory}</li>
 * </ul>
 * 压缩失败不会影响主流程，仅记录警告日志。</p>
 */
public class CompressionService {

    private static final Logger log = LoggerFactory.getLogger(CompressionService.class);

    private final LlmRouter llmRouter;
    private final EpisodicMemory episodicMemory;
    private final PromptRegistry promptRegistry;
    private final MemoryProperties properties;

    public CompressionService(LlmRouter llmRouter,
                              EpisodicMemory episodicMemory,
                              PromptRegistry promptRegistry,
                              MemoryProperties properties) {
        this.llmRouter = llmRouter;
        this.episodicMemory = episodicMemory;
        this.promptRegistry = promptRegistry;
        this.properties = properties;
    }

    /**
     * 判断是否需要压缩。
     *
     * @param tokenCount 消息总 Token 数
     * @return 是否超过压缩阈值
     */
    public boolean shouldCompress(int tokenCount) {
        return tokenCount > properties.getCompressionThresholdTokens();
    }

    /**
     * 滑动窗口压缩 — 将消息按窗口分段，每个窗口独立压缩。
     *
     * <p>实现逻辑：
     * <ol>
     *   <li>过滤 pinned 消息（保持 ORIGINAL，不参与压缩）</li>
     *   <li>将非 pinned 消息按 windowSize 分段，窗口间保留 windowOverlap 条重叠</li>
     *   <li>跳过最后一个窗口（最近消息保持原始）</li>
     *   <li>从最早窗口开始逐窗口调用 LLM 压缩</li>
     *   <li>SUMMARY 压缩完成后，若压缩 Token 仍超阈值 150%，继续 KEYPOINTS 压缩</li>
     * </ol></p>
     *
     * @param conversationId 对话 ID
     * @param messages       待压缩消息列表
     * @param targetLevel    目标压缩层级
     */
    @Async
    public void compressWithSlidingWindow(String conversationId,
                                           List<MessageRecord> messages,
                                           CompressionLevel targetLevel) {
        if (messages == null || messages.isEmpty()) {
            log.debug("滑动窗口压缩: 无消息可压缩, conversationId={}", conversationId);
            return;
        }

        int windowSize = properties.getCompression().getWindowSize();
        int windowOverlap = properties.getCompression().getWindowOverlap();

        // 分离 pinned 消息和非 pinned 消息
        List<MessageRecord> nonPinned = messages.stream()
                .filter(m -> !m.isPinned())
                .toList();

        if (nonPinned.isEmpty()) {
            log.debug("滑动窗口压缩: 所有消息均为 pinned, conversationId={}", conversationId);
            return;
        }

        // 按滑动窗口分段
        List<List<MessageRecord>> windows = partitionSlidingWindows(nonPinned, windowSize, windowOverlap);

        if (windows.size() <= 1) {
            log.debug("滑动窗口压缩: 消息不足以形成多个窗口, conversationId={}", conversationId);
            return;
        }

        // 跳过最后一个窗口（最近消息保持原始）
        int compressedTokenTotal = 0;
        for (int i = 0; i < windows.size() - 1; i++) {
            List<MessageRecord> window = windows.get(i);
            int originalTokens = window.stream().mapToInt(MessageRecord::tokenCount).sum();

            try {
                String prompt = promptRegistry.render("memory/compression-summary",
                        Map.of("conversation", formatMessages(window)));
                var response = llmRouter.call(LlmRequest.of(LlmScene.MEMORY_COMPRESSION, prompt));
                String compressed = response.content();
                int compressedTokens = TokenEstimator.estimate(compressed);

                Map<String, String> compressedTexts = new HashMap<>();
                for (var msg : window) {
                    compressedTexts.put(msg.id(), compressed);
                }
                episodicMemory.compress(conversationId, CompressionLevel.SUMMARY, compressedTexts);

                float ratio = originalTokens > 0 ? (float) compressedTokens / originalTokens : 0;
                log.info("滑动窗口压缩: conversationId={}, window={}/{}, originalTokens={}, compressedTokens={}, ratio={}",
                        conversationId, i + 1, windows.size() - 1, originalTokens, compressedTokens,
                        String.format("%.2f", ratio));

                compressedTokenTotal += compressedTokens;
            } catch (Exception e) {
                log.warn("滑动窗口压缩失败: conversationId={}, window={}, error={}",
                        conversationId, i + 1, e.getMessage());
                // LLM 调用失败 → 保留原始内容，继续下一个窗口
                compressedTokenTotal += originalTokens;
            }
        }

        // 加上最后一个窗口（未压缩）的 Token 数
        List<MessageRecord> lastWindow = windows.getLast();
        compressedTokenTotal += lastWindow.stream().mapToInt(MessageRecord::tokenCount).sum();

        // 两级递进：SUMMARY 压缩后仍超阈值 150%，继续 KEYPOINTS 压缩
        int threshold = properties.getCompressionThresholdTokens();
        if (compressedTokenTotal > threshold * 1.5) {
            log.info("滑动窗口压缩: SUMMARY 后仍超阈值 150%, 触发 KEYPOINTS 压缩, " +
                            "conversationId={}, compressedTokens={}, threshold={}",
                    conversationId, compressedTokenTotal, threshold);

            for (int i = 0; i < windows.size() - 1; i++) {
                List<MessageRecord> window = windows.get(i);
                try {
                    String prompt = promptRegistry.render("memory/compression-keypoints",
                            Map.of("summary", formatMessages(window)));
                    var response = llmRouter.call(LlmRequest.of(LlmScene.MEMORY_COMPRESSION, prompt));
                    String compressed = response.content();

                    Map<String, String> compressedTexts = new HashMap<>();
                    for (var msg : window) {
                        compressedTexts.put(msg.id(), compressed);
                    }
                    episodicMemory.compress(conversationId, CompressionLevel.KEYPOINTS, compressedTexts);

                    log.info("KEYPOINTS 压缩完成: conversationId={}, window={}/{}",
                            conversationId, i + 1, windows.size() - 1);
                } catch (Exception e) {
                    log.warn("KEYPOINTS 压缩失败: conversationId={}, window={}, error={}",
                            conversationId, i + 1, e.getMessage());
                }
            }
        }
    }

    /**
     * 将消息列表按滑动窗口分段。
     *
     * @param messages      消息列表
     * @param windowSize    窗口大小
     * @param windowOverlap 窗口重叠消息数
     * @return 窗口列表
     */
    List<List<MessageRecord>> partitionSlidingWindows(List<MessageRecord> messages,
                                                      int windowSize,
                                                      int windowOverlap) {
        List<List<MessageRecord>> windows = new ArrayList<>();
        int step = Math.max(1, windowSize - windowOverlap);
        for (int start = 0; start < messages.size(); start += step) {
            int end = Math.min(start + windowSize, messages.size());
            windows.add(messages.subList(start, end));
            if (end == messages.size()) break;
        }
        return windows;
    }

    /**
     * 异步压缩指定对话到目标层级。
     *
     * @param conversationId 对话 ID
     * @param messages       待压缩的消息列表
     * @param targetLevel    目标压缩层级
     */
    @Async
    public void compressAsync(String conversationId,
                              List<MessageRecord> messages,
                              CompressionLevel targetLevel) {
        try {
            if (messages == null || messages.isEmpty()) {
                log.debug("对话压缩: 无消息可压缩, conversationId={}", conversationId);
                return;
            }

            // 筛选出可压缩消息（未 pinned、当前层级低于目标层级）
            List<MessageRecord> compressible = messages.stream()
                    .filter(m -> !m.isPinned())
                    .filter(m -> m.compressionLevel().level() < targetLevel.level())
                    .toList();

            if (compressible.isEmpty()) {
                log.debug("对话压缩: 无可压缩消息, conversationId={}", conversationId);
                return;
            }

            String prompt;
            if (targetLevel == CompressionLevel.SUMMARY) {
                prompt = promptRegistry.render("memory/compression-summary",
                        Map.of("conversation", formatMessages(compressible)));
            } else if (targetLevel == CompressionLevel.KEYPOINTS) {
                prompt = promptRegistry.render("memory/compression-keypoints",
                        Map.of("summary", formatMessages(compressible)));
            } else {
                log.warn("对话压缩: 不支持的目标层级 {}, conversationId={}", targetLevel, conversationId);
                return;
            }

            var response = llmRouter.call(LlmRequest.of(LlmScene.MEMORY_COMPRESSION, prompt));
            String compressed = response.content();
            Map<String, String> compressedTexts = new HashMap<>();
            for (var msg : compressible) {
                compressedTexts.put(msg.id(), compressed);
            }

            episodicMemory.compress(conversationId, targetLevel, compressedTexts);
            log.info("对话压缩完成: conversationId={}, level={}, originalMessages={}, compressedLength={}",
                    conversationId, targetLevel, compressible.size(), compressed.length());
        } catch (Exception e) {
            log.warn("对话压缩失败: conversationId={}, error={}", conversationId, e.getMessage());
        }
    }

    /**
     * 将消息列表格式化为适合 LLM 阅读的文本。
     */
    private String formatMessages(List<MessageRecord> messages) {
        StringBuilder sb = new StringBuilder();
        for (var msg : messages) {
            sb.append("[").append(msg.role()).append("] ")
                    .append(msg.effectiveContent())
                    .append("\n");
        }
        return sb.toString();
    }
}

