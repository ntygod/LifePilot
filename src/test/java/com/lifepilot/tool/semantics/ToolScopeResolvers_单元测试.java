package com.lifepilot.tool.semantics;

import com.lifepilot.permission.model.ExecutionGrantScope;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.schema.JsonSchema;
import net.jqwik.api.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolScopeResolvers 工具作用域解析器工厂单元测试。
 *
 * <p>覆盖所有工厂方法（none / paths / workspacePaths / origins / pathTrees / exactValues / composite）
 * 的正常路径、空输入、多参数合并及边界条件。</p>
 *
 * @author zsg
 * @since 2026-04-03
 */
class ToolScopeResolvers_单元测试 {

    // ==================== 辅助方法 ====================

    /**
     * 创建一个包含指定参数的 ToolInput。
     */
    private static ToolInput inputOf(Map<String, Object> parameters) {
        return new ToolInput("test-tool", parameters, JsonSchema.empty(), null, null);
    }

    /**
     * 创建空参数的 ToolInput。
     */
    private static ToolInput emptyInput() {
        return inputOf(Map.of());
    }

    // ==================== none() ====================

    @Nested
    class None_空解析器 {

        @Test
        void 始终返回EMPTY() {
            ToolScopeResolver resolver = ToolScopeResolvers.none();
            ToolScopeResolution result = resolver.resolve(emptyInput());

            assertSame(ToolScopeResolution.EMPTY, result);
        }

        @Test
        void 有参数时仍返回EMPTY() {
            ToolScopeResolver resolver = ToolScopeResolvers.none();
            ToolScopeResolution result = resolver.resolve(inputOf(Map.of("path", "C:/test")));

            assertSame(ToolScopeResolution.EMPTY, result);
        }

        @Test
        void 多次调用返回同一实例() {
            ToolScopeResolver r1 = ToolScopeResolvers.none();
            ToolScopeResolver r2 = ToolScopeResolvers.none();

            assertSame(r1, r2, "none() 应返回同一单例");
        }
    }

    // ==================== paths() ====================

    @Nested
    class Paths_路径解析器 {

        @Test
        void 单参数_生成paths和workspacePaths维度() {
            ToolScopeResolver resolver = ToolScopeResolvers.paths("filePath");
            ToolScopeResolution result = resolver.resolve(inputOf(Map.of("filePath", "C:/Users/test/file.txt")));

            assertNotNull(result);
            assertFalse(result.scope().isEmpty(), "scope 不应为空");
            assertFalse(result.normalizedResources().isEmpty(), "normalizedResources 不应为空");

            // scope 中应有 paths 维度
            List<String> paths = result.scope().stringValues("paths");
            assertFalse(paths.isEmpty(), "scope 应包含 paths 维度");

            // normalizedResources 应包含 path: 前缀的资源标识
            assertTrue(result.normalizedResources().stream().anyMatch(r -> r.startsWith("path:")),
                    "normalizedResources 应包含 path: 前缀的条目");
        }

        @Test
        void 多参数名_合并去重() {
            ToolScopeResolver resolver = ToolScopeResolvers.paths("source", "target");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("source", "C:/project/a.txt");
            params.put("target", "C:/project/b.txt");
            ToolScopeResolution result = resolver.resolve(inputOf(params));

            List<String> paths = result.scope().stringValues("paths");
            assertTrue(paths.size() >= 2, "应包含两个不同的路径");
        }

        @Test
        void 相同路径值_去重() {
            ToolScopeResolver resolver = ToolScopeResolvers.paths("source", "target");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("source", "C:/project/same.txt");
            params.put("target", "C:/project/same.txt");
            ToolScopeResolution result = resolver.resolve(inputOf(params));

            List<String> paths = result.scope().stringValues("paths");
            assertEquals(1, paths.size(), "相同路径应去重为1个");
        }

        @Test
        void 参数不存在_返回EMPTY() {
            ToolScopeResolver resolver = ToolScopeResolvers.paths("nonexistent");
            ToolScopeResolution result = resolver.resolve(emptyInput());

            assertSame(ToolScopeResolution.EMPTY, result);
        }

