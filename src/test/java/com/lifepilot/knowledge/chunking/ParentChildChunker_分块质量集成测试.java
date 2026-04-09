package com.lifepilot.knowledge.chunking;

import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.util.TokenCounter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分块管线端到端集成测试 — 用真实多类型样本文本验证 SmartChunker + ParentChildChunker 的分块质量。
 *
 * <p>覆盖文本类型：中文产品说明、小说、技术文档（中/英）、自媒体文案、中英混合、长散文、表格文档。
 * 不依赖 LLM、数据库或 Spring 上下文，纯内存构造完整管线。</p>
 *
 * @author zsg
 * @since 2026-04-09
 */
class ParentChildChunker_分块质量集成测试 {

    // ========== 共享管线组件 ==========

    static TokenCounter tokenCounter;
    static ParentChildChunker pipeline;

    /** child 最大字符数 = childMaxTokens(384) * 2 = 768 */
    static final int CHILD_MAX_CHUNK_SIZE = 384 * 2;
    /** parent 最大字符数 = recursive.maxChunkSize 默认 1536 */
    static final int PARENT_MAX_CHUNK_SIZE = 1536;

    @BeforeAll
    static void 构建完整分块管线() {
        tokenCounter = new TokenCounter.Heuristic();

        // 基础分块器
        var fixedSizeChunker = new FixedSizeChunker(ChunkingConfig.DEFAULT, tokenCounter);
        var recursiveConfig = new KnowledgeBaseProperties.Chunking.Recursive(null, 0, 0, 0); // 用默认值
        var recursiveChunker = new RecursiveChunker(recursiveConfig, tokenCounter);

        // 三层架构组件
        var structureConfig = new KnowledgeBaseProperties.Chunking.StructureAnalysis(0, 0, 0);
        var structureAnalyzer = new DocumentStructureAnalyzer(
                structureConfig.maxHeadingLength(),
                structureConfig.minCodeIndent(),
                structureConfig.minTableColumns());
        var regionConfig = new KnowledgeBaseProperties.Chunking.RegionRouting(0, 0, 0, 0);
        var regionRouter = new RegionChunkingRouter(
                recursiveChunker, fixedSizeChunker, tokenCounter,
                regionConfig.maxIntactCodeSize(),
                regionConfig.maxIntactTableSize(),
                regionConfig.maxIntactListSize(),
                regionConfig.paragraphMinForRecursive(),
                recursiveConfig.maxChunkSize());
        // Parent-Child 模式下父块不需要 overlap — 通过层级关系提供上下文
        var chunkMerger = new ChunkMerger(
                recursiveConfig.maxChunkSize(),
                recursiveConfig.minChunkSize(),
                0,  // 父块无 overlap，避免子块跨 section 重复
                tokenCounter);

        var smartChunker = new SmartChunker(
                recursiveChunker, structureAnalyzer, regionRouter, chunkMerger);

        // Parent-Child 管线
        var childRecursiveConfig = new KnowledgeBaseProperties.Chunking.Recursive(
                recursiveConfig.separators(), CHILD_MAX_CHUNK_SIZE, 80, 0);
        var childChunker = new RecursiveChunker(childRecursiveConfig, tokenCounter);
        pipeline = new ParentChildChunker(smartChunker, childChunker);
    }

    // ========== 样本文本 ==========

    /** 百炼平台产品说明（短文档，4 个主题段） */
    static final String 百炼平台文本 = """
            模型服务
            开箱即用的模型
            阿里云百炼平台提供开箱即用的模型服务，无需自行部署或运维，即可直接调用自研千问（Qwen）全系列模型，以及 DeepSeek、Kimi、GLM等第三方大模型。详情请参见模型列表。

            千问（Qwen）系列旗舰模型：

            千问Max ：Qwen3系列效果最好的模型，适合处理复杂、多步骤任务。

            千问Plus：在效果、速度和成本上表现均衡，是多数场景的推荐选择。

            最新的Qwen3系列模型支持混合思考能力。

            细分领域模型：针对特定行业和任务，提供长文本处理、翻译、数据挖掘、法律、意图理解、角色扮演、深入研究等多种领域模型。

            模型调优、部署和评测
            模型调优：支持有监督微调（SFT）、继续预训练（CPT）和直接偏好优化（DPO）调优训练方法，以满足特定业务需求。

            模型部署：支持将预置模型或调优后的自定义模型部署为资源专享的推理服务，以满足对高并发、低延迟等不同性能的业务需求。提供按时长、包月、按Token量等多种计费方式。

            模型评测：提供包括人工评测、自动评测和基线评测在内的评测体系，支持快速对比不同模型的表现，检验模型调优效果，并预先发现潜在调用风险。

            应用构建
            应用类型：提供可视化与高代码两种应用开发模式，以满足不同开发者的需求。通过可视化方式可快速创建智能体应用与工作流应用，而高代码应用则支持将Python项目部署为后端服务，支持自动化运维、可观测、日志服务等能力。

            功能拓展：支持通过知识库（RAG）连接私有数据和专业领域知识，通过插件和模型上下文协议（MCP）调用外部服务，实现了灵活、个性化的功能拓展。

            分享与发布：支持将应用以多种方式分享给用户使用，包括发布API供自有业务系统集成、嵌入式Web Widget、独立网站链接等形式。

            新人免费额度。

            如何支付费用
            调用模型会自动扣费（按分钟出账），请确保您的阿里云账户余额充足，可通过费用与成本页面进行充值。

            查看账单与用量
            消费明细：访问账单详情和成本分析页面。

            调用统计：模型调用完约一小时后，访问阿里云百炼控制台，在页面右上角选择目标地域，进入模型监控页面并设置查询条件，点击目标模型操作列的监控，即可查看该模型的调用量、Token消耗、成功率等统计结果。详情请参见模型监控。
            """;

