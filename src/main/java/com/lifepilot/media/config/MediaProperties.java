package com.lifepilot.media.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 媒体处理配置属性。
 *
 * <p>绑定 {@code lifepilot.media} 配置前缀。使用嵌套静态内部类组织
 * 图片、文档、音频、视频和 TTS 配置。</p>
 *
 * @author zsg
 * @since 2026-07-01
 */
@ConfigurationProperties(prefix = "lifepilot.media")
public class MediaProperties {

    /** 图片处理配置。 */
    private Image image = new Image();

    /** 文档处理配置。 */
    private Document document = new Document();

    /** 音频处理配置。 */
    private Audio audio = new Audio();

    /** 视频处理配置。 */
    private Video video = new Video();

    /** 校验配置。 */
    private Validation validation = new Validation();

    /** TTS 语音合成配置。 */
    private Tts tts = new Tts();

    /** 原生视频处理配置。 */
    private NativeVideo nativeVideo = new NativeVideo();

    /** 原生音频处理配置。 */
    private NativeAudio nativeAudio = new NativeAudio();

    public Validation getValidation() { return validation; }
    public void setValidation(Validation validation) { this.validation = validation; }

    public Image getImage() { return image; }
    public void setImage(Image image) { this.image = image; }

    public Document getDocument() { return document; }
    public void setDocument(Document document) { this.document = document; }

    public Audio getAudio() { return audio; }
    public void setAudio(Audio audio) { this.audio = audio; }

    public Video getVideo() { return video; }
    public void setVideo(Video video) { this.video = video; }

    public Tts getTts() { return tts; }
    public void setTts(Tts tts) { this.tts = tts; }

    public NativeVideo getNativeVideo() { return nativeVideo; }
    public void setNativeVideo(NativeVideo nativeVideo) { this.nativeVideo = nativeVideo; }

    public NativeAudio getNativeAudio() { return nativeAudio; }
    public void setNativeAudio(NativeAudio nativeAudio) { this.nativeAudio = nativeAudio; }

    /**
     * 图片处理配置。
     *
     * @author zsg
     * @since 2026-07-01
     */
    public static class Image {

        /** 图片最大文件大小（字节），默认 10MB。 */
        private long maxSizeBytes = 10_485_760L;

        /** 图片最长边最大像素，默认 2048。 */
        private int maxDimension = 2048;

        /** JPEG 压缩质量 [0.0, 1.0]，默认 0.85。 */
        private float jpegQuality = 0.85f;

        /** 单次请求最大图片数量，默认 5。 */
        private int maxPerRequest = 5;

        /** 并行压缩超时时间（秒），默认 30。 */
        private int processTimeoutSeconds = 30;

        /** 支持的图片格式列表。 */
        private List<String> supportedFormats = List.of("png", "jpeg", "gif", "webp", "bmp");

        public long getMaxSizeBytes() { return maxSizeBytes; }
        public void setMaxSizeBytes(long maxSizeBytes) { this.maxSizeBytes = maxSizeBytes; }

        public int getMaxDimension() { return maxDimension; }
        public void setMaxDimension(int maxDimension) { this.maxDimension = maxDimension; }

        public float getJpegQuality() { return jpegQuality; }
        public void setJpegQuality(float jpegQuality) { this.jpegQuality = jpegQuality; }

        public int getMaxPerRequest() { return maxPerRequest; }
        public void setMaxPerRequest(int maxPerRequest) { this.maxPerRequest = maxPerRequest; }

        public int getProcessTimeoutSeconds() { return processTimeoutSeconds; }
        public void setProcessTimeoutSeconds(int processTimeoutSeconds) { this.processTimeoutSeconds = processTimeoutSeconds; }

        public List<String> getSupportedFormats() { return supportedFormats; }
        public void setSupportedFormats(List<String> supportedFormats) { this.supportedFormats = supportedFormats; }
    }

    /**
     * 媒体校验配置。
     *
     * @author zsg
     * @since 2026-03-17
     */
    public static class Validation {