        @Test
        void 参数值为空字符串_返回EMPTY() {
            ToolScopeResolver resolver = ToolScopeResolvers.paths("filePath");
            ToolScopeResolution result = resolver.resolve(inputOf(Map.of("filePath", "")));

            // 空字符串经 getOptionalParam(String, String.class) 会返回 ""，isBlank() 为 true 被跳过
            // 实际上 getOptionalParam 返回 Optional.of("")，然后 normalizeDistinctValues 内跳过
            assertSame(ToolScopeResolution.EMPTY, result);
        }

        @Test
        void 参数值为空白字符串_返回EMPTY() {
            ToolScopeResolver resolver = ToolScopeResolvers.paths("filePath");
            ToolScopeResolution result = resolver.resolve(inputOf(Map.of("filePath", "   ")));

            assertSame(ToolScopeResolution.EMPTY, result);
        }

        @Test
        void 参数值非String类型_返回EMPTY() {
            ToolScopeResolver resolver = ToolScopeResolvers.paths("filePath");
            // Integer 不是 String，getOptionalParam(name, String.class) 返回 empty
            ToolScopeResolution result = resolver.resolve(inputOf(Map.of("filePath", 42)));

            assertSame(ToolScopeResolution.EMPTY, result);
        }

        @Test
        void 多个参数名_部分缺失_仅解析存在的参数() {
            ToolScopeResolver resolver = ToolScopeResolvers.paths("source", "target");
            ToolScopeResolution result = resolver.resolve(inputOf(Map.of("source", "C:/project/a.txt")));

            List<String> paths = result.scope().stringValues("paths");
            assertEquals(1, paths.size(), "只有一个有效参数，应只有一个路径");
        }

        @Test
        void 路径结果按自然序排列() {
            ToolScopeResolver resolver = ToolScopeResolvers.paths("a", "b");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("a", "C:/zzz/file.txt");
            params.put("b", "C:/aaa/file.txt");
            ToolScopeResolution result = resolver.resolve(inputOf(params));

            List<String> paths = result.scope().stringValues("paths");
            if (paths.size() == 2) {
                assertTrue(paths.get(0).compareTo(paths.get(1)) <= 0,
                        "路径应按自然序排列: " + paths);
            }
        }

        @Test
        void Windows路径_workspacePaths维度被填充() {
            ToolScopeResolver resolver = ToolScopeResolvers.paths("filePath");
            ToolScopeResolution result = resolver.resolve(inputOf(Map.of("filePath", "C:/Users/test/file.txt")));

            // file.txt 看起来像文件 → workspacePaths 应为其父目录
            List<String> workspacePaths = result.scope().stringValues("workspacePaths");
            assertFalse(workspacePaths.isEmpty(), "workspacePaths 维度不应为空");
        }
    }

    // ==================== workspacePaths() ====================

    @Nested
    class WorkspacePaths_工作区路径解析器 {

        @Test
        void 单参数_生成workspacePaths维度和workspace资源() {
            ToolScopeResolver resolver = ToolScopeResolvers.workspacePaths("workDir");
            ToolScopeResolution result = resolver.resolve(inputOf(Map.of("workDir", "C:/project/src")));

            assertFalse(result.scope().isEmpty());
            List<String> workspacePaths = result.scope().stringValues("workspacePaths");
            assertFalse(workspacePaths.isEmpty(), "应包含 workspacePaths 维度");

            assertTrue(result.normalizedResources().stream().anyMatch(r -> r.startsWith("workspace:")),
                    "normalizedResources 应包含 workspace: 前缀的条目");
        }

        @Test
        void 不生成paths维度() {
            ToolScopeResolver resolver = ToolScopeResolvers.workspacePaths("workDir");
            ToolScopeResolution result = resolver.resolve(inputOf(Map.of("workDir", "C:/project")));

            List<String> paths = result.scope().stringValues("paths");
            assertTrue(paths.isEmpty(), "workspacePaths 解析器不应生成 paths 维度");
        }

        @Test
        void 参数不存在_返回EMPTY() {
            ToolScopeResolver resolver = ToolScopeResolvers.workspacePaths("workDir");
            ToolScopeResolution result = resolver.resolve(emptyInput());

            assertSame(ToolScopeResolution.EMPTY, result);
        }

        @Test
        void 空白参数值_返回EMPTY() {
            ToolScopeResolver resolver = ToolScopeResolvers.workspacePaths("workDir");
            ToolScopeResolution result = resolver.resolve(inputOf(Map.of("workDir", "  ")));

            assertSame(ToolScopeResolution.EMPTY, result);
        }
    }

