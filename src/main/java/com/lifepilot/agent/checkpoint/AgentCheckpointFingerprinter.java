package com.lifepilot.agent.checkpoint;

import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.llm.multimodal.MediaContent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 同任务指纹计算器。
 *
 * @author zsg
 * @since 2026-03-21
 */
public final class AgentCheckpointFingerprinter {

    private static final Pattern RESUME_USER_INPUT_PATTERN = Pattern.compile(
            "<resume_user_input>\\s*.*?\\s*</resume_user_input>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RESTART_ORIGINAL_INPUT_PATTERN = Pattern.compile(
            "<restart_original_user_input>\\s*(.*?)\\s*</restart_original_user_input>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RESTART_INSTRUCTION_PATTERN = Pattern.compile(
            "<restart_instruction>\\s*.*?\\s*</restart_instruction>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TASK_RECOVERY_CHECKPOINT_PATTERN = Pattern.compile(
            "<task_recovery_checkpoint>\\s*.*?\\s*</task_recovery_checkpoint>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );

    private AgentCheckpointFingerprinter() {}

    /**
     * 基于规范化文本和附件标识生成同任务指纹。
     */
    public static String fingerprint(AgentRequest request) {
        String normalizedMessage = normalizeMessage(request.message());
        String mediaSignature = buildMediaSignature(request.mediaContents());
        String rawFingerprint = normalizedMessage + "\n--media--\n" + mediaSignature;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(rawFingerprint.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("生成任务指纹失败", e);
        }
    }

    static String normalizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String checkpointNeutralMessage = RESUME_USER_INPUT_PATTERN.matcher(message).replaceAll("");
        checkpointNeutralMessage = RESTART_ORIGINAL_INPUT_PATTERN
                .matcher(checkpointNeutralMessage)
                .replaceAll("$1");
        checkpointNeutralMessage = RESTART_INSTRUCTION_PATTERN
                .matcher(checkpointNeutralMessage)
                .replaceAll("");
        checkpointNeutralMessage = TASK_RECOVERY_CHECKPOINT_PATTERN
                .matcher(checkpointNeutralMessage)
                .replaceAll("");
        String normalized = Normalizer.normalize(checkpointNeutralMessage, Normalizer.Form.NFKC);
        return normalized.trim().replaceAll("\\s+", " ");
    }

    private static String buildMediaSignature(List<MediaContent> mediaContents) {
        if (mediaContents == null || mediaContents.isEmpty()) {
            return "";
        }
        return mediaContents.stream()
                .map(AgentCheckpointFingerprinter::describeMedia)
                .sorted()
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private static String describeMedia(MediaContent mediaContent) {
        String fileName = mediaContent.fileName() != null ? mediaContent.fileName() : "";
        return mediaContent.id() + "|" + mediaContent.mimeType() + "|" + fileName + "|" + mediaContent.sizeBytes();
    }
}
