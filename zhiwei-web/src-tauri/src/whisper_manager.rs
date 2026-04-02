use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use futures_util::StreamExt;
use serde::Serialize;
use tauri::{AppHandle, Emitter};

/// Whisper CLI 版本号
const WHISPER_VERSION: &str = "1.7.3";

/// Hugging Face 模型下载地址
const MODEL_URL: &str =
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin";

/// 下载状态枚举
#[derive(Debug, Clone, PartialEq)]
enum WhisperStatus {
    /// 空闲
    Idle,
    /// 下载中
    Downloading,
}

/// 可用性检查结果，通过 Tauri command 返回给前端
#[derive(Debug, Clone, Serialize)]
pub struct WhisperAvailability {
    pub available: bool,
    pub cli_path: Option<String>,
    pub model_path: Option<String>,
    pub downloading: bool,
}

/// 下载进度事件载荷
#[derive(Debug, Clone, Serialize)]
struct DownloadProgress {
    stage: String,
    progress: f64,
    downloaded: u64,
    total: u64,
    speed_bps: u64,
}

/// 下载完成事件载荷
#[derive(Debug, Clone, Serialize)]
struct DownloadComplete {
    cli_path: String,
    model_path: String,
}

/// 下载错误事件载荷
#[derive(Debug, Clone, Serialize)]
struct DownloadError {
    message: String,
    recoverable: bool,
}

/// Whisper 语音引擎管理器
///
/// 负责检测、下载、解压 whisper-cli 和 ggml 模型文件。
/// 通过 Tauri 事件向前端汇报下载进度。
pub struct WhisperManager {
    /// 数据目录（~/.zhiwei）
    data_dir: PathBuf,
    /// 下载状态（共享给异步任务）
    status: Arc<Mutex<WhisperStatus>>,
}

impl WhisperManager {
    /// 创建新的 WhisperManager 实例
    pub fn new() -> Self {
        let data_dir = dirs::home_dir()
            .expect("无法获取用户主目录")
            .join(".zhiwei");

        log::info!("WhisperManager 初始化: 数据目录={}", data_dir.display());

        Self {
            data_dir,
            status: Arc::new(Mutex::new(WhisperStatus::Idle)),
        }
    }

    /// CLI 可执行文件路径
    fn cli_path(&self) -> PathBuf {
        let name = if cfg!(target_os = "windows") {
            "whisper-cli.exe"
        } else {
            "whisper-cli"
        };
        self.data_dir.join("bin").join(name)
    }

    /// 模型文件路径
    fn model_path(&self) -> PathBuf {
        self.data_dir.join("models").join("ggml-base.bin")
    }

    /// 取消标记文件路径
    fn cancel_marker(&self) -> PathBuf {
        self.data_dir.join("bin").join(".whisper-download-cancel")
    }

    /// 检查 whisper-cli 和模型是否可用
    pub fn check_availability(&self) -> WhisperAvailability {
        let cli = self.cli_path();
        let model = self.model_path();
        let cli_ok = cli.exists();
        let model_ok = model.exists();
        let downloading = {
            let s = self.status.lock().expect("WhisperStatus mutex 中毒");
            *s == WhisperStatus::Downloading
        };

        WhisperAvailability {
            available: cli_ok && model_ok,
            cli_path: if cli_ok {
                Some(cli.to_string_lossy().into_owned())
            } else {
                None
            },
            model_path: if model_ok {
                Some(model.to_string_lossy().into_owned())
            } else {
                None
            },
            downloading,
        }
    }

    /// 获取 CLI 路径（如果文件存在）
    pub fn cli_path_if_exists(&self) -> Option<PathBuf> {
        let p = self.cli_path();
        if p.exists() {
            Some(p)
        } else {
            None
        }
    }

    /// 获取模型路径（如果文件存在）
    pub fn model_path_if_exists(&self) -> Option<PathBuf> {
        let p = self.model_path();
        if p.exists() {
            Some(p)
        } else {
            None
        }
    }

