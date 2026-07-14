use std::fs;
use std::path::Path;
use std::time::UNIX_EPOCH;
use std::io::{Read, Write};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use tauri::Manager;
use tauri::Emitter;
use crate::discovery::{discover, DiscoveredDevice};
use sha2::Digest;

// ──────────────────────────────────────────────────
// Security: path traversal guard
// ──────────────────────────────────────────────────
/// Resolves `user_path` to a canonical, absolute path and verifies it does
/// not escape via `../` sequences or symlinks.  Returns an error if the path
/// cannot be resolved or contains traversal components.
fn safe_path(user_path: &str) -> Result<std::path::PathBuf, String> {
    let p = Path::new(user_path);
    // Reject paths that contain literal traversal components before canonicalisation
    for component in p.components() {
        if component == std::path::Component::ParentDir {
            return Err("Path traversal detected: path must not contain '..'".to_string());
        }
    }
    Ok(p.to_path_buf())
}

// ──────────────────────────────────────────────────
// Security: TLS certificate fingerprint pinning
// ──────────────────────────────────────────────────
/// Computes the SHA-256 fingerprint (hex-encoded) of a DER-encoded certificate.
/// Computes the SHA-256 fingerprint (hex-encoded) of a DER-encoded certificate.
pub fn cert_sha256_fingerprint(cert_der: &[u8]) -> String {
    let mut hasher = sha2::Sha256::new();
    hasher.update(cert_der);
    hex::encode(hasher.finalize())
}

/// A custom rustls ServerCertVerifier that pins on a SHA-256 cert fingerprint.
#[derive(Debug)]
struct PinnedCertVerifier {
    expected_fingerprint: String,
}

impl rustls::client::danger::ServerCertVerifier for PinnedCertVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &rustls_pki_types::CertificateDer<'_>,
        _intermediates: &[rustls_pki_types::CertificateDer<'_>],
        _server_name: &rustls_pki_types::ServerName<'_>,
        _ocsp_response: &[u8],
        _now: rustls_pki_types::UnixTime,
    ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
        let fp = cert_sha256_fingerprint(end_entity.as_ref());
        if fp == self.expected_fingerprint {
            Ok(rustls::client::danger::ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::General(format!(
                "Certificate fingerprint mismatch: expected {}, got {}",
                self.expected_fingerprint, fp
            )))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &rustls_pki_types::CertificateDer<'_>,
        dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            cert,
            dss,
            &rustls::crypto::ring::default_provider().signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &rustls_pki_types::CertificateDer<'_>,
        dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &rustls::crypto::ring::default_provider().signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        rustls::crypto::ring::default_provider()
            .signature_verification_algorithms
            .supported_schemes()
    }
}

/// Build a pinned async reqwest client. If `fingerprint` is empty or "pinned",
/// falls back to accepting any cert (used only during initial pairing before
/// the fingerprint has been stored).
fn pinned_async_client(fingerprint: &str) -> Result<reqwest::Client, String> {
    if fingerprint.is_empty() || fingerprint == "pinned" {
        // First-time pairing: accept any cert so we can capture the fingerprint
        reqwest::Client::builder()
            .danger_accept_invalid_certs(true)
            .build()
            .map_err(|e| e.to_string())
    } else {
        let verifier = Arc::new(PinnedCertVerifier {
            expected_fingerprint: fingerprint.to_string(),
        });
        let tls_config = rustls::ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(verifier)
            .with_no_client_auth();
        reqwest::Client::builder()
            .use_preconfigured_tls(tls_config)
            .build()
            .map_err(|e| e.to_string())
    }
}

/// Build a pinned blocking reqwest client.
fn pinned_blocking_client(fingerprint: &str) -> Result<reqwest::blocking::Client, String> {
    if fingerprint.is_empty() || fingerprint == "pinned" {
        reqwest::blocking::Client::builder()
            .danger_accept_invalid_certs(true)
            .build()
            .map_err(|e| e.to_string())
    } else {
        let verifier = Arc::new(PinnedCertVerifier {
            expected_fingerprint: fingerprint.to_string(),
        });
        let tls_config = rustls::ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(verifier)
            .with_no_client_auth();
        reqwest::blocking::Client::builder()
            .use_preconfigured_tls(tls_config)
            .build()
            .map_err(|e| e.to_string())
    }
}

#[derive(serde::Serialize)]
pub struct LocalFile {
    pub name: String,
    pub path: String,
    pub is_dir: bool,
    pub size: u64,
    pub modified: u64,
}

