package com.lifepilot.agent.task.reminder.tracking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 短信信号提取器 — 从转发的短信中提取结构化追踪信息。
 *
 * <p>使用模式匹配识别常见的通知短信（12306、快递、航空等），
 * 提取出追踪标识和相关时间，注册到 {@link TrackingRegistry}。</p>
 *
 * @author zsg
 * @since 2026-04-05
 */
public class SmsSignalExtractor {

    private static final Logger log = LoggerFactory.getLogger(SmsSignalExtractor.class);

    // 12306 火车票模式
    private static final Pattern TRAIN_PATTERN = Pattern.compile(
            "(\\d{1,2})月(\\d{1,2})日[^0-9]*([GCDZTK]\\d{1,4})次"
    );
    private static final Pattern TRAIN_TIME_PATTERN = Pattern.compile(
            "(\\d{1,2}):(\\d{2})开"
    );

    // 快递模式
    private static final Pattern EXPRESS_PATTERN = Pattern.compile(
            "(?:运单号|快递单号|物流单号)[：:]?\\s*([A-Z]{0,3}\\d{10,20})"
    );
    private static final Pattern EXPRESS_ARRIVE_PATTERN = Pattern.compile(
            "(?:已到|已签收|已投递|已放入|取件码)[^\\d]*([\\d-]+)?"
    );
    private static final Pattern EXPRESS_COMPANY_PATTERN = Pattern.compile(
            "(顺丰|中通|圆通|韵达|申通|极兔|京东|邮政|EMS|菜鸟|丰巢)"
    );

    // 航班模式
    private static final Pattern FLIGHT_PATTERN = Pattern.compile(
            "([A-Z]{2}\\d{3,4})(?:次|航班)"
    );
    private static final Pattern FLIGHT_DATE_PATTERN = Pattern.compile(
            "(\\d{1,2})月(\\d{1,2})日"
    );

    private final TrackingRegistry trackingRegistry;

    public SmsSignalExtractor(TrackingRegistry trackingRegistry) {
        this.trackingRegistry = trackingRegistry;
    }

    /**
     * 从短信中提取追踪信息并注册。
     *
     * @param request 短信请求
     * @param userId  用户 ID
     * @return 提取结果描述，空则表示未识别
     */
    public Optional<ExtractionResult> extract(SmsSignalRequest request, String userId) {
        String sender = request.sender() != null ? request.sender() : "";
        String body = request.body() != null ? request.body() : "";

        if (body.isBlank()) {
            return Optional.empty();
        }

        // 按发件人优先匹配
        if (sender.contains("12306") || body.contains("12306")) {
            return extractTrain(body, userId);
        }
        if (sender.contains("菜鸟") || sender.contains("快递") || sender.contains("丰巢")
                || EXPRESS_COMPANY_PATTERN.matcher(body).find()) {
            return extractExpress(body, userId);
        }
        if (sender.contains("航空") || sender.contains("航班") || FLIGHT_PATTERN.matcher(body).find()) {
            return extractFlight(body, userId);
        }

        // 兜底：尝试全部模式
        return extractTrain(body, userId)
                .or(() -> extractExpress(body, userId))
                .or(() -> extractFlight(body, userId));
    }

    private Optional<ExtractionResult> extractTrain(String body, String userId) {
        Matcher trainMatcher = TRAIN_PATTERN.matcher(body);
        if (!trainMatcher.find()) {
            return Optional.empty();
        }

        int month = Integer.parseInt(trainMatcher.group(1));
        int day = Integer.parseInt(trainMatcher.group(2));
        String trainNumber = trainMatcher.group(3);

        LocalDate date = resolveDate(month, day);
        LocalTime time = null;
        Matcher timeMatcher = TRAIN_TIME_PATTERN.matcher(body);
        if (timeMatcher.find()) {
            time = LocalTime.of(Integer.parseInt(timeMatcher.group(1)), Integer.parseInt(timeMatcher.group(2)));
        }

        Instant relevantAt = time != null
                ? LocalDateTime.of(date, time).atZone(ZoneId.systemDefault()).toInstant()
                : date.atStartOfDay(ZoneId.systemDefault()).toInstant();

        String title = trainNumber + " " + month + "月" + day + "日" + (time != null ? " " + time.format(DateTimeFormatter.ofPattern("HH:mm")) : "");

        String id = trackingRegistry.register(userId, TrackingType.TRAIN, trainNumber + ":" + date, title, relevantAt);
        log.info("短信提取火车票: trainNumber={}, date={}", trainNumber, date);
        return Optional.of(new ExtractionResult(TrackingType.TRAIN, trainNumber, title, id));
    }

    private Optional<ExtractionResult> extractExpress(String body, String userId) {
        // 尝试提取运单号
        Matcher expressMatcher = EXPRESS_PATTERN.matcher(body);
        String trackingNumber = null;
        if (expressMatcher.find()) {
            trackingNumber = expressMatcher.group(1);
        }

        // 从快递到达通知中提取
        if (trackingNumber == null) {
            // 尝试匹配纯数字运单号
            Matcher digitMatcher = Pattern.compile("\\b([A-Z]{0,3}\\d{12,18})\\b").matcher(body);
            if (digitMatcher.find()) {
                trackingNumber = digitMatcher.group(1);
            }
        }

        if (trackingNumber == null) {
            return Optional.empty();
        }

        Matcher companyMatcher = EXPRESS_COMPANY_PATTERN.matcher(body);
        String company = companyMatcher.find() ? companyMatcher.group(1) : "快递";
        String title = company + " " + trackingNumber;

        boolean arrived = EXPRESS_ARRIVE_PATTERN.matcher(body).find();
        String id = trackingRegistry.register(userId, TrackingType.PACKAGE, trackingNumber, title, null);

        if (arrived) {
            trackingRegistry.updateStatus(id, "已到达", null);
        }

        log.info("短信提取快递: company={}, trackingNumber={}, arrived={}", company, trackingNumber, arrived);
        return Optional.of(new ExtractionResult(TrackingType.PACKAGE, trackingNumber, title, id));
    }

    private Optional<ExtractionResult> extractFlight(String body, String userId) {
        Matcher flightMatcher = FLIGHT_PATTERN.matcher(body);
        if (!flightMatcher.find()) {
            return Optional.empty();
        }

        String flightNumber = flightMatcher.group(1);
        Matcher dateMatcher = FLIGHT_DATE_PATTERN.matcher(body);
        LocalDate date = dateMatcher.find()
                ? resolveDate(Integer.parseInt(dateMatcher.group(1)), Integer.parseInt(dateMatcher.group(2)))
                : LocalDate.now();

        Instant relevantAt = date.atStartOfDay(ZoneId.systemDefault()).toInstant();
        String title = flightNumber + " " + date.getMonthValue() + "月" + date.getDayOfMonth() + "日";

        String id = trackingRegistry.register(userId, TrackingType.FLIGHT, flightNumber + ":" + date, title, relevantAt);
        log.info("短信提取航班: flightNumber={}, date={}", flightNumber, date);
        return Optional.of(new ExtractionResult(TrackingType.FLIGHT, flightNumber, title, id));
    }

    private LocalDate resolveDate(int month, int day) {
        LocalDate now = LocalDate.now();
        LocalDate candidate = LocalDate.of(now.getYear(), month, day);
        // 如果日期已过超过 30 天，认为是明年
        if (candidate.isBefore(now.minusDays(30))) {
            candidate = candidate.plusYears(1);
        }
        return candidate;
    }

    /**
     * 提取结果。
     */
    public record ExtractionResult(TrackingType type, String trackingKey, String title, String entryId) {
    }
}
