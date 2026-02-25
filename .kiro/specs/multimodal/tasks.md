# Implementation Plan: 多模态能力（Multimodal）

## Overview

在已完成的 LLM Router 基础上，扩展 Provider 适配层支持多模态调用，新增媒体处理层（MIME 检测、校验、图片预处理、文档提取、音频转录、语音合成、视频处理），并实现 MultimodalRouter 作为多模态调用入口。按自底向上顺序：先添加 Maven 依赖和配置，再实现数据模型，然后实现媒体处理组件，接着扩展 ProviderAdapter 和音频处理，再实现视频处理组件，最后实现 MultimodalRouter 路由和自动配置。

## Tasks

- [x] 1. Maven 依赖与配置属性
  - [x] 1.1 添加 Maven 依赖
    - 在 `pom.xml` 中新增 `org.apache.tika:tika-core` 依赖
    - 在 `pom.xml` 中新增 `net.coobird:thumbnailator` 依赖
    - 在 `pom.xml` 中新增 `org.bytedeco:javacv-platform` 依赖（视频帧提取/音轨分离，内嵌 FFmpeg native library）
    - 仅引入 tika-core，不引入 tika-parsers
    - _Requirements: 13.1, 13.2, 13.3, 13.4_

  - [x] 1.2 实现 MediaProperties 配置属性类
    - 创建 `com.lifepilot.media.config.MediaProperties`，使用 `@ConfigurationProperties("lifepilot.media")` 绑定
    - 实现 `Image` 嵌套静态内部类（maxSizeBytes、maxDimension、jpegQuality、maxPerRequest、supportedFormats）
    - 实现 `Document` 嵌套静态内部类（maxSizeBytes、supportedFormats）
    - 实现 `Audio` 嵌套静态内部类（maxSizeBytes、maxDurationSeconds、supportedFormats、whisperCliPath、whisperModel）
    - 实现 `Video` 嵌套静态内部类（maxSizeBytes、maxDurationSeconds、maxKeyFrames、supportedFormats）
    - 实现 `Tts` 嵌套静态内部类（voice、speed、outputFormat、maxTextLength）
    - 在 `application.yml` 中显式声明所有配置项及默认值（含 audio、video 和 tts 配置段）
    - _Requirements: 12.1, 12.2, 12.3_

  - [x] 1.3 扩展 ProviderCapability 枚举
    - 在 `com.lifepilot.llm.config.ProviderCapability` 枚举中新增 `TTS` 和 `STT` 值
    - 现有枚举值保持不变
    - _Requirements: 11.1, 11.2, 11.3_

- [x] 2. 数据模型与异常类
  - [x] 2.1 实现 MediaContent record
    - 创建 `com.lifepilot.llm.multimodal.MediaContent`
    - 包含字段：id、mimeType、data、fileName（可选）、sizeBytes、metadata
    - 紧凑构造器：id/mimeType/data 非空校验，metadata 防御性拷贝（`Map.copyOf`），null metadata 初始化为 `Map.of()`
    - _Requirements: 2.1, 2.2, 2.3_

  - [ ]* 2.2 写属性测试：MediaContent 构造不变量
    - **Property 1: MediaContent 构造不变量**
    - metadata 不可变且防御性拷贝，null 参数抛出 NullPointerException
    - **Validates: Requirements 2.2, 2.3**

  - [x] 2.3 实现 MultimodalRequest record
    - 创建 `com.lifepilot.llm.multimodal.MultimodalRequest`
    - 包含字段：scene、text、mediaList、outputSchema（可选）
    - 紧凑构造器：scene/text 非空校验，mediaList 防御性拷贝（`List.copyOf`），null mediaList 初始化为空列表
    - 实现 `hasImages()` 便捷方法
    - 实现 `hasVideos()` 便捷方法
    - _Requirements: 3.1, 3.2, 3.3_

  - [ ]* 2.4 写属性测试：MultimodalRequest 构造不变量
    - **Property 2: MultimodalRequest 构造不变量**
    - mediaList 不可变且 null 安全，null scene/text 抛出 NullPointerException
    - **Validates: Requirements 3.2, 3.3**

  - [x] 2.5 实现异常类
    - 创建 `com.lifepilot.media.MediaValidationException`（含 reason 字段）
    - 创建 `com.lifepilot.media.DocumentExtractionException`（含 cause 构造函数）
    - 创建 `com.lifepilot.media.audio.AudioTranscriptionException`（含描述性消息和可选 cause）
    - 创建 `com.lifepilot.media.video.VideoProcessException`（含描述性消息和可选 cause）
    - _Requirements: 4.5, 7.5, 9.5, 14.6_

  - [x] 2.6 实现 VideoProcessResult record
    - 创建 `com.lifepilot.media.video.VideoProcessResult`
    - 包含字段：keyFrames（List<MediaContent>）、transcript（可选）、durationSeconds、frameCount
    - 紧凑构造器：keyFrames 防御性拷贝（`List.copyOf`）
    - _Requirements: 15.1, 15.2, 15.3_

  - [ ]* 2.7 写属性测试：VideoProcessResult 构造不变量
    - **Property 15: VideoProcessResult 构造不变量**
    - keyFrames 不可变且防御性拷贝，frameCount 等于 keyFrames 大小
    - **Validates: Requirements 15.2, 15.3**