    // ==================== origins() ====================

    @Nested
    class Origins_URL来源解析器 {

        @Test
        void 标准URL_生成origins和hosts维度() {
            ToolScopeResolver resolver = ToolScopeResolvers.origins("url");
            ToolScopeResolution result = resolver.resolve(inputOf(Map.of("url", "https://api.example.com/v1/data")));

            assertFalse(result.scope().isEmpty());

            List<String> origins = result.scope().stringValues("origins");
            assertFalse(origins.isEmpty(), "应包含 origins 维度");
            // normalizeOrigin 会去除路径，保留 scheme://host
            assertTrue(origins.stream().anyMatch(o -> o.contains("example.com")),
                    "origins 应包含 example.com");

            List<String> hosts = result.scope().stringValues("hosts");
            assertFalse(hosts.isEmpty(), "应包含 hosts 维度");
            assertTrue(hosts.contains("api.example.com"), "hosts 应包含 api.example.com");
        }

        @Test
        void 带端口的URL_保留端口在origin中() {
            ToolScopeResolver resolver = ToolScopeResolvers.origins("endpoint");
            ToolScopeResolution result = resolver.resolve(inputOf(Map.of("endpoint", "http://localhost:8080/api")));

            List<String> origins = result.scope().stringValues("origins");
            assertTrue(origins.stream().anyMatch(o -> o.contains("8080")),
                    "带端口的 URL origin 应保留端口号");
        }

        @Test
        void 多个URL参数_合并去重() {
            ToolScopeResolver resolver = ToolScopeResolvers.origins("source", "target");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("source", "https://api.example.com/v1");
            params.put("target", "https://cdn.example.com/assets");
            ToolScopeResolution result = resolver.resolve(inputOf(params));

            List<String> origins = result.scope().stringValues("origins");
            assertTrue(origins.size() >= 2, "两个不同 origin 应产生至少 2 个条目");

            List<String> hosts = result.scope().stringValues("hosts");
            assertTrue(hosts.size() >= 2, "两个不同 host 应产生至少 2 个条目");
        }

        @Test
        void 相同origin的多个URL_去重() {
            ToolScopeResolver resolver = ToolScopeResolvers.origins("a", "b");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("a", "https://example.com/path1");
            params.put("b", "https://example.com/path2");
            ToolScopeResolution result = resolver.resolve(inputOf(params));

            // 两个 URL 规范化后 origin 相同 (https://example.com)
            List<String> origins = result.scope().stringValues("origins");
            assertEquals(1, origins.size(), "相同 origin 应去重为 1 个");
        }

        @Test
        void 参数不存在_返回EMPTY() {
            ToolScopeResolver resolver = ToolScopeResolvers.origins("url");
            ToolScopeResolution result = resolver.resolve(emptyInput());

            assertSame(ToolScopeResolution.EMPTY, result);
        }

        @Test
        void 非URL字符串_hosts可能为空但origins非空() {
            ToolScopeResolver resolver = ToolScopeResolvers.origins("url");
            // "just-a-string" 作为 origin，normalizeOrigin 返回 trim 值，extractHost 返回 null
            ToolScopeResolution result = resolver.resolve(inputOf(Map.of("url", "just-a-string")));

            List<String> origins = result.scope().stringValues("origins");
            assertFalse(origins.isEmpty(), "非 URL 字符串仍会作为 origin 值");

            // extractHost 返回 null → hosts 中不包含该值 → 过滤掉
            // 如果所有 host 都为 null，hosts 维度可能不存在
        }

        @Test
        void normalizedResources包含origin前缀() {
            ToolScopeResolver resolver = ToolScopeResolvers.origins("url");
            ToolScopeResolution result = resolver.resolve(inputOf(Map.of("url", "https://example.com/api")));

            assertTrue(result.normalizedResources().stream().anyMatch(r -> r.startsWith("origin:")),
                    "normalizedResources 应包含 origin: 前缀");
        }
    }

    // ==================== pathTrees() ====================

    @Nested
    class PathTrees_路径树解析器 {

