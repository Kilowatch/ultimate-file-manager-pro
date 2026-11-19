import React, { useState, useEffect, useRef } from "react";
import { invoke } from "@tauri-apps/api/core";
import { 
  X, 
  DownloadCloud, 
  Plus, 
  Pause, 
  Play, 
  RotateCw, 
  Trash2, 
  Folder, 
  AlertCircle, 
  CheckCircle2, 
  Clock, 
  Link as LinkIcon, 
  Magnet, 
  HardDrive,
  RefreshCw,
  FileText
} from "lucide-react";
import { PairedDeviceConfig } from "../hooks/useUfmApi";
import { TranslationSet } from "../hooks/translations";

interface RemoteDownloadSettings {
  saveLocation: string;
  concurrentDownloads: number;
  autoResume: boolean;
  cellularAllowed: boolean;
  torrentEnabled: boolean;
  maxActiveTorrents: number;
}

interface RemoteTransferItem {
  id: string;
  name: string;
  totalBytes: number;
  downloadedBytes: number;
  status: string;
  speedBps: number;
  etaSeconds: number;
  isTorrent: boolean;
  peers: number;
  seeds: number;
}

interface DownloadManagerModalProps {
  activeDevice: PairedDeviceConfig | null;
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

  const handleAction = async (action: string, id?: string, extra?: Record<string, any>) => {
    if (!activeDevice) return;
    try {
      await sendDmRequest("POST", "action", JSON.stringify({ action, id, ...extra }));
      fetchStatus();
    } catch (e: any) {
      setError(`Action failed: ${e.toString()}`);
    }
  };

  const handleAddUrl = async () => {
    if (!activeDevice || !urlInput.trim()) return;
    try {
      await sendDmRequest("POST", "add-url", JSON.stringify({ url: urlInput.trim() }));
      setUrlInput("");
      setShowUrlDialog(false);
      fetchStatus();
    } catch (e: any) {
      setError(`Add URL failed: ${e.toString()}`);
    }
  };

  const handleAddMagnet = async () => {
    if (!activeDevice || !magnetInput.trim()) return;
    try {
      await sendDmRequest("POST", "add-magnet", JSON.stringify({ magnetUri: magnetInput.trim() }));
      setMagnetInput("");
      setShowMagnetDialog(false);
      fetchStatus();
    } catch (e: any) {
      setError(`Add Magnet failed: ${e.toString()}`);
    }
  };