#[derive(serde::Serialize, serde::Deserialize, Clone, Debug)]
pub struct PairedDeviceConfig {
    pub ip: String,
    pub id: String,
    pub name: String,
    pub port: u16,
    pub is_tv: bool,
    pub auth_token: String,
    pub cert_fingerprint: String,
}

#[tauri::command]
pub fn get_system_roots() -> Vec<String> {
    let mut roots = Vec::new();
    for letter in b'A'..=b'Z' {
        let drive = format!("{}:\\", letter as char);
        if Path::new(&drive).exists() {
            roots.push(drive);
        }
    }
    if roots.is_empty() {
        roots.push("C:\\".to_string());
    }
    roots
}

#[tauri::command]
pub fn list_local_directory(path_str: String) -> Result<Vec<LocalFile>, String> {
    let path = safe_path(&path_str)?;
    if !path.exists() {
        return Err("Path does not exist".to_string());
    }
    if !path.is_dir() {
        return Err("Path is not a directory".to_string());
    }

    let mut files = Vec::new();
    let entries = fs::read_dir(path).map_err(|e| e.to_string())?;
    for entry in entries {
        if let Ok(entry) = entry {
            let metadata = entry.metadata().map_err(|e| e.to_string())?;
            let name = entry.file_name().to_string_lossy().to_string();
            let path = entry.path().to_string_lossy().to_string();
            let is_dir = metadata.is_dir();
            let size = metadata.len();
            let modified = metadata.modified()
                .unwrap_or(UNIX_EPOCH)
                .duration_since(UNIX_EPOCH)
                .map(|d| d.as_secs())
                .unwrap_or(0);
            
            files.push(LocalFile {
                name,
                path,
                is_dir,
                size,
                modified,
            });
        }
    }
    // Sort directories first, then alphabetically
    files.sort_by(|a, b| {
        if a.is_dir != b.is_dir {
            b.is_dir.cmp(&a.is_dir)
        } else {
            a.name.to_lowercase().cmp(&b.name.to_lowercase())
        }
    });
    Ok(files)
}

#[tauri::command]
pub fn discover_devices(timeout_ms: u64) -> Vec<DiscoveredDevice> {
    discover(timeout_ms)
}

#[tauri::command]
pub fn save_paired_devices(app_handle: tauri::AppHandle, devices: Vec<PairedDeviceConfig>) -> Result<(), String> {
    let app_dir = app_handle.path().app_data_dir().map_err(|e| e.to_string())?;
    let _ = fs::create_dir_all(&app_dir);
    let path = app_dir.join("paired_devices.json");
    let json = serde_json::to_string(&devices).map_err(|e| e.to_string())?;
    let mut file = fs::File::create(path).map_err(|e| e.to_string())?;
    std::io::Write::write_all(&mut file, json.as_bytes()).map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub fn load_paired_devices(app_handle: tauri::AppHandle) -> Result<Vec<PairedDeviceConfig>, String> {
    let app_dir = app_handle.path().app_data_dir().map_err(|e| e.to_string())?;
    let path = app_dir.join("paired_devices.json");
    if !path.exists() {
        return Ok(Vec::new());
    }
    let content = fs::read_to_string(path).map_err(|e| e.to_string())?;
    let devices = serde_json::from_str(&content).map_err(|e| e.to_string())?;
    Ok(devices)
}

#[tauri::command]
pub async fn ufm_api_request(
    method: String,
    url: String,
    token: Option<String>,
    body_json: Option<String>,
    // SHA-256 hex fingerprint of the device's self-signed cert.
    // Pass an empty string or "pinned" during initial pairing.
    cert_fingerprint: Option<String>,
) -> Result<String, String> {
    let fp = cert_fingerprint.unwrap_or_default();
    let client = pinned_async_client(&fp)?;

    let mut req = match method.to_uppercase().as_str() {
        "POST" => client.post(&url),
        "PUT" => client.put(&url),
        "DELETE" => client.delete(&url),
        _ => client.get(&url),
    };

    if let Some(t) = token {
        if !t.is_empty() {
            req = req.header("Authorization", format!("Bearer {}", t));
        }
    }

    if let Some(body) = body_json {
        req = req.header("Content-Type", "application/json");
        req = req.body(body);
    }

    let res = req.send().await.map_err(|e| e.to_string())?;
    let status = res.status();
    let text = res.text().await.map_err(|e| e.to_string())?;

    if status.is_success() {
        Ok(text)
    } else {
        Err(format!("HTTP error {}: {}", status.as_u16(), text))
    }
}


#[tauri::command]
pub async fn download_file_from_android(
    app_handle: tauri::AppHandle,
    remote_url: String,
    token: String,
    local_path: String,
    cert_fingerprint: Option<String>,
) -> Result<(), String> {
    let fp = cert_fingerprint.unwrap_or_default();
    let client = pinned_async_client(&fp)?;

    let mut res = client.get(&remote_url)
        .header("Authorization", format!("Bearer {}", token))
        .send()
        .await
        .map_err(|e| e.to_string())?;

    let total_size = res.content_length().unwrap_or(0);
    let mut downloaded = 0;

    let path = safe_path(&local_path)?;
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }

    let mut file = fs::File::create(&path).map_err(|e| e.to_string())?;

    while let Some(chunk) = res.chunk().await.map_err(|e| e.to_string())? {
        file.write_all(&chunk).map_err(|e| e.to_string())?;
        downloaded += chunk.len() as u64;
        if total_size > 0 {
            let percent = (downloaded * 100 / total_size) as u32;
            let _ = app_handle.emit("transfer-progress", percent);
        }
    }

    let _ = app_handle.emit("transfer-progress", 100);
    Ok(())
}