        @Test
        void 生成paths和workspacePaths维度及tree资源() {
            ToolScopeResolver resolver = ToolScopeResolvers.pathTrees("directory");
            ToolScopeResolution result = resolver.resolve(inputOf(Map.of("directory", "C:/project/src")));

            assertFalse(result.scope().isEmpty());

            List<String> paths = result.scope().stringValues("paths");
            assertFalse(paths.isEmpty(), "应包含 paths 维度");

            // normalizedResources 中应有 tree: 前缀（区别于 paths() 的 path: 前缀）
            assertTrue(result.normalizedResources().stream().anyMatch(r -> r.startsWith("tree:")),
                    "normalizedResources 应包含 tree: 前缀（不是 path:）");
        }

        @Test
        void 与paths的区别在于资源前缀是tree而非path() {
            ToolScopeResolver pathResolver = ToolScopeResolvers.paths("dir");
            ToolScopeResolver treeResolver = ToolScopeResolvers.pathTrees("dir");

            ToolInput input = inputOf(Map.of("dir", "C:/project/src"));
            ToolScopeResolution pathResult = pathResolver.resolve(input);
            ToolScopeResolution treeResult = treeResolver.resolve(input);

            // scope 结构应相同
            assertEquals(pathResult.scope().stringValues("paths"), treeResult.scope().stringValues("paths"));

            // 但 normalizedResources 前缀不同
            assertTrue(pathResult.normalizedResources().stream().allMatch(r -> r.startsWith("path:")));
            assertTrue(treeResult.normalizedResources().stream().allMatch(r -> r.startsWith("tree:")));
        }

        @Test
        void 参数不存在_返回EMPTY() {
            ToolScopeResolver resolver = ToolScopeResolvers.pathTrees("dir");
            ToolScopeResolution result = resolver.resolve(emptyInput());

            assertSame(ToolScopeResolution.EMPTY, result);
        }
    }

    // ==================== exactValues() ====================

    @Nested
    class ExactValues_精确值解析器 {

        @Test
        void 单值_生成自定义scopeKey维度() {
            ToolScopeResolver resolver = ToolScopeResolvers.exactValues("collectionId", "collection");
            ToolScopeResolution result = resolver.resolve(inputOf(Map.of("collection", "my-docs")));

            assertFalse(result.scope().isEmpty());
            List<String> values = result.scope().stringValues("collectionId");
            assertEquals(1, values.size());
            assertEquals("my-docs", values.getFirst());
        }

        @Test
        void 默认开启调度资源() {
            ToolScopeResolver resolver = ToolScopeResolvers.exactValues("taskId", "id");
            ToolScopeResolution result = resolver.resolve(inputOf(Map.of("id", "task-123")));

            assertFalse(result.normalizedResources().isEmpty(),
                    "默认 useForScheduling=true，应生成 normalizedResources");
            assertTrue(result.normalizedResources().stream()
                            .anyMatch(r -> r.startsWith("value[taskId]:")),
                    "normalizedResources 应包含 value[taskId]: 前缀");
        }

        @Test
        void 关闭调度资源_normalizedResources为空() {
            ToolScopeResolver resolver = ToolScopeResolvers.exactValues("taskId", false, "id");
            ToolScopeResolution result = resolver.resolve(inputOf(Map.of("id", "task-123")));

            assertFalse(result.scope().isEmpty(), "scope 仍应有值");
            assertTrue(result.normalizedResources().isEmpty(),
                    "useForScheduling=false 时 normalizedResources 应为空");
        }

        @Test
        void 多参数名_合并所有值() {
            ToolScopeResolver resolver = ToolScopeResolvers.exactValues("ids", "primaryId", "secondaryId");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("primaryId", "abc");
            params.put("secondaryId", "xyz");
            ToolScopeResolution result = resolver.resolve(inputOf(params));

            List<String> values = result.scope().stringValues("ids");
            assertEquals(2, values.size());
            assertTrue(values.contains("abc"));
            assertTrue(values.contains("xyz"));
        }

        @Test
        void 值前后有空白_自动trim() {
            ToolScopeResolver resolver = ToolScopeResolvers.exactValues("name", "param");
            ToolScopeResolution result = resolver.resolve(inputOf(Map.of("param", "  hello  ")));

            List<String> values = result.scope().stringValues("name");
            assertEquals(1, values.size());
            assertEquals("hello", values.getFirst(), "exactValues 应 trim 输入值");
        }

