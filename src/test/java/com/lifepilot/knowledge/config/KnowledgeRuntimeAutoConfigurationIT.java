package com.lifepilot.knowledge.config;

import com.lifepilot.LifePilotApplication;
import com.lifepilot.knowledge.enricher.ChunkContextEnricher;
import com.lifepilot.knowledge.extract.KnowledgeExtractionPipeline;
import com.lifepilot.knowledge.index.VectorIndexer;
import com.lifepilot.knowledge.ingest.DocumentIngester;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库增强链装配集成测试。
 *
 * <p>使用接近真实启动路径的 Spring 上下文，验证增强 Bean 与运行时编排 Bean
 * 在自动配置排序后都能正确装配，避免再次出现增强链整体降级的回归。</p>
 *
 * @author zsg
 * @since 2026-03-27
 */
@SpringBootTest(
        classes = LifePilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "lifepilot.llm.enabled=true",
                "lifepilot.memory.enabled=true",
                "lifepilot.knowledge.enabled=true",
                "lifepilot.tool.enabled=true",
                "lifepilot.agent.enabled=false",
                "lifepilot.skills.enabled=false",
                "lifepilot.gateway.enabled=false",
                "lifepilot.media.enabled=false",
                "lifepilot.meta.enabled=false",
                "lifepilot.workflow.enabled=false",
                "lifepilot.a2a.enabled=false",
                "lifepilot.mcp.enabled=false",
                "lifepilot.marketplace.enabled=false",
                "lifepilot.notification.enabled=false"
        }
)
@ActiveProfiles("test")
class KnowledgeRuntimeAutoConfigurationIT {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "lifepilot-knowledge-auto-" + DB_ID + ".db")
                .toString()
                .replace("\\", "/");
        var vecDbPath = Path.of(tmpDir, "lifepilot-knowledge-auto-vec-" + DB_ID + ".db")
                .toString()
                .replace("\\", "/");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.store.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    private VectorIndexer vectorIndexer;

    @Autowired
    private ChunkContextEnricher chunkContextEnricher;

    @Autowired
    private KnowledgeExtractionPipeline knowledgeExtractionPipeline;

    @Autowired
    private DocumentIngester documentIngester;

    @Test
    void 增强链Bean已全部注册且导入管线已接入() {
        assertThat(vectorIndexer).isNotNull();
        assertThat(chunkContextEnricher).isNotNull();
        assertThat(knowledgeExtractionPipeline).isNotNull();
        assertThat(readField(documentIngester, "vectorIndexer")).isSameAs(vectorIndexer);
        assertThat(readField(documentIngester, "contextEnricher")).isSameAs(chunkContextEnricher);
        assertThat(readField(documentIngester, "extractionPipeline")).isSameAs(knowledgeExtractionPipeline);
    }

    private Object readField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("读取字段失败: " + fieldName, e);
        }
    }
}
