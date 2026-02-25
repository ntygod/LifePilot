# 需求文档 — 多模态能力

## 简介

LifePilot 当前仅支持纯文本交互，`LlmRouter` 的所有调用方法（`call`、`callEntity`、`stream`）均以 `String prompt` 为输入。本模块扩展 LLM 路由层以支持图片理解、视频理解、文档内容提取、语音转文字（STT）和文字转语音（TTS），使 Agent 能够处理用户发送的图片、视频、文档和语音消息，并以语音形式回复。

视频理解采用"分治"策略（借鉴 OmAgent 框架）：将视频拆分为关键帧序列 + 音轨，关键帧复用图片预处理管线，音轨复用 STT 管线，最终合并为结构化上下文传给 LLM。视频处理基于 JavaCV（FFmpeg Java 绑定），内嵌各平台 native library，无需用户安装 FFmpeg。

本模块属于 Phase 4 的第一个模块（模块 14），依赖 LLM Router（模块 1，已完成）。

参考文档：
- 架构设计：#[[file:docs/architecture/multimodal.md]]
- 特性设计：#[[file:docs/features/multimodal.md]]
- LLM Router 架构：#[[file:docs/architecture/llm-router.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]

## 术语表

- **MultimodalRouter**：多模态路由入口，处理包含图片的 LLM 调用请求，内部组合 ProviderRegistry 和 CircuitBreakerManager 的能力
- **MediaProcessor**：图片预处理器，负责图片格式检测、尺寸压缩、质量压缩和格式转换
- **MediaValidator**：媒体校验器，在处理前校验媒体内容的文件大小、MIME 类型、数量等合法性
- **DocumentExtractor**：文档内容提取器，组合知识库模块已有的 DocumentParser 实现，提供统一的文本提取接口
- **AudioTranscriber**：语音转文字转录器，采用"本地优先"级联策略（本地 Whisper CLI → 云端 STT Provider）
- **SpeechSynthesizer**：文字转语音合成器，基于 Spring AI TextToSpeechModel 接口
- **WhisperCliTranscriber**：本地 Whisper CLI 适配器，通过 ProcessBuilder 调用 whisper.cpp CLI
- **MediaContent**：媒体内容载体 record，封装图片、文档或音频的二进制数据及元信息
- **MultimodalRequest**：多模态请求 record，包含文本提示词和零或多个 MediaContent 附件
- **ProviderAdapter**：LLM Provider 适配器密封接口，当前仅支持 String prompt 输入
- **ProviderRegistry**：Provider 注册表，管理所有已注册 Provider 的配置和适配器实例
- **ProviderCapability**：Provider 能力枚举，已包含 VISION 枚举值，本模块新增 TTS 和 STT
- **LlmResponse**：LLM 调用统一响应 record
- **MediaProperties**：媒体处理配置属性类，通过 `@ConfigurationProperties("lifepilot.media")` 绑定
- **Apache Tika**：Apache 基金会的内容检测库，本模块仅使用 tika-core 进行 MIME 类型检测
- **Thumbnailator**：纯 Java 图片处理库，用于图片压缩和缩放
- **whisper.cpp**：OpenAI Whisper 模型的 C++ 移植版，提供本地 STT 能力
- **VideoProcessor**：视频预处理器，将视频拆分为关键帧序列和音轨，分别交给图片处理和音频转录管线
- **VideoProcessResult**：视频处理结果 record，包含关键帧列表、音轨转录文本、视频时长和帧数
- **KeyFrameExtractor**：关键帧提取器，基于 JavaCV（FFmpeg）按均匀采样策略从视频中提取代表性帧
- **AudioTrackExtractor**：音轨分离器，基于 JavaCV（FFmpeg）从视频中分离音频轨道
- **VideoProcessException**：视频处理异常，视频解码、帧提取或音轨分离失败时抛出
- **JavaCV**：FFmpeg 的 Java 绑定库，内嵌各平台 native library（Windows / macOS / Linux），用于视频帧提取和音轨分离

## 需求

### 需求 1：ProviderAdapter 多模态调用扩展

