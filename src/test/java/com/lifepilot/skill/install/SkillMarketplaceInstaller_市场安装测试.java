package com.lifepilot.skill.install;

import com.lifepilot.marketplace.clawhub.ClawHubClient;
import com.lifepilot.marketplace.clawhub.ClawHubIndexSource;
import com.lifepilot.marketplace.clawhub.ClawHubZipExtractor;
import com.lifepilot.marketplace.index.IndexManager;
import com.lifepilot.marketplace.model.ExtensionPackage;
import com.lifepilot.marketplace.model.ExtensionType;
import com.lifepilot.skill.MarkdownSkillParser;
import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.validation.SkillBodyValidator;
import com.lifepilot.skill.validation.SkillDescriptionValidator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SkillMarketplaceInstaller 市场安装测试。
 *
 * <p>覆盖：空 ID / 索引未命中 / 非 SKILL 类型 / 索引单文件下载成功 /
 * ClawHub 未启用报错 / ClawHub 下载 + 辅助目录复制。</p>
 *
 * <p>索引通道使用嵌入式 {@link HttpServer} 模拟远程 SKILL.md；
 * ClawHub 通道则 mock {@link ClawHubClient} 返回 zip 字节，
 * 真实走 {@link ClawHubZipExtractor} 解压路径。installer / parser / validator
 * 均用真实实现以确保端到端流水线可被串起来。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
class SkillMarketplaceInstaller_市场安装测试 {

    /** 合法 SKILL.md 文本，通过 description 与 body 校验。 */
    private static final String VALID_SKILL_MD = """
            ---
            name: market-demo
            description: 当需要测试市场安装时使用。关键词 market
            version: 1.0.0
            ---
            ## 适用场景
            - market test
            ## 不适用场景
            - not-market
            ## 工作流
            1. do
            """;

    @TempDir
    Path skillsRoot;

    IndexManager indexManager;
    SkillInstaller installer;
    SkillConfigProperties config;
    SkillInstallationRepository repository;

    @BeforeEach
    void setup() {
        repository = mock(SkillInstallationRepository.class);
        installer = new SkillInstaller(
                new MarkdownSkillParser(),
                new SkillDescriptionValidator(),
                new SkillBodyValidator(),
                repository);
        indexManager = mock(IndexManager.class);
        config = new SkillConfigProperties();
        config.setDirectory(skillsRoot.toString());
    }

    // ────────────────────────────────────────────────────────
    //  入参与索引校验
    // ────────────────────────────────────────────────────────

