package com.lifepilot.interaction.tray;

import java.awt.AWTException;
import java.awt.Desktop;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.Toolkit;
import java.net.URI;
import java.net.URL;

import com.lifepilot.interaction.tray.config.TrayConfigProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 系统托盘管理器 — 封装 java.awt.SystemTray 操作。
 *
 * <p>在 TRAY 模式下初始化系统托盘图标和右键菜单，
 * 提供原生通知显示、暂停/恢复和生命周期管理。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
public class TrayManager {

    private static final Logger log = LoggerFactory.getLogger(TrayManager.class);
    private static final String ICON_RESOURCE = "/tray-icon.png";

    private final TrayConfigProperties config;
    private final ConfigurableApplicationContext applicationContext;

    private TrayIcon trayIcon;
    private volatile boolean notificationPaused = false;
    private volatile boolean initialized = false;

    public TrayManager(TrayConfigProperties config,
                       ConfigurableApplicationContext applicationContext) {
        this.config = config;
        this.applicationContext = applicationContext;
    }

    /**
     * 初始化系统托盘图标和菜单。
     *
     * <p>不支持 SystemTray 时记录 WARN 日志并跳过。</p>
     */
    @PostConstruct
    public void initialize() {
        if (!SystemTray.isSupported()) {
            log.warn("系统托盘: 当前环境不支持 SystemTray，托盘功能已禁用");
            return;
        }

        try {
            Image image = loadIcon();
            PopupMenu popup = buildPopupMenu();

            trayIcon = new TrayIcon(image, config.getTooltip(), popup);
            trayIcon.setImageAutoSize(true);

            SystemTray.getSystemTray().add(trayIcon);
            initialized = true;
            log.info("系统托盘: 初始化完成");
        } catch (AWTException e) {
            log.warn("系统托盘: TrayIcon 注册失败，托盘功能已禁用: error={}", e.getMessage());
        }
    }

    /**
     * 显示原生通知。
     *
     * @param title   通知标题
     * @param message 通知内容
     * @param type    消息类型
     */
    public void displayNotification(String title, String message, TrayIcon.MessageType type) {
        if (!initialized || trayIcon == null) {
            log.debug("系统托盘: 未初始化，跳过通知显示");
            return;
        }
        trayIcon.displayMessage(title, message, type);
    }

    /**
     * 移除托盘图标并清理资源。
     */
    @PreDestroy
    public void shutdown() {
        if (initialized && trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null;
            initialized = false;
            log.info("系统托盘: 已移除托盘图标");
        }
    }

    /** 通知是否暂停。 */
    public boolean isNotificationPaused() {
        return notificationPaused;
    }

    /** 暂停通知显示。 */
    public void pauseNotifications() {
        notificationPaused = true;
        log.info("系统托盘: 通知已暂停");
    }

    /** 恢复通知显示。 */
    public void resumeNotifications() {
        notificationPaused = false;
        log.info("系统托盘: 通知已恢复");
    }

    /** 加载托盘图标，缺失时使用占位图标。 */
    private Image loadIcon() {
        URL iconUrl = getClass().getResource(ICON_RESOURCE);
        if (iconUrl != null) {
            return Toolkit.getDefaultToolkit().getImage(iconUrl);
        }
        log.warn("系统托盘: 图标资源 {} 缺失，使用默认占位图标", ICON_RESOURCE);
        // 创建 16x16 纯色占位图标
        var img = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setColor(java.awt.Color.BLUE);
        g.fillRect(0, 0, 16, 16);
        g.dispose();
        return img;
    }

    /** 构建右键菜单。 */
    private PopupMenu buildPopupMenu() {
        PopupMenu popup = new PopupMenu();

        // 查看最近提醒
        MenuItem viewNotifications = new MenuItem("查看最近提醒");
        viewNotifications.addActionListener(_ -> openBrowser(config.getWebUiUrl() + "/notifications"));
        popup.add(viewNotifications);

        // 打开 Web UI
        MenuItem openWebUi = new MenuItem("打开 Web UI");
        openWebUi.addActionListener(_ -> openBrowser(config.getWebUiUrl()));
        popup.add(openWebUi);

        popup.addSeparator();

        // 暂停/恢复通知
        MenuItem toggleNotification = new MenuItem("暂停通知");
        toggleNotification.addActionListener(_ -> {
            if (notificationPaused) {
                resumeNotifications();
                toggleNotification.setLabel("暂停通知");
            } else {
                pauseNotifications();
                toggleNotification.setLabel("恢复通知");
            }
        });
        popup.add(toggleNotification);

        popup.addSeparator();

        // 退出
        MenuItem exitItem = new MenuItem("退出");
        exitItem.addActionListener(_ -> {
            log.info("系统托盘: 用户点击退出");
            applicationContext.close();
        });
        popup.add(exitItem);

        return popup;
    }

    /** 打开浏览器，失败时通过托盘通知显示 URL。 */
    private void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            } else {
                log.warn("系统托盘: Desktop.browse() 不可用，请手动打开: url={}", url);
                displayNotification("LifePilot", "请手动打开: " + url, TrayIcon.MessageType.INFO);
            }
        } catch (Exception e) {
            log.warn("系统托盘: 打开浏览器失败: url={}, error={}", url, e.getMessage());
            displayNotification("LifePilot", "请手动打开: " + url, TrayIcon.MessageType.INFO);
        }
    }
}
