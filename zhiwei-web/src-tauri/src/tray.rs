use tauri::{
    image::Image,
    menu::{CheckMenuItemBuilder, MenuBuilder, MenuItemBuilder, PredefinedMenuItem},
    tray::TrayIconBuilder,
    AppHandle, Listener, Manager,
};

use crate::java_manager::JavaManager;

/// 创建系统托盘图标和菜单
pub fn setup_tray(app: &AppHandle) -> tauri::Result<()> {
    // 主操作
    let open = MenuItemBuilder::with_id("open", "打开 ZhiWei").build(app)?;
    let sep1 = PredefinedMenuItem::separator(app)?;

    // 后端控制
    let status = MenuItemBuilder::with_id("status", "● 后端启动中...")
        .enabled(false)
        .build(app)?;
    let restart = MenuItemBuilder::with_id("restart", "重启后端服务").build(app)?;
    let sep2 = PredefinedMenuItem::separator(app)?;

    // 设置
    let autostart = CheckMenuItemBuilder::with_id("autostart", "开机自启动")
        .checked(false)
        .build(app)?;
    let sep3 = PredefinedMenuItem::separator(app)?;

    // 版本和退出
    let version = MenuItemBuilder::with_id("version", format!("版本 {}", env!("CARGO_PKG_VERSION")))
        .enabled(false)
        .build(app)?;
    let quit = MenuItemBuilder::with_id("quit", "退出 ZhiWei").build(app)?;

    let menu = MenuBuilder::new(app)
        .items(&[&open, &sep1, &status, &restart, &sep2, &autostart, &sep3, &version, &quit])
        .build()?;

    let icon = Image::from_bytes(include_bytes!("../icons/icon.png"))
        .expect("内嵌图标解码失败");

    let tray = TrayIconBuilder::new()
        .icon(icon)
        .tooltip("ZhiWei - 知微 AI 助手")
        .menu(&menu)
        .on_menu_event(move |app, event| match event.id().as_ref() {
            "open" => show_main_window(app),
            "restart" => {
                let manager = app.state::<JavaManager>();
                if let Err(e) = manager.restart(app) {
                    log::error!("托盘重启后端失败: {}", e);
                }
            }
            "autostart" => {
                // Phase 2: 对接 tauri-plugin-autostart
                log::info!("开机自启动设置变更");
            }
            "quit" => {
                let manager = app.state::<JavaManager>();
                let _ = manager.stop();
                app.exit(0);
            }
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let tauri::tray::TrayIconEvent::DoubleClick { .. } = event {
                show_main_window(tray.app_handle());
            }
        })
        .build(app)?;

    // 监听后端就绪事件，更新托盘状态文字
    let tray_id = tray.id().clone();
    let app_handle = app.clone();
    app.listen("backend-ready", move |_| {
        if let Some(tray) = app_handle.tray_by_id(&tray_id) {
            let _ = tray.set_tooltip(Some("ZhiWei - 知微 AI 助手 (运行中)"));
        }
        // 更新状态菜单项
        update_status_menu(&app_handle, true);
    });

    let tray_id2 = tray.id().clone();
    let app_handle2 = app.clone();
    app.listen("backend-error", move |_| {
        if let Some(tray) = app_handle2.tray_by_id(&tray_id2) {
            let _ = tray.set_tooltip(Some("ZhiWei - 知微 AI 助手 (后端异常)"));
        }
        update_status_menu(&app_handle2, false);
    });

    Ok(())
}

fn show_main_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

fn update_status_menu(app: &AppHandle, running: bool) {
    if let Some(item) = app.menu().and_then(|m| m.get("status")) {
        if let Some(menu_item) = item.as_menuitem() {
            let text = if running { "● 后端运行中" } else { "○ 后端已停止" };
            let _ = menu_item.set_text(text);
        }
    }
}
