package com.lifepilot.skill.marketplace.index;

import com.lifepilot.skill.marketplace.config.MarketplaceProperties;
import com.lifepilot.skill.marketplace.install.InstalledSkillRepository;
import com.lifepilot.skill.marketplace.model.InstalledSkill;
import com.lifepilot.skill.marketplace.model.SkillPackage;
import com.lifepilot.skill.marketplace.version.VersionResolver;
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
import static org.mockito.Mockito.*;

/**
 * IndexManager 单元测试 — Mock HTTP 请求和 Repository。
 *
 * @author zsg
 * @since 2026-03-05
 */
@ExtendWith(MockitoExtension.class)
class IndexManagerTest {

    @Mock
    private IndexRepository indexRepository;

    @Mock
    private InstalledSkillRepository installedSkillRepository;

    @Mock
    private RestClient restClient;

    @Mock
    private RestClient.RequestHeadersUriSpec<?> requestHeadersUriSpec;

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

        // Mock RestClient.Builder 返回 mock RestClient
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.build()).thenReturn(restClient);

        indexManager = new IndexManager(properties, indexRepository, installedSkillRepository,
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
                    "https://source2.com/index.json", SAMPLE_INDEX_JSON_2
            ));

            int count = indexManager.refreshAll();

            assertThat(count).isEqualTo(2);
            verify(indexRepository).save("https://source1.com/index.json", SAMPLE_INDEX_JSON);
            verify(indexRepository).save("https://source2.com/index.json", SAMPLE_INDEX_JSON_2);
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
            when(installedSkillRepository.findAll()).thenReturn(List.of());

            List<SkillPackage> packages = indexManager.getPackages();

            assertThat(packages).hasSize(1);
            assertThat(packages.getFirst().id()).isEqualTo("skill-todo");
            // 未触发刷新
            verify(restClient, never()).get();
        }

        @Test
        void 缓存过期_触发刷新后返回() {
            String staleFetchedAt = Instant.now().minus(25, ChronoUnit.HOURS).toString();
            // 第一次 findAll 返回过期缓存，刷新后第二次返回新缓存
            when(indexRepository.findAll())
                    .thenReturn(List.of(cacheEntry("https://example.com/index.json", SAMPLE_INDEX_JSON, staleFetchedAt)))
                    .thenReturn(List.of(cacheEntry("https://example.com/index.json", SAMPLE_INDEX_JSON,
                            Instant.now().toString())));
            when(installedSkillRepository.findAll()).thenReturn(List.of());

            // Mock HTTP 请求用于刷新
            mockGetRequest(SAMPLE_INDEX_JSON);

            List<SkillPackage> packages = indexManager.getPackages();

            assertThat(packages).hasSize(1);
            // 验证触发了刷新
            verify(indexRepository).save(anyString(), anyString());
        }

        @Test
        void 无缓存_触发刷新() {
            // 第一次 findAll 返回空，刷新后第二次返回数据
            when(indexRepository.findAll())
                    .thenReturn(List.of())
                    .thenReturn(List.of(cacheEntry("https://example.com/index.json", SAMPLE_INDEX_JSON,
                            Instant.now().toString())));
            when(installedSkillRepository.findAll()).thenReturn(List.of());

            mockGetRequest(SAMPLE_INDEX_JSON);

            List<SkillPackage> packages = indexManager.getPackages();

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
            when(installedSkillRepository.findAll()).thenReturn(List.of());

            List<SkillPackage> packages = indexManager.getPackages();

            assertThat(packages).hasSize(1);
            assertThat(packages.getFirst().id()).isEqualTo("skill-todo");
            assertThat(packages.getFirst().version()).isEqualTo("2.0.0");
        }

        @Test
        void 已安装Skill_附加安装状态() {
            String freshFetchedAt = Instant.now().toString();
            when(indexRepository.findAll()).thenReturn(List.of(
                    cacheEntry("https://example.com/index.json", SAMPLE_INDEX_JSON, freshFetchedAt)
            ));
            when(installedSkillRepository.findAll()).thenReturn(List.of(
                    installedSkill("skill-todo", "1.0.0")
            ));

            List<SkillPackage> packages = indexManager.getPackages();

            assertThat(packages).hasSize(1);
            assertThat(packages.getFirst().installed()).isTrue();
            assertThat(packages.getFirst().installedVersion()).isEqualTo("1.0.0");
        }

        @Test
        void 未安装Skill_installed为false() {
            String freshFetchedAt = Instant.now().toString();
            when(indexRepository.findAll()).thenReturn(List.of(
                    cacheEntry("https://example.com/index.json", SAMPLE_INDEX_JSON, freshFetchedAt)
            ));
            when(installedSkillRepository.findAll()).thenReturn(List.of());

            List<SkillPackage> packages = indexManager.getPackages();

            assertThat(packages).hasSize(1);
            assertThat(packages.getFirst().installed()).isFalse();
            assertThat(packages.getFirst().installedVersion()).isNull();
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
            when(installedSkillRepository.findAll()).thenReturn(List.of());

            Optional<SkillPackage> result = indexManager.getPackage("skill-todo");

            assertThat(result).isPresent();
            assertThat(result.get().id()).isEqualTo("skill-todo");
        }

        @Test
        void 不存在的id_返回empty() {
            String freshFetchedAt = Instant.now().toString();
            when(indexRepository.findAll()).thenReturn(List.of(
                    cacheEntry("https://example.com/index.json", SAMPLE_INDEX_JSON, freshFetchedAt)
            ));
            when(installedSkillRepository.findAll()).thenReturn(List.of());

            Optional<SkillPackage> result = indexManager.getPackage("non-existent");

            assertThat(result).isEmpty();
        }
    }

    // ── 辅助方法与测试数据 ─────────────────────────────────────

    private static final String SAMPLE_INDEX_JSON = """
            [
              {
                "id": "skill-todo",
                "name": "Todo 助手",
                "description": "管理待办事项",
                "version": "1.0.0",
                "author": "zsg",
                "repoUrl": "https://github.com/lifepilot-skills/todo",
                "filePath": "skill.yaml",
                "tags": ["productivity", "todo"],
                "minLifepilotVersion": "0.1.0",
                "createdAt": "2026-03-01T00:00:00Z",
                "updatedAt": "2026-03-05T00:00:00Z",
                "downloads": 100,
                "verified": true
              }
            ]
            """;

    private static final String SAMPLE_INDEX_JSON_2 = """
            [
              {
                "id": "skill-schedule",
                "name": "日程助手",
                "description": "管理日程安排",
                "version": "1.0.0",
                "author": "zsg",
                "repoUrl": "https://github.com/lifepilot-skills/schedule",
                "filePath": "skill.yaml",
                "tags": ["productivity", "schedule"],
                "minLifepilotVersion": "0.1.0",
                "createdAt": "2026-03-01T00:00:00Z",
                "updatedAt": "2026-03-05T00:00:00Z",
                "downloads": 50,
                "verified": false
              }
            ]
            """;

    private static final String SAMPLE_INDEX_JSON_HIGHER_VERSION = """
            [
              {
                "id": "skill-todo",
                "name": "Todo 助手",
                "description": "管理待办事项（升级版）",
                "version": "2.0.0",
                "author": "zsg",
                "repoUrl": "https://github.com/lifepilot-skills/todo",
                "filePath": "skill.yaml",
                "tags": ["productivity", "todo"],
                "minLifepilotVersion": "0.1.0",
                "createdAt": "2026-03-01T00:00:00Z",
                "updatedAt": "2026-03-05T00:00:00Z",
                "downloads": 200,
                "verified": true
              }
            ]
            """;

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
     *
     * <p>使用 thenAnswer 根据 URL 参数动态返回不同的 mock 链，
     * 避免 Mockito strict stubbing 冲突。</p>
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

    private static InstalledSkill installedSkill(String packageId, String version) {
        return new InstalledSkill(
                "id-" + packageId,
                packageId,
                "Skill " + packageId,
                version,
                "https://example.com/index.json",
                "https://github.com/example/" + packageId,
                "skills/" + packageId + ".yaml",
                null,
                Instant.now(),
                Instant.now()
        );
    }
}