        @Test
        void 参数值全为空白_返回EMPTY() {
            ToolScopeResolver resolver = ToolScopeResolvers.exactValues("key", "param");
            ToolScopeResolution result = resolver.resolve(inputOf(Map.of("param", "   ")));

            assertSame(ToolScopeResolution.EMPTY, result);
        }

        @Test
        void 参数不存在_返回EMPTY() {
            ToolScopeResolver resolver = ToolScopeResolvers.exactValues("key", "missing");
            ToolScopeResolution result = resolver.resolve(emptyInput());

            assertSame(ToolScopeResolution.EMPTY, result);
        }

        @Test
        void 相同值去重() {
            ToolScopeResolver resolver = ToolScopeResolvers.exactValues("ids", "a", "b");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("a", "same-value");
            params.put("b", "same-value");
            ToolScopeResolution result = resolver.resolve(inputOf(params));

            List<String> values = result.scope().stringValues("ids");
            assertEquals(1, values.size(), "相同值应去重");
        }

        @Test
        void 单值时scope存储为标量而非列表() {
            ToolScopeResolver resolver = ToolScopeResolvers.exactValues("key", "param");
            ToolScopeResolution result = resolver.resolve(inputOf(Map.of("param", "single")));

            // putDimension: size==1 时存为单字符串而非 List
            Object raw = result.scope().get("key");
            assertInstanceOf(String.class, raw,
                    "单值时 scope 维度应存储为标量字符串，实际类型: " + (raw == null ? "null" : raw.getClass()));
        }

        @Test
        void 多值时scope存储为列表() {
            ToolScopeResolver resolver = ToolScopeResolvers.exactValues("key", "a", "b");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("a", "val1");
            params.put("b", "val2");
            ToolScopeResolution result = resolver.resolve(inputOf(params));

            Object raw = result.scope().get("key");
            assertInstanceOf(List.class, raw,
                    "多值时 scope 维度应存储为列表");
        }
    }

    // ==================== composite() ====================

    @Nested
    class Composite_复合解析器 {

        @Test
        void 合并两个不同维度的解析器() {
            ToolScopeResolver pathResolver = ToolScopeResolvers.paths("filePath");
            ToolScopeResolver originResolver = ToolScopeResolvers.origins("url");
            ToolScopeResolver composite = ToolScopeResolvers.composite(pathResolver, originResolver);

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("filePath", "C:/project/file.txt");
            params.put("url", "https://example.com/api");
            ToolScopeResolution result = composite.resolve(inputOf(params));

            assertFalse(result.scope().isEmpty());
            assertFalse(result.scope().stringValues("paths").isEmpty(), "应有 paths 维度");
            assertFalse(result.scope().stringValues("origins").isEmpty(), "应有 origins 维度");

            // normalizedResources 应同时包含 path: 和 origin:
            assertTrue(result.normalizedResources().stream().anyMatch(r -> r.startsWith("path:")));
            assertTrue(result.normalizedResources().stream().anyMatch(r -> r.startsWith("origin:")));
        }

        @Test
        void 合并相同维度_值被合并() {
            ToolScopeResolver r1 = ToolScopeResolvers.exactValues("ids", "id1");
            ToolScopeResolver r2 = ToolScopeResolvers.exactValues("ids", "id2");
            ToolScopeResolver composite = ToolScopeResolvers.composite(r1, r2);

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("id1", "alpha");
            params.put("id2", "beta");
            ToolScopeResolution result = composite.resolve(inputOf(params));

            List<String> ids = result.scope().stringValues("ids");
            assertEquals(2, ids.size(), "相同 scope key 的值应合并");
            assertTrue(ids.contains("alpha"));
            assertTrue(ids.contains("beta"));
        }

        @Test
        void 合并相同维度_重复值去重() {
            ToolScopeResolver r1 = ToolScopeResolvers.exactValues("ids", "id1");
            ToolScopeResolver r2 = ToolScopeResolvers.exactValues("ids", "id2");
            ToolScopeResolver composite = ToolScopeResolvers.composite(r1, r2);

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("id1", "same");
            params.put("id2", "same");
            ToolScopeResolution result = composite.resolve(inputOf(params));

            List<String> ids = result.scope().stringValues("ids");
            assertEquals(1, ids.size(), "合并后相同值应去重");
        }

        @Test
        void 空解析器数组_返回EMPTY() {
            ToolScopeResolver composite = ToolScopeResolvers.composite();
            ToolScopeResolution result = composite.resolve(inputOf(Map.of("x", "y")));

            assertSame(ToolScopeResolution.EMPTY, result);
        }

