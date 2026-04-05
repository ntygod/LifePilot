use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use futures_util::StreamExt;
use serde::Serialize;
use tauri::{AppHandle, Emitter};

/// Whisper CLI 版本号（对应 GitHub Releases tag）
const WHISPER_VERSION: &str = "1.8.4";

/// 模型文件名
const MODEL_FILENAME: &str = "ggml-base.bin";

/// 下载所需最小磁盘空间（字节）
const MIN_DISK_SPACE: u64 = 200 * 1024 * 1024;

/// 进度事件发送间隔（毫秒）
const PROGRESS_INTERVAL_MS: u128 = 300;

/// Whisper 下载状态
#[derive(Debug, Clone, PartialEq)]
#[allow(dead_code)]
enum WhisperStatus {
    /// 空闲
    Idle,
    /// 下载中
    Downloading,
    /// 已就绪
    Available,
    /// 出错
    Error(String),
}

/// 前端进度事件负载
#[derive(Debug, Clone, Serialize)]
pub struct DownloadProgress {
    pub stage: String,
    pub progress: u8,
    pub downloaded: u64,
    pub total: u64,
    pub speed_bps: u64,
}

/// 下载完成事件负载
#[derive(Debug, Clone, Serialize)]
pub struct DownloadComplete {
    pub cli_path: String,
    pub model_path: String,
}

/// 下载错误事件负载
#[derive(Debug, Clone, Serialize)]
pub struct DownloadError {
    pub message: String,
    pub recoverable: bool,
}

/// Whisper CLI 可用性检查结果
#[derive(Debug, Clone, Serialize)]
pub struct WhisperAvailability {
    pub available: bool,
    pub cli_path: String,
    pub model_path: String,
    pub downloading: bool,
}

/// Whisper CLI + 模型自动下载管理器
pub struct WhisperManager {
    /// 数据目录（~/.zhiwei）
    data_dir: PathBuf,
    /// 当前状态（Arc 共享给异步下载任务）
    status: Arc<Mutex<WhisperStatus>>,
}

impl WhisperManager {
    /// 创建管理器实例，数据目录从 `~/.zhiwei` 解析
    pub fn new() -> Self {
        let data_dir = dirs::home_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join(".zhiwei");

        Self {
            data_dir,
            status: Arc::new(Mutex::new(WhisperStatus::Idle)),
        }
    }

    /// 返回 whisper-cli 的目标路径（确定性，不检查是否存在）
    pub fn cli_path(&self) -> PathBuf {
        self.data_dir.join("bin").join(cli_filename())
    }

    /// 返回模型文件的目标路径（确定性，不检查是否存在）
    pub fn model_path(&self) -> PathBuf {
        self.data_dir.join("models").join(MODEL_FILENAME)
    }

    /// 检测 whisper-cli 和模型是否都已就绪
    pub fn check_availability(&self) -> WhisperAvailability {
        let cli = self.cli_path();
        let model = self.model_path();
        let cli_ok = cli.exists() && is_executable(&cli);
        let model_ok = model.exists()
            && std::fs::metadata(&model).map(|m| m.len() > 1024 * 1024).unwrap_or(false);
        let downloading = {
            let s = self.status.lock().expect("WhisperManager status mutex 中毒");
            *s == WhisperStatus::Downloading
        };

        WhisperAvailability {
            available: cli_ok && model_ok,
            cli_path: cli.to_string_lossy().to_string(),
            model_path: model.to_string_lossy().to_string(),
            downloading,
        }
    }

    /// 启动异步下载（非阻塞，立即返回）
    pub fn start_download(&self, app: AppHandle) -> Result<(), String> {
        {
            let mut s = self.status.lock().expect("WhisperManager status mutex 中毒");
            if *s == WhisperStatus::Downloading {
                return Err("已有下载任务进行中".into());
            }
            *s = WhisperStatus::Downloading;
        }

        let cli_path = self.cli_path();
        let model_path = self.model_path();
        let data_dir = self.data_dir.clone();
        let status_ref = Arc::clone(&self.status);
        let cancel_marker = data_dir.join("bin").join(".whisper-cancel");

        tauri::async_runtime::spawn(async move {
            let result =
                download_all(&app, &data_dir, &cli_path, &model_path, &cancel_marker).await;
            let mut s = status_ref.lock().expect("WhisperManager status mutex 中毒");
            match result {
                Ok(()) => {
                    *s = WhisperStatus::Available;
                    let _ = app.emit(
                        "whisper-download-complete",
                        DownloadComplete {
                            cli_path: cli_path.to_string_lossy().to_string(),
                            model_path: model_path.to_string_lossy().to_string(),
                        },
                    );
                }
                Err(e) => {
                    log::error!("Whisper 下载失败: {}", e);
                    *s = WhisperStatus::Error(e.clone());
                    let _ = app.emit(
                        "whisper-download-error",
                        DownloadError {
                            message: e,
                            recoverable: true,
                        },
                    );
                }
            }
        });

        Ok(())
    }

