# 多模态能力 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.media`
> **最后更新**：2026-03

## 1. 功能概述

多模态模块为知微提供图片、文档、音频、视频四类媒体的预处理能力。它将各类媒体内容转换为 LLM 可消费的格式——图片压缩为合适尺寸、文档提取为纯文本、语音转录为文字、视频拆分为关键帧序列和转录文本。同时提供文字转语音（TTS）能力，支持同步和流式两种合成模式。

## 2. 核心特性

### 2.1 MIME 类型智能检测

基于 Apache Tika 的二进制魔数字节检测，不依赖文件扩展名。自动将检测结果分类为图片、文档、音频、视频四大类别，为后续处理管线提供准确的媒体类型判断。

### 2.2 媒体内容校验

按媒体类别执行差异化校验：
- 数据非空检查
- 文件大小限制（图片 10MB、文档 50MB、音频 25MB、视频 500MB）
- MIME 格式白名单过滤
- 图片批量请求数量限制（默认单次最多 5 张）

### 2.3 图片预处理

自动压缩和格式转换，确保图片适合 LLM 消费：
- 最长边超过配置阈值（默认 2048px）时等比缩放
- JPEG 按配置质量（默认 0.85）压缩
- BMP、WebP 格式自动转换为 PNG
- 尺寸和大小均在限制内时跳过处理，直接返回原始数据

### 2.4 文档文本提取

复用知识库模块的 `DocumentParser`，支持 PDF、Word（.docx）、Excel（.xlsx）、PowerPoint（.pptx）、Markdown、纯文本（txt / log / csv / tsv）六类格式。通过临时文件中转，提取完成后自动清理。

### 2.5 语音转文字（STT）

采用"本地优先"级联策略：
1. 优先使用本地 Whisper CLI（零成本、完全隐私）
2. 本地不可用时回退到云端 Spring AI TranscriptionModel
3. 全部不可用时抛出异常，明确告知用户

### 2.6 文字转语音（TTS）

基于 Spring AI `TextToSpeechModel` 接口：
- 同步合成：一次性返回完整音频数据
- 流式合成：返回 `Flux<byte[]>` 音频流，适合实时播放
- 超长文本自动截断并追加省略提示

### 2.7 视频预处理

将视频拆分为 LLM 可理解的结构化内容：
- 关键帧提取：按时间间隔采样代表性画面
- 音轨分离：提取视频音轨并通过 AudioTranscriber 转录为文字
- 输出 `VideoProcessResult`：包含关键帧列表、转录文本、视频时长、帧数

## 3. 使用场景

用户发送图片附件时，系统自动检测格式、校验大小、压缩尺寸，将处理后的图片传递给支持视觉能力的 LLM 进行理解。

用户上传文档时，系统提取文档文本内容作为上下文，LLM 基于文本内容进行分析和问答。

用户发送语音消息时，系统优先使用本地 Whisper 转录为文字，转录后的文本进入正常的对话处理流程，Agent 引擎无需感知音频模态。

用户上传视频时，系统提取关键帧和音轨转录文本，将结构化内容作为上下文传递给 LLM，实现视频内容理解和摘要。

Agent 回复需要语音输出时，通过 SpeechSynthesizer 将文本转为音频流，由 Channel 适配器决定是否启用。

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.media.image.max-size-bytes` | `10485760` | 图片最大文件大小（10MB） |
| `lifepilot.media.image.max-dimension` | `2048` | 图片最长边最大像素 |
| `lifepilot.media.image.jpeg-quality` | `0.85` | JPEG 压缩质量 |
| `lifepilot.media.image.max-per-request` | `5` | 单次请求最大图片数 |
| `lifepilot.media.document.max-size-bytes` | `52428800` | 文档最大文件大小（50MB） |
| `lifepilot.media.audio.max-size-bytes` | `26214400` | 音频最大文件大小（25MB） |
| `lifepilot.media.audio.max-duration-seconds` | `300` | 音频最大时长（秒） |
| `lifepilot.media.audio.whisper-model` | `base` | Whisper 模型名称 |
| `lifepilot.media.video.max-size-bytes` | `524288000` | 视频最大文件大小（500MB） |
| `lifepilot.media.video.max-duration-seconds` | `1800` | 视频最大时长（秒） |
| `lifepilot.media.video.max-key-frames` | `120` | 最大关键帧数量 |
| `lifepilot.media.tts.voice` | `alloy` | TTS 语音风格 |
| `lifepilot.media.tts.speed` | `1.0` | TTS 语速倍率 |
| `lifepilot.media.tts.max-text-length` | `4096` | TTS 最大文本长度 |

## 5. 限制与未来方向

当前限制：
- 本地 Whisper CLI 需用户自行安装
- 视频处理基于关键帧 + 音轨分治策略，非原生视频输入
- 图片内容不经过语义缓存，每次请求独立处理
- TTS 依赖云端服务，本地 TTS 引擎暂未集成

未来方向：
- 集成本地 TTS 引擎（如 Piper TTS）实现离线语音合成
- 支持原生视频输入的 LLM（如 Gemini 2.0）
- 音频流式输入（实时语音对话）
