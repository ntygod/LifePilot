package com.lifepilot.config.path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 路径权限控制组件 —— 基于白名单/黑名单机制约束 Agent 可访问的本机目录。
 *
 * <h3>四种模式</h3>
 * <ul>
 *   <li><b>unrestricted</b>（默认）：允许访问所有路径</li>
 *   <li><b>whitelist-only</b>：仅允许白名单路径</li>
 *   <li><b>blacklist-only</b>：拒绝黑名单路径，允许其余</li>
 *   <li><b>whitelist-plus-blacklist</b>：黑名单优先于白名单</li>
 * </ul>
 *
 * <h3>默认行为</h3>
 * <p>HOME 目录默认加入黑名单，防止 Agent 直接操作知微内部文件。</p>
 *
 * <h3>平台适配</h3>
 * <p>Windows 平台下使用大小写不敏感的路径前缀比较。</p>
 *
 * @author zsg
 * @since 2026-06-15
 */
@Component
public class PathAccessControl {

    private static final Logger log = LoggerFactory.getLogger(PathAccessControl.class);

    /** Windows 平台标识 —— 用于路径大小写不敏感比较。 */
    private static final boolean IS_WINDOWS = java.io.File.separatorChar == '\\';

    // ==================== 访问控制模式 ====================

    /**
     * 路径访问控制模式。
     */
    public enum Mode {
        /** 不限制，允许所有路径 */
        UNRESTRICTED,
        /** 仅允许白名单中的路径 */
        WHITELIST_ONLY,
        /** 拒绝黑名单中的路径，允许其余 */
        BLACKLIST_ONLY,
        /** 白名单 + 黑名单，黑名单优先 */
        WHITELIST_PLUS_BLACKLIST;

        /**
         * 从字符串解析模式，不区分大小写，支持连字符和下划线。
         *
         * @param value 模式字符串
         * @return 对应的模式枚举，无法识别时返回 UNRESTRICTED
         */
        public static Mode fromString(String value) {
            if (value == null || value.isBlank()) {
                return UNRESTRICTED;
            }
            String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            return switch (normalized) {
                case "UNRESTRICTED" -> UNRESTRICTED;
                case "WHITELIST_ONLY" -> WHITELIST_ONLY;
                case "BLACKLIST_ONLY" -> BLACKLIST_ONLY;
                case "WHITELIST_PLUS_BLACKLIST" -> WHITELIST_PLUS_BLACKLIST;
                default -> {
                    log.warn("无法识别的路径访问控制模式: {}，使用默认 UNRESTRICTED", value);
                    yield UNRESTRICTED;
                }
            };
        }
    }

    // ==================== 访问检查结果 ====================

    /**
     * 路径访问检查结果 —— 使用 sealed interface 表达允许/拒绝两种情况。
     */
    public sealed interface AccessResult permits AccessResult.Allowed, AccessResult.Denied {

        /** 是否允许访问 */
        boolean allowed();

        /** 访问被允许 */
        record Allowed() implements AccessResult {
            @Override
            public boolean allowed() {
                return true;
            }
        }

        /**
         * 访问被拒绝，包含拒绝原因。
         *
         * @param reason 描述性错误消息，指明哪条规则阻止了访问
         */
        record Denied(String reason) implements AccessResult {
            @Override
            public boolean allowed() {
                return false;
            }
        }
    }

    /** 单例允许结果，避免重复创建 */
    private static final AccessResult.Allowed ALLOWED = new AccessResult.Allowed();

    // ==================== 依赖注入 ====================

    private final ZhiweiPaths zhiweiPaths;

    // ==================== 内部状态 ====================

    private volatile Mode mode = Mode.UNRESTRICTED;
    private volatile List<Path> whitelist = List.of();
    private volatile List<Path> blacklist = List.of();

    // ==================== 构造与初始化 ====================

    public PathAccessControl(ZhiweiPaths zhiweiPaths) {
        this.zhiweiPaths = zhiweiPaths;
    }

    @PostConstruct
    void init() {
        // 默认将 HOME 目录加入黑名单
        List<Path> defaultBlacklist = new ArrayList<>();
        defaultBlacklist.add(zhiweiPaths.home());

        // 尝试从 BootstrapConfig 的 pathAccess 字段加载规则
        loadFromBootstrapConfig(defaultBlacklist);

        log.info("PathAccessControl 初始化完成: mode={}, whitelist={}, blacklist={}",
                mode, whitelist, blacklist);
    }