    /// 标记取消下载
    pub fn cancel_download(&self) -> Result<(), String> {
        let cancel_marker = self.data_dir.join("bin").join(".whisper-cancel");
        if let Some(parent) = cancel_marker.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|e| format!("创建目录失败: {}", e))?;
        }
        std::fs::write(&cancel_marker, b"cancel")
            .map_err(|e| format!("写入取消标记失败: {}", e))?;
        // 异步任务会在下一轮 chunk 检测到取消标记后自行更新状态
        Ok(())
    }
}

/// 执行完整下载流程
async fn download_all(
    app: &AppHandle,
    data_dir: &Path,
    cli_path: &Path,
    model_path: &Path,
    cancel_marker: &Path,
) -> Result<(), String> {
    // 清理可能残留的取消标记
    let _ = std::fs::remove_file(cancel_marker);

    // 1. 创建目录
    std::fs::create_dir_all(data_dir.join("bin"))
        .map_err(|e| format!("创建 bin 目录失败: {}", e))?;
    std::fs::create_dir_all(data_dir.join("models"))
        .map_err(|e| format!("创建 models 目录失败: {}", e))?;

    // 2. 检查磁盘空间
    emit_progress(app, "正在检查磁盘空间...", 0, 0, 0, 0);
    check_disk_space(data_dir)?;

    if is_cancelled(cancel_marker) {
        return Err("下载已取消".into());
    }

    // 3. 下载 CLI 压缩包
    let cli_url = build_cli_download_url();
    let zip_path = data_dir.join("bin").join("whisper-cli.zip");
    emit_progress(app, "正在下载语音引擎...", 1, 0, 0, 0);

    download_file_with_progress(app, &cli_url, &zip_path, cancel_marker, "正在下载语音引擎...", 1, 30)
        .await?;

    // 4. 解压 CLI
    emit_progress(app, "正在解压语音引擎...", 31, 0, 0, 0);
    extract_cli_from_zip(&zip_path, cli_path)?;
    let _ = std::fs::remove_file(&zip_path);

    // 5. 设置可执行权限（Unix）
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut perms = std::fs::metadata(cli_path)
            .map_err(|e| format!("读取文件权限失败: {}", e))?
            .permissions();
        perms.set_mode(0o755);
        std::fs::set_permissions(cli_path, perms)
            .map_err(|e| format!("设置可执行权限失败: {}", e))?;
    }

    if is_cancelled(cancel_marker) {
        return Err("下载已取消".into());
    }

    // 6. 下载模型文件（支持断点续传）
    let model_url = build_model_download_url();
    emit_progress(app, "正在下载语音模型...", 35, 0, 0, 0);

    download_file_with_progress(app, &model_url, model_path, cancel_marker, "正在下载语音模型...", 35, 95)
        .await?;

    // 7. 验证
    emit_progress(app, "正在验证...", 96, 0, 0, 0);
    verify_cli(cli_path)?;
    verify_model(model_path)?;

    emit_progress(app, "下载完成", 100, 0, 0, 0);
    let _ = std::fs::remove_file(cancel_marker);

    Ok(())
}