- [x] 3. Checkpoint - 确保数据模型和配置编译通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. MIME 类型检测
  - [x] 4.1 实现 MediaType
    - 创建 `com.lifepilot.media.MediaType`
    - 实现 `detect(byte[], @Nullable String)` 方法，使用 Apache Tika 基于文件内容检测 MIME 类型
    - 无法识别时返回 `application/octet-stream`
    - 实现 `isImage(String)` 方法（以 `image/` 开头）
    - 实现 `isDocument(String)` 方法（匹配文档 MIME 类型集合）
    - 实现 `isAudio(String)` 方法（以 `audio/` 开头）
    - 实现 `isVideo(String)` 方法（以 `video/` 开头）
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7_

  - [ ]* 4.2 写属性测试：MIME 类型分类正确性
    - **Property 5: MIME 类型分类正确性**
    - isImage 当且仅当以 `image/` 开头返回 true，isDocument 匹配文档 MIME 集合，isAudio 当且仅当以 `audio/` 开头返回 true，isVideo 当且仅当以 `video/` 开头返回 true，四者不同时为 true
    - **Validates: Requirements 5.4, 5.5, 5.6, 5.7**

  - [ ]* 4.3 写单元测试：MediaType
    - 测试 PNG/JPEG/GIF 魔数字节检测、扩展名伪造场景、无法识别字节返回 octet-stream、音频 MIME 分类、视频 MIME 分类
    - _Requirements: 5.1, 5.2, 5.3, 5.6, 5.7_

- [x] 5. 媒体校验
  - [x] 5.1 实现 MediaValidator
    - 创建 `com.lifepilot.media.MediaValidator`
    - 实现 `validate(MediaContent)` 方法：校验数据非空、文件大小（图片/文档/音频/视频各自限制）、MIME 类型
    - 实现 `validateAll(List<MediaContent>)` 方法：校验图片数量限制 + 逐项校验
    - 校验失败抛出 `MediaValidationException`，携带具体失败原因
    - 音频文件大小校验使用 `audio.maxSizeBytes` 配置
    - 视频文件大小校验使用 `video.maxSizeBytes` 配置
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_

  - [ ]* 5.2 写属性测试：MediaValidator 单项校验
    - **Property 3: MediaValidator 单项校验**
    - 大小超限或 MIME 不支持时抛出异常，合法输入正常通过（含音频类型校验）
    - **Validates: Requirements 4.1, 4.2**

  - [ ]* 5.3 写属性测试：MediaValidator 图片数量限制
    - **Property 4: MediaValidator 图片数量限制**
    - 图片数量超过 maxPerRequest 时抛出异常，未超过时正常通过
    - **Validates: Requirements 4.3**

