package com.lifepilot.agent.context;

import com.lifepilot.agent.task.reminder.ReminderSignal;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 天气服务接口 — 统一天气数据获取和异常信号评估的抽象。
 *
 * <p>实现类负责具体 API 调用和缓存策略：
 * <ul>
 *   <li>{@link OpenMeteoWeatherService} — 默认实现，基于免费 Open-Meteo API，零配置</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-12
 */
public interface WeatherService {

    /**
     * 获取天气摘要文本，用于注入对话上下文。
     *
     * <p>实现应仅读缓存，不阻塞调用方。</p>
     *
     * @return 天气摘要（如"北京 22°C 晴；今日 15~26°C；明日 多云转小雨 12~22°C"），或 null
     */
    @Nullable
    String getWeatherSummary();

    /**
     * 评估是否有异常天气需要产生提醒信号。
     *
     * <p>由心跳周期调用，允许同步网络请求。</p>
     *
     * @param hasOutdoorEvents 用户明日是否有外出事件
     * @return 信号列表（可能为空）
     */
    List<ReminderSignal> evaluateWeatherSignals(boolean hasOutdoorEvents);

    /**
     * 触发后台天气数据预取。应在应用启动后调用。
     *
     * <p>默认空实现，不强制要求所有实现支持预取。</p>
     */
    default void triggerPrefetch() {}
}