/// 带进度追踪的文件下载（支持断点续传）
async fn download_file_with_progress(
    app: &AppHandle,
    url: &str,
    target_path: &Path,
    cancel_marker: &Path,
    stage_label: &str,
    progress_start: u8,
    progress_end: u8,
) -> Result<(), String> {
    let part_path = PathBuf::from(format!("{}.part", target_path.display()));

    // 检查已有的部分下载
    let existing_bytes = if part_path.exists() {
        std::fs::metadata(&part_path).map(|m| m.len()).unwrap_or(0)
    } else {
        0
    };

    let client = reqwest::Client::new();
    let mut request = client.get(url);

    if existing_bytes > 0 {
        log::info!("断点续传: 从 {} 字节继续下载 {}", existing_bytes, url);
        request = request.header("Range", format!("bytes={}-", existing_bytes));
    }

    let response = request
        .send()
        .await
        .map_err(|e| format!("网络请求失败: {}", e))?;

    if !response.status().is_success() && response.status().as_u16() != 206 {
        return Err(format!("下载失败，HTTP 状态码: {}", response.status()));
    }

    let total_bytes = if response.status().as_u16() == 206 {
        response
            .headers()
            .get("content-range")
            .and_then(|v| v.to_str().ok())
            .and_then(|s| s.rsplit('/').next())
            .and_then(|s| s.parse::<u64>().ok())
            .unwrap_or(0)
    } else {
        response.content_length().unwrap_or(0)
    };

    let mut downloaded = existing_bytes;
    let mut file = if existing_bytes > 0 && response.status().as_u16() == 206 {
        std::fs::OpenOptions::new()
            .append(true)
            .open(&part_path)
            .map_err(|e| format!("打开文件失败: {}", e))?
    } else {
        downloaded = 0;
        std::fs::File::create(&part_path).map_err(|e| format!("创建文件失败: {}", e))?
    };

    let mut stream = response.bytes_stream();
    let mut last_emit = std::time::Instant::now();
    let mut speed_bytes = 0u64;
    let mut speed_start = std::time::Instant::now();

    while let Some(chunk) = stream.next().await {
        if is_cancelled(cancel_marker) {
            return Err("下载已取消".into());
        }

        let chunk = chunk.map_err(|e| format!("下载数据块失败: {}", e))?;
        file.write_all(&chunk).map_err(|e| format!("写入文件失败: {}", e))?;
        downloaded += chunk.len() as u64;
        speed_bytes += chunk.len() as u64;

        if last_emit.elapsed().as_millis() >= PROGRESS_INTERVAL_MS {
            let elapsed_secs = speed_start.elapsed().as_secs_f64();
            let speed = if elapsed_secs > 0.0 {
                (speed_bytes as f64 / elapsed_secs) as u64
            } else {
                0
            };

            let pct = if total_bytes > 0 {
                let ratio = downloaded as f64 / total_bytes as f64;
                progress_start + ((progress_end - progress_start) as f64 * ratio) as u8
            } else {
                progress_start
            };

            emit_progress(app, stage_label, pct, downloaded, total_bytes, speed);
            last_emit = std::time::Instant::now();
            speed_bytes = 0;
            speed_start = std::time::Instant::now();
        }
    }

    file.flush().map_err(|e| format!("刷新文件失败: {}", e))?;
    drop(file);

    std::fs::rename(&part_path, target_path).map_err(|e| format!("重命名文件失败: {}", e))?;

    Ok(())
}

/// 从 zip 压缩包中提取 whisper-cli 二进制
fn extract_cli_from_zip(zip_path: &Path, target: &Path) -> Result<(), String> {
    let file =
        std::fs::File::open(zip_path).map_err(|e| format!("打开 zip 文件失败: {}", e))?;
    let mut archive =
        zip::ZipArchive::new(file).map_err(|e| format!("解析 zip 文件失败: {}", e))?;

    let cli_name = cli_filename();

    // 第一轮：精确匹配文件名
    for i in 0..archive.len() {
        let mut entry = archive
            .by_index(i)
            .map_err(|e| format!("读取 zip 条目失败: {}", e))?;

        let name = entry.name().to_string();
        if name.ends_with(&cli_name) || name.ends_with(&format!("/{}", cli_name)) {
            let mut out =
                std::fs::File::create(target).map_err(|e| format!("创建目标文件失败: {}", e))?;
            std::io::copy(&mut entry, &mut out).map_err(|e| format!("提取文件失败: {}", e))?;
            log::info!("已提取 whisper-cli 到: {}", target.display());
            return Ok(());
        }
    }

    // 第二轮：模糊匹配含 "whisper" 的可执行文件
    for i in 0..archive.len() {
        let mut entry = archive
            .by_index(i)
            .map_err(|e| format!("读取 zip 条目失败: {}", e))?;

        let name = entry.name().to_string();
        let is_executable = if cfg!(target_os = "windows") {
            name.ends_with(".exe")
        } else {
            !name.contains('.') || name.ends_with("/whisper")
        };

        if name.contains("whisper") && is_executable && !entry.is_dir() {
            let mut out =
                std::fs::File::create(target).map_err(|e| format!("创建目标文件失败: {}", e))?;
            std::io::copy(&mut entry, &mut out).map_err(|e| format!("提取文件失败: {}", e))?;
            log::info!(
                "已提取 whisper 可执行文件 '{}' 到: {}",
                name,
                target.display()
            );
            return Ok(());
        }
    }

    Err("zip 压缩包中未找到 whisper-cli 可执行文件".into())
}

