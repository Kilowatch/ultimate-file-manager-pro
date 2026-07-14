import React, { useState, useRef } from "react";
import { DownloadCloud, AlertTriangle } from "lucide-react";

interface SideloadZoneProps {
  onSideload: (localPath: string, filename: string, isXapk: boolean) => void;
  disabled: boolean;
}

export const SideloadZone: React.FC<SideloadZoneProps> = ({ onSideload, disabled }) => {
  const [isDragging, setIsDragging] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    if (disabled) return;
    setIsDragging(true);
  };

  const handleDragLeave = () => {
    setIsDragging(false);
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
    if (disabled) return;

    const files = e.dataTransfer.files;
    if (files.length > 0) {
      processFile(files[0]);
    }
  };

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (files && files.length > 0) {
      processFile(files[0]);
    }
  };

  const processFile = (file: File) => {
    const filename = file.name;
    const isApk = filename.toLowerCase().endsWith(".apk");
    const isXapk = filename.toLowerCase().endsWith(".xapk");

    if (!isApk && !isXapk) {
      alert("Invalid file format. Please drop a .apk or .xapk package.");
      return;
    }

    // Tauri injects the absolute path into the File object
    const absolutePath = (file as any).path;
    if (!absolutePath) {
      alert("Could not determine file path. If you are running in browser, compile the Tauri app.");
      return;
    }

    onSideload(absolutePath, filename, isXapk);
  };

  const handleClick = () => {
    if (disabled) return;
    fileInputRef.current?.click();
  };

  return (
    <div
      className={`sideload-zone glass-panel ${isDragging ? "dragging" : ""}`}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
      onClick={handleClick}
      style={{
        opacity: disabled ? 0.5 : 1,
        cursor: disabled ? "not-allowed" : "pointer"
      }}
    >
      <input
        type="file"
        ref={fileInputRef}
        onChange={handleFileChange}
        accept=".apk,.xapk"
        style={{ display: "none" }}
      />
      <DownloadCloud className="sideload-icon" />
      <div className="sideload-title">
        {disabled ? "Connect to a Device to Install Apps" : "Remote App Sideload Zone"}
      </div>
      <div className="sideload-desc">
        {disabled 
          ? "Establish connection first" 
          : "Drag & drop a .apk or .xapk here, or click to browse"
        }
      </div>
      {!disabled && (
        <div style={{ display: "flex", alignItems: "center", gap: "6px", marginTop: "12px", color: "var(--text-sec)", fontSize: "0.7rem" }}>
          <AlertTriangle style={{ width: 12, height: 12, color: "var(--warning)" }} />
          Triggers installation prompt on the target Phone or TV screen.
        </div>
      )}
    </div>
  );
};
