import React, { useState, useEffect, useRef } from "react";
import { invoke } from "@tauri-apps/api/core";
import { Download, Pause, Play, Trash2, X, RefreshCw, Link as LinkIcon, FileText, CheckCircle2, AlertCircle, HardDrive } from "lucide-react";
import { PairedDevice } from "../hooks/useUfmApi";
import { TranslationSet } from "../hooks/translations";

export interface RemoteDownloadSettings {
  downloadDir: string;
  freeBytes: number;
  totalBytes: number;
  networkPolicy: string;
  legalAccepted: boolean;
}

export interface RemoteTransferItem {
  id: string;
  title: string;
  type: string;
  state: string;
  totalBytes: number;
  downloadedBytes: number;
  progressPercent: number;
  speedBytesPerSec: number;
  etaSeconds: number;
  connectedPeers: number;
  totalPeers: number;
  connectedSeeds: number;
  targetFolder: string;
  errorMessage?: string | null;
}

interface DownloadManagerModalProps {
  activeDevice: PairedDevice | null;
  onClose: () => void;
  t?: TranslationSet;
}

export const DownloadManagerModal: React.FC<DownloadManagerModalProps> = ({
  activeDevice,
  onClose,
  t: _t,
}) => {
  const [settings, setSettings] = useState<RemoteDownloadSettings | null>(null);
  const [transfers, setTransfers] = useState<RemoteTransferItem[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Dialog states
  const [showUrlDialog, setShowUrlDialog] = useState(false);
  const [urlInput, setUrlInput] = useState("");
  const [showMagnetDialog, setShowMagnetDialog] = useState(false);
  const [magnetInput, setMagnetInput] = useState("");
  const [deleteConfirmItem, setDeleteConfirmItem] = useState<RemoteTransferItem | null>(null);

  const torrentInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!activeDevice) return;
    fetchStatus();
    const interval = setInterval(fetchStatus, 1500);
    return () => clearInterval(interval);
  }, [activeDevice]);

  const sendDmRequest = async (method: string, endpoint: string, bodyJson: string | null = null): Promise<string> => {
    if (!activeDevice) throw new Error("No active device");
    try {
      const res = await invoke<string>("ufm_api_request", {
        method,
        url: `https://${activeDevice.ip}:${activeDevice.port}/tv/download-manager/${endpoint}`,
        token: activeDevice.auth_token,
        bodyJson,
        certFingerprint: activeDevice.cert_fingerprint,
      });
      if (res.trim().startsWith("{")) {
        return res;
      }
      throw new Error("Non-JSON response from /tv route");
    } catch (_e) {
      return await invoke<string>("ufm_api_request", {
        method,
        url: `https://${activeDevice.ip}:${activeDevice.port}/api/download-manager/${endpoint}`,
        token: activeDevice.auth_token,
        bodyJson,
        certFingerprint: activeDevice.cert_fingerprint,
      });
    }
  };

  const fetchStatus = async () => {
    if (!activeDevice) return;
    setIsLoading(true);
    try {
      const res = await sendDmRequest("GET", "status");

      if (!res.trim().startsWith("{")) {
        throw new Error("Device did not return download manager status (check that Download Service is running on device)");
      }

      const parsed = JSON.parse(res);
      if (parsed.success) {
        setSettings(parsed.settings);
        setTransfers(parsed.transfers || []);
        setError(null);
      }
    } catch (e: any) {
      setError(e.toString());
    } finally {
      setIsLoading(false);
    }
  };

  const handleAction = async (id: string, action: string, deleteFiles: boolean = false) => {
    if (!activeDevice) return;
    try {
      await sendDmRequest("POST", "action", JSON.stringify({ id, action, deleteFiles }));
      fetchStatus();
    } catch (e: any) {
      alert(`Action failed: ${e.toString()}`);
    }
  };

  const handleDirectDownload = async () => {
    if (!activeDevice || !urlInput.trim()) return;
    try {
      await sendDmRequest("POST", "add-url", JSON.stringify({ url: urlInput.trim() }));
      setShowUrlDialog(false);
      setUrlInput("");
      fetchStatus();
    } catch (e: any) {
      alert(`Failed to add URL: ${e.toString()}`);
    }
  };

  const handleMagnetDownload = async () => {
    if (!activeDevice || !magnetInput.trim()) return;
    try {
      await sendDmRequest("POST", "add-magnet", JSON.stringify({ magnetUri: magnetInput.trim() }));
      setShowMagnetDialog(false);
      setMagnetInput("");
      fetchStatus();
    } catch (e: any) {
      alert(`Failed to add magnet: ${e.toString()}`);
    }
  };

  const handleTorrentFileSelected = async (e: React.ChangeEvent<HTMLInputElement>) => {
    if (!activeDevice) return;
    const files = e.target.files;
    if (!files || files.length === 0) return;

    const file = files[0];
    const reader = new FileReader();
    reader.onload = async () => {
      try {
        const buffer = reader.result as ArrayBuffer;
        const bytes = new Uint8Array(buffer);
        let binary = "";
        for (let i = 0; i < bytes.byteLength; i++) {
          binary += String.fromCharCode(bytes[i]);
        }
        const base64 = btoa(binary);

        await sendDmRequest("POST", "add-torrent", JSON.stringify({ torrentBase64: base64 }));
        fetchStatus();
      } catch (err: any) {
        alert(`Failed to dispatch torrent file: ${err.toString()}`);
      }
    };
    reader.readAsArrayBuffer(file);
    e.target.value = "";
  };

  const handleClearCompleted = async () => {
    const completed = transfers.filter((t) => t.state === "COMPLETED");
    for (const item of completed) {
      await handleAction(item.id, "delete", false);
    }
  };

  const formatBytes = (bytes: number) => {
    if (bytes <= 0) return "0 B";
    const k = 1024;
    const sizes = ["B", "KB", "MB", "GB", "TB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + " " + sizes[i];
  };

  const formatSpeed = (bytesPerSec: number) => {
    if (bytesPerSec <= 0) return "";
    return `${formatBytes(bytesPerSec)}/s`;
  };

  const formatEta = (seconds: number) => {
    if (seconds <= 0) return "";
    const m = Math.floor((seconds / 60) % 60);
    const h = Math.floor(seconds / 3600);
    const s = Math.floor(seconds % 60);
    return h > 0 ? `${h}h ${m}m` : `${m}m ${s}s`;
  };

  return (
    <div className="dialog-overlay" onClick={onClose}>
      <div 
        className="dialog-box glass-panel" 
        onClick={(e) => e.stopPropagation()} 
        style={{ width: "720px", maxWidth: "90vw", maxHeight: "85vh", display: "flex", flexDirection: "column", padding: "24px" }}
      >
        {/* Modal Header */}
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "16px" }}>
          <div style={{ display: "flex", alignItems: "center", gap: "12px" }}>
            <div style={{ background: "rgba(0, 230, 118, 0.15)", borderRadius: "10px", padding: "8px", display: "flex" }}>
              <Download style={{ width: 22, height: 22, color: "#00E676" }} />
            </div>
            <div>
              <h2 style={{ fontSize: "1.2rem", fontWeight: 700, margin: 0 }}>
                {activeDevice ? `${activeDevice.name} — Download Manager` : "Remote Download Manager"}
              </h2>
              <div style={{ fontSize: "0.8rem", color: "var(--text-sec)" }}>
                {activeDevice ? `${activeDevice.ip} • Port ${activeDevice.port}` : "No device connected"}
              </div>
            </div>
          </div>
          <button 
            className="icon-btn" 
            onClick={onClose} 
            title="Close"
            style={{ border: "none", background: "transparent", color: "var(--text-sec)", cursor: "pointer" }}
          >
            <X style={{ width: 20, height: 20 }} />
          </button>
        </div>

        {/* TV Storage Status Card */}
        {settings && (
          <div style={{ background: "var(--surface)", border: "1px solid var(--glass-border)", borderRadius: "12px", padding: "14px", marginBottom: "16px" }}>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "8px" }}>
              <div style={{ display: "flex", alignItems: "center", gap: "8px", fontSize: "0.85rem", fontWeight: 600 }}>
                <HardDrive style={{ width: 16, height: 16, color: "var(--primary-light)" }} />
                <span>TV Download Location:</span>
                <span style={{ color: "var(--primary-light)", fontFamily: "monospace" }}>{settings.downloadDir}</span>
              </div>
              <span style={{ fontSize: "0.75rem", background: "rgba(255, 255, 255, 0.08)", padding: "2px 8px", borderRadius: "12px", color: "var(--text-sec)" }}>
                {settings.networkPolicy}
              </span>
            </div>
            {settings.totalBytes > 0 && (
              <div>
                <div style={{ height: "6px", background: "rgba(255, 255, 255, 0.1)", borderRadius: "3px", overflow: "hidden", marginBottom: "4px" }}>
                  <div 
                    style={{ 
                      height: "100%", 
                      width: `${Math.min(100, Math.round(((settings.totalBytes - settings.freeBytes) / settings.totalBytes) * 100))}%`, 
                      background: "var(--primary)" 
                    }} 
                  />
                </div>
                <div style={{ display: "flex", justifyContent: "space-between", fontSize: "0.75rem", color: "var(--text-sec)" }}>
                  <span>{formatBytes(settings.freeBytes)} free</span>
                  <span>{formatBytes(settings.totalBytes)} total</span>
                </div>
              </div>
            )}
          </div>
        )}

        {/* Quick Action Buttons */}
        <div style={{ display: "flex", gap: "10px", marginBottom: "16px" }}>
          <button 
            className="btn-primary" 
            onClick={() => setShowUrlDialog(true)} 
            style={{ fontSize: "0.85rem", padding: "8px 14px" }}
          >
            <Download style={{ width: 16, height: 16 }} />
            Add URL
          </button>
          <button 
            className="btn-primary" 
            onClick={() => setShowMagnetDialog(true)} 
            style={{ fontSize: "0.85rem", padding: "8px 14px", background: "rgba(255, 112, 67, 0.2)", borderColor: "#FF7043", color: "#FF7043" }}
          >
            <LinkIcon style={{ width: 16, height: 16 }} />
            Add Magnet
          </button>
          <input
            type="file"
            ref={torrentInputRef}
            onChange={handleTorrentFileSelected}
            accept=".torrent"
            style={{ display: "none" }}
          />
          <button 
            className="btn-primary" 
            onClick={() => torrentInputRef.current?.click()} 
            style={{ fontSize: "0.85rem", padding: "8px 14px", background: "rgba(66, 165, 245, 0.2)", borderColor: "#42A5F5", color: "#42A5F5" }}
          >
            <FileText style={{ width: 16, height: 16 }} />
            Upload .Torrent
          </button>
          <div style={{ flex: 1 }} />
          <button 
            className="btn-secondary" 
            onClick={handleClearCompleted} 
            title="Clear Completed"
            style={{ fontSize: "0.85rem", padding: "8px 12px" }}
          >
            <CheckCircle2 style={{ width: 16, height: 16 }} />
            Clear Completed
          </button>
          <button 
            className="btn-secondary" 
            onClick={fetchStatus} 
            title="Refresh"
            disabled={isLoading}
            style={{ padding: "8px 10px" }}
          >
            <RefreshCw style={{ width: 16, height: 16 }} className={isLoading ? "animate-spin" : ""} />
          </button>
        </div>

        {/* Transfers List */}
        <div style={{ flex: 1, overflowY: "auto", display: "flex", flexDirection: "column", gap: "10px" }}>
          {error && (
            <div style={{ background: "rgba(239, 68, 68, 0.15)", border: "1px solid var(--danger)", borderRadius: "8px", padding: "10px", color: "var(--danger)", fontSize: "0.85rem", display: "flex", alignItems: "center", gap: "8px" }}>
              <AlertCircle style={{ width: 16, height: 16 }} />
              <span>Failed to communicate with TV: {error}</span>
            </div>
          )}

          {transfers.length === 0 && !error && (
            <div style={{ textAlign: "center", padding: "40px 20px", color: "var(--text-sec)" }}>
              <Download style={{ width: 48, height: 48, opacity: 0.3, marginBottom: "12px" }} />
              <div style={{ fontSize: "1rem", fontWeight: 600 }}>No active downloads on TV</div>
              <div style={{ fontSize: "0.8rem", marginTop: "4px" }}>Add a direct link, magnet, or torrent file above</div>
            </div>
          )}

          {transfers.map((item) => {
            const isPaused = item.state === "PAUSED";
            const isCompleted = item.state === "COMPLETED";
            const isError = item.state === "ERROR";

            return (
              <div 
                key={item.id} 
                style={{ 
                  background: "var(--surface)", 
                  border: "1px solid var(--glass-border)", 
                  borderRadius: "12px", 
                  padding: "14px", 
                  display: "flex", 
                  flexDirection: "column", 
                  gap: "8px" 
                }}
              >
                {/* Item Header */}
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                  <div style={{ display: "flex", alignItems: "center", gap: "10px", minWidth: 0, flex: 1 }}>
                    <span 
                      style={{ 
                        fontSize: "0.7rem", 
                        fontWeight: 700, 
                        padding: "2px 6px", 
                        borderRadius: "4px", 
                        background: item.type === "TORRENT" ? "rgba(66, 165, 245, 0.2)" : item.type === "MAGNET" ? "rgba(255, 112, 67, 0.2)" : "rgba(0, 230, 118, 0.2)",
                        color: item.type === "TORRENT" ? "#42A5F5" : item.type === "MAGNET" ? "#FF7043" : "#00E676"
                      }}
                    >
                      {item.type}
                    </span>
                    <span style={{ fontSize: "0.95rem", fontWeight: 600, color: "var(--text)", textOverflow: "ellipsis", overflow: "hidden", whiteSpace: "nowrap" }}>
                      {item.title}
                    </span>
                    <span 
                      style={{ 
                        fontSize: "0.75rem", 
                        fontWeight: 600, 
                        color: isCompleted ? "var(--success)" : isPaused ? "var(--warning)" : isError ? "var(--danger)" : "var(--primary-light)" 
                      }}
                    >
                      {item.state}
                    </span>
                  </div>

                  {/* Actions */}
                  <div style={{ display: "flex", gap: "6px" }}>
                    {!isCompleted && (
                      <button 
                        className="icon-btn" 
                        onClick={() => handleAction(item.id, isPaused ? "resume" : "pause")} 
                        title={isPaused ? "Resume" : "Pause"}
                        style={{ border: "none", background: "transparent", color: "var(--text)", cursor: "pointer", padding: "4px" }}
                      >
                        {isPaused ? <Play style={{ width: 16, height: 16 }} /> : <Pause style={{ width: 16, height: 16 }} />}
                      </button>
                    )}
                    <button 
                      className="icon-btn" 
                      onClick={() => setDeleteConfirmItem(item)} 
                      title="Remove"
                      style={{ border: "none", background: "transparent", color: "var(--danger)", cursor: "pointer", padding: "4px" }}
                    >
                      <Trash2 style={{ width: 16, height: 16 }} />
                    </button>
                  </div>
                </div>

                {/* Progress Bar */}
                <div style={{ display: "flex", alignItems: "center", gap: "10px" }}>
                  <div style={{ flex: 1, height: "6px", background: "rgba(255, 255, 255, 0.1)", borderRadius: "3px", overflow: "hidden" }}>
                    <div 
                      style={{ 
                        height: "100%", 
                        width: `${item.progressPercent}%`, 
                        background: isCompleted ? "var(--success)" : "var(--primary)" 
                      }} 
                    />
                  </div>
                  <span style={{ fontSize: "0.8rem", fontWeight: 700, color: isCompleted ? "var(--success)" : "var(--primary-light)", width: "42px", textAlign: "right" }}>
                    {item.progressPercent}%
                  </span>
                </div>

                {/* Metrics */}
                <div style={{ display: "flex", justifyContent: "space-between", fontSize: "0.75rem", color: "var(--text-sec)" }}>
                  <span>
                    {formatBytes(item.downloadedBytes)} / {formatBytes(item.totalBytes)}
                    {item.speedBytesPerSec > 0 && ` • ${formatSpeed(item.speedBytesPerSec)}`}
                  </span>
                  <span>
                    {item.etaSeconds > 0 && `ETA: ${formatEta(item.etaSeconds)}`}
                    {item.connectedPeers > 0 && ` • Peers: ${item.connectedPeers} (${item.totalPeers})`}
                  </span>
                </div>
              </div>
            );
          })}
        </div>

        {/* Direct URL Input Modal */}
        {showUrlDialog && (
          <div className="dialog-overlay" onClick={() => setShowUrlDialog(false)}>
            <div className="dialog-box glass-panel" onClick={(e) => e.stopPropagation()} style={{ width: "480px" }}>
              <h3 style={{ marginBottom: "12px" }}>Direct Download URL</h3>
              <input
                type="text"
                className="input-field"
                placeholder="https://example.com/file.iso"
                value={urlInput}
                onChange={(e) => setUrlInput(e.target.value)}
                style={{ width: "100%", padding: "10px", borderRadius: "8px", background: "var(--surface)", border: "1px solid var(--glass-border)", color: "var(--text)", marginBottom: "16px" }}
              />
              <div style={{ display: "flex", justifyContent: "flex-end", gap: "10px" }}>
                <button className="btn-secondary" onClick={() => setShowUrlDialog(false)}>Cancel</button>
                <button className="btn-primary" onClick={handleDirectDownload}>Download on TV</button>
              </div>
            </div>
          </div>
        )}

        {/* Magnet Input Modal */}
        {showMagnetDialog && (
          <div className="dialog-overlay" onClick={() => setShowMagnetDialog(false)}>
            <div className="dialog-box glass-panel" onClick={(e) => e.stopPropagation()} style={{ width: "480px" }}>
              <h3 style={{ marginBottom: "12px" }}>Magnet Link</h3>
              <input
                type="text"
                className="input-field"
                placeholder="magnet:?xt=urn:btih:..."
                value={magnetInput}
                onChange={(e) => setMagnetInput(e.target.value)}
                style={{ width: "100%", padding: "10px", borderRadius: "8px", background: "var(--surface)", border: "1px solid var(--glass-border)", color: "var(--text)", marginBottom: "16px" }}
              />
              <div style={{ display: "flex", justifyContent: "flex-end", gap: "10px" }}>
                <button className="btn-secondary" onClick={() => setShowMagnetDialog(false)}>Cancel</button>
                <button className="btn-primary" onClick={handleMagnetDownload}>Download on TV</button>
              </div>
            </div>
          </div>
        )}

        {/* Delete Confirmation Modal */}
        {deleteConfirmItem && (
          <div className="dialog-overlay" onClick={() => setDeleteConfirmItem(null)}>
            <div className="dialog-box glass-panel" onClick={(e) => e.stopPropagation()} style={{ width: "450px" }}>
              <h3 style={{ marginBottom: "12px" }}>Remove Transfer</h3>
              <p style={{ color: "var(--text-sec)", fontSize: "0.9rem", marginBottom: "16px" }}>
                How would you like to remove "{deleteConfirmItem.title}" from the TV?
              </p>
              <div style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
                <button 
                  className="btn-secondary" 
                  onClick={() => {
                    handleAction(deleteConfirmItem.id, "delete", false);
                    setDeleteConfirmItem(null);
                  }}
                  style={{ justifyContent: "center" }}
                >
                  Remove from List Only (Keep files on TV)
                </button>
                <button 
                  className="btn-primary" 
                  onClick={() => {
                    handleAction(deleteConfirmItem.id, "delete", true);
                    setDeleteConfirmItem(null);
                  }}
                  style={{ justifyContent: "center", background: "var(--danger)", borderColor: "var(--danger)" }}
                >
                  Remove & Delete Files from TV Storage
                </button>
                <button 
                  className="btn-secondary" 
                  onClick={() => setDeleteConfirmItem(null)}
                  style={{ justifyContent: "center" }}
                >
                  Cancel
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