        @Test
        void 所有子解析器返回EMPTY_composite也返回EMPTY() {
            ToolScopeResolver r1 = ToolScopeResolvers.none();
            ToolScopeResolver r2 = ToolScopeResolvers.paths("nonexistent");
            ToolScopeResolver composite = ToolScopeResolvers.composite(r1, r2);

            ToolScopeResolution result = composite.resolve(emptyInput());

            assertSame(ToolScopeResolution.EMPTY, result);
        }

        @Test
        void 传入null数组_不抛异常返回EMPTY() {
            // composite(null) 中 resolvers 为 null → 退化为空委托列表
            ToolScopeResolver composite = ToolScopeResolvers.composite((ToolScopeResolver[]) null);
            ToolScopeResolution result = composite.resolve(inputOf(Map.of("param", "value")));

            assertSame(ToolScopeResolution.EMPTY, result);
        }

        @Test
        void 含null元素的数组_抛出NullPointerException() {
            // List.of(resolvers) 不允许 null 元素，预期抛出 NPE
            ToolScopeResolver resolver = ToolScopeResolvers.exactValues("key", "param");
            assertThrows(NullPointerException.class,
                    () -> ToolScopeResolvers.composite(null, resolver, null));
        }

        @Test
        void normalizedResources合并后按自然序排列且去重() {
            ToolScopeResolver r1 = ToolScopeResolvers.exactValues("x", "a");
            ToolScopeResolver r2 = ToolScopeResolvers.exactValues("y", "b");
            ToolScopeResolver composite = ToolScopeResolvers.composite(r1, r2);

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("a", "zzz");
            params.put("b", "aaa");
            ToolScopeResolution result = composite.resolve(inputOf(params));

            List<String> resources = result.normalizedResources();
            for (int i = 1; i < resources.size(); i++) {
                assertTrue(resources.get(i - 1).compareTo(resources.get(i)) <= 0,
                        "normalizedResources 应按自然序排列: " + resources);
            }
        }

        @Test
        void 三层复合_正确合并() {
            ToolScopeResolver r1 = ToolScopeResolvers.exactValues("a", "p1");
            ToolScopeResolver r2 = ToolScopeResolvers.exactValues("b", "p2");
            ToolScopeResolver r3 = ToolScopeResolvers.exactValues("c", "p3");
            ToolScopeResolver composite = ToolScopeResolvers.composite(r1, r2, r3);

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("p1", "v1");
            params.put("p2", "v2");
            params.put("p3", "v3");
            ToolScopeResolution result = composite.resolve(inputOf(params));

            assertEquals(List.of("v1"), result.scope().stringValues("a"));
            assertEquals(List.of("v2"), result.scope().stringValues("b"));
            assertEquals(List.of("v3"), result.scope().stringValues("c"));
        }

        @Test
        void 嵌套composite_正确展开() {
            ToolScopeResolver inner = ToolScopeResolvers.composite(
                    ToolScopeResolvers.exactValues("a", "p1"),
                    ToolScopeResolvers.exactValues("b", "p2")
            );
            ToolScopeResolver outer = ToolScopeResolvers.composite(
                    inner,
                    ToolScopeResolvers.exactValues("c", "p3")
            );

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("p1", "v1");
            params.put("p2", "v2");
            params.put("p3", "v3");
            ToolScopeResolution result = outer.resolve(inputOf(params));

            assertEquals(List.of("v1"), result.scope().stringValues("a"));
            assertEquals(List.of("v2"), result.scope().stringValues("b"));
            assertEquals(List.of("v3"), result.scope().stringValues("c"));
        }

        @Test
        void 部分子解析器有结果部分为空_正确合并() {
            ToolScopeResolver withResult = ToolScopeResolvers.exactValues("key", "present");
            ToolScopeResolver noResult = ToolScopeResolvers.exactValues("key2", "absent");
            ToolScopeResolver composite = ToolScopeResolvers.composite(withResult, noResult);

            // 只提供 present 参数，absent 不存在
            ToolScopeResolution result = composite.resolve(inputOf(Map.of("present", "hello")));

            List<String> keyValues = result.scope().stringValues("key");
            assertEquals(1, keyValues.size());
            assertEquals("hello", keyValues.getFirst());

            List<String> key2Values = result.scope().stringValues("key2");
            assertTrue(key2Values.isEmpty(), "absent 参数未提供，key2 维度不应存在");
        }
    }