    /**
     * 从 BootstrapConfig（~/zhiwei/config.json）的 pathAccess 字段加载规则。
     *
     * <p>config.json 中 pathAccess 字段结构示例：</p>
     * <pre>{@code
     * {
     *   "pathAccess": {
     *     "mode": "blacklist-only",
     *     "whitelist": ["/home/user/projects", "/tmp"],
     *     "blacklist": ["/home/user/zhiwei"]
     *   }
     * }
     * }</pre>
     *
     * @param defaultBlacklist 默认黑名单条目（HOME 目录）
     */
    private void loadFromBootstrapConfig(List<Path> defaultBlacklist) {
        Path configPath = Path.of(System.getProperty("user.home"), "zhiwei", "config.json");
        if (!Files.exists(configPath)) {
            // 无配置文件，使用默认值：blacklist-only + HOME 黑名单
            this.mode = Mode.BLACKLIST_ONLY;
            this.blacklist = List.copyOf(defaultBlacklist);
            return;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(configPath.toFile());
            JsonNode pathAccess = root.get("pathAccess");

            if (pathAccess == null || pathAccess.isNull()) {
                // pathAccess 字段不存在，使用默认值
                this.mode = Mode.BLACKLIST_ONLY;
                this.blacklist = List.copyOf(defaultBlacklist);
                return;
            }

            // 解析模式
            JsonNode modeNode = pathAccess.get("mode");
            this.mode = Mode.fromString(modeNode != null ? modeNode.asText() : null);

            // 解析白名单
            JsonNode whitelistNode = pathAccess.get("whitelist");
            if (whitelistNode != null && whitelistNode.isArray()) {
                List<Path> wl = new ArrayList<>();
                for (JsonNode entry : whitelistNode) {
                    String expanded = PathResolver.expand(entry.asText());
                    if (expanded != null && !expanded.isBlank()) {
                        wl.add(Path.of(expanded).toAbsolutePath().normalize());
                    }
                }
                this.whitelist = List.copyOf(wl);
            } else {
                this.whitelist = List.of();
            }

            // 解析黑名单（始终包含默认的 HOME 目录）
            List<Path> bl = new ArrayList<>(defaultBlacklist);
            JsonNode blacklistNode = pathAccess.get("blacklist");
            if (blacklistNode != null && blacklistNode.isArray()) {
                for (JsonNode entry : blacklistNode) {
                    String expanded = PathResolver.expand(entry.asText());
                    if (expanded != null && !expanded.isBlank()) {
                        Path p = Path.of(expanded).toAbsolutePath().normalize();
                        if (!bl.contains(p)) {
                            bl.add(p);
                        }
                    }
                }
            }
            this.blacklist = List.copyOf(bl);

        } catch (IOException e) {
            log.warn("读取 BootstrapConfig pathAccess 配置失败，使用默认值: {}", e.getMessage());
            this.mode = Mode.BLACKLIST_ONLY;
            this.blacklist = List.copyOf(defaultBlacklist);
        }
    }

    // ==================== 公开 API ====================

    /**
     * 检查指定路径是否允许访问。
     *
     * <p>基于当前模式和白名单/黑名单规则判断路径是否允许 Agent 访问。</p>
     *
     * @param path 待检查的路径，不可为 null
     * @return 访问检查结果，包含允许/拒绝及拒绝原因
     * @throws IllegalArgumentException 如果 path 为 null
     */
    public AccessResult isAllowed(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("路径不能为 null");
        }

        Path normalized = path.toAbsolutePath().normalize();