    @Test
    void 空marketplaceId应拒绝() {
        var service = newInstaller(null, null);
        assertThatThrownBy(() -> service.installById(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("marketplaceId");
        assertThatThrownBy(() -> service.installById(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 索引未命中应拒绝() {
        when(indexManager.getPackage("ghost")).thenReturn(Optional.empty());
        var service = newInstaller(null, null);

        assertThatThrownBy(() -> service.installById("ghost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未找到");
    }

    @Test
    void 非SKILL类型应拒绝() {
        when(indexManager.getPackage("foo"))
                .thenReturn(Optional.of(pkg("foo", ExtensionType.AGENT, "https://x", "y.md", List.of())));
        var service = newInstaller(null, null);

        assertThatThrownBy(() -> service.installById("foo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKILL");
    }

    // ────────────────────────────────────────────────────────
    //  自有索引通道
    // ────────────────────────────────────────────────────────

    @Test
    void 自有索引包应下载并走installer() throws Exception {
        try (var source = LocalSource.start("/skill/SKILL.md", VALID_SKILL_MD)) {
            when(indexManager.getPackage("demo"))
                    .thenReturn(Optional.of(pkg("demo", ExtensionType.SKILL,
                            source.baseUrl() + "/skill", "SKILL.md", List.of())));
            var service = newInstaller(null, null);

            var install = service.installById("demo");

            assertThat(install.sourceType()).isEqualTo(SkillSourceType.MARKETPLACE);
            assertThat(install.marketplaceId()).isEqualTo("demo");
            assertThat(install.name()).isEqualTo("market-demo");
            assertThat(install.sourceUri()).isEqualTo(source.baseUrl() + "/skill");
            assertThat(Files.readString(skillsRoot.resolve("market-demo/SKILL.md")))
                    .isEqualTo(VALID_SKILL_MD);
        }
    }

    @Test
    void 自有索引包缺repoUrl应拒绝() {
        when(indexManager.getPackage("bad"))
                .thenReturn(Optional.of(pkg("bad", ExtensionType.SKILL, "", "SKILL.md", List.of())));
        var service = newInstaller(null, null);

        assertThatThrownBy(() -> service.installById("bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repoUrl");
    }

    // ────────────────────────────────────────────────────────
    //  ClawHub 通道
    // ────────────────────────────────────────────────────────

    @Test
    void ClawHub包但组件未启用应抛IllegalState() {
        when(indexManager.getPackage("claw-x"))
                .thenReturn(Optional.of(pkg("claw-x", ExtensionType.SKILL,
                        "https://clawhub.ai/claw-x", "SKILL.md",
                        List.of(ClawHubIndexSource.CLAWHUB_TAG))));
        var service = newInstaller(null, null);

        assertThatThrownBy(() -> service.installById("claw-x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ClawHub");
    }

    @Test
    void ClawHub下载应解压安装并复制辅助目录() throws Exception {
        byte[] zipBytes = buildZip(
                "SKILL.md", VALID_SKILL_MD,
                "references/api.md", "# API",
                "scripts/setup.sh", "#!/bin/bash\necho hi");

        var clawHubClient = mock(ClawHubClient.class);
        when(clawHubClient.downloadZip("claw-y")).thenReturn(zipBytes);

        when(indexManager.getPackage("claw-y"))
                .thenReturn(Optional.of(pkg("claw-y", ExtensionType.SKILL,
                        "https://clawhub.ai/claw-y", "SKILL.md",
                        List.of(ClawHubIndexSource.CLAWHUB_TAG))));

        var service = newInstaller(clawHubClient, new ClawHubZipExtractor());

        var install = service.installById("claw-y");

        assertThat(install.sourceType()).isEqualTo(SkillSourceType.MARKETPLACE);
        assertThat(install.marketplaceId()).isEqualTo("claw-y");
        assertThat(install.name()).isEqualTo("market-demo");
        assertThat(Files.exists(skillsRoot.resolve("market-demo/SKILL.md"))).isTrue();
        assertThat(Files.readString(skillsRoot.resolve("market-demo/references/api.md")))
                .isEqualTo("# API");
        assertThat(Files.exists(skillsRoot.resolve("market-demo/scripts/setup.sh"))).isTrue();
    }

    @Test
    void ClawHub包缺SKILL_md应拒绝() throws Exception {
        byte[] zipBytes = buildZip("README.md", "no skill here");

        var clawHubClient = mock(ClawHubClient.class);
        when(clawHubClient.downloadZip("claw-z")).thenReturn(zipBytes);

        when(indexManager.getPackage("claw-z"))
                .thenReturn(Optional.of(pkg("claw-z", ExtensionType.SKILL,
                        "https://clawhub.ai/claw-z", "SKILL.md",
                        List.of(ClawHubIndexSource.CLAWHUB_TAG))));

        var service = newInstaller(clawHubClient, new ClawHubZipExtractor());

        assertThatThrownBy(() -> service.installById("claw-z"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKILL.md");
    }

    // ────────────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────────────

    private SkillMarketplaceInstaller newInstaller(ClawHubClient clawHubClient,
                                                    ClawHubZipExtractor clawHubZipExtractor) {
        return new SkillMarketplaceInstaller(
                installer,
                indexManager,
                config,
                RestClient.builder(),
                clawHubClient,
                clawHubZipExtractor);
    }

    private static ExtensionPackage pkg(String id,
                                         ExtensionType type,
                                         String repoUrl,
                                         String filePath,
                                         List<String> tags) {
        return ExtensionPackage.builder()
                .id(id)
                .name(id)
                .type(type)
                .version("1.0.0")
                .author("test")
                .description("test package")
                .repoUrl(repoUrl)
                .filePath(filePath)
                .tags(tags)
                .requirements(List.of())
                .minLifepilotVersion("1.0.0")
                .createdAt("2026-04-24T00:00:00Z")
                .updatedAt("2026-04-24T00:00:00Z")
                .downloads(0)
                .verified(true)
                .build();
    }

    /** 把一串 (path, content) 成对打成内存 zip，供 ClawHub 测试注入。 */
    private static byte[] buildZip(String... pathsAndContents) throws IOException {
        if (pathsAndContents.length % 2 != 0) {
            throw new IllegalArgumentException("pathsAndContents 必须成对");
        }
        var baos = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(baos)) {
            for (int i = 0; i < pathsAndContents.length; i += 2) {
                zos.putNextEntry(new ZipEntry(pathsAndContents[i]));
                zos.write(pathsAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    /** 嵌入式 HTTP Server，模拟远程索引 SKILL.md 文件下载。 */
    private record LocalSource(HttpServer server, String baseUrl) implements AutoCloseable {

        static LocalSource start(String path, String body) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            server.createContext(path, exchange -> write(exchange, bytes));
            server.start();
            return new LocalSource(server,
                    "http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static void write(HttpExchange exchange, byte[] body) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "text/markdown; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            } finally {
                exchange.close();
            }
        }
    }

    @AfterEach
    void teardown() {
        // TempDir 会自动清理，无需额外操作。
    }
}