    // ==================== putDimension 间接测试 ====================

    @Nested
    class PutDimension_维度存储行为 {

        @Test
        void 空列表值_不放入scope() {
            // 通过 paths 解析器传入无法解析为 workspacePath 的输入来间接测试
            // 当 resolveWorkspacePath 返回的全是 null 时，workspacePaths 不应出现在 scope 中
            // 这很难直接构造，但我们可以测试 EMPTY 情况
            ToolScopeResolver resolver = ToolScopeResolvers.paths("p");
            ToolScopeResolution result = resolver.resolve(inputOf(Map.of("p", "  ")));

            assertSame(ToolScopeResolution.EMPTY, result);
        }
    }

    // ==================== 跨解析器一致性测试 ====================

    @Nested
    class 一致性测试 {

        @Test
        void 所有解析器_空输入都返回EMPTY() {
            ToolInput empty = emptyInput();

            assertSame(ToolScopeResolution.EMPTY, ToolScopeResolvers.none().resolve(empty));
            assertSame(ToolScopeResolution.EMPTY, ToolScopeResolvers.paths("x").resolve(empty));
            assertSame(ToolScopeResolution.EMPTY, ToolScopeResolvers.workspacePaths("x").resolve(empty));
            assertSame(ToolScopeResolution.EMPTY, ToolScopeResolvers.origins("x").resolve(empty));
            assertSame(ToolScopeResolution.EMPTY, ToolScopeResolvers.pathTrees("x").resolve(empty));
            assertSame(ToolScopeResolution.EMPTY, ToolScopeResolvers.exactValues("k", "x").resolve(empty));
            assertSame(ToolScopeResolution.EMPTY, ToolScopeResolvers.composite().resolve(empty));
        }

        @Test
        void 非EMPTY结果_scope和normalizedResources都非空() {
            ToolInput input = inputOf(Map.of("p", "C:/test/file.txt"));

            ToolScopeResolution pathsResult = ToolScopeResolvers.paths("p").resolve(input);
            assertFalse(pathsResult.scope().isEmpty());
            assertFalse(pathsResult.normalizedResources().isEmpty());

            ToolScopeResolution treeResult = ToolScopeResolvers.pathTrees("p").resolve(input);
            assertFalse(treeResult.scope().isEmpty());
            assertFalse(treeResult.normalizedResources().isEmpty());
        }

        @Test
        void 所有解析器_normalizedResources不包含null和空白项() {
            ToolInput input = inputOf(Map.of(
                    "path", "C:/project/src",
                    "url", "https://example.com",
                    "val", "test-value"
            ));

            List<ToolScopeResolver> resolvers = List.of(
                    ToolScopeResolvers.paths("path"),
                    ToolScopeResolvers.workspacePaths("path"),
                    ToolScopeResolvers.origins("url"),
                    ToolScopeResolvers.pathTrees("path"),
                    ToolScopeResolvers.exactValues("key", "val")
            );

            for (ToolScopeResolver resolver : resolvers) {
                ToolScopeResolution result = resolver.resolve(input);
                for (String resource : result.normalizedResources()) {
                    assertNotNull(resource, "normalizedResources 不应包含 null");
                    assertFalse(resource.isBlank(), "normalizedResources 不应包含空白字符串");
                }
            }
        }
    }

    // ==================== jqwik 属性测试 ====================

    @Group
    class 属性测试 {

        /**
         * 任何 exactValues 解析器，非空输入解析后的 scope 值等于 trim 后的输入值。
         */
        @Property(tries = 100)
        void exactValues结果等于trim后的值(
                @ForAll("nonBlankStrings") String scopeKey,
                @ForAll("nonBlankStrings") String value
        ) {
            ToolScopeResolver resolver = ToolScopeResolvers.exactValues(scopeKey, "param");
            ToolScopeResolution result = resolver.resolve(inputOf(Map.of("param", value)));

            if (value.isBlank()) {
                assertSame(ToolScopeResolution.EMPTY, result);
            } else {
                List<String> values = result.scope().stringValues(scopeKey);
                assertEquals(1, values.size());
                assertEquals(value.trim(), values.getFirst(),
                        "exactValues 的结果应等于输入 trim 后的值");
            }
        }