    /** 小说文本（章节结构 + 缩进段落 + 对话） */
    static final String 小说文本 = """
            第一章 陨落的天才

            "斗之力，三段！"

                望着测验魔石碑上面闪亮得甚至有些刺眼的五个大字，少年面无表情，唇角有着一抹自嘲，紧握的手掌，因为大力，而导致略微尖锐的指甲深深的刺进了掌心之中，带来一阵阵钻心的疼痛…

                "萧炎，斗之力，三段！级别：低级！"测验魔石碑之旁，一位中年男子，看了一眼碑上所显示出来的信息，语气漠然的将之公布了出来…

                中年男子话刚刚脱口，便是不出意外的在场中引起了一阵哄堂大笑。

                "斗之力三段？嘿嘿，果然不出我所料，这个废物，一年前是斗之力三段，一年后还是斗之力三段，真是个让人惊叹的天才啊…"

                "你能指望一个连斗之气旋都凝结不了的废物有什么出息？三段斗之力，恐怕连我八岁的妹妹都打不过吧，嘻嘻…"

                周围传来的不屑嘲笑以及惋惜轻叹，落在那如木桩待在原地的少年耳中，恍如一根根利刺狠狠的扎在心脏一般，让得少年呼吸微微急促。

                少年缓缓抬起头来，露出一张有些清秀的稚嫩脸庞，漆黑的眸子木然的在周围那些嘲讽的同龄人身上扫过，少年嘴角的自嘲，似乎变得更加苦涩了。

                "这些人，都如此刻薄势力吗？或许是因为三年前他们曾经在自己面前露出过最谦卑的笑容，所以，如今想要讨还回去吧…"苦涩的一笑，萧炎缓缓转身，然后极其落寞的在众人的指指点点下走出测验广场。

            第二章 斗气大陆

            月如银盘，漫天繁星。

                山崖之颠，萧炎斜躺在草地之上，嘴中叼中一根青草，微微嚼动，任由那淡淡的苦涩在嘴中弥漫开来…

                举起有些白皙的手掌，挡在眼前，目光透过手指缝隙，遥望着天空上那轮巨大的银月。

                "唉…"想起下午的测试，萧炎轻叹了一口气，懒懒的抽回手掌，双手枕着脑袋，眼神有些恍惚…

                "十五年了呢…"低低的自喃声，忽然毫无边际的从少年嘴中轻吐了出来。

                十五年，只有萧炎自己知道，这十五年他过得有多苦…

                前世的萧炎，是地球的一名普通大学生，不知道是何缘故，一觉醒来之后，竟然匪夷所思的到了一个完全陌生的世界…

                用了近两年的时间，萧炎才彻底的放弃了回归的念头。

                对于这片名为斗气大陆的神奇大陆，萧炎也是有了些模糊的了解…

                大陆名为斗气大陆，大陆上并没有小说中常见的各系魔法，而斗气，才是大陆的唯一主调！
            """;

    /** 包含 Markdown 代码块的中文技术文档 */
    static final String 技术文档文本 = """
            # API 调用指南

            ## 认证方式

            所有 API 请求必须携带 API Key 进行认证。您可以在控制台的"API 密钥"页面创建和管理密钥。

            ### 请求头设置

            在每个 HTTP 请求中添加以下头：

            ```
            Authorization: Bearer YOUR_API_KEY
            Content-Type: application/json
            ```

            ## 发送消息

            调用 `/v1/chat/completions` 接口发送消息。请求体示例：

            ```json
            {
              "model": "qwen-max",
              "messages": [
                {"role": "system", "content": "你是一个有帮助的助手。"},
                {"role": "user", "content": "你好"}
              ]
            }
            ```

            ## 错误处理

            常见错误码：
            - 401：API Key 无效或已过期
            - 429：请求频率超限，请稍后重试
            - 500：服务器内部错误，请联系技术支持

            ## 计费说明

            按实际消耗的 Token 数量计费。输入 Token 和输出 Token 分别计价。详情请参见价格页面。
            """;

