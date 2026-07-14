import { useState, useEffect, useRef } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { Smartphone, Tv, Link, Download, Upload, AlertCircle, UploadCloud } from "lucide-react";
import { FilePane } from "./components/FilePane";
import { DeviceSelector } from "./components/DeviceSelector";
import { ProgressBar } from "./components/ProgressBar";
import { useUfmApi, UfmFile } from "./hooks/useUfmApi";
import logo from "./assets/logo.png";
import "./App.css";

const appWindow = getCurrentWindow();

function App() {
  const ufm = useUfmApi();
  const sideloadInputRef = useRef<HTMLInputElement>(null);

  // Dialog state
  const [showDeviceSelector, setShowDeviceSelector] = useState(false);
  const [isMaximized, setIsMaximized] = useState(false);

  // Local Filesystem state
  const [localRoots, setLocalRoots] = useState<string[]>([]);
  const [localPath, setLocalPath] = useState<string>("");
  const [localFiles, setLocalFiles] = useState<UfmFile[]>([]);
  const [isLocalLoading, setIsLocalLoading] = useState(false);
  const [selectedLocalFiles, setSelectedLocalFiles] = useState<UfmFile[]>([]);

  // Remote Filesystem state
  const [remoteRoots, setRemoteRoots] = useState<string[]>([]);
  const [remotePath, setRemotePath] = useState<string>("");
  const [remoteFiles, setRemoteFiles] = useState<UfmFile[]>([]);
  const [isRemoteLoading, setIsRemoteLoading] = useState(false);
  const [selectedRemoteFiles, setSelectedRemoteFiles] = useState<UfmFile[]>([]);

  // Transfer Progress State
  const [transferLabel, setTransferLabel] = useState("");
  const [transferProgress, setTransferProgress] = useState(0);
  const [isTransferActive, setIsTransferActive] = useState(false);

  // 1. Initialize Local Filesystem
  useEffect(() => {
    initLocalFs();
  }, []);

  // Listen to transfer progress from Rust backend
  useEffect(() => {
    let unlisten: any;
    listen<number>("transfer-progress", (event) => {
      setTransferProgress(event.payload);
    }).then(fn => {
      unlisten = fn;
    });
    return () => {
      if (unlisten) unlisten();
    };
  }, []);

  const initLocalFs = async () => {
    try {
      const roots = await invoke<string[]>("get_system_roots");
      setLocalRoots(roots);
      if (roots.length > 0) {
        const saved = localStorage.getItem("ufm_last_local_path");
        const defaultPath = roots[0];
        const pathToLoad = saved || defaultPath;
        setLocalPath(pathToLoad);
        loadLocalDir(pathToLoad, defaultPath);
      }
    } catch (e) {
      console.error("Failed to init local filesystem:", e);
    }
  };

  const loadLocalDir = async (path: string, fallbackPath?: string) => {
    setIsLocalLoading(true);
    setSelectedLocalFiles([]);
    try {
      const list = await invoke<any[]>("list_local_directory", { pathStr: path });
      setLocalFiles(list.map(f => ({
        name: f.name,
        path: f.path,
        is_dir: f.is_dir,
        size: f.size,
        modified: f.modified
      })));
      setLocalPath(path);
      localStorage.setItem("ufm_last_local_path", path);
    } catch (e) {
      console.error("Failed to load local directory:", e);
      if (fallbackPath) {
        loadLocalDir(fallbackPath);
      } else if (localRoots.length > 0 && path !== localRoots[0]) {
        loadLocalDir(localRoots[0]);
      }
    } finally {
      setIsLocalLoading(false);
    }
  };

  // 2. Refresh Remote volumes when connection/active device changes
  useEffect(() => {
    if (ufm.activeDevice) {
      initRemoteFs();
    } else {
      setRemoteFiles([]);
      setRemoteRoots([]);
      setRemotePath("");
      setSelectedRemoteFiles([]);
    }
  }, [ufm.activeDevice]);

  const initRemoteFs = async () => {
    if (!ufm.activeDevice) return;
    setIsRemoteLoading(true);
    try {
      const volumes = await ufm.getVolumes();
      if (volumes && volumes.length > 0) {
        const paths = volumes.map(v => v.path);
        setRemoteRoots(paths);
        
        const defaultPath = paths[0];
        const savedKey = `ufm_last_remote_path_${ufm.activeDevice.id}`;
        const saved = localStorage.getItem(savedKey);
        const pathToLoad = saved || defaultPath;
        
        setRemotePath(pathToLoad);
        loadRemoteDir(pathToLoad, defaultPath);
      } else {
        const defaultPath = "/storage/emulated/0";
        setRemoteRoots([defaultPath]);
        
        const savedKey = `ufm_last_remote_path_${ufm.activeDevice.id}`;
        const saved = localStorage.getItem(savedKey);
        const pathToLoad = saved || defaultPath;
        
        setRemotePath(pathToLoad);
        loadRemoteDir(pathToLoad, defaultPath);
      }
    } catch (e) {
      console.error("Failed to fetch remote volumes:", e);
    } finally {
      setIsRemoteLoading(false);
    }
  };

  const loadRemoteDir = async (path: string, fallbackPath?: string) => {
    if (!ufm.activeDevice) return;
    setIsRemoteLoading(true);
    setSelectedRemoteFiles([]);
    try {
      const files = await ufm.browse(path);
      setRemoteFiles(files);
      setRemotePath(path);
      const savedKey = `ufm_last_remote_path_${ufm.activeDevice.id}`;
      localStorage.setItem(savedKey, path);
    } catch (e) {
      console.error("Failed to read remote directory:", e);
      if (fallbackPath) {
        loadRemoteDir(fallbackPath);
      } else if (remoteRoots.length > 0 && path !== remoteRoots[0]) {
        loadRemoteDir(remoteRoots[0]);
      }
    } finally {
      setIsRemoteLoading(false);
    }
  };

  // --- File Actions ---

  const handleMkdir = async (isRemoteFs: boolean, currentPath: string, name: string) => {
    if (isRemoteFs) {
      const success = await ufm.mkdir(currentPath, name);
      if (success) loadRemoteDir(currentPath);
    }
  };

  const handleDelete = async (isRemoteFs: boolean, paths: string[]) => {
    if (paths.length === 0) return;
    const label = paths.length === 1 ? "this item" : `these ${paths.length} items`;
    if (confirm(`Are you sure you want to delete ${label}?`)) {
      if (isRemoteFs) {
        let successCount = 0;
        for (const path of paths) {
          const success = await ufm.deletePath(path);
          if (success) successCount++;
        }
        loadRemoteDir(remotePath);
      }
    }
  };

  // --- Sideload Action ---
  const handleSideload = async (localFilePath: string, filename: string, isXapk: boolean) => {
    if (!ufm.activeDevice) return;

    setIsTransferActive(true);
    setTransferLabel(`Uploading & installing ${filename}...`);
    setTransferProgress(20);

    const success = await ufm.sideloadPackage(localFilePath, filename, isXapk, (progress) => {
      setTransferProgress(progress);
    });

    if (success) {
      setTransferLabel("Installation request sent successfully!");
      setTimeout(() => setIsTransferActive(false), 3000);
    } else {
      setTransferLabel("Installation failed. View device screen for details.");
      setTimeout(() => setIsTransferActive(false), 5000);
    }
  };

  // --- Copy File Actions (Upload/Download) ---
  const handleUpload = async () => {
    if (selectedLocalFiles.length === 0 || !ufm.activeDevice) return;

    setIsTransferActive(true);
    setTransferProgress(0);

    let successCount = 0;
    for (let i = 0; i < selectedLocalFiles.length; i++) {
      const file = selectedLocalFiles[i];
      setTransferLabel(`Uploading ${file.name} (${i + 1}/${selectedLocalFiles.length})...`);
      const success = await ufm.uploadLocalFile(
        file.path,
        remotePath,
        file.is_dir
      );
      if (success) successCount++;
    }

    if (successCount === selectedLocalFiles.length) {
      setTransferLabel("Upload complete!");
    } else {
      setTransferLabel(`Uploaded ${successCount} of ${selectedLocalFiles.length} successfully.`);
    }

    loadRemoteDir(remotePath);
    setTimeout(() => setIsTransferActive(false), 2000);
  };

  const handleDownload = async () => {
    if (selectedRemoteFiles.length === 0 || !ufm.activeDevice) return;

    setIsTransferActive(true);
    setTransferProgress(0);

    const isWindows = localPath.includes("\\");
    const separator = isWindows ? "\\" : "/";

    let successCount = 0;
    for (let i = 0; i < selectedRemoteFiles.length; i++) {
      const file = selectedRemoteFiles[i];
      setTransferLabel(`Downloading ${file.name} (${i + 1}/${selectedRemoteFiles.length})...`);
      
      const destPath = localPath.endsWith(separator) 
        ? localPath + file.name 
        : localPath + separator + file.name;

      const success = await ufm.downloadRemoteFile(
        file.path, 
        destPath,
        file.is_dir
      );
      if (success) successCount++;
    }

    if (successCount === selectedRemoteFiles.length) {
      setTransferLabel("Download complete!");
    } else {
      setTransferLabel(`Downloaded ${successCount} of ${selectedRemoteFiles.length} successfully.`);
    }

    loadLocalDir(localPath);
    setTimeout(() => setIsTransferActive(false), 2000);
  };

  const handleMinimize = () => appWindow.minimize();
  const handleMaximize = async () => {
    await appWindow.toggleMaximize();
    const max = await appWindow.isMaximized();
    setIsMaximized(max);
  };
  const handleClose = () => appWindow.close();

  return (
    <>
      <div className="titlebar" data-tauri-drag-region>
        <div className="titlebar-logo" data-tauri-drag-region>
          <img src={logo} alt="UFM Logo" style={{ width: "16px", height: "16px", objectFit: "contain", borderRadius: "3px", pointerEvents: "none" }} />
          <span style={{ fontSize: "0.75rem", fontWeight: 600, color: "var(--text-hint)", pointerEvents: "none" }}>
            Ultimate File Manager Pro
          </span>
        </div>
        <div className="titlebar-title" data-tauri-drag-region>
          Companion Console
        </div>
        <div className="titlebar-controls">
          <button className="titlebar-btn" onClick={handleMinimize} title="Minimize">—</button>
          <button className="titlebar-btn" onClick={handleMaximize} title="Maximize">
            {isMaximized ? "❐" : "⬜"}
          </button>
          <button className="titlebar-btn close-btn" onClick={handleClose} title="Close">✕</button>
        </div>
      </div>

      <div className="app-container" style={{ height: "calc(100vh - 32px)" }}>
      {/* Header */}
      <header className="app-header glass-panel">
        <div className="brand-section">
          <div style={{ display: "flex", alignItems: "center", gap: "10px" }}>
            <img 
              src={logo} 
              alt="UFM Logo" 
              style={{ width: "28px", height: "28px", objectFit: "contain", borderRadius: "6px" }} 
            />
            <span className="app-title">Ultimate File Manager Pro</span>
          </div>
        </div>

        <div className="connection-section">
          {ufm.activeDevice ? (
            <div className="device-status">
              <span className="status-dot connected"></span>
              {ufm.activeDevice.is_tv ? <Tv style={{ width: 14, height: 14 }} /> : <Smartphone style={{ width: 14, height: 14 }} />}
              <span>{ufm.activeDevice.name} ({ufm.activeDevice.ip})</span>
            </div>
          ) : (
            <div className="device-status">
              <span className="status-dot"></span>
              <span>Disconnected</span>
            </div>
          )}

          <button 
            className="btn-primary" 
            onClick={() => {
              ufm.searchDevices();
              setShowDeviceSelector(true);
            }}
          >
            <Link style={{ width: 16, height: 16 }} />
            Connect Device
          </button>
        </div>
      </header>

      {/* Main Filesystem Panels */}
      <div className="panes-container">
        {/* Local PC Pane */}
        <FilePane
          title="Local Storage"
          isRemote={false}
          files={localFiles}
          currentPath={localPath}
          roots={localRoots}
          isLoading={isLocalLoading}
          onNavigate={loadLocalDir}
          onRefresh={() => loadLocalDir(localPath)}
          onMkdir={() => {}} // Local mkdir can be implemented locally or omitted for read-only local safety
          onDelete={() => {}} // Read-only local filesystem protection in MVP
          onFileSelect={setSelectedLocalFiles}
          selectedFiles={selectedLocalFiles}
        />

        {/* Transfer Button Column */}
        <div style={{ display: "flex", flexDirection: "column", justifyContent: "center", gap: "16px" }}>
          <button 
            className="btn-primary" 
            onClick={handleUpload}
            disabled={selectedLocalFiles.length === 0 || !ufm.activeDevice}
            style={{ 
              borderRadius: "50%", width: "48px", height: "48px", 
              justifyContent: "center", padding: 0,
              opacity: (selectedLocalFiles.length === 0 || !ufm.activeDevice) ? 0.4 : 1
            }}
            title="Upload selected PC file(s) to Android"
          >
            <Upload style={{ width: 20, height: 20 }} />
          </button>

          <input
            type="file"
            ref={sideloadInputRef}
            onChange={(e) => {
              const files = e.target.files;
              if (files && files.length > 0) {
                const file = files[0];
                const name = file.name;
                const isXapk = name.toLowerCase().endsWith(".xapk");
                const path = (file as any).path;
                if (path) {
                  handleSideload(path, name, isXapk);
                } else {
                  alert("Could not determine absolute file path.");
                }
              }
            }}
            accept=".apk,.xapk"
            style={{ display: "none" }}
          />
          <button 
            className="btn-primary" 
            onClick={() => sideloadInputRef.current?.click()}
            disabled={!ufm.activeDevice}
            style={{ 
              borderRadius: "50%", width: "48px", height: "48px", 
              justifyContent: "center", padding: 0,
              opacity: (!ufm.activeDevice) ? 0.4 : 1
            }}
            title="Sideload APK/XAPK to Android"
          >
            <UploadCloud style={{ width: 20, height: 20 }} />
          </button>

          <button 
            className="btn-primary" 
            onClick={handleDownload}
            disabled={selectedRemoteFiles.length === 0 || !ufm.activeDevice}
            style={{ 
              borderRadius: "50%", width: "48px", height: "48px", 
              justifyContent: "center", padding: 0,
              opacity: (selectedRemoteFiles.length === 0 || !ufm.activeDevice) ? 0.4 : 1
            }}
            title="Download selected Android file(s) to PC"
          >
            <Download style={{ width: 20, height: 20 }} />
          </button>
        </div>

        {/* Remote Android / TV Pane */}
        {ufm.activeDevice ? (
          <FilePane
            title="Remote Storage"
            isRemote={true}
            isTv={ufm.activeDevice.is_tv}
            files={remoteFiles}
            currentPath={remotePath}
            roots={remoteRoots}
            isLoading={isRemoteLoading}
            onNavigate={loadRemoteDir}
            onRefresh={() => loadRemoteDir(remotePath)}
            onMkdir={(path, name) => handleMkdir(true, path, name)}
            onDelete={(paths) => handleDelete(true, paths)}
            onFileSelect={setSelectedRemoteFiles}
            selectedFiles={selectedRemoteFiles}
          />
        ) : (
          <div className="file-pane glass-panel" style={{ flex: 1, display: "flex", alignItems: "center", justifyContent: "center" }}>
            <div className="empty-state">
              <Smartphone style={{ width: 48, height: 48, color: "var(--text-hint)" }} />
              <span style={{ fontWeight: 600 }}>No Device Connected</span>
              <span style={{ fontSize: "0.8rem", textAlign: "center" }}>
                Click the "Connect Device" button in the header to pair and explore your phone or Android TV storage.
              </span>
            </div>
          </div>
        )}
      </div>

      {/* Progress Widget */}
      <ProgressBar 
        label={transferLabel} 
        progress={transferProgress} 
        visible={isTransferActive} 
      />


      {/* Device Selector Modal */}
      {showDeviceSelector && (
        <DeviceSelector
          discovered={ufm.discoveredDevices}
          paired={ufm.pairedDevices}
          active={ufm.activeDevice}
          isSearching={ufm.isSearching}
          isConnecting={ufm.isConnecting}
          onSearch={ufm.searchDevices}
          onSelect={ufm.selectDevice}
          onPair={ufm.pairDevice}
          onUnpair={ufm.unpairDevice}
          onClose={() => setShowDeviceSelector(false)}
        />
      )}

      {/* Global Error Banner */}
      {ufm.error && (
        <div style={{
          position: "fixed", bottom: "24px", left: "24px",
          background: "rgba(239, 68, 68, 0.95)", color: "white",
          padding: "12px 20px", borderRadius: "8px", display: "flex",
          alignItems: "center", gap: "10px", fontSize: "0.8rem",
          boxShadow: "0 10px 15px -3px rgba(0,0,0,0.3)", zIndex: 5000
        }}>
          <AlertCircle style={{ width: 16, height: 16 }} />
          <span>{ufm.error}</span>
          <button 
            onClick={() => ufm.setError(null)}
            style={{ background: "transparent", border: "none", color: "white", cursor: "pointer", marginLeft: "10px" }}
          >
            ✕
          </button>
        </div>
      )}
    </div>
    </>
  );
}

export default App;