- [x] 6. 图片预处理
  - [x] 6.1 实现 MediaProcessor
    - 创建 `com.lifepilot.media.MediaProcessor`
    - 实现 `process(MediaContent)` 方法：格式检测 → 尺寸压缩 → 质量压缩 → 格式转换
    - 使用 Thumbnailator 执行图片缩放和压缩
    - 最长边超过 maxDimension 时等比缩放
    - JPEG 格式按 jpegQuality 质量压缩
    - BMP/WebP 格式转换为 PNG 或 JPEG
    - 尺寸和大小均在限制内时跳过压缩，直接返回原始 MediaContent
    - 实现 `processAll(List<MediaContent>)` 批量处理方法
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6_

  - [ ]* 6.2 写属性测试：MediaProcessor 尺寸不变量与元数据保留
    - **Property 6: MediaProcessor 尺寸不变量与元数据保留**
    - 处理后最长边 ≤ maxDimension，fileName 和 metadata 与输入一致
    - **Validates: Requirements 6.1, 6.5, 6.6**

  - [ ]* 6.3 写属性测试：MediaProcessor 格式转换
    - **Property 7: MediaProcessor 格式转换**
    - BMP/WebP 输入处理后 mimeType 为 image/png 或 image/jpeg
    - **Validates: Requirements 6.3**

  - [ ]* 6.4 写单元测试：MediaProcessor
    - 测试小图跳过压缩、大图缩放、BMP→PNG 转换、JPEG 质量压缩
    - _Requirements: 6.1, 6.2, 6.3, 6.6_

- [x] 7. Checkpoint - 确保媒体处理组件测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. 文档内容提取
  - [x] 8.1 实现 DocumentExtractor
    - 创建 `com.lifepilot.media.DocumentExtractor`
    - 构造函数注入 `List<DocumentParser>`（Spring 自动收集）
    - 实现 `extract(byte[], String, @Nullable String)` 方法
    - 将 byte[] 写入临时文件，委托匹配的 DocumentParser 解析，返回 ParseResult.text()
    - MIME 类型到解析器映射：application/pdf → PdfParser，Word MIME → WordParser，text/markdown → MarkdownParser，text/plain → PlainTextParser
    - 无匹配解析器时抛出 DocumentExtractionException
    - 解析失败时包装 DocumentParseException 为 DocumentExtractionException
    - try-finally 确保临时文件删除
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_

  - [ ]* 8.2 写属性测试：DocumentExtractor MIME 路由
    - **Property 8: DocumentExtractor MIME 路由**
    - 受支持 MIME 类型委托给对应 DocumentParser，不支持类型抛出异常
    - **Validates: Requirements 7.2, 7.3**

  - [ ]* 8.3 写单元测试：DocumentExtractor
    - 测试各格式提取、不支持格式异常、解析器异常包装
    - _Requirements: 7.1, 7.2, 7.3, 7.5_

- [x] 9. ProviderAdapter 多模态扩展
  - [x] 9.1 扩展 ProviderAdapter sealed interface
    - 在 `ProviderAdapter` 中新增 `callWithMedia(String, List<MediaContent>, Duration)` 方法
    - 在 `ProviderAdapter` 中新增 `streamWithMedia(String, List<MediaContent>)` 方法
    - 现有方法签名保持不变
    - _Requirements: 1.1, 1.4_

  - [x] 9.2 实现 SpringAiProviderAdapter.callWithMedia()
    - 检查 `config.hasCapability(ProviderCapability.VISION)`，不支持则抛出 UnsupportedOperationException
    - 将 MediaContent 列表转换为 Spring AI Media 对象列表
    - 通过 `UserMessage.builder().text(...).media(...)` 构建多模态消息
    - 调用 `chatModel.call(new Prompt(userMessage))` 获取响应
    - _Requirements: 1.2, 1.3_

  - [x] 9.3 实现 SpringAiProviderAdapter.streamWithMedia()
    - 与 callWithMedia 相同的 VISION 能力检查和消息构建
    - 调用 `chatModel.stream(new Prompt(userMessage))` 返回 Flux<String>
    - _Requirements: 1.1_

  - [ ]* 9.4 写属性测试：非 VISION Provider 拒绝多模态调用
    - **Property 12: 非 VISION Provider 拒绝多模态调用**
    - 不含 VISION 能力的 Provider 调用 callWithMedia 抛出 UnsupportedOperationException
    - **Validates: Requirements 1.3**

  - [ ]* 9.5 写单元测试：SpringAiProviderAdapter callWithMedia
    - 测试 VISION 能力检查、Media 对象构建、正常调用流程
    - _Requirements: 1.1, 1.2, 1.3_

