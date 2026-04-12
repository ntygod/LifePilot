package com.lifepilot.agent.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.reminder.ReminderSignal;
import com.lifepilot.agent.task.reminder.ReminderSignalKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 Open-Meteo 的天气服务 — 完全免费，无需 API key。
 *
 * <p>提供两条利用路径：
 * <ul>
 *   <li>对话上下文注入：{@link #getWeatherSummary()} 仅读缓存，不阻塞</li>
 *   <li>主动提醒信号：{@link #evaluateWeatherSignals(boolean)} 评估异常天气条件</li>
 * </ul>
 *
 * <p>天气数据通过后台 virtual thread 异步预取并缓存，对话路径零阻塞。</p>
 *
 * @author zsg
 * @since 2026-04-12
 */
public class OpenMeteoWeatherService implements WeatherService {

    private static final Logger log = LoggerFactory.getLogger(OpenMeteoWeatherService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private static final String GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast";

    private final LocationResolver locationResolver;
    private final AgentConfigProperties config;
    private final HttpClient httpClient;

    /** 地理编码缓存（永久）。 */
    private final AtomicReference<GeoLocation> cachedGeo = new AtomicReference<>();

    /** 天气预报缓存。 */
    private final AtomicReference<CachedForecast> cachedForecast = new AtomicReference<>();

    /** 标记是否已触发过后台预取。 */
    private volatile boolean prefetchTriggered;

    public OpenMeteoWeatherService(LocationResolver locationResolver,
                                   AgentConfigProperties config) {
        this.locationResolver = locationResolver;
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    @Override
    public void triggerPrefetch() {
        if (prefetchTriggered) {
            return;
        }
        prefetchTriggered = true;
        Thread.ofVirtual().name("weather-prefetch").start(() -> {
            try {
                fetchWeatherData();
                log.info("天气数据预取完成");
            } catch (Exception e) {
                log.debug("天气数据预取失败（不影响功能）: {}", e.getMessage());
            }
        });
    }

    /**
     * 获取天气摘要文本，用于注入对话上下文。
     *
     * <p>仅读缓存，不发起网络请求，不会阻塞对话路径。
     * 缓存由后台预取和心跳周期刷新。</p>
     *
     * @return 天气摘要文本，或 null（缓存为空或过期时）
     */
    @Override
    @Nullable
    public String getWeatherSummary() {
        WeatherData data = getCachedWeatherData();
        if (data == null) {
            return null;
        }
        return "%s %d°C %s；今日 %d~%d°C；明日 %s %d~%d°C".formatted(
                data.locationName(),
                data.currentTemp(), data.currentDesc(),
                data.todayMin(), data.todayMax(),
                data.tomorrowDesc(), data.tomorrowMin(), data.tomorrowMax()
        );
    }

    /**
     * 评估是否有异常天气需要产生提醒信号。
     *
     * <p>由心跳周期调用，允许同步拉取（心跳在后台线程执行，不阻塞用户）。</p>
     *
     * @param hasOutdoorEvents 用户明日是否有外出事件
     * @return 信号列表（可能为空）
     */
    @Override
    public List<ReminderSignal> evaluateWeatherSignals(boolean hasOutdoorEvents) {
        WeatherData data = getOrFetchWeatherData();
        if (data == null) {
            return List.of();
        }

        var weatherConfig = config.getTask();
        int tempDiffThreshold = weatherConfig.getWeatherTempDiffThreshold();
        double precipThreshold = weatherConfig.getWeatherPrecipitationThreshold();
        double heavyPrecipThreshold = weatherConfig.getWeatherHeavyPrecipitationThreshold();

        Instant now = Instant.now();
        List<ReminderSignal> signals = new ArrayList<>();

        // 温差检查
        int tempDiff = data.tomorrowMax() - data.tomorrowMin();
        if (tempDiff >= tempDiffThreshold) {
            signals.add(new ReminderSignal(
                    UUID.randomUUID().toString(),
                    ReminderSignalKind.EVENT,
                    0.8f, 0.6f, 1,
                    now, null, null,
                    7, 9,
                    0.3f, true, false,
                    "明日温差较大（%d°C），注意增减衣物".formatted(tempDiff)
            ));
        }

        // 降水检查（有外出事件时才提醒）
        if (hasOutdoorEvents && data.tomorrowPrecipitation() > precipThreshold) {
            signals.add(new ReminderSignal(
                    UUID.randomUUID().toString(),
                    ReminderSignalKind.EVENT,
                    0.85f, 0.7f, 1,
                    now, null, null,
                    7, 9,
                    0.4f, true, false,
                    "明日有%s（降水 %.1fmm），你有外出安排，建议携带雨具".formatted(
                            data.tomorrowDesc(), data.tomorrowPrecipitation())
            ));
        }

        // 强降水（无论是否外出）
        if (data.tomorrowPrecipitation() > heavyPrecipThreshold) {
            signals.add(new ReminderSignal(
                    UUID.randomUUID().toString(),
                    ReminderSignalKind.EVENT,
                    0.9f, 0.75f, 1,
                    now, null, null,
                    7, 9,
                    0.5f, true, false,
                    "明日有强降水（%s，%.1fmm），出行请注意安全".formatted(
                            data.tomorrowDesc(), data.tomorrowPrecipitation())
            ));
        }

        return signals;
    }

    // ===== 内部方法 =====

    /** 仅读缓存，不发网络请求。 */
    @Nullable
    private WeatherData getCachedWeatherData() {
        CachedForecast cached = cachedForecast.get();
        if (cached == null) {
            return null;
        }
        long cacheTtlHours = config.getTask().getWeatherCacheTtlHours();
        if (Duration.between(cached.fetchedAt(), Instant.now()).toHours() >= cacheTtlHours) {
            return null;
        }
        return cached.data();
    }

    /** 带缓存的天气数据获取（缓存命中直接返回，否则同步拉取）。 */
    @Nullable
    private WeatherData getOrFetchWeatherData() {
        WeatherData cached = getCachedWeatherData();
        if (cached != null) {
            return cached;
        }
        return fetchWeatherData();
    }

    /** 同步拉取天气数据并更新缓存。 */
    @Nullable
    private WeatherData fetchWeatherData() {
        GeoLocation geo = getOrFetchGeoLocation();
        if (geo == null) {
            return null;
        }

        try {
            String url = FORECAST_URL
                    + "?latitude=" + geo.latitude()
                    + "&longitude=" + geo.longitude()
                    + "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_sum"
                    + "&current=temperature_2m,weather_code"
                    + "&timezone=auto&forecast_days=2";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("天气 API 请求失败: statusCode={}", response.statusCode());
                return null;
            }

            JsonNode root = MAPPER.readTree(response.body());
            WeatherData data = parseWeatherResponse(root, geo.name());
            if (data != null) {
                cachedForecast.set(new CachedForecast(data, Instant.now()));
                log.debug("天气数据获取成功: location={}, currentTemp={}°C", geo.name(), data.currentTemp());
            }
            return data;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("天气 API 请求被中断");
            return null;
        } catch (Exception e) {
            log.debug("天气 API 请求异常: {}", e.getMessage());
            return null;
        }
    }

    @Nullable
    private GeoLocation getOrFetchGeoLocation() {
        GeoLocation cached = cachedGeo.get();
        if (cached != null) {
            return cached;
        }

        String city = locationResolver.resolve();
        if (city == null || city.isBlank() || "未设置".equals(city)) {
            return null;
        }

        // CAS 保护：同一城市只尝试一次地理编码
        if (!cachedGeo.compareAndSet(null, null)) {
            return cachedGeo.get();
        }

        try {
            String encoded = URLEncoder.encode(city, StandardCharsets.UTF_8);
            String url = GEOCODING_URL + "?name=" + encoded + "&count=1&language=zh";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("地理编码请求失败: statusCode={}", response.statusCode());
                return null;
            }

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                log.debug("地理编码未找到结果: city={}", city);
                return null;
            }

            JsonNode first = results.get(0);
            double lat = first.path("latitude").asDouble();
            double lon = first.path("longitude").asDouble();
            String name = first.path("name").asText(city);

            GeoLocation geo = new GeoLocation(lat, lon, name);
            cachedGeo.set(geo);
            log.info("地理编码成功: city={} → lat={}, lon={}", name, lat, lon);
            return geo;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("地理编码请求被中断");
            return null;
        } catch (Exception e) {
            log.debug("地理编码请求异常: {}", e.getMessage());
            return null;
        }
    }

    @Nullable
    private WeatherData parseWeatherResponse(JsonNode root, String locationName) {
        try {
            JsonNode current = root.path("current");
            int currentTemp = current.path("temperature_2m").asInt(0);
            int currentCode = current.path("weather_code").asInt(-1);

            JsonNode daily = root.path("daily");
            JsonNode maxTemps = daily.path("temperature_2m_max");
            JsonNode minTemps = daily.path("temperature_2m_min");
            JsonNode codes = daily.path("weather_code");
            JsonNode precip = daily.path("precipitation_sum");

            if (!maxTemps.isArray() || maxTemps.size() < 2) {
                log.debug("天气数据天数不足: size={}", maxTemps.size());
                return null;
            }

            return new WeatherData(
                    locationName,
                    currentTemp, wmoCodeToDescription(currentCode),
                    maxTemps.get(0).asInt(), minTemps.get(0).asInt(), wmoCodeToDescription(codes.get(0).asInt()),
                    maxTemps.get(1).asInt(), minTemps.get(1).asInt(), wmoCodeToDescription(codes.get(1).asInt()),
                    precip.size() > 1 ? precip.get(1).asDouble(0) : 0
            );
        } catch (Exception e) {
            log.debug("天气数据解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * WMO 天气代码转中文描述。
     *
     * @see <a href="https://open-meteo.com/en/docs">Open-Meteo WMO Weather interpretation codes</a>
     */
    private static String wmoCodeToDescription(int code) {
        return switch (code) {
            case 0 -> "晴";
            case 1 -> "大部晴";
            case 2 -> "多云";
            case 3 -> "阴";
            case 45, 48 -> "雾";
            case 51 -> "小毛毛雨";
            case 53 -> "毛毛雨";
            case 55 -> "大毛毛雨";
            case 56, 57 -> "冻毛毛雨";
            case 61 -> "小雨";
            case 63 -> "中雨";
            case 65 -> "大雨";
            case 66, 67 -> "冻雨";
            case 71 -> "小雪";
            case 73 -> "中雪";
            case 75 -> "大雪";
            case 77 -> "米雪";
            case 80 -> "小阵雨";
            case 81 -> "阵雨";
            case 82 -> "强阵雨";
            case 85 -> "小阵雪";
            case 86 -> "大阵雪";
            case 95 -> "雷阵雨";
            case 96, 99 -> "雷阵雨伴冰雹";
            default -> "未知";
        };
    }

    // ===== 内部类型 =====

    record GeoLocation(double latitude, double longitude, String name) {}

    record CachedForecast(WeatherData data, Instant fetchedAt) {}

    record WeatherData(
            String locationName,
            int currentTemp, String currentDesc,
            int todayMax, int todayMin, String todayDesc,
            int tomorrowMax, int tomorrowMin, String tomorrowDesc,
            double tomorrowPrecipitation
    ) {}
}
