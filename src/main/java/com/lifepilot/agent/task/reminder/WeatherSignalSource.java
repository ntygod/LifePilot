package com.lifepilot.agent.task.reminder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 天气信号源 — 每日定时拉取天气预报，仅在异常天气时产生 EVENT 类型信号。
 *
 * <p>产生信号的条件：
 * <ul>
 *   <li>明日温差 >= 10°C</li>
 *   <li>明日有雨/雪且有外出事件</li>
 *   <li>空气质量指数 > 150</li>
 * </ul>
 * </p>
 *
 * <p>使用和风天气 API（https://devapi.qweather.com/v7/weather/3d）。</p>
 *
 * @author zsg
 * @since 2026-04-05
 */
public class WeatherSignalSource {

    private static final Logger log = LoggerFactory.getLogger(WeatherSignalSource.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    /** 温差阈值（°C）。 */
    private static final int TEMP_DIFF_THRESHOLD = 10;

    /** 空气质量指数阈值。 */
    private static final int AQI_THRESHOLD = 150;

    private final HttpClient httpClient;
    private final String weatherApiBase;

    public WeatherSignalSource(String weatherApiBase) {
        this.weatherApiBase = weatherApiBase;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();
    }

    /**
     * 拉取天气预报。
     *
     * @param apiKey   和风天气 API key
     * @param location 城市 ID 或经纬度
     * @return 明日天气预报数据，失败时返回 null
     */
    public WeatherForecast fetchWeatherForecast(String apiKey, String location) {
        Objects.requireNonNull(apiKey, "apiKey 不能为空");
        Objects.requireNonNull(location, "location 不能为空");

        try {
            String url = weatherApiBase + "?key=" + apiKey + "&location=" + location;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("天气 API 请求失败: statusCode={}, location={}", response.statusCode(), location);
                return null;
            }

            JsonNode root = MAPPER.readTree(response.body());
            String code = root.path("code").asText("");
            if (!"200".equals(code)) {
                log.warn("天气 API 业务错误: code={}, location={}", code, location);
                return null;
            }

            JsonNode daily = root.path("daily");
            if (!daily.isArray() || daily.size() < 2) {
                log.warn("天气 API 返回天数不足: size={}", daily.size());
                return null;
            }

            // 取明日数据（索引 1）
            JsonNode tomorrow = daily.get(1);
            int tempMax = tomorrow.path("tempMax").asInt(0);
            int tempMin = tomorrow.path("tempMin").asInt(0);
            String textDay = tomorrow.path("textDay").asText("");
            String textNight = tomorrow.path("textNight").asText("");

            log.debug("天气预报获取成功: location={}, tempMax={}, tempMin={}, textDay={}, textNight={}",
                    location, tempMax, tempMin, textDay, textNight);

            return new WeatherForecast(tempMax, tempMin, textDay, textNight, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("天气 API 请求被中断: location={}", location);
            return null;
        } catch (Exception e) {
            log.warn("天气 API 请求异常: location={}, error={}", location, e.getMessage());
            return null;
        }
    }

    /**
     * 评估天气预报是否需要产生提醒信号。
     *
     * @param forecast          天气预报
     * @param hasOutdoorEvents  是否有外出事件
     * @return 信号列表（可能为空）
     */
    public List<ReminderSignal> evaluateWeatherSignals(WeatherForecast forecast, boolean hasOutdoorEvents) {
        if (forecast == null) {
            return List.of();
        }

        Instant now = Instant.now();
        List<ReminderSignal> signals = new ArrayList<>();

        // 温差检查
        int tempDiff = forecast.tempMax() - forecast.tempMin();
        if (tempDiff >= TEMP_DIFF_THRESHOLD) {
            signals.add(new ReminderSignal(
                    UUID.randomUUID().toString(),
                    ReminderSignalKind.EVENT,
                    0.8f,
                    0.6f,
                    1,
                    now,
                    null,
                    null,
                    7, 9,
                    0.3f,
                    true,
                    false,
                    "明日温差较大（%d°C），注意增减衣物".formatted(tempDiff)
            ));
        }

        // 降水 + 外出事件检查
        if (hasOutdoorEvents && isRainOrSnow(forecast.textDay(), forecast.textNight())) {
            signals.add(new ReminderSignal(
                    UUID.randomUUID().toString(),
                    ReminderSignalKind.EVENT,
                    0.85f,
                    0.7f,
                    1,
                    now,
                    null,
                    null,
                    7, 9,
                    0.4f,
                    true,
                    false,
                    "明日有%s，你有外出安排，建议携带雨具".formatted(forecast.textDay())
            ));
        }

        // 空气质量检查
        if (forecast.aqi() > AQI_THRESHOLD) {
            signals.add(new ReminderSignal(
                    UUID.randomUUID().toString(),
                    ReminderSignalKind.EVENT,
                    0.75f,
                    0.55f,
                    1,
                    now,
                    null,
                    null,
                    7, 9,
                    0.35f,
                    true,
                    false,
                    "明日空气质量较差（AQI %d），建议减少户外活动".formatted(forecast.aqi())
            ));
        }

        return signals;
    }

    /** 判断天气文本是否包含降水关键词。 */
    private boolean isRainOrSnow(String textDay, String textNight) {
        String combined = textDay + textNight;
        return combined.contains("雨") || combined.contains("雪")
                || combined.contains("Rain") || combined.contains("Snow");
    }

    /**
     * 天气预报数据。
     *
     * @param tempMax   最高温度（°C）
     * @param tempMin   最低温度（°C）
     * @param textDay   白天天气描述
     * @param textNight 夜间天气描述
     * @param aqi       空气质量指数
     */
    public record WeatherForecast(
            int tempMax,
            int tempMin,
            String textDay,
            String textNight,
            int aqi
    ) {}
}