**用户故事：** 作为 LifePilot 开发者，我希望 ProviderAdapter 支持携带媒体内容的 LLM 调用，以便多模态请求能通过现有 Provider 适配层发送给支持视觉能力的模型。

#### 验收标准

1. THE ProviderAdapter SHALL 新增 `callWithMedia` 方法，接受文本提示词、MediaContent 列表和超时时间作为参数，返回 LlmResponse
2. THE SpringAiProviderAdapter SHALL 实现 `callWithMedia` 方法，将 MediaContent 列表转换为 Spring AI `Media` 对象，通过 `UserMessage.builder().text(...).media(...)` 构建多模态消息并调用 ChatModel
3. WHEN `callWithMedia` 被调用且 Provider 不支持 VISION 能力时，THE SpringAiProviderAdapter SHALL 抛出 UnsupportedOperationException 并携带描述性错误消息
4. THE ProviderAdapter 的现有方法签名（`call`、`callEntity`、`embed`、`stream`、`chatClient`、`healthCheck`）SHALL 保持不变

### 需求 2：MediaContent 数据模型

**用户故事：** 作为 LifePilot 开发者，我希望有一个统一的媒体内容载体，以便在多模态调用链路中传递图片、文档和音频的二进制数据及元信息。

#### 验收标准

1. THE MediaContent SHALL 使用 Java record 实现，包含以下字段：唯一标识（id）、MIME 类型（mimeType）、二进制数据（data）、原始文件名（fileName，可选）、数据大小（sizeBytes）、附加元数据（metadata）
2. THE MediaContent 的 metadata 字段 SHALL 使用不可变 Map 存储附加元数据（如图片宽高、音频时长）
3. THE MediaContent 的紧凑构造器 SHALL 对 id、mimeType、data 执行非空校验，对 metadata 执行防御性拷贝

### 需求 3：MultimodalRequest 请求模型

**用户故事：** 作为 LifePilot 开发者，我希望有一个多模态请求模型，以便调用方能够将文本和媒体附件组合为一个请求发送给多模态路由。

#### 验收标准

1. THE MultimodalRequest SHALL 使用 Java record 实现，包含以下字段：LLM 场景（scene）、文本提示词（text）、媒体附件列表（mediaList）、输出 Schema（outputSchema，可选）
2. THE MultimodalRequest 的紧凑构造器 SHALL 对 scene 和 text 执行非空校验，对 mediaList 执行防御性拷贝（`List.copyOf`）
3. WHEN mediaList 为 null 时，THE MultimodalRequest SHALL 将其初始化为空列表

### 需求 4：媒体校验

**用户故事：** 作为 LifePilot 用户，我希望系统在处理媒体附件前进行合法性校验，以便不合规的文件被及时拒绝并给出明确的错误提示。

#### 验收标准

1. THE MediaValidator SHALL 校验单个媒体文件的大小不超过配置的最大限制（图片默认 10MB，文档默认 50MB，音频默认 25MB，视频默认 500MB）
2. THE MediaValidator SHALL 校验媒体文件的 MIME 类型在配置的支持列表中（图片默认支持 png、jpeg、gif、webp、bmp；音频默认支持 wav、mp3、ogg、m4a、flac、webm；视频默认支持 mp4、avi、mov、mkv、webm、flv）
3. THE MediaValidator SHALL 校验单次请求中的图片数量不超过配置的最大限制（默认 5 张）
4. THE MediaValidator SHALL 校验媒体数据非空且可读（data 字段不为 null 且长度大于 0）
5. IF 校验失败，THEN THE MediaValidator SHALL 抛出包含具体失败原因的异常

### 需求 5：MIME 类型检测

**用户故事：** 作为 LifePilot 用户，我希望系统基于文件实际内容检测 MIME 类型，以便防止文件扩展名伪造导致的处理错误。

#### 验收标准

