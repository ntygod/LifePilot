package com.lifepilot.tool.yaml;

import com.lifepilot.tool.SkillTool;
import com.lifepilot.tool.config.ToolConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Skill Tool 持久化服务 — 负责 Skill Tool 的文件系统持久化。
 *
 * <p>功能：
 * <ul>
 *   <li>保存 Skill Tool 到文件系统</li>
 *   <li>从文件系统加载 Skill Tool</li>
 *   <li>更新 Skill Tool 文件</li>
 *   <li>删除 Skill Tool 文件</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-02-28
 */
@Service
@ConditionalOnProperty(prefix = "lifepilot.tool", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SkillToolPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(SkillToolPersistenceService.class);

    private final SkillToolSerializer serializer;
    private final SkillToolLoader loader;
    private final Path toolsDirectory;

    public SkillToolPersistenceService(ToolConfigProperties config,
                                       SkillToolSerializer serializer,
                                       SkillToolLoader loader) {
        this.serializer = serializer;
        this.loader = loader;
        this.toolsDirectory = Path.of(config.getYaml().getBaseDir());
    }

    /**
     * 保存 Skill Tool 到文件系统。
     *
     * @param tool Skill Tool
     * @return 是否保存成功
     */
    public boolean save(SkillTool tool) {
        try {
            // 确保目录存在
            if (!Files.exists(toolsDirectory)) {
                Files.createDirectories(toolsDirectory);
                log.info("Skill Tool 目录不存在，已自动创建: path={}", toolsDirectory);
            }

            // 序列化为 YAML
            String yamlContent = serializer.serialize(tool);
            
            // 写入文件（文件名：{toolId}.yaml）
            Path filePath = toolsDirectory.resolve(tool.id() + ".yaml");
            Files.writeString(filePath, yamlContent);
            
            log.info("Skill Tool 已保存: id={}, path={}", tool.id(), filePath);
            return true;
        } catch (IOException e) {
            log.error("保存 Skill Tool 失败: id={}, error={}", tool.id(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * 从文件系统加载 Skill Tool。
     *
     * @param toolId Tool ID
     * @return Skill Tool，文件不存在或加载失败返回 Optional.empty()
     */
    public Optional<SkillTool> load(String toolId) {
        Path filePath = toolsDirectory.resolve(toolId + ".yaml");
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        
        return loader.loadFile(filePath);
    }

    /**
     * 更新 Skill Tool 文件。
     *
     * @param tool 更新后的 Skill Tool
     * @return 是否更新成功
     */
    public boolean update(SkillTool tool) {
        // 更新就是保存（覆盖文件）
        return save(tool);
    }

    /**
     * 删除 Skill Tool 文件。
     *
     * @param toolId Tool ID
     * @return 是否删除成功
     */
    public boolean delete(String toolId) {
        Path filePath = toolsDirectory.resolve(toolId + ".yaml");
        if (!Files.exists(filePath)) {
            log.warn("Skill Tool 文件不存在，无法删除: id={}, path={}", toolId, filePath);
            return false;
        }
        
        try {
            Files.delete(filePath);
            log.info("Skill Tool 文件已删除: id={}, path={}", toolId, filePath);
            return true;
        } catch (IOException e) {
            log.error("删除 Skill Tool 文件失败: id={}, path={}, error={}", 
                    toolId, filePath, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 检查 Skill Tool 文件是否存在。
     *
     * @param toolId Tool ID
     * @return 文件是否存在
     */
    public boolean exists(String toolId) {
        Path filePath = toolsDirectory.resolve(toolId + ".yaml");
        return Files.exists(filePath);
    }
}