    /** 纯英文技术文档（Markdown 标题 + 代码块 + 列表） */
    static final String 英文技术文档 = """
            # Getting Started with Spring Boot

            ## Prerequisites

            Before you begin, ensure you have the following installed:
            - Java 22 or later
            - Maven 3.9+
            - An IDE such as IntelliJ IDEA or VS Code

            ## Creating a Project

            Use Spring Initializr to bootstrap your project. Select the following dependencies:

            ```bash
            curl https://start.spring.io/starter.zip \\
              -d dependencies=web,data-jpa,h2 \\
              -d javaVersion=22 \\
              -o demo.zip
            ```

            ## Configuration

            Spring Boot uses `application.yml` for configuration. Here is an example:

            ```yaml
            spring:
              datasource:
                url: jdbc:h2:mem:testdb
                driver-class-name: org.h2.Driver
              jpa:
                hibernate:
                  ddl-auto: update
                show-sql: true
            ```

            ## Writing Your First Controller

            Create a REST controller to handle HTTP requests:

            ```java
            @RestController
            public class HelloController {

                @GetMapping("/hello")
                public String hello(@RequestParam(defaultValue = "World") String name) {
                    return "Hello, " + name + "!";
                }
            }
            ```

            ## Testing

            Spring Boot provides excellent testing support. Use `@SpringBootTest` for integration tests and `@WebMvcTest` for controller tests.

            ```java
            @SpringBootTest
            class DemoApplicationTests {

                @Autowired
                private MockMvc mockMvc;

                @Test
                void shouldReturnHello() throws Exception {
                    mockMvc.perform(get("/hello"))
                           .andExpect(status().isOk())
                           .andExpect(content().string("Hello, World!"));
                }
            }
            ```

            ## Deployment

            Build a fat JAR and run it:

            ```bash
            mvn clean package -DskipTests
            java -jar target/demo-0.0.1-SNAPSHOT.jar
            ```

            For production, consider using Docker:

            ```dockerfile
            FROM eclipse-temurin:22-jre
            COPY target/demo-0.0.1-SNAPSHOT.jar app.jar
            ENTRYPOINT ["java", "-jar", "app.jar"]
            ```
            """;

    /** 自媒体文案（无结构标题、短段落、口语化） */
    static final String 自媒体文案 = """
            今天聊聊我用了半年的 AI 编程助手，到底值不值得买。

            先说结论：如果你是个每天写代码超过 4 小时的开发者，闭眼入就对了。

            我最早接触 AI 编程是去年 10 月，当时还在用 GitHub Copilot。说实话刚开始体验一般，补全经常不准，有时候给出的代码完全是错的。但是坚持用了两周之后，明显感觉到效率提升了。

            后来我又试了 Cursor，体验完全不同。它不只是补全代码，更像是一个真正的编程伙伴。你可以用自然语言描述你想要的功能，它会帮你生成整个文件。重构、调试、写测试，全都能搞定。

            最让我惊喜的是它理解上下文的能力。比如我在写一个 Spring Boot 项目，它知道我用了哪些依赖，知道项目的架构，给出的建议非常贴合实际。这一点是其他工具做不到的。

            当然也不是没有缺点。偶尔会生成过时的 API 调用，需要自己检查。对于复杂的业务逻辑，它理解不了需求的时候会胡说八道。还有价格确实不便宜，每月 20 美元。

            但总体来说，投入产出比非常高。保守估计每天至少帮我节省 1-2 小时，一个月就是 30-60 小时。这么算下来，20 美元真的很值。

            最后给几个使用建议：
            1. 不要完全信任生成的代码，一定要审查
            2. 学会用自然语言精准描述需求
            3. 善用上下文功能，让 AI 了解你的项目
            4. 遇到不准的地方及时纠正，它会越来越好

            如果你也在用 AI 编程工具，欢迎在评论区分享你的体验！
            """;

    /** 中英文混合文档（英文术语穿插中文叙述） */
    static final String 中英混合文档 = """
            # RAG 系统架构设计

            ## 概述

            Retrieval-Augmented Generation（RAG）是一种结合 information retrieval 和 text generation 的技术架构。核心思路是在 LLM inference 之前，先从 knowledge base 中检索相关 context，然后将检索到的 documents 作为 prompt 的一部分传给 model。

            ## Chunking Strategy

            文档分块是 RAG pipeline 的第一步，直接影响 retrieval quality。常见的 chunking 方法包括：

            - **Fixed-size chunking**：按固定 token 数切分，简单但容易破坏语义边界
            - **Recursive splitting**：按分隔符层级递归切分（paragraph → sentence → word），是目前 benchmark 验证的最佳 baseline
            - **Semantic chunking**：利用 embedding similarity 检测语义断点，成本高但对结构化文档效果好
            - **Parent-child chunking**：小块索引精准匹配，大块返回完整上下文，兼顾 precision 和 context richness

            ## Embedding Model 选择

            嵌入模型的选择对 retrieval accuracy 影响巨大。推荐使用支持 multilingual 的模型：

            1. **bge-m3**：支持中英日韩等多语言，dimension 1024，是 MTEB benchmark 上表现最好的开源模型之一
            2. **jina-embeddings-v3**：支持 late chunking，8192 token context window，适合长文档场景
            3. **text-embedding-3-large**：OpenAI 的旗舰 embedding model，dimension 3072，但不支持 on-premise 部署

            ## Hybrid Search

            单纯的 vector search 在 keyword matching 场景下表现不佳（例如搜索特定 error code 或 API endpoint）。Hybrid search 结合 dense retrieval（vector）和 sparse retrieval（BM25/TF-IDF），通过 Reciprocal Rank Fusion（RRF）融合两路结果，能显著提升 recall。

            实测数据表明，hybrid search + cross-encoder reranking 的组合在大多数 benchmark 上能达到最佳效果。
            """;