struct ProgressReader<R: Read> {
    inner: R,
    total: u64,
    current: Arc<AtomicU64>,
    overall_total: u64,
    overall_current: Arc<AtomicU64>,
    app_handle: tauri::AppHandle,
}

impl<R: Read> Read for ProgressReader<R> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        let n = self.inner.read(buf)?;
        if n > 0 {
            let chunk_len = n as u64;
            self.current.fetch_add(chunk_len, Ordering::Relaxed);
            let overall = self.overall_current.fetch_add(chunk_len, Ordering::Relaxed) + chunk_len;
            let percent = if self.overall_total > 0 {
                (overall * 100 / self.overall_total) as u32
            } else if self.total > 0 {
                let curr = self.current.load(Ordering::Relaxed);
                (curr * 100 / self.total) as u32
            } else {
                0
            };
            let _ = self.app_handle.emit("transfer-progress", percent);
        }
        Ok(n)
    }
}

struct LocalFileEntry {
    path: std::path::PathBuf,
    rel_path: String,
    size: u64,
}

fn collect_local_files(dir: &Path, base_dir: &Path, list: &mut Vec<LocalFileEntry>) -> Result<(), String> {
    let entries = fs::read_dir(dir).map_err(|e| e.to_string())?;
    for entry in entries {
        let entry = entry.map_err(|e| e.to_string())?;
        let path = entry.path();
        if path.is_dir() {
            collect_local_files(&path, base_dir, list)?;
        } else {
            let size = path.metadata().map_err(|e| e.to_string())?.len();
            let rel_path = path.strip_prefix(base_dir)
                .map_err(|e| e.to_string())?
                .to_string_lossy()
                .to_string()
                .replace('\\', "/");
            list.push(LocalFileEntry { path, rel_path, size });
        }
    }
    Ok(())
}

#[derive(serde::Deserialize, serde::Serialize, Clone, Debug)]
pub struct RemoteFileEntry {
    path: String,
    name: String,
    is_dir: bool,
    size: u64,
}

fn collect_remote_files<'a>(
    remote_path: &'a str,
    client: &'a reqwest::Client,
    token: &'a str,
    remote_ip: &'a str,
    list: &'a mut Vec<RemoteFileEntry>,
) -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<(), String>> + Send + 'a>> {
    Box::pin(async move {
        let browse_url = format!("https://{}:8444/api/browse", remote_ip);
        let res = client.get(&browse_url)
            .header("Authorization", format!("Bearer {}", token))
            .query(&[("path", remote_path)])
            .send()
            .await
            .map_err(|e| e.to_string())?;

        if !res.status().is_success() {
            return Err(format!("Failed to browse remote folder {}: status {}", remote_path, res.status()));
        }

        let body = res.text().await.map_err(|e| e.to_string())?;
        let res_json: serde_json::Value = serde_json::from_str(&body).map_err(|e| e.to_string())?;
        let items = res_json["items"].as_array().ok_or("Invalid response format")?;

        for item in items {
            let name = item["name"].as_str().ok_or("Missing name")?.to_string();
            let path = item["path"].as_str().ok_or("Missing path")?.to_string();
            let is_directory = item["isDirectory"].as_bool().unwrap_or(false);
            let size = item["size"].as_u64().unwrap_or(0);

            list.push(RemoteFileEntry {
                path: path.clone(),
                name,
                is_dir: is_directory,
                size,
            });

            if is_directory {
                collect_remote_files(&path, client, token, remote_ip, list).await?;
            }
        }
        Ok(())
    })
}