1. THE MediaType SHALL 使用 Apache Tika 的 MIME 检测能力，基于文件二进制内容检测实际 MIME 类型
2. WHEN 检测到的 MIME 类型与文件扩展名不一致时，THE MediaType SHALL 以检测到的实际 MIME 类型为准
3. IF Apache Tika 无法识别文件类型，THEN THE MediaType SHALL 返回 `application/octet-stream`
4. THE MediaType SHALL 提供判断 MIME 类型是否为图片类型的便捷方法
5. THE MediaType SHALL 提供判断 MIME 类型是否为文档类型的便捷方法
6. THE MediaType SHALL 提供判断 MIME 类型是否为音频类型的便捷方法
7. THE MediaType SHALL 提供判断 MIME 类型是否为视频类型的便捷方法

### 需求 6：图片预处理

**用户故事：** 作为 LifePilot 用户，我希望系统在将图片发送给 LLM 前自动进行预处理（压缩、缩放），以便控制 Token 消耗和传输成本。

#### 验收标准

1. WHEN 图片的最长边超过配置的最大尺寸（默认 2048 像素）时，THE MediaProcessor SHALL 将图片等比缩放至最长边不超过最大尺寸
2. WHEN 图片为 JPEG 格式时，THE MediaProcessor SHALL 按配置的质量参数（默认 0.85）进行质量压缩
3. WHEN 图片格式为 BMP 或 WebP 时，THE MediaProcessor SHALL 将图片转换为 PNG 或 JPEG 格式
4. THE MediaProcessor SHALL 使用 Thumbnailator 库执行图片压缩和缩放操作
5. THE MediaProcessor SHALL 在预处理完成后返回包含处理后数据的新 MediaContent 实例，保留原始文件名和元数据
6. WHEN 图片尺寸和大小均在限制范围内时，THE MediaProcessor SHALL 跳过压缩步骤，直接返回原始 MediaContent

### 需求 7：文档内容提取

**用户故事：** 作为 LifePilot 用户，我希望系统能从文档附件中提取纯文本内容，以便将文档内容作为上下文传递给 LLM 进行分析。

#### 验收标准

1. THE DocumentExtractor SHALL 支持 PDF、Word（.docx/.doc）、Markdown、TXT 四种文档格式的文本提取
2. THE DocumentExtractor SHALL 组合知识库模块已有的 DocumentParser 实现（PdfParser、WordParser、MarkdownParser、PlainTextParser），通过 MIME 类型匹配选择对应的解析器
3. WHEN 文档 MIME 类型无法匹配到任何已有解析器时，THE DocumentExtractor SHALL 抛出包含不支持格式信息的异常
4. THE DocumentExtractor SHALL 接受 byte[] 数据和 MIME 类型作为输入，返回提取的纯文本字符串
5. IF 文档解析过程中发生错误，THEN THE DocumentExtractor SHALL 将底层 DocumentParseException 包装为描述性异常向上抛出

### 需求 8：多模态路由

**用户故事：** 作为 LifePilot 用户，我希望发送图片或视频时系统自动路由到支持视觉能力的模型，以便获得图片/视频理解的回复。

#### 验收标准

1. WHEN MultimodalRequest 包含图片附件时，THE MultimodalRouter SHALL 仅将请求路由到支持 VISION 能力的 Provider
2. THE MultimodalRouter SHALL 按 Provider 优先级排序选择目标 Provider，并通过 CircuitBreakerManager 过滤熔断状态为 OPEN 的 Provider
3. WHEN 首选 Provider 调用失败时，THE MultimodalRouter SHALL 自动故障转移到下一个可用的 VISION Provider
4. IF 所有支持 VISION 能力的 Provider 均不可用，THEN THE MultimodalRouter SHALL 抛出 LlmUnavailableException 并携带场景名称和已尝试的 Provider 列表
5. WHEN MultimodalRequest 不包含图片或视频附件时，THE MultimodalRouter SHALL 委托给现有 LlmRouter 的 `call` 方法处理纯文本请求
6. THE MultimodalRouter SHALL 在调用前通过 MediaProcessor 对所有图片附件执行预处理
7. THE MultimodalRouter SHALL 支持流式调用模式（返回 `Flux<String>`），路由策略与同步调用一致
8. WHEN MultimodalRequest 包含视频附件时，THE MultimodalRouter SHALL 先通过 VideoProcessor 将视频拆分为关键帧和音轨转录文本，再将关键帧作为图片附件、转录文本追加到提示词中，后续按图片理解流程处理

