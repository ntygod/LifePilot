use tauri::{
    image::Image,
    menu::{MenuBuilder, MenuItemBuilder, PredefinedMenuItem},
    tray::TrayIconBuilder,
    AppHandle, Manager,
};

use crate::java_manager::JavaManager;

/// 创建系统托盘图标和菜单
pub fn setup_tray(app: &AppHandle) -> tauri::Result<()> {
    let open = MenuItemBuilder::with_id("open", "打开 ZhiWei").build(app)?;
    let restart = MenuItemBuilder::with_id("restart", "重启后端服务").build(app)?;
    let separator = PredefinedMenuItem::separator(app)?;
    let quit = MenuItemBuilder::with_id("quit", "退出").build(app)?;

    let menu = MenuBuilder::new(app)
        .items(&[&open, &restart, &separator, &quit])
        .build()?;

    let icon = Image::from_path("icons/icon.png")
        .unwrap_or_else(|_| Image::from_bytes(include_bytes!("../icons/icon.png")).unwrap());

    TrayIconBuilder::new()
        .icon(icon)
        .tooltip("ZhiWei - 知微 AI 助手")
        .menu(&menu)
        .on_menu_event(move |app, event| match event.id().as_ref() {
            "open" => {
                if let Some(window) = app.get_webview_window("main") {
                    let _ = window.show();
                    let _ = window.unminimize();
                    let _ = window.set_focus();
                }
            }
            "restart" => {
                let manager = app.state::<JavaManager>();
                if let Err(e) = manager.restart(app) {
                    log::error!("托盘重启后端失败: {}", e);
                }
            }
            "quit" => {
                // 先关闭 Java 后端，再退出应用
                let manager = app.state::<JavaManager>();
                let _ = manager.stop();
                app.exit(0);
            }
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let tauri::tray::TrayIconEvent::DoubleClick { .. } = event {
                if let Some(window) = tray.app_handle().get_webview_window("main") {
                    let _ = window.show();
                    let _ = window.unminimize();
                    let _ = window.set_focus();
                }
            }
        })
        .build(app)?;

    Ok(())
}
