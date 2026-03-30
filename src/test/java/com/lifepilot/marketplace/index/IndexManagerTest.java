package com.lifepilot.marketplace.index;

import com.lifepilot.marketplace.install.InstalledExtensionRepository;
import com.lifepilot.marketplace.model.ExtensionPackage;
import com.lifepilot.marketplace.model.ExtensionType;
import com.lifepilot.marketplace.model.InstalledExtension;
import com.lifepilot.marketplace.version.VersionResolver;
import com.lifepilot.marketplace.config.MarketplaceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * IndexManager 单元测试 — 验证索引获取、缓存、合并和已安装状态丰富。
 *
 * @author zsg
 * @since 2026-03-08
 */
@ExtendWith(MockitoExtension.class)
class IndexManagerTest {

    @Mock
    private IndexRepository indexRepository;

    @Mock
    private InstalledExtensionRepository installedExtensionRepository;

    @Mock
    private RestClient restClient;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    private MarketplaceProperties properties;
    private VersionResolver versionResolver;
    private IndexManager indexManager;

    @BeforeEach
    void setUp() {
        properties = new MarketplaceProperties();
        properties.setIndexSources(List.of("https://example.com/index.json"));
        properties.setCacheTtlHours(24);

        versionResolver = new VersionResolver();

        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.build()).thenReturn(restClient);