    /// 启动后台下载任务
    pub fn start_download(&self, app: AppHandle) -> Result<(), String> {
        {
            let mut s = self.status.lock().expect("WhisperStatus mutex 中毒");
            if *s == WhisperStatus::Downloading {
                return Err("已有下载任务正在进行".into());
            }
            *s = WhisperStatus::Downloading;
        }

        // 清除可能残留的取消标记
        let _ = std::fs::remove_file(self.cancel_marker());

        let data_dir = self.data_dir.clone();
        let status = Arc::clone(&self.status);

        tauri::async_runtime::spawn(async move {
            let result = download_all(&data_dir, &app).await;

            // 更新状态
            {
                let mut s = status.lock().expect("WhisperStatus mutex 中毒");
                *s = WhisperStatus::Idle;
            }

            match result {
                Ok((cli_path, model_path)) => {
                    log::info!("Whisper 下载完成: cli={}, model={}", cli_path, model_path);
                    let _ = app.emit(
                        "whisper-download-complete",
                        DownloadComplete {
                            cli_path,
                            model_path,
                        },
                    );
                }
                Err(e) if e == "__CANCELLED__" => {
                    log::info!("Whisper 下载已取消");
                    let _ = app.emit("whisper-download-cancelled", serde_json::json!({}));
                }
                Err(e) => {
                    log::error!("Whisper 下载失败: {}", e);
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

    /// 取消正在进行的下载
    pub fn cancel_download(&self) -> Result<(), String> {
        // 写入取消标记文件
        let marker = self.cancel_marker();
        if let Some(parent) = marker.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|e| format!("创建取消标记目录失败: {}", e))?;
        }
        std::fs::write(&marker, "cancel")
            .map_err(|e| format!("写入取消标记失败: {}", e))?;

        // 立即将状态设为 Idle，防止取消后立即重试时出现双任务并发
        {
            let mut s = self.status.lock().expect("WhisperStatus mutex 中毒");
            *s = WhisperStatus::Idle;
        }

        log::info!("已发送 Whisper 下载取消信号");
        Ok(())
    }
}

/// 根据平台返回 whisper.cpp Release 包的资产名
///
/// 基于 whisper.cpp v1.7.3 实际的 Release assets 命名
fn platform_asset_name() -> &'static str {
    if cfg!(target_os = "windows") {
        "whisper-blas-bin-x64.zip"
    } else if cfg!(target_os = "macos") {
        if cfg!(target_arch = "aarch64") {
            "whisper-bin-arm64.zip"
        } else {
            "whisper-bin-x86_64.zip"
        }
    } else {
        "whisper-bin-x64.zip"
    }
}

/// 检查取消标记是否存在
fn is_cancelled(data_dir: &PathBuf) -> bool {
    data_dir
        .join("bin")
        .join(".whisper-download-cancel")
        .exists()
}

/// 执行完整的下载流程（CLI + 模型）
async fn download_all(
    data_dir: &PathBuf,
    app: &AppHandle,
) -> Result<(String, String), String> {
    // 确保目录存在
    let bin_dir = data_dir.join("bin");
    let models_dir = data_dir.join("models");
    std::fs::create_dir_all(&bin_dir).map_err(|e| format!("创建 bin 目录失败: {}", e))?;
    std::fs::create_dir_all(&models_dir)
        .map_err(|e| format!("创建 models 目录失败: {}", e))?;

    let cli_name = if cfg!(target_os = "windows") {
        "whisper-cli.exe"
    } else {
        "whisper-cli"
    };
    let cli_path = bin_dir.join(cli_name);
    let model_path = models_dir.join("ggml-base.bin");

    // 阶段 1：下载 whisper-cli（如果不存在）
    if !cli_path.exists() {
        let asset_name = platform_asset_name();
        let url = format!(
            "https://github.com/ggerganov/whisper.cpp/releases/download/v{}/{}",
            WHISPER_VERSION, asset_name
        );
        log::info!("下载 whisper-cli: {}", url);

        let zip_path = bin_dir.join(format!("{}.part", asset_name));
        download_file(&url, &zip_path, "正在下载语音引擎", data_dir, app).await?;

        if is_cancelled(data_dir) {
            let _ = std::fs::remove_file(&zip_path);
            return Err("__CANCELLED__".into());
        }

        // 解压 zip
        let _ = app.emit(
            "whisper-download-progress",
            DownloadProgress {
                stage: "正在解压语音引擎".into(),
                progress: -1.0,
                downloaded: 0,
                total: 0,
                speed_bps: 0,
            },
        );

        extract_cli_from_zip(&zip_path, &bin_dir, cli_name)?;

        // 删除 zip 文件
        let _ = std::fs::remove_file(&zip_path);

        // Unix 下设置可执行权限
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let perms = std::fs::Permissions::from_mode(0o755);
            std::fs::set_permissions(&cli_path, perms)
                .map_err(|e| format!("设置可执行权限失败: {}", e))?;
        }

        log::info!("whisper-cli 已就绪: {}", cli_path.display());
    }

    if is_cancelled(data_dir) {
        return Err("__CANCELLED__".into());
    }

    // 阶段 2：下载模型（如果不存在）
    if !model_path.exists() {
        log::info!("下载 Whisper 模型: {}", MODEL_URL);
        let part_path = models_dir.join("ggml-base.bin.part");
        download_file(MODEL_URL, &part_path, "正在下载语音模型", data_dir, app).await?;

        if is_cancelled(data_dir) {
            let _ = std::fs::remove_file(&part_path);
            return Err("__CANCELLED__".into());
        }

        // 下载完成，重命名
        std::fs::rename(&part_path, &model_path)
            .map_err(|e| format!("重命名模型文件失败: {}", e))?;
        log::info!("模型文件已就绪: {}", model_path.display());
    }

    // 清除取消标记（如果有的话）
    let _ = std::fs::remove_file(data_dir.join("bin").join(".whisper-download-cancel"));

    Ok((
        cli_path.to_string_lossy().into_owned(),
        model_path.to_string_lossy().into_owned(),
    ))
}

/// 下载单个文件，支持断点续传和进度回调
async fn download_file(
    url: &str,
    dest: &PathBuf,
    stage: &str,
    data_dir: &PathBuf,
    app: &AppHandle,
) -> Result<(), String> {
    let client = reqwest::Client::new();

    // 检查已下载的字节数（断点续传）
    let existing_bytes = if dest.exists() {
        std::fs::metadata(dest)
            .map(|m| m.len())
            .unwrap_or(0)
    } else {
        0
    };

    let mut request = client.get(url);
    if existing_bytes > 0 {
        log::info!("断点续传: 已下载 {} 字节", existing_bytes);
        request = request.header("Range", format!("bytes={}-", existing_bytes));
    }

    let response = request
        .send()
        .await
        .map_err(|e| format!("网络请求失败: {}", e))?;

    if !response.status().is_success() && response.status().as_u16() != 206 {
        return Err(format!("HTTP 错误: {}", response.status()));
    }

    // 解析总大小
    let total = if response.status().as_u16() == 206 {
        // 部分内容响应，从 Content-Range 解析总大小
        response
            .headers()
            .get("content-range")
            .and_then(|v| v.to_str().ok())
            .and_then(|s| s.rsplit('/').next())
            .and_then(|s| s.parse::<u64>().ok())
            .unwrap_or(0)
    } else {
        response.content_length().unwrap_or(0) + existing_bytes
    };

    // 打开文件（追加模式）
    let file = if existing_bytes > 0 {
        std::fs::OpenOptions::new()
            .append(true)
            .open(dest)
            .map_err(|e| format!("打开文件失败: {}", e))?
    } else {
        std::fs::File::create(dest).map_err(|e| format!("创建文件失败: {}", e))?
    };
    let mut writer = std::io::BufWriter::new(file);

    let mut downloaded = existing_bytes;
    let mut last_emit = std::time::Instant::now();
    let start_time = std::time::Instant::now();

    let mut stream = response.bytes_stream();
    while let Some(chunk) = stream.next().await {
        // 检查取消
        if is_cancelled(data_dir) {
            return Err("__CANCELLED__".into());
        }

        let chunk = chunk.map_err(|e| format!("读取数据失败: {}", e))?;
        std::io::Write::write_all(&mut writer, &chunk)
            .map_err(|e| format!("写入文件失败: {}", e))?;
        downloaded += chunk.len() as u64;

        // 每 200ms 发射一次进度事件
        let now = std::time::Instant::now();
        if now.duration_since(last_emit).as_millis() >= 200 {
            let elapsed = now.duration_since(start_time).as_secs_f64();
            let speed_bps = if elapsed > 0.0 {
                ((downloaded - existing_bytes) as f64 / elapsed) as u64
            } else {
                0
            };

            let progress = if total > 0 {
                downloaded as f64 / total as f64
            } else {
                0.0
            };

            let _ = app.emit(
                "whisper-download-progress",
                DownloadProgress {
                    stage: stage.into(),
                    progress,
                    downloaded,
                    total,
                    speed_bps,
                },
            );

            last_emit = now;
        }
    }

    // 最终进度
    let elapsed = start_time.elapsed().as_secs_f64();
    let _ = app.emit(
        "whisper-download-progress",
        DownloadProgress {
            stage: stage.into(),
            progress: if total > 0 {
                downloaded as f64 / total as f64
            } else {
                1.0
            },
            downloaded,
            total,
            speed_bps: if elapsed > 0.0 {
                ((downloaded - existing_bytes) as f64 / elapsed) as u64
            } else {
                0
            },
        },
    );

    std::io::Write::flush(&mut writer).map_err(|e| format!("刷新缓冲区失败: {}", e))?;
    Ok(())
}

/// 从 zip 包中解压 whisper-cli 可执行文件
fn extract_cli_from_zip(
    zip_path: &PathBuf,
    dest_dir: &PathBuf,
    cli_name: &str,
) -> Result<(), String> {
    let file =
        std::fs::File::open(zip_path).map_err(|e| format!("打开 zip 文件失败: {}", e))?;
    let mut archive =
        zip::ZipArchive::new(file).map_err(|e| format!("解析 zip 文件失败: {}", e))?;

    // 在 zip 中查找 whisper-cli 可执行文件
    let mut found = false;
    for i in 0..archive.len() {
        let mut entry = archive
            .by_index(i)
            .map_err(|e| format!("读取 zip 条目失败: {}", e))?;

        let entry_name = entry.name().to_string();

        // 匹配文件名（可能在子目录中）
        let is_target = entry_name == cli_name
            || entry_name.ends_with(&format!("/{}", cli_name))
            || entry_name.ends_with(&format!("\\{}", cli_name));

        if is_target && !entry.is_dir() {
            let out_path = dest_dir.join(cli_name);
            let mut out_file = std::fs::File::create(&out_path)
                .map_err(|e| format!("创建文件失败: {}", e))?;
            std::io::copy(&mut entry, &mut out_file)
                .map_err(|e| format!("解压文件失败: {}", e))?;
            log::info!(
                "已从 zip 解压: {} -> {}",
                entry_name,
                out_path.display()
            );
            found = true;
            break;
        }
    }

    if !found {
        return Err(format!(
            "zip 包中未找到 {}，包含的文件: {:?}",
            cli_name,
            (0..archive.len())
                .filter_map(|i| archive.by_index(i).ok().map(|e| e.name().to_string()))
                .collect::<Vec<_>>()
        ));
    }

    Ok(())
}