        /** 并行校验超时时间（秒），默认 5。 */
        private int parallelTimeoutSeconds = 5;

        public int getParallelTimeoutSeconds() { return parallelTimeoutSeconds; }
        public void setParallelTimeoutSeconds(int parallelTimeoutSeconds) { this.parallelTimeoutSeconds = parallelTimeoutSeconds; }
    }

    /**
     * 文档处理配置。
     *
     * @author zsg
     * @since 2026-07-01
     */
    public static class Document {

        /** 文档最大文件大小（字节），默认 50MB。 */
        private long maxSizeBytes = 52_428_800L;

        /** 支持的文档格式列表。 */
        private List<String> supportedFormats = List.of("pdf", "docx", "doc", "md", "txt");

        public long getMaxSizeBytes() { return maxSizeBytes; }
        public void setMaxSizeBytes(long maxSizeBytes) { this.maxSizeBytes = maxSizeBytes; }

        public List<String> getSupportedFormats() { return supportedFormats; }
        public void setSupportedFormats(List<String> supportedFormats) { this.supportedFormats = supportedFormats; }
    }

    /**
     * 音频处理配置。
     *
     * @author zsg
     * @since 2026-07-01
     */
    public static class Audio {

        /** 音频最大文件大小（字节），默认 25MB。 */
        private long maxSizeBytes = 26_214_400L;

        /** 音频最大时长（秒），默认 300（5 分钟）。 */
        private int maxDurationSeconds = 300;

        /** 支持的音频格式列表。 */
        private List<String> supportedFormats = List.of("wav", "mp3", "ogg", "m4a", "flac", "webm");

        /** Whisper CLI 路径，auto 表示自动检测 PATH。 */
        private String whisperCliPath = "auto";

        /** Whisper 模型名称（tiny/base/small/medium/large），默认 base。 */
        private String whisperModel = "base";

        /** 前端录音最大时长（秒），默认 120。 */
        private int maxRecordingSeconds = 120;

        public long getMaxSizeBytes() { return maxSizeBytes; }
        public void setMaxSizeBytes(long maxSizeBytes) { this.maxSizeBytes = maxSizeBytes; }

        public int getMaxDurationSeconds() { return maxDurationSeconds; }
        public void setMaxDurationSeconds(int maxDurationSeconds) { this.maxDurationSeconds = maxDurationSeconds; }

        public List<String> getSupportedFormats() { return supportedFormats; }
        public void setSupportedFormats(List<String> supportedFormats) { this.supportedFormats = supportedFormats; }

        public String getWhisperCliPath() { return whisperCliPath; }
        public void setWhisperCliPath(String whisperCliPath) { this.whisperCliPath = whisperCliPath; }

        public String getWhisperModel() { return whisperModel; }
        public void setWhisperModel(String whisperModel) { this.whisperModel = whisperModel; }

        public int getMaxRecordingSeconds() { return maxRecordingSeconds; }
        public void setMaxRecordingSeconds(int maxRecordingSeconds) { this.maxRecordingSeconds = maxRecordingSeconds; }
    }

    /**
     * 视频处理配置。
     *
     * @author zsg
     * @since 2026-07-01
     */
    public static class Video {

        /** 视频最大文件大小（字节），默认 500MB。 */
        private long maxSizeBytes = 524_288_000L;

        /** 视频最大时长（秒），默认 1800（30 分钟）。 */
        private int maxDurationSeconds = 1800;

        /** 最大关键帧数量，默认 120。 */
        private int maxKeyFrames = 120;

        /** 支持的视频格式列表。 */
        private List<String> supportedFormats = List.of("mp4", "avi", "mov", "mkv", "webm", "flv");

        public long getMaxSizeBytes() { return maxSizeBytes; }
        public void setMaxSizeBytes(long maxSizeBytes) { this.maxSizeBytes = maxSizeBytes; }

        public int getMaxDurationSeconds() { return maxDurationSeconds; }
        public void setMaxDurationSeconds(int maxDurationSeconds) { this.maxDurationSeconds = maxDurationSeconds; }