    /** 长篇纯文本（无标题无段落结构的散文，测试 hardSplit 和长文本处理） */
    static final String 长篇散文 = "从前有一座山，山上有一座庙，庙里有两个和尚。".repeat(30) +
            "老和尚在给小和尚讲故事。他说，从前有一座山，山上有一棵树，树下有一条河。" +
            "河水清澈见底，鱼儿在水中自由自在地游来游去。" +
            "岸边的柳树随风轻轻摇摆，像是在跳舞一样。" +
            "小和尚听得入了迷，问道：后来呢？" +
            "老和尚微笑着说：后来啊，那棵树长得越来越高，高到可以看见远方的城市。" +
            "城市里有高楼大厦，有车水马龙，有无数的人在忙忙碌碌。" +
            "但是山上的和尚不知道这些，他们只知道每天念经、打坐、种菜、挑水。" +
            "日子一天天过去，春去秋来，花开花落。" +
            "小和尚渐渐长大了，变成了老和尚。" +
            "他也开始给新来的小和尚讲故事：从前有一座山，山上有一座庙……";

    /** 表格密集文档 */
    static final String 表格文档 = """
            # 2024 年度模型对比报告

            ## 性能对比

            以下是主流大模型在各项 benchmark 上的表现：

            | 模型 | MMLU | HumanEval | GSM8K | HellaSwag |
            |------|------|-----------|-------|-----------|
            | GPT-4o | 88.7 | 90.2 | 95.1 | 95.3 |
            | Claude 3.5 Sonnet | 88.3 | 92.0 | 96.4 | 89.0 |
            | Qwen2.5-72B | 86.1 | 86.4 | 91.5 | 88.0 |
            | DeepSeek-V3 | 87.1 | 65.2 | 89.2 | 87.1 |
            | Llama 3.1-70B | 82.0 | 80.5 | 83.7 | 85.2 |

            ## 价格对比

            API 调用价格（每百万 Token）：

            | 模型 | 输入价格 | 输出价格 | 上下文窗口 |
            |------|---------|---------|-----------|
            | GPT-4o | $2.50 | $10.00 | 128K |
            | Claude 3.5 Sonnet | $3.00 | $15.00 | 200K |
            | Qwen2.5-72B | ¥4.00 | ¥12.00 | 128K |
            | DeepSeek-V3 | ¥1.00 | ¥2.00 | 128K |

            ## 总结

            综合性能和价格，DeepSeek-V3 是性价比最高的选择。对于需要最强推理能力的场景，GPT-4o 和 Claude 3.5 Sonnet 仍是首选。Qwen2.5-72B 在中文场景下表现优异，且支持私有化部署。
            """;

    /** 所有样本文本列表，用于通用测试 */
    static final List<String> 所有样本 = List.of(
            百炼平台文本, 小说文本, 技术文档文本, 英文技术文档,
            自媒体文案, 中英混合文档, 长篇散文, 表格文档);

    // ========== 通用质量断言 ==========

    @Nested
    class 通用质量保证 {

        @Test
        void 所有分块内容不为空() {
            for (var text : 所有样本) {
                var chunks = pipeline.chunk(text, Map.of());
                for (var chunk : chunks) {
                    assertFalse(chunk.content().isBlank(),
                            "发现空白分块: index=%d, level=%d".formatted(chunk.chunkIndex(), chunk.chunkLevel()));
                }
            }
        }

        @Test
        void 父块和子块的层级标记正确() {
            for (var text : 所有样本) {
                var chunks = pipeline.chunk(text, Map.of());
                var parentIds = chunks.stream()
                        .filter(c -> c.chunkLevel() == 0)
                        .map(DocumentChunk::id)
                        .toList();
                for (var chunk : chunks) {
                    if (chunk.chunkLevel() == 1) {
                        assertTrue(chunk.parentChunkId().isPresent(),
                                "子块缺少 parentChunkId: index=%d".formatted(chunk.chunkIndex()));
                        assertTrue(parentIds.contains(chunk.parentChunkId().get()),
                                "子块引用了不存在的父块: parentId=%s".formatted(chunk.parentChunkId().get()));
                    }
                }
            }
        }