  const handleTorrentFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    if (!activeDevice || !e.target.files || e.target.files.length === 0) return;
    const file = e.target.files[0];
    const reader = new FileReader();
    reader.onload = async () => {
      try {
        const base64Data = (reader.result as string).split(",")[1];
        await sendDmRequest("POST", "add-torrent", JSON.stringify({ fileName: file.name, torrentBase64: base64Data }));
        fetchStatus();
      } catch (err: any) {
        setError(`Add Torrent failed: ${err.toString()}`);
      }
    };
    reader.readAsDataURL(file);
    e.target.value = "";
  };

  const formatBytes = (bytes: number) => {
    if (!bytes || bytes <= 0) return "0 B";
    const k = 1024;
    const sizes = ["B", "KB", "MB", "GB", "TB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + " " + sizes[i];
  };

  const formatSpeed = (bps: number) => {
    return formatBytes(bps) + "/s";
  };

  const formatEta = (seconds: number) => {
    if (seconds <= 0 || !isFinite(seconds)) return "--";
    if (seconds < 60) return `${seconds}s`;
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    if (m < 60) return `${m}m ${s}s`;
    const h = Math.floor(m / 60);
    return `${h}h ${m % 60}m`;
  };

  return (
    <div style={{
      position: "fixed",
      top: 0,
      left: 0,
      right: 0,
      bottom: 0,
      background: "rgba(0, 0, 0, 0.75)",
      backdropFilter: "blur(6px)",
      zIndex: 1000,
      display: "flex",
      alignItems: "center",
      justifyContent: "center",
      padding: "20px"
    }}>
      <div style={{
        background: "var(--card-bg, #1e222b)",
        border: "1px solid var(--border-color, #2d3342)",
        borderRadius: "16px",
        width: "900px",
        maxWidth: "95vw",
        height: "85vh",
        display: "flex",
        flexDirection: "column",
        boxShadow: "0 20px 40px rgba(0,0,0,0.5)",
        overflow: "hidden"
      }}>
        {/* Header */}
        <div style={{
          padding: "16px 24px",
          borderBottom: "1px solid var(--border-color, #2d3342)",
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between"
        }}>
          <div style={{ display: "flex", alignItems: "center", gap: "12px" }}>
            <DownloadCloud style={{ width: 24, height: 24, color: "var(--accent, #3b82f6)" }} />
            <div>
              <h2 style={{ fontSize: "1.2rem", fontWeight: 600, margin: 0 }}>TV Download Manager</h2>
              <span style={{ fontSize: "0.8rem", color: "var(--text-secondary, #94a3b8)" }}>
                {activeDevice ? activeDevice.name : "Disconnected"}
              </span>
            </div>
          </div>
          <button 
            onClick={onClose} 
            className="btn-icon" 
            style={{ background: "transparent", border: "none", cursor: "pointer", color: "var(--text-secondary, #94a3b8)" }}
          >
            <X size={20} />
          </button>
        </div>

        {/* Storage / Settings Banner */}
        <div style={{
          padding: "12px 24px",
          background: "rgba(255, 255, 255, 0.03)",
          borderBottom: "1px solid var(--border-color, #2d3342)",
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          flexWrap: "wrap",
          gap: "10px"
        }}>
          <div style={{ display: "flex", alignItems: "center", gap: "8px", fontSize: "0.85rem" }}>
            <HardDrive style={{ width: 16, height: 16, color: "var(--text-secondary, #94a3b8)" }} />
            <span style={{ color: "var(--text-secondary, #94a3b8)" }}>Download Location:</span>
            <code style={{ background: "rgba(0,0,0,0.3)", padding: "2px 6px", borderRadius: "4px" }}>
              {settings?.saveLocation || "Default"}
            </code>
          </div>
          <div style={{ display: "flex", gap: "16px", fontSize: "0.8rem", color: "var(--text-secondary, #94a3b8)" }}>
            <span>Concurrent: <b>{settings?.concurrentDownloads ?? 3}</b></span>
            <span>Auto-Resume: <b>{settings?.autoResume ? "Yes" : "No"}</b></span>
            <span>Torrents: <b>{settings?.torrentEnabled ? "Enabled" : "Disabled"}</b></span>
          </div>
        </div>

        {/* Content / Controls Area */}
        <div style={{ flex: 1, padding: "20px 24px", overflowY: "auto", display: "flex", flexDirection: "column", gap: "16px" }}>
          
          {/* Top Actions Bar */}
          <div style={{ display: "flex", gap: "10px", flexWrap: "wrap" }}>
            <button className="btn-primary" onClick={() => setShowUrlDialog(true)} style={{ display: "flex", alignItems: "center", gap: "6px" }}>
              <LinkIcon size={16} /> Add URL
            </button>
            <button className="btn-primary" onClick={() => setShowMagnetDialog(true)} style={{ display: "flex", alignItems: "center", gap: "6px" }}>
              <Magnet size={16} /> Add Magnet
            </button>
            <button className="btn-primary" onClick={() => torrentInputRef.current?.click()} style={{ display: "flex", alignItems: "center", gap: "6px" }}>
              <FileText size={16} /> Add .torrent
            </button>
            <input 
              type="file" 
              ref={torrentInputRef} 
              accept=".torrent" 
              style={{ display: "none" }} 
              onChange={handleTorrentFileUpload} 
            />

            <div style={{ flex: 1 }} />

            <button 
              className="btn-secondary" 
              onClick={() => handleAction("resume_all")} 
              title="Resume All"
              style={{ padding: "8px 12px", display: "flex", alignItems: "center", gap: "6px" }}
            >
              <Play size={14} /> Resume All
            </button>
            <button 
              className="btn-secondary" 
              onClick={() => handleAction("pause_all")} 
              title="Pause All"
              style={{ padding: "8px 12px", display: "flex", alignItems: "center", gap: "6px" }}
            >
              <Pause size={14} /> Pause All
            </button>
            <button 
              className="btn-secondary" 
              onClick={() => handleAction("clear_completed")} 
              title="Clear Completed"
              style={{ padding: "8px 12px", display: "flex", alignItems: "center", gap: "6px" }}
            >
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

            {transfers.length === 0 ? (
              <div style={{ flex: 1, display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", color: "var(--text-secondary, #94a3b8)", minHeight: "200px" }}>
                <DownloadCloud size={48} style={{ opacity: 0.3, marginBottom: "12px" }} />
                <p>No active or completed downloads on this TV</p>
              </div>
            ) : (
              transfers.map(item => {
                const percent = item.totalBytes > 0 
                  ? Math.min(100, Math.round((item.downloadedBytes / item.totalBytes) * 100))
                  : 0;
                const isRunning = item.status === "DOWNLOADING" || item.status === "SEEDING";
                const isPaused = item.status === "PAUSED";
                const isFailed = item.status === "FAILED";
                const isCompleted = item.status === "COMPLETED";

                return (
                  <div key={item.id} style={{
                    background: "rgba(255, 255, 255, 0.03)",
                    border: "1px solid var(--border-color, #2d3342)",
                    borderRadius: "10px",
                    padding: "12px 16px",
                    display: "flex",
                    flexDirection: "column",
                    gap: "8px"
                  }}>
                    <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
                      <div style={{ display: "flex", alignItems: "center", gap: "8px", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                        {item.isTorrent ? (
                          <Magnet size={16} style={{ color: "#a855f7", flexShrink: 0 }} />
                        ) : (
                          <LinkIcon size={16} style={{ color: "var(--accent, #3b82f6)", flexShrink: 0 }} />
                        )}
                        <span style={{ fontWeight: 500, fontSize: "0.95rem" }}>{item.name}</span>
                      </div>
                      <div style={{ display: "flex", alignItems: "center", gap: "6px" }}>
                        {isRunning && (
                          <button 
                            className="btn-icon" 
                            title="Pause"
                            onClick={() => handleAction("pause", item.id)}
                            style={{ background: "transparent", border: "none", cursor: "pointer", color: "var(--text-secondary)" }}
                          >
                            <Pause size={16} />
                          </button>
                        )}
                        {isPaused && (
                          <button 
                            className="btn-icon" 
                            title="Resume"
                            onClick={() => handleAction("resume", item.id)}
                            style={{ background: "transparent", border: "none", cursor: "pointer", color: "var(--accent)" }}
                          >
                            <Play size={16} />
                          </button>
                        )}
                        {isFailed && (
                          <button 
                            className="btn-icon" 
                            title="Retry"
                            onClick={() => handleAction("retry", item.id)}
                            style={{ background: "transparent", border: "none", cursor: "pointer", color: "var(--accent)" }}
                          >
                            <RotateCw size={16} />
                          </button>
                        )}
                        <button 
                          className="btn-icon" 
                          title="Delete"
                          onClick={() => setDeleteConfirmItem(item)}
                          style={{ background: "transparent", border: "none", cursor: "pointer", color: "var(--danger, #ef4444)" }}
                        >
                          <Trash2 size={16} />
                        </button>
                      </div>
                    </div>

                    {/* Progress Bar */}
                    <div style={{ width: "100%", height: "6px", background: "rgba(255, 255, 255, 0.08)", borderRadius: "3px", overflow: "hidden" }}>
                      <div style={{
                        width: `${percent}%`,
                        height: "100%",
                        background: isCompleted ? "var(--success, #10b981)" : isFailed ? "var(--danger, #ef4444)" : "var(--accent, #3b82f6)",
                        transition: "width 0.3s ease"
                      }} />
                    </div>

                    {/* Stats details */}
                    <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", fontSize: "0.8rem", color: "var(--text-secondary, #94a3b8)" }}>
                      <div style={{ display: "flex", gap: "12px" }}>
                        <span>{formatBytes(item.downloadedBytes)} / {item.totalBytes > 0 ? formatBytes(item.totalBytes) : "--"} ({percent}%)</span>
                        {isRunning && <span>• {formatSpeed(item.speedBps)}</span>}
                        {isRunning && item.etaSeconds > 0 && <span>• ETA: {formatEta(item.etaSeconds)}</span>}
                        {item.isTorrent && (
                          <span>• Peers: {item.peers} / Seeds: {item.seeds}</span>
                        )}
                      </div>
                      <span style={{ 
                        textTransform: "uppercase", 
                        fontSize: "0.75rem", 
                        fontWeight: 600,
                        color: isCompleted ? "var(--success, #10b981)" : isFailed ? "var(--danger, #ef4444)" : "var(--accent, #3b82f6)"
                      }}>
                        {item.status}
                      </span>
                    </div>
                  </div>
                );
              })
            )}
          </div>
        </div>

        {/* Delete Dialog */}
        {deleteConfirmItem && (
          <div style={{
            position: "absolute",
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            background: "rgba(0,0,0,0.6)",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            zIndex: 1100
          }}>
            <div style={{
              background: "var(--card-bg, #1e222b)",
              border: "1px solid var(--border-color, #2d3342)",
              borderRadius: "12px",
              padding: "20px",
              width: "420px",
              display: "flex",
              flexDirection: "column",
              gap: "16px"
            }}>
              <h3 style={{ margin: 0, fontSize: "1.1rem" }}>Remove Download</h3>
              <p style={{ margin: 0, fontSize: "0.9rem", color: "var(--text-secondary, #94a3b8)" }}>
                Remove <b>{deleteConfirmItem.name}</b> from the TV download queue?
              </p>
              <div style={{ display: "flex", justifyContent: "flex-end", gap: "10px", marginTop: "8px" }}>
                <button className="btn-secondary" onClick={() => setDeleteConfirmItem(null)}>
                  Cancel
                </button>
                <button 
                  className="btn-secondary" 
                  onClick={() => {
                    handleAction("remove", deleteConfirmItem.id, { deleteFiles: false });
                    setDeleteConfirmItem(null);
                  }}
                >
                  List Only
                </button>
                <button 
                  className="btn-danger" 
                  style={{ background: "var(--danger, #ef4444)", color: "#fff", border: "none", borderRadius: "6px", padding: "8px 12px", cursor: "pointer" }}
                  onClick={() => {
                    handleAction("remove", deleteConfirmItem.id, { deleteFiles: true });
                    setDeleteConfirmItem(null);
                  }}
                >
                  Delete Files & Remove
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Add URL Dialog */}
        {showUrlDialog && (
          <div style={{
            position: "absolute",
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            background: "rgba(0,0,0,0.6)",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            zIndex: 1100
          }}>
            <div style={{
              background: "var(--card-bg, #1e222b)",
              border: "1px solid var(--border-color, #2d3342)",
              borderRadius: "12px",
              padding: "20px",
              width: "450px",
              display: "flex",
              flexDirection: "column",
              gap: "14px"
            }}>
              <h3 style={{ margin: 0, fontSize: "1.1rem" }}>Add Direct Download URL</h3>
              <input 
                type="text" 
                placeholder="https://example.com/file.zip"
                value={urlInput}
                onChange={e => setUrlInput(e.target.value)}
                style={{
                  width: "100%",
                  padding: "10px",
                  borderRadius: "6px",
                  border: "1px solid var(--border-color, #2d3342)",
                  background: "rgba(0,0,0,0.2)",
                  color: "#fff"
                }}
              />
              <div style={{ display: "flex", justifyContent: "flex-end", gap: "10px" }}>
                <button className="btn-secondary" onClick={() => setShowUrlDialog(false)}>Cancel</button>
                <button className="btn-primary" onClick={handleAddUrl}>Start Download</button>
              </div>
            </div>
          </div>
        )}

        {/* Add Magnet Dialog */}
        {showMagnetDialog && (
          <div style={{
            position: "absolute",
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            background: "rgba(0,0,0,0.6)",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            zIndex: 1100
          }}>
            <div style={{
              background: "var(--card-bg, #1e222b)",
              border: "1px solid var(--border-color, #2d3342)",
              borderRadius: "12px",
              padding: "20px",
              width: "450px",
              display: "flex",
              flexDirection: "column",
              gap: "14px"
            }}>
              <h3 style={{ margin: 0, fontSize: "1.1rem" }}>Add Magnet Link</h3>
              <input 
                type="text" 
                placeholder="magnet:?xt=urn:btih:..."
                value={magnetInput}
                onChange={e => setMagnetInput(e.target.value)}
                style={{
                  width: "100%",
                  padding: "10px",
                  borderRadius: "6px",
                  border: "1px solid var(--border-color, #2d3342)",
                  background: "rgba(0,0,0,0.2)",
                  color: "#fff"
                }}
              />
              <div style={{ display: "flex", justifyContent: "flex-end", gap: "10px" }}>
                <button className="btn-secondary" onClick={() => setShowMagnetDialog(false)}>Cancel</button>
                <button className="btn-primary" onClick={handleAddMagnet}>Start Download</button>
              </div>
            </div>
          </div>
        )}

      </div>
    </div>
  );
};