- [x] 10. Checkpoint - 确保 ProviderAdapter 扩展测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 11. 音频处理（STT/TTS）
  - [x] 11.1 实现 WhisperCliTranscriber
    - 创建 `com.lifepilot.media.audio.WhisperCliTranscriber`
    - 构造函数注入 whisperCliPath 和 whisperModel（从 MediaProperties.Audio 获取）
    - 实现 `isAvailable()` 方法：通过 `which whisper-cli` 或 `which whisper` 检测 CLI 是否在 PATH 中
    - 实现 `transcribe(byte[], String)` 方法：将音频写入临时文件，通过 ProcessBuilder 调用 CLI，解析输出文本
    - 超时控制：默认 60 秒，超时则 `process.destroyForcibly()`
    - try-finally 确保临时文件删除
    - _Requirements: 9.3, 9.4_

  - [x] 11.2 实现 AudioTranscriber
    - 创建 `com.lifepilot.media.audio.AudioTranscriber`
    - 构造函数注入 `@Nullable WhisperCliTranscriber`、`@Nullable TranscriptionModel`、`MediaProperties`
    - 实现 `transcribe(byte[], String)` 方法，采用"本地优先"级联策略：
      1. 检查 `whisperCli != null && whisperCli.isAvailable()` → 调用本地 Whisper CLI
      2. 本地失败或不可用 → 检查 `transcriptionModel != null` → 调用 Spring AI TranscriptionModel
      3. 全部不可用 → 抛出 `AudioTranscriptionException`
    - _Requirements: 9.1, 9.2, 9.5, 9.6, 9.7_

  - [x] 11.3 实现 SpeechSynthesizer
    - 创建 `com.lifepilot.media.audio.SpeechSynthesizer`
    - 构造函数注入 `TextToSpeechModel` 和 `MediaProperties`
    - 实现 `synthesize(String)` 同步合成方法，返回 byte[] 音频数据
    - 实现 `stream(String)` 流式合成方法，返回 Flux<byte[]>
    - 文本长度超过 `maxTextLength` 时截断并添加"…（内容已截断）"
    - 通过 `TextToSpeechPrompt` 传递语音风格、语速等配置
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5_

  - [ ]* 11.4 写属性测试：AudioTranscriber 级联降级
    - **Property 13: AudioTranscriber 级联降级**
    - 本地 Whisper CLI 可用时优先使用本地 CLI，不可用或失败时回退到云端 TranscriptionModel，两者均不可用时抛出 AudioTranscriptionException
    - **Validates: Requirements 9.2, 9.5**

  - [ ]* 11.5 写属性测试：SpeechSynthesizer 文本截断
    - **Property 14: SpeechSynthesizer 文本截断**
    - 文本长度超过 maxTextLength 时截断，未超过时完整合成
    - **Validates: Requirements 10.4**

  - [ ]* 11.6 写单元测试：WhisperCliTranscriber
    - 测试 CLI 检测（可用/不可用）、进程调用、超时处理、临时文件清理
    - _Requirements: 9.3, 9.4_

  - [ ]* 11.7 写单元测试：AudioTranscriber
    - 测试级联降级（本地优先 → 云端回退 → 全部不可用异常）、音频大小校验
    - _Requirements: 9.1, 9.2, 9.5, 9.6_

  - [ ]* 11.8 写单元测试：SpeechSynthesizer
    - 测试同步合成、流式合成、文本截断、TTS 不可用异常
    - _Requirements: 10.1, 10.2, 10.4, 10.5_

