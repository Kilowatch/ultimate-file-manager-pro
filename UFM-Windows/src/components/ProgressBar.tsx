import React from "react";

interface ProgressBarProps {
  label: string;
  progress: number; // 0 to 100
  visible: boolean;
}

export const ProgressBar: React.FC<ProgressBarProps> = ({ label, progress, visible }) => {
  if (!visible) return null;

  return (
    <div className="transfer-status-bar glass-panel" style={{ width: "100%", margin: "8px 0" }}>
      <div className="transfer-info">
        <span style={{ fontWeight: 600, fontSize: "0.85rem" }}>{label}</span>
        <span>{progress}%</span>
      </div>
      <div className="transfer-progress-bg">
        <div className="transfer-progress-fill" style={{ width: `${progress}%` }}></div>
      </div>
    </div>
  );
};
