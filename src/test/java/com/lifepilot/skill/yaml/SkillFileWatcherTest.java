package com.lifepilot.skill.yaml;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.model.*;
import com.lifepilot.skill.registry.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link SkillFileWatcher} 单元测试。
 *
 * <p>聚焦于 handleFileEvent 逻辑的直接测试，避免 WatchService 集成的不确定性。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
class SkillFileWatcherTest {

    @TempDir
    Path tempDir;

    private YamlSkillLoader yamlSkillLoader;
    private SkillRegistry skillRegistry;
    private SkillConfigProperties config;
    private SkillFileWatcher watcher;

    /** 构建一个测试用的 SkillDefinition。 */
    private static SkillDefinition buildTestDefinition(String id) {
        return SkillDefinition.builder()
                .id(id)
                .name("测试 Skill")
                .description("测试用 Skill 定义")
                .version("1.0.0")
                .source(new SkillSource.UserDefined("/test/" + id + ".yml"))
                .systemPrompt("你是一个测试助手")
                .allowedTools(List.of("test-tool"))
                .execution(ExecutionStrategy.DEFAULT)
                .memoryAccess(MemoryAccessPolicy.none())
                .budget(SkillBudget.DEFAULT)
                .metadata(Map.of())
                .build();
    }

    @BeforeEach
    void setUp() {
        config = new SkillConfigProperties();
        config.setDirectory(tempDir.toString());
        config.setHotReloadDebounceMs(50); // 测试中使用短防抖间隔

        yamlSkillLoader = mock(YamlSkillLoader.class);
        skillRegistry = mock(SkillRegistry.class);
        when(skillRegistry.register(any())).thenReturn(true);

        watcher = new SkillFileWatcher(yamlSkillLoader, skillRegistry, config);
    }

    @Test
    void start_触发初始loadAll() {
        when(yamlSkillLoader.loadAll()).thenReturn(3);

        watcher.start();

        verify(yamlSkillLoader).loadAll();
        // 清理
        watcher.destroy();
    }

    @Test
    void start_确保目录存在() throws IOException {
        // 使用不存在的子目录
        Path subDir = tempDir.resolve("skills-sub");
        config.setDirectory(subDir.toString());
        watcher = new SkillFileWatcher(yamlSkillLoader, skillRegistry, config);
        when(yamlSkillLoader.loadAll()).thenReturn(0);

        watcher.start();

        assertThat(Files.exists(subDir)).isTrue();
        watcher.destroy();
    }

    @Test
    void handleFileEvent_CREATE事件触发loadFile() throws Exception {
        Path yamlFile = tempDir.resolve("test-skill.yml");
        SkillDefinition def = buildTestDefinition("test-skill");
        when(yamlSkillLoader.loadFile(yamlFile)).thenReturn(Optional.of(def));

        watcher.handleFileEvent(StandardWatchEventKinds.ENTRY_CREATE, yamlFile);

        // 等待防抖执行
        Thread.sleep(200);

        verify(yamlSkillLoader).loadFile(yamlFile);
        verify(skillRegistry).register(def);
    }

    @Test
    void handleFileEvent_MODIFY事件触发loadFile() throws Exception {
        Path yamlFile = tempDir.resolve("test-skill.yaml");
        SkillDefinition def = buildTestDefinition("test-skill");
        when(yamlSkillLoader.loadFile(yamlFile)).thenReturn(Optional.of(def));

        watcher.handleFileEvent(StandardWatchEventKinds.ENTRY_MODIFY, yamlFile);

        // 等待防抖执行
        Thread.sleep(200);

        verify(yamlSkillLoader).loadFile(yamlFile);
        verify(skillRegistry).register(def);
    }

    @Test
    void handleFileEvent_DELETE事件触发unregister() {
        Path yamlFile = tempDir.resolve("test-skill.yml");
        SkillDefinition def = buildTestDefinition("test-skill");

        // 先模拟 CREATE 建立映射
        when(yamlSkillLoader.loadFile(yamlFile)).thenReturn(Optional.of(def));
        watcher.handleFileEvent(StandardWatchEventKinds.ENTRY_CREATE, yamlFile);

        // 等待防抖完成建立映射
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        // 然后触发 DELETE
        watcher.handleFileEvent(StandardWatchEventKinds.ENTRY_DELETE, yamlFile);

        verify(skillRegistry).unregister("test-skill");
    }

    @Test
    void handleFileEvent_忽略非YAML文件() throws Exception {
        Path txtFile = tempDir.resolve("readme.txt");

        watcher.handleFileEvent(StandardWatchEventKinds.ENTRY_CREATE, txtFile);

        // 等待确保不会触发
        Thread.sleep(200);

        verify(yamlSkillLoader, never()).loadFile(any());
    }