- [x] 12. Checkpoint - 确保音频处理组件测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 13. 视频处理（VideoProcessor / KeyFrameExtractor / AudioTrackExtractor）
  - [x] 13.1 实现 KeyFrameExtractor
    - 创建 `com.lifepilot.media.video.KeyFrameExtractor`
    - 构造函数注入 `MediaProcessor` 和 `MediaProperties`
    - 实现 `extract(Path)` 方法：使用 JavaCV `FFmpegFrameGrabber` 按均匀采样策略提取关键帧
    - 采样策略根据视频时长自适应（≤30s 每 2s 1 帧，30s~5min 每 5s 1 帧，5~30min 每 15s 1 帧，>30min 每 30s 1 帧）
    - 限制最大关键帧数量不超过 `video.maxKeyFrames` 配置值（默认 120）
    - 提取的帧图片经 `MediaProcessor.process()` 预处理后封装为 `MediaContent`
    - 实现 `getDurationSeconds(Path)` 方法
    - _Requirements: 16.1, 16.2, 16.3_

  - [x] 13.2 实现 AudioTrackExtractor
    - 创建 `com.lifepilot.media.video.AudioTrackExtractor`
    - 实现 `extract(Path)` 方法：使用 JavaCV 从视频中分离音频轨道，返回 `Optional<byte[]>`
    - 无音轨时返回 `Optional.empty()`
    - try-finally 确保临时文件清理
    - _Requirements: 16.4, 16.5, 16.6_

  - [x] 13.3 实现 VideoProcessor
    - 创建 `com.lifepilot.media.video.VideoProcessor`
    - 构造函数注入 `KeyFrameExtractor`、`AudioTrackExtractor`、`AudioTranscriber`、`MediaProperties`
    - 实现 `process(byte[], String)` 方法：
      1. 校验视频大小和时长
      2. 写入临时文件
      3. `KeyFrameExtractor.extract()` → 关键帧列表
      4. `AudioTrackExtractor.extract()` → 音轨数据
      5. 若有音轨 → `AudioTranscriber.transcribe()` → 转录文本
      6. 组装 `VideoProcessResult`
    - try-finally 确保临时文件删除
    - 视频解码失败时抛出 `VideoProcessException`
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 14.6_

  - [ ]* 13.4 写属性测试：VideoProcessor 关键帧数量限制
    - **Property 16: VideoProcessor 关键帧数量限制**
    - 处理后 frameCount ≤ video.maxKeyFrames
    - **Validates: Requirements 14.2, 16.3**

  - [ ]* 13.5 写单元测试：VideoProcessor / KeyFrameExtractor / AudioTrackExtractor
    - 测试视频处理流程、静音视频（无音轨）、大小/时长超限校验、关键帧采样策略
    - Mock JavaCV FFmpegFrameGrabber
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 14.6, 16.1, 16.4, 16.5_

