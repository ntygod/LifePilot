package com.lifepilot.memory.compression;

import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.memory.episodic.CompressionLevel;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.episodic.MessageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

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

    private static final String SUMMARY_PROMPT = """
            请将以下对话内容压缩为简洁的摘要，保留：
            1. 关键决策和结论
            2. 重要的事实信息（人名、日期、数字）
            3. 用户的明确意图和偏好
            4. 工具执行的关键结果

            丢弃：
            1. 寒暄和礼貌用语
            2. 重复的确认信息
            3. 中间推理过程（只保留结论）

            对话内容：
            %s
            """;

    private static final String KEYPOINTS_PROMPT = """
            请将以下摘要进一步浓缩为关键要点列表（每个要点一行，以 "- " 开头）：
            仅保留最核心的决策、结论和事实。

            摘要内容：
            %s
            """;

    private final LlmRouter llmRouter;
    private final EpisodicMemory episodicMemory;

    public CompressionService(LlmRouter llmRouter, EpisodicMemory episodicMemory) {
        this.llmRouter = llmRouter;
        this.episodicMemory = episodicMemory;
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
                prompt = SUMMARY_PROMPT.formatted(formatMessages(compressible));
            } else if (targetLevel == CompressionLevel.KEYPOINTS) {
                prompt = KEYPOINTS_PROMPT.formatted(formatMessages(compressible));
            } else {
                log.warn("对话压缩: 不支持的目标层级 {}, conversationId={}", targetLevel, conversationId);
                return;
            }

            var response = llmRouter.call(LlmScene.MEMORY_COMPRESSION, prompt, null);
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