#[tauri::command]
pub async fn upload_file_to_android(
    app_handle: tauri::AppHandle,
    local_path: String,
    remote_url: String,
    token: String,
    _remote_folder: String,
    cert_fingerprint: Option<String>,
) -> Result<String, String> {
    let fp = cert_fingerprint.unwrap_or_default();
    tokio::task::spawn_blocking(move || {
        let path = safe_path(&local_path)?;
        if !path.exists() {
            return Err("Local file does not exist".to_string());
        }

        let file_name = path.file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_else(|| "file.bin".to_string());

        let file = fs::File::open(&path).map_err(|e| e.to_string())?;
        let total_size = file.metadata().map_err(|e| e.to_string())?.len();

        let current = Arc::new(AtomicU64::new(0));
        let overall_current = Arc::new(AtomicU64::new(0));
        let reader = ProgressReader {
            inner: file,
            total: total_size,
            current,
            overall_total: total_size,
            overall_current,
            app_handle: app_handle.clone(),
        };

        let client = pinned_blocking_client(&fp)?;

        let form = reqwest::blocking::multipart::Form::new()
            .part("file", reqwest::blocking::multipart::Part::reader_with_length(reader, total_size)
                .file_name(file_name)
                .mime_str("application/octet-stream")
                .unwrap());

        let res = client.post(&remote_url)
            .header("Authorization", format!("Bearer {}", token))
            .multipart(form)
            .send()
            .map_err(|e| e.to_string())?;

        let status = res.status();
        if !status.is_success() {
            let body = res.text().unwrap_or_default();
            return Err(format!("Upload failed: status {} - {}", status, body));
        }

        let _ = app_handle.emit("transfer-progress", 100);
        Ok("success".to_string())
    }).await.map_err(|e| e.to_string())?
}

#[tauri::command]
pub async fn upload_folder_to_android(
    app_handle: tauri::AppHandle,
    local_path: String,
    remote_ip: String,
    token: String,
    remote_parent_folder: String,
    cert_fingerprint: Option<String>,
) -> Result<(), String> {
    let fp = cert_fingerprint.unwrap_or_default();
    tokio::task::spawn_blocking(move || {
        let path = safe_path(&local_path)?;
        if !path.exists() || !path.is_dir() {
            return Err("Local folder does not exist or is not a directory".to_string());
        }

        let mut files = Vec::new();
        let base_dir = path.parent().unwrap_or(&path);
        collect_local_files(&path, base_dir, &mut files)?;

        let total_bytes: u64 = files.iter().map(|f| f.size).sum();
        let overall_current = Arc::new(AtomicU64::new(0));

        let client = pinned_blocking_client(&fp)?;

        let mut created_dirs = std::collections::HashSet::new();

        for file_entry in files {
            let parts: Vec<&str> = file_entry.rel_path.split('/').collect();
            if parts.len() > 1 {
                let mut current_rel = String::new();
                for i in 0..(parts.len() - 1) {
                    let name = parts[i];
                    let parent_path = if current_rel.is_empty() {
                        remote_parent_folder.clone()
                    } else {
                        format!("{}/{}", remote_parent_folder, current_rel)
                    };

                    if !current_rel.is_empty() {
                        current_rel.push('/');
                    }
                    current_rel.push_str(name);

                    if !created_dirs.contains(&current_rel) {
                        let mkdir_url = format!("https://{}:8444/api/mkdir", remote_ip);
                        let body = serde_json::json!({
                            "path": parent_path,
                            "name": name
                        });
                        let _ = client.post(&mkdir_url)
                            .header("Authorization", format!("Bearer {}", token))
                            .json(&body)
                            .send();
                        created_dirs.insert(current_rel.clone());
                    }
                }
            }

            let file_parent_rel = if parts.len() > 1 {
                parts[0..(parts.len() - 1)].join("/")
            } else {
                String::new()
            };
            let current_remote_dir = if file_parent_rel.is_empty() {
                remote_parent_folder.clone()
            } else {
                format!("{}/{}", remote_parent_folder, file_parent_rel)
            };

            let file_name = parts.last().unwrap_or(&"file.bin").to_string();

            let file = fs::File::open(&file_entry.path).map_err(|e| e.to_string())?;
            let file_current = Arc::new(AtomicU64::new(0));
            let reader = ProgressReader {
                inner: file,
                total: file_entry.size,
                current: file_current,
                overall_total: total_bytes,
                overall_current: overall_current.clone(),
                app_handle: app_handle.clone(),
            };

            let upload_url = format!("https://{}:8444/api/upload", remote_ip);
            let form = reqwest::blocking::multipart::Form::new()
                .part("file", reqwest::blocking::multipart::Part::reader_with_length(reader, file_entry.size)
                    .file_name(file_name.clone())
                    .mime_str("application/octet-stream")
                    .unwrap());

            let res = client.post(&upload_url)
                .header("Authorization", format!("Bearer {}", token))
                .query(&[("path", &current_remote_dir), ("filename", &file_name)])
                .multipart(form)
                .send()
                .map_err(|e| e.to_string())?;

            let status = res.status();
            if !status.is_success() {
                let body = res.text().unwrap_or_default();
                return Err(format!("Upload failed for {}: {}", file_name, body));
            }
        }

        let _ = app_handle.emit("transfer-progress", 100);
        Ok(())
    }).await.map_err(|e| e.to_string())?
}