- [x] 14. Checkpoint - 确保视频处理组件测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 15. MultimodalRouter 路由实现
  - [x] 15.1 实现 MultimodalRouter 同步调用
    - 创建 `com.lifepilot.llm.multimodal.MultimodalRouter`
    - 构造函数注入 ProviderRegistry、CircuitBreakerManager、MediaProcessor、MediaValidator、@Nullable VideoProcessor、LlmRouter
    - 实现 `call(MultimodalRequest)` 方法
    - 有视频请求：VideoProcessor.process() → 关键帧加入 mediaList，转录文本追加到 text
    - 无图片请求委托 `llmRouter.call(scene, text, outputSchema)`
    - 有图片请求：validateAll → processAll → VISION 能力过滤 → 熔断器过滤 → 优先级排序 → 故障转移循环调用 callWithMedia
    - 所有 VISION Provider 不可用时抛出 LlmUnavailableException
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.8_

  - [x] 15.2 实现 MultimodalRouter 流式调用
    - 实现 `stream(MultimodalRequest)` 方法
    - 路由策略与同步调用一致
    - 无图片委托 `llmRouter.stream(scene, text)`
    - 有图片调用 `adapter.streamWithMedia()`
    - _Requirements: 8.7_

  - [ ]* 15.3 写属性测试：MultimodalRouter VISION 候选选择
    - **Property 9: MultimodalRouter VISION 候选选择**
    - 仅尝试具备 VISION 能力且熔断器非 OPEN 的 Provider，按 priority 升序
    - **Validates: Requirements 8.1, 8.2**

  - [ ]* 15.4 写属性测试：MultimodalRouter 故障转移与穷尽
    - **Property 10: MultimodalRouter 故障转移与穷尽**
    - 首选 Provider 失败后自动尝试下一个，全部失败抛出 LlmUnavailableException
    - **Validates: Requirements 8.3, 8.4**

  - [ ]* 15.5 写属性测试：MultimodalRouter 纯文本委托
    - **Property 11: MultimodalRouter 纯文本委托**
    - 不含图片的请求委托给 LlmRouter.call，不执行 VISION 过滤
    - **Validates: Requirements 8.5**

  - [ ]* 15.6 写属性测试：MultimodalRouter 视频预处理集成
    - **Property 17: MultimodalRouter 视频预处理集成**
    - 包含视频的请求先经 VideoProcessor 处理，关键帧加入 mediaList，转录文本追加到 text
    - **Validates: Requirements 8.8**

  - [ ]* 15.7 写单元测试：MultimodalRouter
    - 测试纯文本委托、VISION 过滤、故障转移、全部失败异常、流式调用、视频预处理集成
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8_

- [x] 16. 自动配置与集成测试
  - [x] 16.1 实现 MediaAutoConfiguration
    - 创建 `com.lifepilot.media.config.MediaAutoConfiguration`
    - 注册 MediaType、MediaValidator、MediaProcessor、DocumentExtractor、MultimodalRouter 为 Spring Bean
    - 注册 WhisperCliTranscriber Bean（从 MediaProperties.Audio 读取配置）
    - 注册 AudioTranscriber Bean（注入 @Nullable WhisperCliTranscriber 和 @Nullable TranscriptionModel）
    - 注册 SpeechSynthesizer Bean（`@ConditionalOnBean(TextToSpeechModel.class)`，仅在 TTS Provider 可用时注册）
    - 注册 KeyFrameExtractor Bean（注入 MediaProcessor 和 MediaProperties）
    - 注册 AudioTrackExtractor Bean
    - 注册 VideoProcessor Bean（注入 KeyFrameExtractor、AudioTrackExtractor、AudioTranscriber、MediaProperties）
    - MultimodalRouter Bean 注入 @Nullable VideoProcessor
    - 所有 Bean 使用 `@ConditionalOnMissingBean`
    - _Requirements: 12.4_

  - [ ]* 16.2 写集成测试：MediaAutoConfiguration Bean 注册
    - 验证 Spring Context 加载，所有 Bean 注册成功（含音频和视频相关 Bean）
    - 验证 Bean 注入链完整
    - _Requirements: 12.4_

  - [ ]* 16.3 写集成测试：MultimodalRouter 与 LlmRouter 协作
    - 验证多模态路由与现有 LLM 路由的端到端协作
    - Mock ChatModel，验证纯文本委托和多模态调用两条路径
    - _Requirements: 8.1, 8.5_

- [x] 17. Final checkpoint - 确保所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Property tests use jqwik (already in project dependencies)
- 文档提取复用知识库模块已有的 DocumentParser 实现，通过临时文件桥接 byte[] → Path 接口差异
- 音频处理采用"转录前置"模式（借鉴 OpenClaw）：STT 在 AgentLoop 前完成，TTS 是可选后处理
- 视频处理采用"分治"策略（借鉴 OmAgent）：关键帧提取 + 音轨 STT，复用图片和音频管线
- 视频处理基于 JavaCV（FFmpeg Java 绑定），内嵌各平台 native library，无需用户安装 FFmpeg
- 遵循编码规范：中文注释/Javadoc/测试方法名，@author zsg，@since 2026-07-01