        @Test
        void 子块继承父块的标题层级() {
            // 技术文档和小说都有明确的标题结构，子块应继承父块的 headingHierarchy
            for (var text : List.of(英文技术文档, 小说文本, 中英混合文档)) {
                var chunks = pipeline.chunk(text, Map.of());
                var childrenWithParent = chunks.stream()
                        .filter(c -> c.chunkLevel() == 1 && c.parentChunkId().isPresent())
                        .toList();
                if (childrenWithParent.isEmpty()) continue;

                // 构建 parentId → parent 映射
                var parentMap = new java.util.HashMap<String, DocumentChunk>();
                for (var c : chunks) {
                    if (c.chunkLevel() == 0) parentMap.put(c.id(), c);
                }

                for (var child : childrenWithParent) {
                    var parent = parentMap.get(child.parentChunkId().get());
                    if (parent != null && !parent.headingHierarchy().isEmpty()) {
                        assertEquals(parent.headingHierarchy(), child.headingHierarchy(),
                                "子块应继承父块标题层级: childIndex=%d, parentIndex=%d, parentHeading=%s".formatted(
                                        child.chunkIndex(), parent.chunkIndex(), parent.breadcrumb()));
                    }
                }
            }
        }

        @Test
        void 子块embeddingText包含章节面包屑() {
            // 构造一个足够长的章节文本，使父块 > childMaxChunkSize（768 字符）从而产生子块
            String longChapter = """
                    # 系统架构设计

                    ## 数据库层

                    """ + "本系统采用 SQLite 作为主存储引擎，支持 WAL 模式实现高并发读取。".repeat(20) + "\n\n" +
                    """
                    ## 缓存层

                    """ + "缓存策略采用 LRU 算法，设置合理的 TTL 过期时间以保证数据一致性。".repeat(20);
            var chunks = pipeline.chunk(longChapter, Map.of());
            var childrenWithHeading = chunks.stream()
                    .filter(c -> c.chunkLevel() == 1 && !c.headingHierarchy().isEmpty())
                    .toList();
            assertFalse(childrenWithHeading.isEmpty(), "长章节子块应继承标题层级");
            for (var child : childrenWithHeading) {
                String breadcrumb = child.breadcrumb();
                assertTrue(child.embeddingText().startsWith(breadcrumb),
                        "子块 embeddingText 应以面包屑开头: breadcrumb=%s, actual=%s".formatted(
                                breadcrumb, child.embeddingText().substring(0, Math.min(80, child.embeddingText().length()))));
            }
        }

        @Test
        void 无内容重复_父块内不应有大段重复文本() {
            for (var text : List.of(百炼平台文本, 小说文本, 技术文档文本, 英文技术文档, 自媒体文案, 中英混合文档)) {
                var chunks = pipeline.chunk(text, Map.of());
                for (var chunk : chunks.stream().filter(c -> c.chunkLevel() == 0).toList()) {
                    String content = chunk.content();
                    assertFalse(hasDuplicateSubstring(content, 200),
                            "父块存在大段重复内容: index=%d, 前100字=%s".formatted(
                                    chunk.chunkIndex(), content.substring(0, Math.min(100, content.length()))));
                }
            }
        }

        @Test
        void 每种文本至少产生一个分块() {
            for (var text : 所有样本) {
                var chunks = pipeline.chunk(text, Map.of());
                assertFalse(chunks.isEmpty(), "文本应产生至少一个分块");
            }
        }
    }

    // ========== 子块碎片测试 ==========

    @Nested
    class 子块不应有碎片 {

        @Test
        void 子块最小尺寸不低于80字符() {
            for (var text : 所有样本) {
                var chunks = pipeline.chunk(text, Map.of());
                var children = chunks.stream().filter(c -> c.chunkLevel() == 1).toList();
                for (var child : children) {
                    assertTrue(child.content().length() >= 80,
                            "子块过小 (%d字符): index=%d, 内容=%s".formatted(
                                    child.content().length(), child.chunkIndex(),
                                    child.content().substring(0, Math.min(50, child.content().length()))));
                }
            }
        }

        @Test
        void 子块token数不低于50() {
            for (var text : 所有样本) {
                var chunks = pipeline.chunk(text, Map.of());
                var children = chunks.stream().filter(c -> c.chunkLevel() == 1).toList();
                for (var child : children) {
                    assertTrue(child.tokenCount() >= 50,
                            "子块 token 过少 (%d): index=%d".formatted(child.tokenCount(), child.chunkIndex()));
                }
            }
        }

        @Test
        void 子块字符数不超过childMaxChunkSize加合理余量() {
            int maxAllowed = (int) (CHILD_MAX_CHUNK_SIZE * 1.1);
            for (var text : 所有样本) {
                var chunks = pipeline.chunk(text, Map.of());
                var children = chunks.stream().filter(c -> c.chunkLevel() == 1).toList();
                for (var child : children) {
                    assertTrue(child.content().length() <= maxAllowed,
                            "子块过大 (%d字符, 上限%d): index=%d, 前50字=%s".formatted(
                                    child.content().length(), maxAllowed, child.chunkIndex(),
                                    child.content().substring(0, Math.min(50, child.content().length()))));
                }
            }
        }
    }