        /**
         * composite(r) 等价于 r 本身。
         */
        @Property(tries = 50)
        void composite单解析器等价于原解析器(@ForAll("nonBlankStrings") String value) {
            ToolScopeResolver single = ToolScopeResolvers.exactValues("key", "p");
            ToolScopeResolver wrapped = ToolScopeResolvers.composite(single);

            ToolInput input = inputOf(Map.of("p", value));
            ToolScopeResolution directResult = single.resolve(input);
            ToolScopeResolution wrappedResult = wrapped.resolve(input);

            assertEquals(directResult.scope().stringValues("key"),
                    wrappedResult.scope().stringValues("key"),
                    "composite(r) 的 scope 应等价于 r");
            assertEquals(directResult.normalizedResources(),
                    wrappedResult.normalizedResources(),
                    "composite(r) 的 normalizedResources 应等价于 r");
        }

        /**
         * composite 合并是幂等的：composite(r, r) 与 composite(r) 结果相同（因为去重）。
         */
        @Property(tries = 50)
        void composite重复解析器幂等(@ForAll("nonBlankStrings") String value) {
            ToolScopeResolver r = ToolScopeResolvers.exactValues("key", "p");
            ToolScopeResolver once = ToolScopeResolvers.composite(r);
            ToolScopeResolver twice = ToolScopeResolvers.composite(r, r);

            ToolInput input = inputOf(Map.of("p", value));
            ToolScopeResolution onceResult = once.resolve(input);
            ToolScopeResolution twiceResult = twice.resolve(input);

            assertEquals(onceResult.scope().stringValues("key"),
                    twiceResult.scope().stringValues("key"),
                    "composite(r, r) 应与 composite(r) 结果相同（去重）");
            assertEquals(onceResult.normalizedResources(),
                    twiceResult.normalizedResources(),
                    "composite(r, r) 的 normalizedResources 应与 composite(r) 相同（去重）");
        }

        /**
         * none 解析器在 composite 中不影响结果。
         */
        @Property(tries = 50)
        void composite中none不影响结果(@ForAll("nonBlankStrings") String value) {
            ToolScopeResolver r = ToolScopeResolvers.exactValues("key", "p");
            ToolScopeResolver withNone = ToolScopeResolvers.composite(ToolScopeResolvers.none(), r, ToolScopeResolvers.none());
            ToolScopeResolver withoutNone = ToolScopeResolvers.composite(r);

            ToolInput input = inputOf(Map.of("p", value));
            ToolScopeResolution withNoneResult = withNone.resolve(input);
            ToolScopeResolution withoutNoneResult = withoutNone.resolve(input);

            assertEquals(withoutNoneResult.scope().stringValues("key"),
                    withNoneResult.scope().stringValues("key"));
            assertEquals(withoutNoneResult.normalizedResources(),
                    withNoneResult.normalizedResources());
        }

        /**
         * normalizedResources 始终有序。
         */
        @Property(tries = 50)
        void normalizedResources始终排序(@ForAll("scopeKeys") String k1, @ForAll("scopeKeys") String k2) {
            ToolScopeResolver r1 = ToolScopeResolvers.exactValues(k1, "a");
            ToolScopeResolver r2 = ToolScopeResolvers.exactValues(k2, "b");
            ToolScopeResolver composite = ToolScopeResolvers.composite(r1, r2);

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("a", "value-z");
            params.put("b", "value-a");
            ToolScopeResolution result = composite.resolve(inputOf(params));

            List<String> resources = result.normalizedResources();
            for (int i = 1; i < resources.size(); i++) {
                assertTrue(resources.get(i - 1).compareTo(resources.get(i)) <= 0,
                        "normalizedResources 应始终有序: " + resources);
            }
        }

        // ---- Arbitrary 提供者 ----

        @Provide
        Arbitrary<String> nonBlankStrings() {
            return Arbitraries.of(
                    "hello", "world", "test-value", "my-collection",
                    "  padded  ", "CamelCase", "with spaces",
                    "abc123", "x", "some.dotted.value"
            );
        }

        @Provide
        Arbitrary<String> scopeKeys() {
            return Arbitraries.of(
                    "ids", "names", "collections", "taskId",
                    "collectionId", "workspaceId", "projectId"
            );
        }
    }
}
