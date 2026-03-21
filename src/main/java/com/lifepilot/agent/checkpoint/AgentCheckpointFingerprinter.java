package com.lifepilot.agent.checkpoint;

import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.llm.multimodal.MediaContent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;

/**
 * 同任务指纹计算器。
 *
 * @author zsg
 * @since 2026-03-21
 */
public final class AgentCheckpointFingerprinter {

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
        String normalized = Normalizer.normalize(message, Normalizer.Form.NFKC);
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
