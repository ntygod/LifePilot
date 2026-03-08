package com.lifepilot.meta.infra.env;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户画像工具执行器 — 获取用户偏好配置信息。
 *
 * <p>从 {@code MetaProperties.Infra.UserProfile} 读取用户偏好并返回。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class UserProfileToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(UserProfileToolExecutor.class);

    private final MetaProperties properties;

    public UserProfileToolExecutor(MetaProperties properties) {
        this.properties = properties;
    }

    /**
     * 执行用户画像查询。
     *
     * @param input 工具输入（无必需参数）
     * @return 包含用户偏好配置的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        try {
            var userProfile = properties.getInfra().getUserProfile();
            String timezone = userProfile.getTimezone();
            if (timezone == null || timezone.isBlank()) {
                timezone = ZoneId.systemDefault().getId();
            }

            var data = new LinkedHashMap<String, Object>();
            data.put("timezone", timezone);
            data.put("cacheTtlSeconds", userProfile.getCacheTtlSeconds());

            return ToolResult.success(Map.copyOf(data));
        } catch (Exception e) {
            log.error("获取用户画像失败: {}", e.getMessage(), e);
            return ToolResult.error("获取用户画像失败: " + e.getMessage());
        }
    }
}