### 需求 9：语音转文字（STT）

**用户故事：** 作为 LifePilot 用户，我希望发送语音消息时系统自动将语音转为文字，以便 Agent 能理解我的语音指令。

#### 验收标准

1. THE AudioTranscriber SHALL 支持将音频数据转录为文本，接受 byte[] 数据和 MIME 类型作为输入
2. THE AudioTranscriber SHALL 采用"本地优先"级联策略：优先使用本地 Whisper CLI（如果已安装且可用），不可用时回退到 Spring AI TranscriptionModel（云端 STT）
3. THE WhisperCliTranscriber SHALL 通过 ProcessBuilder 调用本地 whisper CLI，将音频写入临时文件后执行转录，解析 CLI 输出获取转录文本
4. THE WhisperCliTranscriber SHALL 在启动时自动检测 whisper CLI 是否在 PATH 中可用（支持 `whisper-cli`、`whisper` 两种命令名）
5. WHEN 本地 Whisper CLI 不可用且云端 STT Provider 也不可用时，THE AudioTranscriber SHALL 抛出 AudioTranscriptionException 并携带描述性错误消息
6. THE AudioTranscriber SHALL 校验音频文件大小不超过配置的最大限制（默认 25MB）
7. THE AudioTranscriber SHALL 支持 WAV、MP3、OGG、M4A、FLAC、WebM 音频格式

### 需求 10：文字转语音（TTS）

**用户故事：** 作为 LifePilot 用户，我希望 Agent 的回复能以语音形式播放，以便在不方便看屏幕时通过语音获取信息。

#### 验收标准

1. THE SpeechSynthesizer SHALL 基于 Spring AI TextToSpeechModel 接口实现文字转语音合成
2. THE SpeechSynthesizer SHALL 支持同步合成（返回完整 byte[] 音频数据）和流式合成（返回 Flux<byte[]> 音频流）
3. THE SpeechSynthesizer SHALL 通过配置支持语音风格（voice）、语速（speed）、输出格式（output-format）的调整
4. WHEN 文本长度超过配置的最大限制（默认 4096 字符）时，THE SpeechSynthesizer SHALL 截断文本并在末尾添加省略提示
5. WHEN TTS Provider 不可用时，THE SpeechSynthesizer SHALL 抛出异常，调用方可选择回退为纯文本回复

### 需求 11：ProviderCapability 扩展

**用户故事：** 作为 LifePilot 开发者，我希望 ProviderCapability 枚举包含 TTS 和 STT 能力值，以便路由层能够根据请求类型选择支持对应能力的 Provider。

#### 验收标准

1. THE ProviderCapability 枚举 SHALL 新增 `TTS` 值，表示文字转语音能力
2. THE ProviderCapability 枚举 SHALL 新增 `STT` 值，表示语音转文字能力
3. THE 现有枚举值（CHAT、EMBEDDING、STRUCTURED_OUTPUT、FUNCTION_CALLING、STREAMING、VISION）SHALL 保持不变

### 需求 12：媒体处理配置外部化

**用户故事：** 作为 LifePilot 用户，我希望媒体处理的各项参数可通过配置文件调整，以便根据实际使用场景灵活配置。

#### 验收标准

1. THE MediaProperties SHALL 通过 `@ConfigurationProperties("lifepilot.media")` 绑定以下配置项：图片配置（最大大小、最大尺寸、JPEG 质量、最大数量、支持格式）、文档配置（最大大小、支持格式）、音频配置（最大大小、最大时长、支持格式、Whisper CLI 路径、Whisper 模型）、视频配置（最大大小、最大时长、最大关键帧数、支持格式）、TTS 配置（语音风格、语速、输出格式、最大文本长度）
2. THE MediaProperties SHALL 使用嵌套静态内部类组织图片配置（Image）、文档配置（Document）、音频配置（Audio）、视频配置（Video）和 TTS 配置（Tts）
3. THE MediaProperties 的所有配置项 SHALL 在 `application.yml` 中显式声明默认值
4. THE MediaAutoConfiguration SHALL 注册 MediaProcessor、MediaValidator、DocumentExtractor、MultimodalRouter、AudioTranscriber、SpeechSynthesizer、VideoProcessor、KeyFrameExtractor、AudioTrackExtractor 为 Spring Bean

