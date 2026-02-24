package com.lifepilot.knowledge.parser;

/**
 * 文档解析异常。
 *
 * <p>在文档解析过程中发生错误时抛出此异常，包含失败阶段（{@link Phase}）和文件路径，
 * 便于定位问题发生的具体环节。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class DocumentParseException extends RuntimeException {

    /**
     * 文档解析阶段枚举。
     */
    public enum Phase {
        /** 文件读取阶段 */
        FILE_READ,
        /** 格式解码阶段 */
        FORMAT_DECODE,
        /** 文本提取阶段 */
        TEXT_EXTRACTION,
        /** 元数据提取阶段 */
        METADATA_EXTRACTION
    }

    private final Phase phase;
    private final String filePath;

    /**
     * 构造文档解析异常。
     *
     * @param message  异常描述信息
     * @param phase    失败阶段
     * @param filePath 文件路径
     */
    public DocumentParseException(String message, Phase phase, String filePath) {
        super(message);
        this.phase = phase;
        this.filePath = filePath;
    }

    /**
     * 构造文档解析异常（带原因链）。
     *
     * @param message  异常描述信息
     * @param phase    失败阶段
     * @param filePath 文件路径
     * @param cause    原始异常
     */
    public DocumentParseException(String message, Phase phase, String filePath, Throwable cause) {
        super(message, cause);
        this.phase = phase;
        this.filePath = filePath;
    }

    /**
     * 获取失败阶段。
     *
     * @return 解析失败的阶段
     */
    public Phase getPhase() {
        return phase;
    }

    /**
     * 获取文件路径。
     *
     * @return 解析失败的文件路径
     */
    public String getFilePath() {
        return filePath;
    }
}
