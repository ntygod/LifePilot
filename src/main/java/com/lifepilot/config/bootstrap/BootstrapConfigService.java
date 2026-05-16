package com.lifepilot.config.bootstrap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 引导配置文件读写服务。
 *
 * <p>读写 {@code ~/zhiwei/config.json}，供设置页面修改 HOME、WORKSPACE、
 * PathAccessControl 等启动级配置。HOME 修改后需重启应用才能生效。</p>
 *
 * <h3>config.json 结构</h3>
 * <pre>{@code
 * {
 *   "home": "/custom/path/zhiwei",
 *   "previousHome": "/old/path/zhiwei",
 *   "workspace": "/custom/workspace",
 *   "pathAccess": {
 *     "mode": "whitelist-only",
 *     "whitelist": ["/home/user/projects"],
 *     "blacklist": ["/etc"]
 *   }
 * }
 * }</pre>
 *
 * @author zsg
 * @since 2026-04-12
 */
@Service
public class BootstrapConfigService {

    private static final Logger log = LoggerFactory.getLogger(BootstrapConfigService.class);

    /** 固定配置文件路径：{user.home}/zhiwei/config.json */
    private static final Path CONFIG_PATH = Path.of(System.getProperty("user.home"), "zhiwei", "config.json");

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== HOME ====================

    /**
     * 读取引导配置中的 HOME 目录路径。
     *
     * @return 用户配置的 HOME 目录，未配置则返回 null
     */
    public String getHome() {
        return readStringField("home");
    }

    /**
     * 保存 HOME 目录路径到引导配置。
     *
     * @param home HOME 目录路径，null 或空字符串表示清除自定义设置（使用默认值）
     */
    public void saveHome(String home) {
        writeStringField("home", home);
        log.info("引导配置已保存: home={}", home);
    }

    // ==================== WORKSPACE ====================

    /**
     * 读取引导配置中的 WORKSPACE 目录路径。
     *
     * @return 用户配置的 WORKSPACE 目录，未配置则返回 null
     */
    public String getWorkspace() {
        return readStringField("workspace");
    }

    /**
     * 保存 WORKSPACE 目录路径到引导配置。
     *
     * @param workspace WORKSPACE 目录路径，null 或空字符串表示清除自定义设置（使用默认值）
     */
    public void saveWorkspace(String workspace) {
        writeStringField("workspace", workspace);
        log.info("引导配置已保存: workspace={}", workspace);
    }

    // ==================== PREVIOUS HOME（迁移用） ====================

    /**
     * 读取引导配置中的旧 HOME 目录路径（迁移用）。
     *
     * <p>当用户修改 HOME 路径时，系统将旧路径保存到 {@code previousHome} 字段，
     * 下次启动时检测到该字段后执行数据迁移。</p>
     *
     * @return 旧 HOME 目录路径，未配置则返回 null
     */
    public String getPreviousHome() {
        return readStringField("previousHome");
    }

    /**
     * 保存旧 HOME 目录路径到引导配置（迁移用）。
     *
     * @param previousHome 旧 HOME 目录路径，null 或空字符串表示清除（迁移完成后调用）
     */
    public void savePreviousHome(String previousHome) {
        writeStringField("previousHome", previousHome);
        log.info("引导配置已保存: previousHome={}", previousHome);
    }

    // ==================== PATH ACCESS ====================

    /**
     * 读取引导配置中的路径访问控制规则。
     *
     * @return 路径访问控制配置，未配置则返回默认配置（unrestricted 模式）
     */
    public PathAccessConfig getPathAccess() {
        if (!Files.exists(CONFIG_PATH)) {
            return PathAccessConfig.defaultConfig();
        }
        try {
            JsonNode root = objectMapper.readTree(CONFIG_PATH.toFile());
            JsonNode node = root.get("pathAccess");
            if (node == null || !node.isObject()) {
                return PathAccessConfig.defaultConfig();
            }

            String mode = node.has("mode") && node.get("mode").isTextual()
                    ? node.get("mode").asText()
                    : PathAccessConfig.MODE_UNRESTRICTED;

            List<String> whitelist = node.has("whitelist") && node.get("whitelist").isArray()
                    ? objectMapper.convertValue(node.get("whitelist"), new TypeReference<>() {})
                    : List.of();

            List<String> blacklist = node.has("blacklist") && node.get("blacklist").isArray()
                    ? objectMapper.convertValue(node.get("blacklist"), new TypeReference<>() {})
                    : List.of();

            return new PathAccessConfig(mode, whitelist, blacklist);
        } catch (IOException e) {
            log.warn("读取引导配置 pathAccess 失败: {}", e.getMessage());
            return PathAccessConfig.defaultConfig();
        }
    }

    /**
     * 保存路径访问控制规则到引导配置。
     *
     * @param config 路径访问控制配置，null 表示清除（恢复默认）
     */
    public void savePathAccess(PathAccessConfig config) {
        try {
            ObjectNode root = readOrCreateRoot();

            if (config == null) {
                root.remove("pathAccess");
            } else {
                ObjectNode pathAccessNode = objectMapper.createObjectNode();
                pathAccessNode.put("mode", config.mode());

                ArrayNode whitelistNode = objectMapper.createArrayNode();
                if (config.whitelist() != null) {
                    config.whitelist().forEach(whitelistNode::add);
                }
                pathAccessNode.set("whitelist", whitelistNode);

                ArrayNode blacklistNode = objectMapper.createArrayNode();
                if (config.blacklist() != null) {
                    config.blacklist().forEach(blacklistNode::add);
                }
                pathAccessNode.set("blacklist", blacklistNode);

                root.set("pathAccess", pathAccessNode);
            }

            writeRoot(root);
            log.info("引导配置已保存: pathAccess.mode={}", config != null ? config.mode() : "cleared");
        } catch (IOException e) {
            throw new IllegalStateException("保存引导配置 pathAccess 失败: " + e.getMessage(), e);
        }
    }

    // ==================== 内部工具方法 ====================

    /**
     * 读取指定字符串字段。
     */
    private String readStringField(String fieldName) {
        if (!Files.exists(CONFIG_PATH)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(CONFIG_PATH.toFile());
            JsonNode node = root.get(fieldName);
            if (node != null && node.isTextual() && !node.asText().isBlank()) {
                return node.asText();
            }
        } catch (IOException e) {
            log.warn("读取引导配置字段 {} 失败: {}", fieldName, e.getMessage());
        }
        return null;
    }

    /**
     * 写入指定字符串字段。
     */
    private void writeStringField(String fieldName, String value) {
        try {
            ObjectNode root = readOrCreateRoot();

            if (value == null || value.isBlank()) {
                root.remove(fieldName);
            } else {
                root.put(fieldName, value);
            }

            writeRoot(root);
        } catch (IOException e) {
            throw new IllegalStateException("保存引导配置字段 " + fieldName + " 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读取现有配置文件或创建空 ObjectNode。
     */
    private ObjectNode readOrCreateRoot() throws IOException {
        if (Files.exists(CONFIG_PATH)) {
            JsonNode existing = objectMapper.readTree(CONFIG_PATH.toFile());
            return existing.isObject() ? (ObjectNode) existing : objectMapper.createObjectNode();
        } else {
            Files.createDirectories(CONFIG_PATH.getParent());
            return objectMapper.createObjectNode();
        }
    }

    /**
     * 将 ObjectNode 写入配置文件。
     */
    private void writeRoot(ObjectNode root) throws IOException {
        Files.createDirectories(CONFIG_PATH.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(CONFIG_PATH.toFile(), root);
    }
}