### 需求 13：Maven 依赖管理

**用户故事：** 作为 LifePilot 开发者，我希望多模态模块所需的外部依赖被正确引入，以便编译和运行时能正常使用 Apache Tika 和 Thumbnailator。

#### 验收标准

1. THE pom.xml SHALL 新增 `org.apache.tika:tika-core` 依赖，用于 MIME 类型检测
2. THE pom.xml SHALL 新增 `net.coobird:thumbnailator` 依赖，用于图片压缩和缩放
3. THE pom.xml SHALL 仅引入 tika-core（轻量级核心模块），不引入 tika-parsers 完整解析器包
4. THE pom.xml SHALL 新增 `org.bytedeco:javacv-platform` 依赖，用于视频帧提取和音轨分离（内嵌 FFmpeg native library）

### 需求 14：视频预处理

**用户故事：** 作为 LifePilot 用户，我希望发送视频时系统自动提取关键帧和音轨转录文本，以便 Agent 能理解视频内容并回答相关问题。

#### 验收标准

1. THE VideoProcessor SHALL 接受视频二进制数据和 MIME 类型作为输入，返回 VideoProcessResult（包含关键帧列表和音轨转录文本）
2. THE VideoProcessor SHALL 通过 KeyFrameExtractor 按均匀采样策略提取关键帧，采样间隔根据视频时长自适应调整（≤30 秒每 2 秒 1 帧，30 秒~5 分钟每 5 秒 1 帧，5~30 分钟每 15 秒 1 帧，>30 分钟每 30 秒 1 帧）
3. THE VideoProcessor SHALL 通过 AudioTrackExtractor 分离视频音轨，并委托 AudioTranscriber 将音轨转录为文本
4. WHEN 视频无音轨（静音视频）时，THE VideoProcessor SHALL 正常返回结果，transcript 字段为 null
5. THE VideoProcessor SHALL 校验视频文件大小不超过配置的最大限制（默认 500MB）和时长不超过配置的最大限制（默认 30 分钟）
6. IF 视频解码或帧提取过程中发生错误，THEN THE VideoProcessor SHALL 抛出 VideoProcessException 并携带描述性错误消息

### 需求 15：VideoProcessResult 数据模型

**用户故事：** 作为 LifePilot 开发者，我希望有一个视频处理结果载体，以便在视频处理管线中传递关键帧和转录文本。

#### 验收标准

1. THE VideoProcessResult SHALL 使用 Java record 实现，包含以下字段：关键帧列表（keyFrames）、音轨转录文本（transcript，可选）、视频时长秒数（durationSeconds）、提取的关键帧数量（frameCount）
2. THE VideoProcessResult 的紧凑构造器 SHALL 对 keyFrames 执行防御性拷贝（`List.copyOf`）
3. THE VideoProcessResult 的 frameCount SHALL 等于 keyFrames 列表的大小

### 需求 16：关键帧提取与音轨分离

**用户故事：** 作为 LifePilot 开发者，我希望有独立的关键帧提取器和音轨分离器，以便视频处理的各个步骤可独立测试和复用。

#### 验收标准

1. THE KeyFrameExtractor SHALL 基于 JavaCV（FFmpegFrameGrabber）从视频中按指定间隔提取帧，返回帧图片的 byte[] 列表
2. THE KeyFrameExtractor SHALL 将提取的帧图片经 MediaProcessor 预处理后封装为 MediaContent 列表
3. THE KeyFrameExtractor SHALL 限制最大关键帧数量不超过配置的最大限制（默认 120 帧）
4. THE AudioTrackExtractor SHALL 基于 JavaCV 从视频中分离音频轨道，返回音频 byte[] 数据
5. WHEN 视频不包含音频轨道时，THE AudioTrackExtractor SHALL 返回空 Optional
6. THE KeyFrameExtractor 和 AudioTrackExtractor SHALL 在处理完成后确保临时文件被清理（try-finally）