        indexManager = new IndexManager(properties, indexRepository, installedExtensionRepository,
                versionResolver, builder);
    }

    // ── refreshAll ─────────────────────────────────────────────

    @Nested
    class RefreshAll {

        @Test
        void 成功获取索引_持久化到缓存() {
            mockGetRequest(SAMPLE_INDEX_JSON);

            int count = indexManager.refreshAll();

            assertThat(count).isEqualTo(1);
            verify(indexRepository).save("https://example.com/index.json", SAMPLE_INDEX_JSON);
        }

        @Test
        void 多个索引源_逐个获取() {
            properties.setIndexSources(List.of(
                    "https://source1.com/index.json",
                    "https://source2.com/index.json"
            ));

            mockGetRequestForUrls(java.util.Map.of(
                    "https://source1.com/index.json", SAMPLE_INDEX_JSON,
                    "https://source2.com/index.json", SAMPLE_INDEX_JSON_AGENT
            ));

            int count = indexManager.refreshAll();

            assertThat(count).isEqualTo(2);
            verify(indexRepository).save("https://source1.com/index.json", SAMPLE_INDEX_JSON);
            verify(indexRepository).save("https://source2.com/index.json", SAMPLE_INDEX_JSON_AGENT);
        }

        @Test
        void HTTP请求失败_记录警告不中断其他源() {
            properties.setIndexSources(List.of(
                    "https://fail.com/index.json",
                    "https://ok.com/index.json"
            ));

            mockGetRequestForUrlWithError("https://fail.com/index.json",
                    "https://ok.com/index.json", SAMPLE_INDEX_JSON);

            int count = indexManager.refreshAll();

            assertThat(count).isEqualTo(1);
            verify(indexRepository, never()).save(eq("https://fail.com/index.json"), anyString());
            verify(indexRepository).save("https://ok.com/index.json", SAMPLE_INDEX_JSON);
        }

        @Test
        void rawGithub失败_自动回退到JsDelivr() {
            properties.setIndexSources(List.of("https://raw.githubusercontent.com/test/index/main/index.json"));

            mockGetRequestForUrls(java.util.Map.of(
                    "https://raw.githubusercontent.com/test/index/main/index.json",
                    (Object) new ResourceAccessException("Connection reset"),
                    "https://cdn.jsdelivr.net/gh/test/index@main/index.json",
                    (Object) SAMPLE_INDEX_JSON
            ));

            int count = indexManager.refreshAll();

            assertThat(count).isEqualTo(1);
            verify(indexRepository).save("https://raw.githubusercontent.com/test/index/main/index.json", SAMPLE_INDEX_JSON);
        }

        @Test
        void 返回空内容_跳过该源() {
            mockGetRequest("");

            int count = indexManager.refreshAll();

            assertThat(count).isEqualTo(0);
            verify(indexRepository, never()).save(anyString(), anyString());
        }

        @Test
        void 返回null_跳过该源() {
            mockGetRequest(null);

            int count = indexManager.refreshAll();

            assertThat(count).isEqualTo(0);
            verify(indexRepository, never()).save(anyString(), anyString());
        }

        @Test
        void 返回非法JSON_跳过该源() {
            mockGetRequest("not valid json");

            int count = indexManager.refreshAll();

            assertThat(count).isEqualTo(0);
            verify(indexRepository, never()).save(anyString(), anyString());
        }

        @Test
        void 无索引源配置_返回0() {
            properties.setIndexSources(List.of());

            int count = indexManager.refreshAll();

            assertThat(count).isEqualTo(0);
            verifyNoInteractions(indexRepository);
        }
    }

    // ── getPackages ────────────────────────────────────────────

    @Nested
    class GetPackages {

        @Test
        void 缓存未过期_直接返回缓存数据() {
            String freshFetchedAt = Instant.now().minus(1, ChronoUnit.HOURS).toString();
            when(indexRepository.findAll()).thenReturn(List.of(
                    cacheEntry("https://example.com/index.json", SAMPLE_INDEX_JSON, freshFetchedAt)
            ));
            when(installedExtensionRepository.findAll()).thenReturn(List.of());

            List<ExtensionPackage> packages = indexManager.getPackages();

            assertThat(packages).hasSize(1);
            assertThat(packages.getFirst().id()).isEqualTo("weekly-planner");
            assertThat(packages.getFirst().type()).isEqualTo(ExtensionType.SKILL);
            // 未触发刷新
            verify(restClient, never()).get();
        }

        @Test
        void 解析type字段_支持三种扩展类型() {
            String freshFetchedAt = Instant.now().toString();
            when(indexRepository.findAll()).thenReturn(List.of(
                    cacheEntry("https://example.com/index.json", SAMPLE_INDEX_JSON_MIXED_TYPES, freshFetchedAt)
            ));
            when(installedExtensionRepository.findAll()).thenReturn(List.of());

            List<ExtensionPackage> packages = indexManager.getPackages();

            assertThat(packages).hasSize(3);
            assertThat(packages.stream().map(ExtensionPackage::type).toList())
                    .containsExactlyInAnyOrder(ExtensionType.SKILL, ExtensionType.AGENT, ExtensionType.WORKFLOW);
        }

        @Test
        void 解析requirements字段() {
            String freshFetchedAt = Instant.now().toString();
            when(indexRepository.findAll()).thenReturn(List.of(
                    cacheEntry("https://example.com/index.json", SAMPLE_INDEX_JSON, freshFetchedAt)
            ));
            when(installedExtensionRepository.findAll()).thenReturn(List.of());

            List<ExtensionPackage> packages = indexManager.getPackages();

            assertThat(packages.getFirst().requirements())
                    .containsExactly("内置 Todo 和 Schedule 功能（ZhiWei 默认包含）");
        }

        @Test
        void 缓存过期_触发刷新后返回() {
            String staleFetchedAt = Instant.now().minus(25, ChronoUnit.HOURS).toString();
            when(indexRepository.findAll())
                    .thenReturn(List.of(cacheEntry("https://example.com/index.json", SAMPLE_INDEX_JSON, staleFetchedAt)))
                    .thenReturn(List.of(cacheEntry("https://example.com/index.json", SAMPLE_INDEX_JSON,
                            Instant.now().toString())));
            when(installedExtensionRepository.findAll()).thenReturn(List.of());

            mockGetRequest(SAMPLE_INDEX_JSON);

            List<ExtensionPackage> packages = indexManager.getPackages();

            assertThat(packages).hasSize(1);
            verify(indexRepository).save(anyString(), anyString());
        }

        @Test
        void 无缓存_触发刷新() {
            when(indexRepository.findAll())
                    .thenReturn(List.of())
                    .thenReturn(List.of(cacheEntry("https://example.com/index.json", SAMPLE_INDEX_JSON,
                            Instant.now().toString())));
            when(installedExtensionRepository.findAll()).thenReturn(List.of());

            mockGetRequest(SAMPLE_INDEX_JSON);

            List<ExtensionPackage> packages = indexManager.getPackages();

            assertThat(packages).hasSize(1);
            verify(indexRepository).save(anyString(), anyString());
        }

        @Test
        void 多源合并_相同id保留版本更高的() {
            String freshFetchedAt = Instant.now().toString();
            when(indexRepository.findAll()).thenReturn(List.of(
                    cacheEntry("https://source1.com/index.json", SAMPLE_INDEX_JSON, freshFetchedAt),
                    cacheEntry("https://source2.com/index.json", SAMPLE_INDEX_JSON_HIGHER_VERSION, freshFetchedAt)
            ));
            when(installedExtensionRepository.findAll()).thenReturn(List.of());

            List<ExtensionPackage> packages = indexManager.getPackages();

            assertThat(packages).hasSize(1);
            assertThat(packages.getFirst().id()).isEqualTo("weekly-planner");
            assertThat(packages.getFirst().version()).isEqualTo("2.0.0");
        }

        @Test
        void 已安装扩展_附加安装状态() {
            String freshFetchedAt = Instant.now().toString();
            when(indexRepository.findAll()).thenReturn(List.of(
                    cacheEntry("https://example.com/index.json", SAMPLE_INDEX_JSON, freshFetchedAt)
            ));
            when(installedExtensionRepository.findAll()).thenReturn(List.of(
                    installedExtension("weekly-planner", ExtensionType.SKILL, "1.0.0")
            ));

            List<ExtensionPackage> packages = indexManager.getPackages();

            assertThat(packages).hasSize(1);
            assertThat(packages.getFirst().installed()).isTrue();
            assertThat(packages.getFirst().installedVersion()).isEqualTo("1.0.0");
        }

        @Test
        void 未安装扩展_installed为false() {
            String freshFetchedAt = Instant.now().toString();
            when(indexRepository.findAll()).thenReturn(List.of(
                    cacheEntry("https://example.com/index.json", SAMPLE_INDEX_JSON, freshFetchedAt)
            ));
            when(installedExtensionRepository.findAll()).thenReturn(List.of());

            List<ExtensionPackage> packages = indexManager.getPackages();

            assertThat(packages).hasSize(1);
            assertThat(packages.getFirst().installed()).isFalse();
            assertThat(packages.getFirst().installedVersion()).isNull();
        }

        @Test
        void 无type字段_默认解析为null不报错() {
            String freshFetchedAt = Instant.now().toString();
            when(indexRepository.findAll()).thenReturn(List.of(
                    cacheEntry("https://example.com/index.json", SAMPLE_INDEX_JSON_NO_TYPE, freshFetchedAt)
            ));
            when(installedExtensionRepository.findAll()).thenReturn(List.of());

            List<ExtensionPackage> packages = indexManager.getPackages();

            // JSON 中无 type 字段时，Jackson 反序列化为 null
            assertThat(packages).hasSize(1);
            assertThat(packages.getFirst().id()).isEqualTo("legacy-skill");
        }
    }

    // ── getPackage ─────────────────────────────────────────────

    @Nested
    class GetPackage {

        @Test
        void 存在的id_返回对应包() {
            String freshFetchedAt = Instant.now().toString();
            when(indexRepository.findAll()).thenReturn(List.of(
                    cacheEntry("https://example.com/index.json", SAMPLE_INDEX_JSON, freshFetchedAt)
            ));
            when(installedExtensionRepository.findAll()).thenReturn(List.of());

            Optional<ExtensionPackage> result = indexManager.getPackage("weekly-planner");

            assertThat(result).isPresent();
            assertThat(result.get().id()).isEqualTo("weekly-planner");
            assertThat(result.get().type()).isEqualTo(ExtensionType.SKILL);
        }

        @Test
        void 不存在的id_返回empty() {
            String freshFetchedAt = Instant.now().toString();
            when(indexRepository.findAll()).thenReturn(List.of(
                    cacheEntry("https://example.com/index.json", SAMPLE_INDEX_JSON, freshFetchedAt)
            ));
            when(installedExtensionRepository.findAll()).thenReturn(List.of());

            Optional<ExtensionPackage> result = indexManager.getPackage("non-existent");

            assertThat(result).isEmpty();
        }
    }

    // ── 测试数据 ───────────────────────────────────────────────

    private static final String SAMPLE_INDEX_JSON = """
            [
              {
                "id": "weekly-planner",
                "name": "周计划助手",
                "type": "SKILL",
                "version": "1.2.0",
                "author": "community",
                "description": "基于日程和习惯数据生成个性化周计划",
                "repoUrl": "https://github.com/lifepilot-extensions/weekly-planner",
                "filePath": "SKILL.md",
                "tags": ["productivity", "planning"],
                "requirements": ["内置 Todo 和 Schedule 功能（ZhiWei 默认包含）"],
                "minLifepilotVersion": "1.0.0",
                "createdAt": "2026-02-01T00:00:00Z",
                "updatedAt": "2026-03-01T00:00:00Z",
                "downloads": 1200,
                "verified": true
              }
            ]
            """;

    private static final String SAMPLE_INDEX_JSON_AGENT = """
            [
              {
                "id": "research-agent",
                "name": "调研助手",
                "type": "AGENT",
                "version": "1.0.0",
                "author": "community",
                "description": "自动化调研和信息收集",
                "repoUrl": "https://github.com/lifepilot-extensions/research-agent",
                "filePath": "AGENT.md",
                "tags": ["research", "agent"],
                "requirements": [],
                "minLifepilotVersion": "1.0.0",
                "createdAt": "2026-03-01T00:00:00Z",
                "updatedAt": "2026-03-05T00:00:00Z",
                "downloads": 500,
                "verified": true
              }
            ]
            """;

    private static final String SAMPLE_INDEX_JSON_HIGHER_VERSION = """
            [
              {
                "id": "weekly-planner",
                "name": "周计划助手",
                "type": "SKILL",
                "version": "2.0.0",
                "author": "community",
                "description": "基于日程和习惯数据生成个性化周计划（升级版）",
                "repoUrl": "https://github.com/lifepilot-extensions/weekly-planner",
                "filePath": "SKILL.md",
                "tags": ["productivity", "planning"],
                "requirements": ["内置 Todo 和 Schedule 功能（ZhiWei 默认包含）"],
                "minLifepilotVersion": "1.0.0",
                "createdAt": "2026-02-01T00:00:00Z",
                "updatedAt": "2026-03-05T00:00:00Z",
                "downloads": 2000,
                "verified": true
              }
            ]
            """;

    private static final String SAMPLE_INDEX_JSON_MIXED_TYPES = """
            [
              {
                "id": "weekly-planner",
                "name": "周计划助手",
                "type": "SKILL",
                "version": "1.2.0",
                "author": "community",
                "description": "基于日程和习惯数据生成个性化周计划",
                "repoUrl": "https://github.com/lifepilot-extensions/weekly-planner",
                "filePath": "SKILL.md",
                "tags": ["productivity"],
                "requirements": [],
                "minLifepilotVersion": "1.0.0",
                "createdAt": "2026-02-01T00:00:00Z",
                "updatedAt": "2026-03-01T00:00:00Z",
                "downloads": 1200,
                "verified": true
              },
              {
                "id": "research-agent",
                "name": "调研助手",
                "type": "AGENT",
                "version": "1.0.0",
                "author": "community",
                "description": "自动化调研和信息收集",
                "repoUrl": "https://github.com/lifepilot-extensions/research-agent",
                "filePath": "AGENT.md",
                "tags": ["research"],
                "requirements": [],
                "minLifepilotVersion": "1.0.0",
                "createdAt": "2026-03-01T00:00:00Z",
                "updatedAt": "2026-03-05T00:00:00Z",
                "downloads": 500,
                "verified": true
              },
              {
                "id": "morning-routine",
                "name": "晨间流程",
                "type": "WORKFLOW",
                "version": "0.5.0",
                "author": "community",
                "description": "自动化晨间日程安排",
                "repoUrl": "https://github.com/lifepilot-extensions/morning-routine",
                "filePath": "workflow.yml",
                "tags": ["workflow", "routine"],
                "requirements": ["内置 Schedule 功能"],
                "minLifepilotVersion": "1.0.0",
                "createdAt": "2026-03-01T00:00:00Z",
                "updatedAt": "2026-03-05T00:00:00Z",
                "downloads": 300,
                "verified": false
              }
            ]
            """;

    private static final String SAMPLE_INDEX_JSON_NO_TYPE = """
            [
              {
                "id": "legacy-skill",
                "name": "旧版 Skill",
                "version": "1.0.0",
                "author": "zsg",
                "description": "无 type 字段的旧格式",
                "repoUrl": "https://github.com/example/legacy",
                "filePath": "skill.yaml",
                "tags": [],
                "minLifepilotVersion": "0.1.0",
                "createdAt": "2026-01-01T00:00:00Z",
                "updatedAt": "2026-01-01T00:00:00Z",
                "downloads": 10,
                "verified": false
              }
            ]
            """;

    // ── 辅助方法 ───────────────────────────────────────────────

    /**
     * Mock RestClient GET 请求链（不区分 URL）。
     */
    @SuppressWarnings("unchecked")
    private void mockGetRequest(String responseBody) {
        var headerUriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        var headerSpec = mock(RestClient.RequestHeadersSpec.class);

        when(restClient.get()).thenReturn(headerUriSpec);
        when(headerUriSpec.uri(anyString())).thenReturn(headerSpec);
        when(headerSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(responseBody);
    }

    /**
     * Mock RestClient GET 请求链（按 URL 路由到不同响应）。
     */
    @SuppressWarnings("unchecked")
    private void mockGetRequestForUrls(java.util.Map<String, Object> urlResponses) {
        var headerUriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        when(restClient.get()).thenReturn(headerUriSpec);

        lenient().when(headerUriSpec.uri(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0, String.class);
            Object response = urlResponses.get(url);

            var headerSpec = mock(RestClient.RequestHeadersSpec.class);

            if (response instanceof Throwable t) {
                when(headerSpec.retrieve()).thenThrow(t);
            } else {
                var urlResponseSpec = mock(RestClient.ResponseSpec.class);
                when(headerSpec.retrieve()).thenReturn(urlResponseSpec);
                when(urlResponseSpec.body(String.class)).thenReturn((String) response);
            }
            return headerSpec;
        });
    }

    /**
     * 便捷方法：Mock 一个失败 URL + 一个成功 URL。
     */
    private void mockGetRequestForUrlWithError(String failUrl, String okUrl, String okResponseBody) {
        mockGetRequestForUrls(java.util.Map.of(
                failUrl, (Object) new ResourceAccessException("连接超时"),
                okUrl, (Object) okResponseBody
        ));
    }

    private static IndexRepository.IndexCacheEntry cacheEntry(String sourceUrl, String indexJson, String fetchedAt) {
        return new IndexRepository.IndexCacheEntry(
                "cache-id-1", sourceUrl, indexJson, fetchedAt, Instant.now().toString()
        );
    }

    private static InstalledExtension installedExtension(String packageId, ExtensionType type, String version) {
        return new InstalledExtension(
                "id-" + packageId,
                packageId,
                type,
                "Extension " + packageId,
                version,
                "https://example.com/index.json",
                "https://github.com/example/" + packageId,
                "extensions/" + packageId,
                "extensions/" + packageId,
                null,
                null,
                null,
                Instant.now(),
                Instant.now()
        );
    }
}