        public int getMaxKeyFrames() { return maxKeyFrames; }
        public void setMaxKeyFrames(int maxKeyFrames) { this.maxKeyFrames = maxKeyFrames; }

        public List<String> getSupportedFormats() { return supportedFormats; }
        public void setSupportedFormats(List<String> supportedFormats) { this.supportedFormats = supportedFormats; }
    }

    /**
     * TTS 语音合成配置。
     *
     * @author zsg
     * @since 2026-07-01
     */
    public static class Tts {

        /** 是否启用 TTS 端点，默认 true。 */
        private boolean enabled = true;

        /** 语音风格，默认 alloy。 */
        private String voice = "alloy";

        /** 语速倍率，默认 1.0。 */
        private double speed = 1.0;

        /** 输出音频格式，默认 mp3。 */
        private String outputFormat = "mp3";

        /** 最大文本长度（字符），默认 4096。 */
        private int maxTextLength = 4096;

        public String getVoice() { return voice; }
        public void setVoice(String voice) { this.voice = voice; }

        public double getSpeed() { return speed; }
        public void setSpeed(double speed) { this.speed = speed; }

        public String getOutputFormat() { return outputFormat; }
        public void setOutputFormat(String outputFormat) { this.outputFormat = outputFormat; }

        public int getMaxTextLength() { return maxTextLength; }
        public void setMaxTextLength(int maxTextLength) { this.maxTextLength = maxTextLength; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /**
     * 原生视频处理配置。
     *
     * <p>配置 Gemini File API 集成参数，包括文件上传超时、状态轮询策略和 API 凭证。</p>
     *
     * @author zsg
     * @since 2026-03-18
     */
    public static class NativeVideo {

        /** 是否启用原生视频路由，默认 false。 */
        private boolean enabled = false;

        /** 文件上传超时（秒），默认 300。 */
        private int uploadTimeoutSeconds = 300;

        /** 状态轮询超时（秒），默认 120。 */
        private int pollTimeoutSeconds = 120;

        /** 轮询间隔（毫秒），默认 2000。 */
        private int pollIntervalMs = 2000;

        /** 最大文件大小（字节），默认 2GB。 */
        private long maxFileSizeBytes = 2_147_483_648L;

        /** Gemini API Key。 */
        private String geminiApiKey = "";

        /** Gemini API 基础 URL。 */
        private String geminiApiBaseUrl = "https://generativelanguage.googleapis.com";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getUploadTimeoutSeconds() { return uploadTimeoutSeconds; }
        public void setUploadTimeoutSeconds(int uploadTimeoutSeconds) { this.uploadTimeoutSeconds = uploadTimeoutSeconds; }

        public int getPollTimeoutSeconds() { return pollTimeoutSeconds; }
        public void setPollTimeoutSeconds(int pollTimeoutSeconds) { this.pollTimeoutSeconds = pollTimeoutSeconds; }

        public int getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(int pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

        public long getMaxFileSizeBytes() { return maxFileSizeBytes; }
        public void setMaxFileSizeBytes(long maxFileSizeBytes) { this.maxFileSizeBytes = maxFileSizeBytes; }

        public String getGeminiApiKey() { return geminiApiKey; }
        public void setGeminiApiKey(String geminiApiKey) { this.geminiApiKey = geminiApiKey; }

        public String getGeminiApiBaseUrl() { return geminiApiBaseUrl; }
        public void setGeminiApiBaseUrl(String geminiApiBaseUrl) { this.geminiApiBaseUrl = geminiApiBaseUrl; }
    }

    /**
     * 原生音频处理配置。
     *
     * <p>配置原生音频路由参数。启用后，音频附件将直接发送给支持 NATIVE_AUDIO 能力的 Provider，
     * 跳过 STT 转录步骤。</p>
     *
     * @author zsg
     * @since 2026-03-18
     */
    public static class NativeAudio {

        /** 是否启用原生音频路由，默认 true。 */
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