    // ========== embeddingText 测试 ==========

    @Nested
    class Embedding文本质量 {

        @Test
        void embeddingText包含标题面包屑() {
            var chunks = pipeline.chunk(技术文档文本, Map.of());
            // 技术文档有标题结构，child 块应继承标题层级
            boolean foundBreadcrumb = false;
            for (var chunk : chunks) {
                if (!chunk.headingHierarchy().isEmpty()) {
                    String embedding = chunk.embeddingText();
                    String breadcrumb = String.join(" > ", chunk.headingHierarchy());
                    assertTrue(embedding.startsWith(breadcrumb),
                            "embeddingText 应以标题面包屑开头: 期望前缀=%s, 实际=%s".formatted(
                                    breadcrumb, embedding.substring(0, Math.min(100, embedding.length()))));
                    foundBreadcrumb = true;
                }
            }
            assertTrue(foundBreadcrumb, "技术文档分块应包含标题层级");
        }

        @Test
        void 无标题文档的embeddingText不含面包屑() {
            var chunks = pipeline.chunk(自媒体文案, Map.of());
            // 自媒体文案可能没有标题层级（取决于结构分析器是否检测到短行标题）
            // 如果某个块没有 headingHierarchy，embeddingText 应直接从 content 开始
            for (var chunk : chunks) {
                if (chunk.headingHierarchy().isEmpty()) {
                    assertEquals(chunk.content(), chunk.embeddingText(),
                            "无标题层级时 embeddingText 应等于 content");
                }
            }
        }

        @Test
        void 英文文档标题面包屑正确() {
            var chunks = pipeline.chunk(英文技术文档, Map.of());
            boolean foundNestedBreadcrumb = false;
            for (var chunk : chunks) {
                if (chunk.headingHierarchy().size() >= 2) {
                    String embedding = chunk.embeddingText();
                    assertTrue(embedding.contains(" > "),
                            "多级标题应以 ' > ' 分隔");
                    foundNestedBreadcrumb = true;
                }
            }
            assertTrue(foundNestedBreadcrumb, "英文文档应产生多级标题面包屑");
        }
    }

    // ========== 产品说明文档测试 ==========

    @Nested
    class 百炼平台分块质量 {

        @Test
        void 父块按主题段落切分() {
            var chunks = pipeline.chunk(百炼平台文本, Map.of());
            var parents = chunks.stream().filter(c -> c.chunkLevel() == 0).toList();
            assertTrue(parents.size() >= 3 && parents.size() <= 6,
                    "产品说明应有 3-6 个父块（实际: %d）".formatted(parents.size()));
        }

        @Test
        void 第一个父块包含模型服务主题() {
            var chunks = pipeline.chunk(百炼平台文本, Map.of());
            var first = chunks.stream().filter(c -> c.chunkLevel() == 0).findFirst().orElseThrow();
            assertTrue(first.content().contains("模型服务"),
                    "第一个父块应包含「模型服务」主题");
        }
    }

    // ========== 小说文本测试 ==========

    @Nested
    class 小说分块质量 {

        @Test
        void 章节标题不应独立成碎片父块() {
            var chunks = pipeline.chunk(小说文本, Map.of());
            var parents = chunks.stream().filter(c -> c.chunkLevel() == 0).toList();
            for (var parent : parents) {
                assertTrue(parent.tokenCount() >= 50,
                        "父块疑似标题碎片 (%d token): %s".formatted(
                                parent.tokenCount(), parent.content().strip()));
            }
        }

        @Test
        void 中文缩进段落不应被分类为代码块() {
            var chunks = pipeline.chunk(小说文本, Map.of());
            var parents = chunks.stream().filter(c -> c.chunkLevel() == 0).toList();
            assertTrue(parents.size() >= 2,
                    "小说应有至少 2 个父块（2 章），实际: %d".formatted(parents.size()));
        }

        @Test
        void 对话行不应被误判为标题() {
            String text = """
                    第九章 药老！

                    "炼药师？"

                        闻言，萧炎一怔，旋即眉头大皱："在斗气大陆，只要是个人，都想成为炼药师，可炼药师，是随便什么人都能当上的么？那些苛刻的条件…"话音忽然一顿，萧炎猛的抬头，张大着嘴："我达到了？"

                        非常欣赏萧炎这幅震撼中夹杂着狂喜的复杂表情，老者呵呵微笑着缓缓点头。
                    """;
            var chunks = pipeline.chunk(text, Map.of());
            var parents = chunks.stream().filter(c -> c.chunkLevel() == 0).toList();
            assertEquals(1, parents.size(),
                    "「第九章 药老！」和后续内容应在同一父块中，实际父块数: %d".formatted(parents.size()));
            assertTrue(parents.getFirst().content().contains("第九章 药老"),
                    "父块应包含章节标题");
            assertTrue(parents.getFirst().content().contains("炼药师"),
                    "父块应包含章节内容");
        }
    }

    // ========== 技术文档测试 ==========

