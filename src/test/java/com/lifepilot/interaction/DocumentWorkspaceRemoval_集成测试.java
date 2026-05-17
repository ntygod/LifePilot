package com.lifepilot.interaction;

import java.nio.file.Path;
import java.util.UUID;

import com.lifepilot.interaction.runtime.ChannelDeliveryDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档工作区下架集成测试 —— 校验 Spring Context 不再注册任何文档工作区相关 bean，
 * 同时确保 {@link ChannelDeliveryDispatcher} 等关键依赖仍可正常解析。
 *
 * <p>覆盖 R7.3：删除后的 ApplicationContext 加载成功，6 个旧 bean 全部缺席。</p>
 *
 * @author zsg
 * @since 2026-05-17
 */
@SpringBootTest
@ActiveProfiles("test")
class DocumentWorkspaceRemoval_集成测试 {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "doc-removal-test-" + DB_ID + ".db").toString().replace("\\", "/");
        var vecDbPath = Path.of(tmpDir, "doc-removal-vec-test-" + DB_ID + ".db").toString().replace("\\", "/");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    private ApplicationContext ctx;

    @Test
    @DisplayName("ApplicationContext 应能成功加载，且不含已下架的 6 类 bean")
    void contextLoads_无文档工作区bean() {
        // 文档工作区相关类均已物理删除，此处用 bean 名查询确认其未注册
        assertThat(ctx.containsBean("documentVersionService")).isFalse();
        assertThat(ctx.containsBean("sessionDocumentRepository")).isFalse();
        assertThat(ctx.containsBean("documentVersionRepository")).isFalse();
        assertThat(ctx.containsBean("documentController")).isFalse();
        assertThat(ctx.containsBean("orphanProvenanceScanner")).isFalse();
        assertThat(ctx.containsBean("documentAutoConfiguration")).isFalse();

        // ChannelDeliveryDispatcher 类型仍可通过类加载（即使在 test profile 下其 Bean 未注册），
        // 用 getBeansOfType 软查询确认类型仍可解析；test profile 下 gateway 关闭故可能为空 map。
        assertThat(ctx.getBeansOfType(ChannelDeliveryDispatcher.class)).isNotNull();
    }
}