/// 验证 CLI 可执行
fn verify_cli(cli_path: &Path) -> Result<(), String> {
    if !cli_path.exists() {
        return Err("whisper-cli 文件不存在".into());
    }
    if !is_executable(cli_path) {
        return Err("whisper-cli 无执行权限".into());
    }
    log::info!("whisper-cli 验证通过: {}", cli_path.display());
    Ok(())
}

/// 验证模型文件完整性（基于大小检查）
fn verify_model(model_path: &Path) -> Result<(), String> {
    let meta = std::fs::metadata(model_path)
        .map_err(|e| format!("读取模型文件元数据失败: {}", e))?;

    if meta.len() < 100 * 1024 * 1024 {
        return Err(format!(
            "模型文件大小异常: {} 字节，可能下载不完整",
            meta.len()
        ));
    }
    log::info!(
        "模型文件验证通过: {} ({} MB)",
        model_path.display(),
        meta.len() / 1024 / 1024
    );
    Ok(())
}

/// 检查磁盘空间
fn check_disk_space(path: &Path) -> Result<(), String> {
    let test_file = path.join(".space-check");
    std::fs::write(&test_file, b"ok").map_err(|e| format!("磁盘写入测试失败: {}", e))?;
    let _ = std::fs::remove_file(&test_file);

    let disks = sysinfo::Disks::new_with_refreshed_list();
    for disk in disks.list() {
        let mount = disk.mount_point();
        if path.starts_with(mount) {
            if disk.available_space() < MIN_DISK_SPACE {
                return Err(format!(
                    "磁盘空间不足，需要约 200MB，当前可用: {} MB",
                    disk.available_space() / 1024 / 1024
                ));
            }
            return Ok(());
        }
    }

    log::warn!("无法检测磁盘空间，继续下载");
    Ok(())
}

/// 检查是否已取消
fn is_cancelled(cancel_marker: &Path) -> bool {
    cancel_marker.exists()
}

/// 发送进度事件
fn emit_progress(
    app: &AppHandle,
    stage: &str,
    progress: u8,
    downloaded: u64,
    total: u64,
    speed_bps: u64,
) {
    let _ = app.emit(
        "whisper-download-progress",
        DownloadProgress {
            stage: stage.to_string(),
            progress,
            downloaded,
            total,
            speed_bps,
        },
    );
}

/// 获取平台对应的 CLI 文件名
fn cli_filename() -> String {
    if cfg!(target_os = "windows") {
        "whisper-cli.exe".to_string()
    } else {
        "whisper-cli".to_string()
    }
}

/// 构建 whisper-cli 下载 URL
///
/// GitHub Releases 资源命名格式（v1.8.x 起）：
/// - Windows x64: `whisper-bin-x64.zip`
/// - Windows x86: `whisper-bin-Win32.zip`
fn build_cli_download_url() -> String {
    let archive = platform_archive_name();
    format!(
        "https://github.com/ggerganov/whisper.cpp/releases/download/v{}/{}",
        WHISPER_VERSION, archive
    )
}

/// 构建模型下载 URL
fn build_model_download_url() -> String {
    format!(
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/{}",
        MODEL_FILENAME
    )
}

/// 生成平台对应的压缩包名（仅 Windows 提供预编译包）
fn platform_archive_name() -> String {
    if cfg!(target_os = "windows") {
        let arch = if cfg!(target_arch = "aarch64") || cfg!(target_arch = "x86") {
            "Win32"
        } else {
            "x64"
        };
        format!("whisper-bin-{}.zip", arch)
    } else {
        // macOS / Linux 暂无官方预编译 CLI，保留旧格式以便后续适配
        let os = if cfg!(target_os = "macos") { "macos" } else { "linux" };
        let arch = if cfg!(target_arch = "aarch64") { "arm64" } else { "x64" };
        format!("whisper-{}-bin-{}-{}.zip", WHISPER_VERSION, os, arch)
    }
}

/// 检查文件是否可执行
fn is_executable(path: &Path) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::metadata(path)
            .map(|m| m.permissions().mode() & 0o111 != 0)
            .unwrap_or(false)
    }
    #[cfg(windows)]
    {
        path.exists()
    }
}