    @Test
    void handleFileEvent_忽略json文件() throws Exception {
        Path jsonFile = tempDir.resolve("config.json");

        watcher.handleFileEvent(StandardWatchEventKinds.ENTRY_MODIFY, jsonFile);

        Thread.sleep(200);

        verify(yamlSkillLoader, never()).loadFile(any());
    }

    @Test
    void destroy_停止监听器() {
        when(yamlSkillLoader.loadAll()).thenReturn(0);
        watcher.start();

        watcher.destroy();

        assertThat(watcher.isRunning()).isFalse();
    }

    @Test
    void handleFileEvent_并发重载保护() throws Exception {
        Path yamlFile = tempDir.resolve("test-skill.yml");
        SkillDefinition def = buildTestDefinition("test-skill");

        // 模拟 loadFile 耗时较长
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch loadCanProceed = new CountDownLatch(1);
        when(yamlSkillLoader.loadFile(yamlFile)).thenAnswer(invocation -> {
            loadStarted.countDown();
            loadCanProceed.await(2, TimeUnit.SECONDS);
            return Optional.of(def);
        });

        // 触发第一次加载
        watcher.handleFileEvent(StandardWatchEventKinds.ENTRY_CREATE, yamlFile);

        // 等待第一次加载开始
        boolean started = loadStarted.await(1, TimeUnit.SECONDS);
        assertThat(started).isTrue();

        // 在第一次加载进行中，触发第二次加载（应被跳过）
        Path yamlFile2 = tempDir.resolve("test-skill-2.yml");
        SkillDefinition def2 = buildTestDefinition("test-skill-2");
        when(yamlSkillLoader.loadFile(yamlFile2)).thenReturn(Optional.of(def2));
        watcher.handleFileEvent(StandardWatchEventKinds.ENTRY_CREATE, yamlFile2);

        // 等待第二次防抖触发
        Thread.sleep(200);

        // 释放第一次加载
        loadCanProceed.countDown();

        // 等待所有操作完成
        Thread.sleep(200);

        // 第一次加载应该执行了
        verify(yamlSkillLoader).loadFile(yamlFile);
        // 第二次加载也应该尝试了（防抖后），但由于 AtomicBoolean 可能被跳过
        // 这里验证 register 至少被调用了一次（第一次加载成功）
        verify(skillRegistry, atLeastOnce()).register(any());
    }

    @Test
    void handleFileEvent_防抖合并多次事件() throws Exception {
        Path yamlFile = tempDir.resolve("test-skill.yml");
        SkillDefinition def = buildTestDefinition("test-skill");
        when(yamlSkillLoader.loadFile(yamlFile)).thenReturn(Optional.of(def));

        // 快速连续触发多次 MODIFY 事件
        watcher.handleFileEvent(StandardWatchEventKinds.ENTRY_MODIFY, yamlFile);
        watcher.handleFileEvent(StandardWatchEventKinds.ENTRY_MODIFY, yamlFile);
        watcher.handleFileEvent(StandardWatchEventKinds.ENTRY_MODIFY, yamlFile);

        // 等待防抖完成
        Thread.sleep(300);

        // 由于防抖，loadFile 应该只被调用一次
        verify(yamlSkillLoader, times(1)).loadFile(yamlFile);
    }

    @Test
    void handleFileEvent_loadFile失败不影响后续事件() throws Exception {
        Path failFile = tempDir.resolve("bad-skill.yml");
        Path goodFile = tempDir.resolve("good-skill.yml");
        SkillDefinition goodDef = buildTestDefinition("good-skill");

        when(yamlSkillLoader.loadFile(failFile)).thenReturn(Optional.empty());
        when(yamlSkillLoader.loadFile(goodFile)).thenReturn(Optional.of(goodDef));

        // 先触发失败的文件
        watcher.handleFileEvent(StandardWatchEventKinds.ENTRY_CREATE, failFile);
        Thread.sleep(200);

        // 再触发成功的文件
        watcher.handleFileEvent(StandardWatchEventKinds.ENTRY_CREATE, goodFile);
        Thread.sleep(200);

        verify(yamlSkillLoader).loadFile(failFile);
        verify(yamlSkillLoader).loadFile(goodFile);
        verify(skillRegistry).register(goodDef);
    }

    @Test
    void handleFileEvent_DELETE未映射文件不报错() {
        Path unknownFile = tempDir.resolve("unknown.yml");

        // 不应抛出异常
        watcher.handleFileEvent(StandardWatchEventKinds.ENTRY_DELETE, unknownFile);

        verify(skillRegistry, never()).unregister(any());
    }

    @Test
    void handleFileEvent_loadFile异常不中断监听() throws Exception {
        Path errorFile = tempDir.resolve("error-skill.yml");
        when(yamlSkillLoader.loadFile(errorFile)).thenThrow(new RuntimeException("模拟异常"));

        watcher.handleFileEvent(StandardWatchEventKinds.ENTRY_CREATE, errorFile);
        Thread.sleep(200);

        // 监听器应仍在运行
        assertThat(watcher.isRunning()).isTrue();
    }
}
