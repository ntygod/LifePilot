package com.lifepilot.meta.infra.env;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Map;

/**
 * 日期时间工具执行器 — 获取当前日期、时间、星期和时区信息。
 *
 * <p>时区优先级：输入参数 timezone &gt; 配置 {@code lifepilot.meta.infra.user-profile.timezone} &gt; 系统时区。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class DateTimeToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(DateTimeToolExecutor.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final MetaProperties properties;

    public DateTimeToolExecutor(MetaProperties properties) {
        this.properties = properties;
    }

    /**
     * 执行日期时间查询。
     *
     * @param input 工具输入，可选参数 timezone
     * @return 包含日期、时间、星期、时区的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        try {
            ZoneId zoneId = resolveTimezone(input);
            ZonedDateTime now = ZonedDateTime.now(zoneId);

            String date = now.format(DATE_FORMATTER);
            String time = now.format(TIME_FORMATTER);
            String dayOfWeek = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINESE);
            String timezone = zoneId.getId();

            return ToolResult.success(Map.of(
                    "date", date,
                    "time", time,
                    "dayOfWeek", dayOfWeek,
                    "timezone", timezone,
                    "formatted", "%s %s %s (%s)".formatted(date, time, dayOfWeek, timezone)
            ));
        } catch (Exception e) {
            log.error("获取日期时间失败: {}", e.getMessage(), e);
            return ToolResult.error("获取日期时间失败: " + e.getMessage());
        }
    }

    /** 解析时区：输入参数 > 用户配置 > 系统默认。 */
    private ZoneId resolveTimezone(ToolInput input) {
        // 优先使用输入参数中的 timezone
        var inputTz = input.getOptionalParam("timezone", String.class);
        if (inputTz.isPresent() && !inputTz.get().isBlank()) {
            return ZoneId.of(inputTz.get());
        }

        // 其次使用配置中的 timezone
        String configTz = properties.getInfra().getUserProfile().getTimezone();
        if (configTz != null && !configTz.isBlank()) {
            return ZoneId.of(configTz);
        }

        // 最后使用系统时区
        return ZoneId.systemDefault();
    }
}