        return switch (mode) {
            case UNRESTRICTED -> ALLOWED;
            case WHITELIST_ONLY -> checkWhitelistOnly(normalized);
            case BLACKLIST_ONLY -> checkBlacklistOnly(normalized);
            case WHITELIST_PLUS_BLACKLIST -> checkWhitelistPlusBlacklist(normalized);
        };
    }

    /**
     * 便捷方法 —— 检查路径字符串是否允许访问。
     *
     * @param pathStr 待检查的路径字符串，不可为 null 或空白
     * @return 访问检查结果
     * @throws IllegalArgumentException 如果 pathStr 为 null 或空白
     */
    public AccessResult isAllowed(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) {
            throw new IllegalArgumentException("路径不能为 null 或空白");
        }
        return isAllowed(Path.of(pathStr));
    }

    /**
     * 便捷方法 —— 校验路径是否允许访问，不允许时抛出 SecurityException。
     *
     * @param pathStr 待校验的路径字符串
     * @throws SecurityException 路径被拒绝访问
     * @throws IllegalArgumentException 路径为 null 或空白
     */
    public void validate(String pathStr) {
        AccessResult result = isAllowed(pathStr);
        if (result instanceof AccessResult.Denied denied) {
            throw new SecurityException(denied.reason());
        }
    }

    /**
     * 返回当前访问控制模式。
     *
     * @return 当前模式
     */
    public Mode getMode() {
        return mode;
    }

    /**
     * 返回当前白名单条目（不可变副本）。
     *
     * @return 白名单路径列表
     */
    public List<Path> getWhitelist() {
        return whitelist;
    }

    /**
     * 返回当前黑名单条目（不可变副本）。
     *
     * @return 黑名单路径列表
     */
    public List<Path> getBlacklist() {
        return blacklist;
    }

    // ==================== 模式检查逻辑 ====================

    /**
     * whitelist-only 模式：仅允许白名单路径。
     */
    private AccessResult checkWhitelistOnly(Path normalized) {
        for (Path entry : whitelist) {
            if (pathStartsWith(normalized, entry)) {
                return ALLOWED;
            }
        }
        return new AccessResult.Denied(
                "路径不在白名单范围内: " + normalized + "（允许范围: " + whitelist + "）");
    }

    /**
     * blacklist-only 模式：拒绝黑名单路径，允许其余。
     */
    private AccessResult checkBlacklistOnly(Path normalized) {
        for (Path entry : blacklist) {
            if (pathStartsWith(normalized, entry)) {
                return new AccessResult.Denied(
                        "路径被黑名单规则阻止: " + normalized + "（匹配黑名单条目: " + entry + "）");
            }
        }
        return ALLOWED;
    }

    /**
     * whitelist-plus-blacklist 模式：黑名单优先于白名单。
     *
     * <p>先检查黑名单（命中则拒绝），再检查白名单（命中则允许），都不命中则拒绝。</p>
     */
    private AccessResult checkWhitelistPlusBlacklist(Path normalized) {
        // 黑名单优先
        for (Path entry : blacklist) {
            if (pathStartsWith(normalized, entry)) {
                return new AccessResult.Denied(
                        "路径被黑名单规则阻止（即使在白名单范围内）: " + normalized
                                + "（匹配黑名单条目: " + entry + "）");
            }
        }
        // 再检查白名单
        for (Path entry : whitelist) {
            if (pathStartsWith(normalized, entry)) {
                return ALLOWED;
            }
        }
        return new AccessResult.Denied(
                "路径不在白名单范围内: " + normalized + "（允许范围: " + whitelist + "）");
    }

    // ==================== 路径前缀匹配 ====================

    /**
     * 路径前缀匹配 —— Windows 下大小写不敏感，其他平台使用 {@link Path#startsWith(Path)}。
     *
     * <p>使用 {@code Path.startsWith()} 做组件级比较，避免 "foobar" 匹配 "foo" 前缀。</p>
     *
     * @param path   待检查的路径（已规范化）
     * @param prefix 前缀路径（已规范化）
     * @return 如果 path 以 prefix 为前缀则返回 true
     */
    private static boolean pathStartsWith(Path path, Path prefix) {
        if (IS_WINDOWS) {
            return Path.of(path.toString().toLowerCase(Locale.ROOT))
                    .startsWith(Path.of(prefix.toString().toLowerCase(Locale.ROOT)));
        }
        return path.startsWith(prefix);
    }

    // ==================== 运行时更新（供设置页调用） ====================

    /**
     * 运行时更新访问控制规则。
     *
     * <p>供设置页保存配置后刷新内存中的规则，无需重启。</p>
     *
     * @param newMode      新模式
     * @param newWhitelist 新白名单条目
     * @param newBlacklist 新黑名单条目（HOME 目录会自动追加）
     */
    public void updateRules(Mode newMode, List<Path> newWhitelist, List<Path> newBlacklist) {
        this.mode = newMode;
        this.whitelist = newWhitelist != null ? List.copyOf(newWhitelist) : List.of();

        // 确保 HOME 目录始终在黑名单中
        List<Path> bl = new ArrayList<>();
        Path homePath = zhiweiPaths.home();
        bl.add(homePath);
        if (newBlacklist != null) {
            for (Path p : newBlacklist) {
                Path normalized = p.toAbsolutePath().normalize();
                if (!pathStartsWith(normalized, homePath) || !pathStartsWith(homePath, normalized)) {
                    // 避免重复添加 HOME 本身
                    bl.add(normalized);
                }
            }
        }
        this.blacklist = List.copyOf(bl);

        log.info("PathAccessControl 规则已更新: mode={}, whitelist={}, blacklist={}",
                mode, whitelist, blacklist);
    }
}