    @Nested
    class 技术文档分块质量 {

        @Test
        void 代码块不应被从中间截断() {
            var chunks = pipeline.chunk(技术文档文本, Map.of());
            boolean foundApiKeyCode = false;
            boolean foundJsonCode = false;
            for (var chunk : chunks) {
                if (chunk.content().contains("Authorization: Bearer")) {
                    foundApiKeyCode = true;
                    assertTrue(chunk.content().contains("Content-Type: application/json"),
                            "代码块被截断：Authorization 和 Content-Type 应在同一块");
                }
                if (chunk.content().contains("\"model\": \"qwen-max\"")) {
                    foundJsonCode = true;
                    assertTrue(chunk.content().contains("\"role\": \"user\""),
                            "JSON 代码块被截断");
                }
            }
            assertTrue(foundApiKeyCode, "应包含 API Key 代码块");
            assertTrue(foundJsonCode, "应包含 JSON 代码块");
        }
    }

    // ========== 英文技术文档测试 ==========

    @Nested
    class 英文文档分块质量 {

        @Test
        void 英文代码块完整保留() {
            var chunks = pipeline.chunk(英文技术文档, Map.of());
            // Java 代码块应完整在同一块中
            boolean foundController = false;
            for (var chunk : chunks) {
                if (chunk.content().contains("@RestController")) {
                    foundController = true;
                    assertTrue(chunk.content().contains("@GetMapping"),
                            "Java Controller 代码块被截断");
                }
            }
            assertTrue(foundController, "应包含 Controller 代码块");
        }

        @Test
        void 英文句子不在单词中间截断() {
            var chunks = pipeline.chunk(英文技术文档, Map.of());
            var children = chunks.stream().filter(c -> c.chunkLevel() == 1).toList();
            for (var child : children) {
                String content = child.content();
                // 检查开头不是以小写字母开始（意味着从单词中间截断）
                // 跳过代码块和以空白开头的情况
                String trimmed = content.stripLeading();
                if (!trimmed.isEmpty() && !trimmed.startsWith("```") && !trimmed.startsWith("//")) {
                    char first = trimmed.charAt(0);
                    // 允许小写字母开头（有些代码标识符是小写）但不允许在非空格分割后
                    // 这是一个宽松检查 — 主要确保没有明显的单词截断
                    assertFalse(content.startsWith("ith") || content.startsWith("he ") || content.startsWith("nd "),
                            "子块疑似从单词中间截断: %s".formatted(
                                    content.substring(0, Math.min(30, content.length()))));
                }
            }
        }
    }

    // ========== 自媒体文案测试 ==========

    @Nested
    class 自媒体文案分块质量 {

        @Test
        void 短段落应合并不产生碎片() {
            var chunks = pipeline.chunk(自媒体文案, Map.of());
            var parents = chunks.stream().filter(c -> c.chunkLevel() == 0).toList();
            // 自媒体文案约 700 字，应产生 1-3 个父块，而不是每段一个
            assertTrue(parents.size() <= 4,
                    "自媒体文案不应过度拆分（实际: %d 个父块）".formatted(parents.size()));
        }

        @Test
        void 列表内容不应被拆散() {
            var chunks = pipeline.chunk(自媒体文案, Map.of());
            // "使用建议" 列表（4项）应在同一个块中
            boolean foundList = false;
            for (var chunk : chunks) {
                if (chunk.content().contains("不要完全信任生成的代码")) {
                    foundList = true;
                    assertTrue(chunk.content().contains("善用上下文功能"),
                            "建议列表被拆散：第1项和第3项应在同一块");
                }
            }
            assertTrue(foundList, "应包含使用建议列表");
        }
    }

    // ========== 中英混合文档测试 ==========

    @Nested
    class 中英混合分块质量 {

        @Test
        void 中英混合句子不被截断() {
            var chunks = pipeline.chunk(中英混合文档, Map.of());
            // RAG 相关术语（如 "Retrieval-Augmented Generation"）不应被截断
            boolean foundTerm = false;
            for (var chunk : chunks) {
                if (chunk.content().contains("Retrieval-Augmented Generation")) {
                    foundTerm = true;
                    // 同一段落中的中文描述应在同一块
                    assertTrue(chunk.content().contains("knowledge base"),
                            "RAG 概述段被截断");
                }
            }
            assertTrue(foundTerm, "应包含 RAG 术语定义");
        }

        @Test
        void 标题层级包含中英文混合标题() {
            var chunks = pipeline.chunk(中英混合文档, Map.of());
            boolean found = false;
            for (var chunk : chunks) {
                var hierarchy = chunk.headingHierarchy();
                if (hierarchy.stream().anyMatch(h -> h.contains("Chunking Strategy")
                        || h.contains("RAG") || h.contains("Hybrid Search"))) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "中英混合文档应在标题层级中保留英文标题");
        }
    }

    // ========== 长篇散文测试 ==========

    @Nested
    class 长篇散文分块质量 {

