import React, { useState } from "react";
import { 
  Folder, File, ArrowUp, RefreshCw, FolderPlus, 
  Trash2, HardDrive, Smartphone, Tv, ChevronRight 
} from "lucide-react";
import { UfmFile } from "../hooks/useUfmApi";

interface FilePaneProps {
  title: string;
  isRemote: boolean;
  isTv?: boolean;
  files: UfmFile[];
  currentPath: string;
  roots: string[];
  isLoading: boolean;
  onNavigate: (path: string) => void;
  onRefresh: () => void;
  onMkdir: (path: string, name: string) => void;
  onDelete: (paths: string[]) => void;
  onFileSelect?: (files: UfmFile[]) => void;
  selectedFiles: UfmFile[];
}

export const FilePane: React.FC<FilePaneProps> = ({
  title,
  isRemote,
  isTv = false,
  files,
  currentPath,
  roots,
  isLoading,
  onNavigate,
  onRefresh,
  onMkdir,
  onDelete,
  onFileSelect,
  selectedFiles,
}) => {
  const [newFolderActive, setNewFolderActive] = useState(false);
  const [newFolderName, setNewFolderName] = useState("");
  const [lastSelectedIndex, setLastSelectedIndex] = useState<number | null>(null);

  const formatSize = (bytes: number) => {
    if (bytes === 0) return "-";
    const k = 1024;
    const sizes = ["B", "KB", "MB", "GB", "TB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + " " + sizes[i];
  };

  const formatDate = (secs: number) => {
    if (secs === 0) return "-";
    return new Date(secs * 1000).toLocaleDateString() + " " + new Date(secs * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  };

  const getBreadcrumbs = () => {
    const isWindows = currentPath.includes("\\") || /^[A-Z]:\\/.test(currentPath);
    const separator = isWindows ? "\\" : "/";
    let parts = currentPath.split(separator).filter(Boolean);
    const list: { name: string; path: string }[] = [];
    
    if (isWindows) {
      const drive = currentPath.match(/^[A-Z]:\\/)?.[0] || roots[0] || "C:\\";
      list.push({ name: drive, path: drive });
      let accumulated = drive;
      
      parts.forEach((p, idx) => {
        if (idx === 0 && p.endsWith(":")) return;
        accumulated = accumulated + p + "\\";
        list.push({ name: p, path: accumulated });
      });
    } else {
      list.push({ name: "Root", path: "/" });
      let accumulated = "";
      
      parts.forEach((p) => {
        accumulated = accumulated + "/" + p;
        list.push({ name: p, path: accumulated });
      });
    }
    return list;
  };

  const handleGoUp = () => {
    const isWindows = currentPath.includes("\\") || /^[A-Z]:\\/.test(currentPath);
    if (isWindows) {
      if (/^[A-Z]:\\$/.test(currentPath)) return;
      const cleanPath = currentPath.endsWith("\\") ? currentPath.slice(0, -1) : currentPath;
      const idx = cleanPath.lastIndexOf("\\");
      if (idx !== -1) {
        let parent = cleanPath.substring(0, idx + 1);
        if (/^[A-Z]:$/.test(parent)) parent += "\\";
        onNavigate(parent);
      }
    } else {
      if (currentPath === "/") return;
      const idx = currentPath.lastIndexOf("/");
      const parent = idx === 0 ? "/" : currentPath.substring(0, idx);
      onNavigate(parent);
    }
  };

  const handleMkdirSubmit = () => {
    if (!newFolderName.trim()) return;
    onMkdir(currentPath, newFolderName.trim());
    setNewFolderName("");
    setNewFolderActive(false);
  };

  const handleItemClick = (e: React.MouseEvent, file: UfmFile, index: number) => {
    let newSelected: UfmFile[] = [];

    if (e.ctrlKey) {
      // Toggle selection
      if (selectedFiles.some(f => f.path === file.path)) {
        newSelected = selectedFiles.filter(f => f.path !== file.path);
      } else {
        newSelected = [...selectedFiles, file];
      }
      setLastSelectedIndex(index);
    } else if (e.shiftKey && lastSelectedIndex !== null) {
      // Range selection
      const start = Math.min(lastSelectedIndex, index);
      const end = Math.max(lastSelectedIndex, index);
      const range = files.slice(start, end + 1);
      
      newSelected = Array.from(new Map([...selectedFiles, ...range].map(f => [f.path, f])).values());
    } else {
      // Single selection
      newSelected = [file];
      setLastSelectedIndex(index);
    }

    if (onFileSelect) {
      onFileSelect(newSelected);
    }
  };

  return (
    <div className="file-pane glass-panel">
      {/* Header */}
      <div className="pane-header">
        <div className="pane-title" style={{ minWidth: 0, flex: 1, marginRight: "12px" }}>
          {isRemote ? (
            isTv ? <Tv style={{ color: "var(--accent)", flexShrink: 0 }} /> : <Smartphone style={{ color: "var(--primary-light)", flexShrink: 0 }} />
          ) : (
            <HardDrive style={{ color: "var(--text-sec)", flexShrink: 0 }} />
          )}
          <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", flexShrink: 1 }}>
            {title}
          </span>
          <span className="pane-badge" style={{ flexShrink: 0 }}>{isRemote ? "Remote" : "Local PC"}</span>
        </div>

        <div style={{ display: "flex", gap: "8px" }}>
          <select 
            value={roots.find(r => currentPath.startsWith(r)) || roots[0] || ""}
            onChange={(e) => onNavigate(e.target.value)}
            style={{
              background: "rgba(255, 255, 255, 0.05)",
              border: "1px solid var(--glass-border)",
              borderRadius: "4px",
              color: "white",
              padding: "4px 8px",
              fontSize: "0.75rem",
              outline: "none"
            }}
          >
            {roots.map(r => (
              <option key={r} value={r} style={{ background: "var(--surface)" }}>{r}</option>
            ))}
          </select>

          <button onClick={handleGoUp} className="btn-secondary" style={{ padding: "4px 8px" }} title="Go up one level">
            <ArrowUp style={{ width: 14, height: 14 }} />
          </button>
          <button onClick={onRefresh} className="btn-secondary" style={{ padding: "4px 8px" }} title="Refresh list">
            <RefreshCw className={isLoading ? "status-dot pulsing" : ""} style={{ width: 14, height: 14 }} />
          </button>
          <button onClick={() => setNewFolderActive(true)} className="btn-secondary" style={{ padding: "4px 8px" }} title="Create folder">
            <FolderPlus style={{ width: 14, height: 14 }} />
          </button>
          <button 
            onClick={() => selectedFiles.length > 0 && onDelete(selectedFiles.map(f => f.path))} 
            className="btn-secondary" 
            style={{ padding: "4px 8px", color: selectedFiles.length > 0 ? "var(--danger)" : "var(--text-hint)" }} 
            disabled={selectedFiles.length === 0}
            title="Delete selected item(s)"
          >
            <Trash2 style={{ width: 14, height: 14 }} />
          </button>
        </div>
      </div>

      {/* Path Bar */}
      <div className="path-bar">
        {getBreadcrumbs().map((b, idx, arr) => (
          <React.Fragment key={b.path}>
            <span className="path-segment" onClick={() => onNavigate(b.path)}>
              {b.name}
            </span>
            {idx < arr.length - 1 && <ChevronRight className="path-separator" style={{ width: 12, height: 12 }} />}
          </React.Fragment>
        ))}
      </div>

      {/* New Folder Inline Form */}
      {newFolderActive && (
        <div style={{ display: "flex", gap: "8px", padding: "8px 16px", background: "rgba(255, 255, 255, 0.02)", borderBottom: "1px solid var(--glass-border)" }}>
          <input 
            type="text"
            placeholder="New folder name..."
            value={newFolderName}
            onChange={(e) => setNewFolderName(e.target.value)}
            style={{
              flex: 1, padding: "6px 12px", background: "rgba(0,0,0,0.2)",
              border: "1px solid var(--primary-light)", borderRadius: "4px",
              color: "white", fontSize: "0.8rem", outline: "none"
            }}
            onKeyDown={(e) => {
              if (e.key === "Enter") handleMkdirSubmit();
              if (e.key === "Escape") setNewFolderActive(false);
            }}
            autoFocus
          />
          <button onClick={handleMkdirSubmit} className="btn-primary" style={{ padding: "6px 12px", fontSize: "0.75rem" }}>Create</button>
          <button onClick={() => setNewFolderActive(false)} className="btn-secondary" style={{ padding: "6px 12px", fontSize: "0.75rem" }}>Cancel</button>
        </div>
      )}

      {/* File List */}
      <div className="file-list">
        {isLoading ? (
          <div className="empty-state">
            <RefreshCw className="status-dot pulsing" style={{ width: 32, height: 32 }} />
            <span>Reading directories...</span>
          </div>
        ) : files.length === 0 ? (
          <div className="empty-state">
            <Folder style={{ width: 32, height: 32, color: "var(--text-hint)" }} />
            <span>Folder is empty</span>
          </div>
        ) : (
          files.map((file, idx) => {
            const isSelected = selectedFiles.some(f => f.path === file.path);
            const isApk = file.name.toLowerCase().endsWith(".apk") || file.name.toLowerCase().endsWith(".xapk");

            return (
              <div 
                key={file.path} 
                className={`file-item ${isSelected ? "selected" : ""}`}
                onClick={(e) => handleItemClick(e, file, idx)}
                onDoubleClick={() => {
                  if (file.is_dir) {
                    onNavigate(file.path);
                  }
                }}
              >
                <div className="file-info">
                  {file.is_dir ? (
                    <Folder className="file-icon folder" />
                  ) : (
                    <File className={`file-icon ${isApk ? "apk" : "file"}`} />
                  )}
                  <span className="file-name" style={{ color: isApk ? "var(--primary-light)" : "var(--text)" }}>
                    {file.name}
                  </span>
                </div>
                <div className="file-meta">
                  <span>{formatSize(file.size)}</span>
                  <span>{formatDate(file.modified)}</span>
                </div>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
};