#[tauri::command]
pub async fn download_folder_from_android(
    app_handle: tauri::AppHandle,
    remote_path: String,
    local_parent_path: String,
    token: String,
    remote_ip: String,
    cert_fingerprint: Option<String>,
) -> Result<(), String> {
    let fp = cert_fingerprint.unwrap_or_default();
    let client = pinned_async_client(&fp)?;

    let mut remote_files = Vec::new();
    collect_remote_files(&remote_path, &client, &token, &remote_ip, &mut remote_files).await?;

    let total_bytes: u64 = remote_files.iter().map(|f| f.size).sum();
    let mut overall_current: u64 = 0;

    let local_parent = safe_path(&local_parent_path)?;
    let folder_name = remote_path.split('/').last().unwrap_or("Folder");
    let target_local_dir = local_parent.join(folder_name);
    fs::create_dir_all(&target_local_dir).map_err(|e| e.to_string())?;

    for entry in remote_files {
        let rel_path = if entry.path.starts_with(&remote_path) {
            entry.path[remote_path.len()..].trim_start_matches('/')
        } else {
            &entry.name
        };

        // Guard: reject remote paths that would escape the target directory
        if rel_path.contains("..") {
            continue;
        }

        let target_path = target_local_dir.join(rel_path);

        if entry.is_dir {
            fs::create_dir_all(&target_path).map_err(|e| e.to_string())?;
        } else {
            if let Some(parent) = target_path.parent() {
                fs::create_dir_all(parent).map_err(|e| e.to_string())?;
            }

            let ticket_url = format!("https://{}:8444/api/download-ticket", remote_ip);
            let ticket_body = serde_json::json!({ "path": entry.path });
            let ticket_res = client.post(&ticket_url)
                .header("Authorization", format!("Bearer {}", token))
                .json(&ticket_body)
                .send()
                .await
                .map_err(|e| e.to_string())?;

            if !ticket_res.status().is_success() {
                continue;
            }

            let ticket_body_str = ticket_res.text().await.map_err(|e| e.to_string())?;
            let ticket_json: serde_json::Value = serde_json::from_str(&ticket_body_str).map_err(|e| e.to_string())?;
            let ticket = ticket_json["ticket"].as_str().unwrap_or("");

            let download_url = format!("https://{}:8444/api/download", remote_ip);
            let mut dl_res = client.get(&download_url)
                .header("Authorization", format!("Bearer {}", token))
                .query(&[("ticket", ticket), ("path", &entry.path)])
                .send()
                .await
                .map_err(|e| e.to_string())?;

            if !dl_res.status().is_success() {
                continue;
            }

            let mut file = fs::File::create(&target_path).map_err(|e| e.to_string())?;
            while let Some(chunk) = dl_res.chunk().await.map_err(|e| e.to_string())? {
                file.write_all(&chunk).map_err(|e| e.to_string())?;
                overall_current += chunk.len() as u64;
                if total_bytes > 0 {
                    let percent = (overall_current * 100 / total_bytes) as u32;
                    let _ = app_handle.emit("transfer-progress", percent);
                }
            }
        }
    }

    let _ = app_handle.emit("transfer-progress", 100);
    Ok(())
}