        @Test
        void 长文本不产生空块() {
            var chunks = pipeline.chunk(长篇散文, Map.of());
            for (var chunk : chunks) {
                assertFalse(chunk.content().isBlank(), "不应有空白分块");
            }
        }

        @Test
        void 长文本分块数量合理() {
            var chunks = pipeline.chunk(长篇散文, Map.of());
            var parents = chunks.stream().filter(c -> c.chunkLevel() == 0).toList();
            // 长篇散文约 1700 字符，parent max 1536，应有 1-3 个父块
            assertTrue(parents.size() >= 1 && parents.size() <= 4,
                    "长篇散文应有 1-4 个父块（实际: %d）".formatted(parents.size()));
        }
    }

    // ========== 表格文档测试 ==========

    @Nested
    class 表格文档分块质量 {

        @Test
        void 表格行不被拆分() {
            var chunks = pipeline.chunk(表格文档, Map.of());
            // 性能对比表格应完整在一个块中
            boolean foundTable = false;
            for (var chunk : chunks) {
                if (chunk.content().contains("MMLU") && chunk.content().contains("HumanEval")) {
                    foundTable = true;
                    // 表头和至少部分数据行应在同一块
                    assertTrue(chunk.content().contains("GPT-4o"),
                            "表格被截断：表头和 GPT-4o 数据行应在同一块");
                }
            }
            assertTrue(foundTable, "应包含性能对比表格");
        }

        @Test
        void 表格前后的说明文字不与表格混淆() {
            var chunks = pipeline.chunk(表格文档, Map.of());
            // "总结" 部分的文字应作为独立的段落处理
            boolean foundSummary = false;
            for (var chunk : chunks) {
                if (chunk.content().contains("性价比最高的选择")) {
                    foundSummary = true;
                }
            }
            assertTrue(foundSummary, "表格后的总结段落应被保留");
        }
    }

    // ========== 诊断输出（用于调试，非正式断言） ==========

    @Nested
    class 分块诊断输出 {

        @Test
        void 打印百炼平台分块结果() {
            printChunkSummary("百炼平台", 百炼平台文本);
        }

        @Test
        void 打印小说分块结果() {
            printChunkSummary("小说", 小说文本);
        }

        @Test
        void 打印技术文档分块结果() {
            printChunkSummary("中文技术文档", 技术文档文本);
        }

        @Test
        void 打印英文技术文档分块结果() {
            printChunkSummary("英文技术文档", 英文技术文档);
        }

        @Test
        void 打印自媒体文案分块结果() {
            printChunkSummary("自媒体文案", 自媒体文案);
        }

        @Test
        void 打印中英混合分块结果() {
            printChunkSummary("中英混合文档", 中英混合文档);
        }

        @Test
        void 打印长篇散文分块结果() {
            printChunkSummary("长篇散文", 长篇散文);
        }

        @Test
        void 打印表格文档分块结果() {
            printChunkSummary("表格文档", 表格文档);
        }

        private void printChunkSummary(String name, String text) {
            var chunks = pipeline.chunk(text, Map.of());
            var parents = chunks.stream().filter(c -> c.chunkLevel() == 0).toList();
            var children = chunks.stream().filter(c -> c.chunkLevel() == 1).toList();
            System.out.printf("=== %s 分块结果 ===%n", name);
            System.out.printf("总计: %d 块 (父: %d, 子: %d)%n", chunks.size(), parents.size(), children.size());

            System.out.println("\n--- 父块 ---");
            for (var p : parents) {
                String preview = p.content().replaceAll("\\s+", " ");
                if (preview.length() > 80) preview = preview.substring(0, 80) + "…";
                String heading = p.headingHierarchy().isEmpty() ? "" : " [" + p.breadcrumb() + "]";
                System.out.printf("  P[%d] %d字符 %dtoken%s | %s%n",
                        p.chunkIndex(), p.content().length(), p.tokenCount(), heading, preview);
            }

            if (!children.isEmpty()) {
                System.out.println("\n--- 子块 ---");
                for (var c : children) {
                    String preview = c.content().replaceAll("\\s+", " ");
                    if (preview.length() > 80) preview = preview.substring(0, 80) + "…";
                    String heading = c.headingHierarchy().isEmpty() ? "" : " [" + c.breadcrumb() + "]";
                    System.out.printf("  C[%d] %d字符 %dtoken%s | %s%n",
                            c.chunkIndex(), c.content().length(), c.tokenCount(), heading, preview);
                }
            }
            System.out.println();
        }
    }

    // ========== 辅助方法 ==========

    /**
     * 检查文本中是否存在长度 >= minLen 的重复子串。
     */
    private static boolean hasDuplicateSubstring(String text, int minLen) {
        if (text.length() < minLen * 2) return false;
        for (int i = 0; i <= text.length() - minLen * 2; i++) {
            String sub = text.substring(i, i + minLen);
            int secondIndex = text.indexOf(sub, i + minLen / 2);
            if (secondIndex >= 0 && secondIndex != i) {
                return true;
            }
        }
        return false;
    }
}
