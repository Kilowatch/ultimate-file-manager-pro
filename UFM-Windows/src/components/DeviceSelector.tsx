import React, { useState } from "react";
import { Smartphone, Tv, Search, Lock, Trash2, RefreshCw } from "lucide-react";
import { DiscoveredDevice, PairedDevice } from "../hooks/useUfmApi";

interface DeviceSelectorProps {
  discovered: DiscoveredDevice[];
  paired: PairedDevice[];
  active: PairedDevice | null;
  isSearching: boolean;
  isConnecting: boolean;
  onSearch: () => void;
  onSelect: (device: PairedDevice) => void;
  onPair: (device: DiscoveredDevice, pin: string) => Promise<boolean>;
  onUnpair: (deviceId: string) => void;
  onClose: () => void;
}

export const DeviceSelector: React.FC<DeviceSelectorProps> = ({
  discovered,
  paired,
  active,
  isSearching,
  isConnecting,
  onSearch,
  onSelect,
  onPair,
  onUnpair,
  onClose,
}) => {
  const [selectedDiscovered, setSelectedDiscovered] = useState<DiscoveredDevice | null>(null);
  const [pinDigits, setPinDigits] = useState<string[]>(["", "", "", ""]);
  const [pairError, setPairError] = useState<string | null>(null);
  const [manualIp, setManualIp] = useState("");

  const handleManualSubmit = () => {
    const ipPattern = /^(\d{1,3}\.){3}\d{1,3}$/;
    if (!ipPattern.test(manualIp.trim())) {
      alert("Please enter a valid IP address.");
      return;
    }
    const manualDevice: DiscoveredDevice = {
      ip: manualIp.trim(),
      id: "manual-" + manualIp.trim().replace(/\./g, "-"),
      name: "Android Device (Manual)",
      port: 8444,
      is_tv: false,
    };
    setSelectedDiscovered(manualDevice);
  };

  const handleDigitChange = (index: number, val: string) => {
    if (val.length > 1) val = val[val.length - 1];
    if (val !== "" && !/^\d$/.test(val)) return;

    const newDigits = [...pinDigits];
    newDigits[index] = val;
    setPinDigits(newDigits);

    // Auto-focus next input
    if (val !== "" && index < 3) {
      const nextInput = document.getElementById(`pin-${index + 1}`);
      nextInput?.focus();
    }
  };

  const handleKeyDown = (index: number, e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Backspace" && pinDigits[index] === "" && index > 0) {
      const prevInput = document.getElementById(`pin-${index - 1}`);
      prevInput?.focus();
    }
  };

  const handlePairSubmit = async () => {
    if (!selectedDiscovered) return;
    const pin = pinDigits.join("");
    if (pin.length !== 4) {
      setPairError("Please enter a 4-digit PIN.");
      return;
    }

    setPairError(null);
    const success = await onPair(selectedDiscovered, pin);
    if (success) {
      setSelectedDiscovered(null);
      setPinDigits(["", "", "", ""]);
    } else {
      setPairError("Invalid PIN or connection refused.");
    }
  };

  return (
    <div className="modal-overlay">
      <div className="modal-content glass-panel" style={{ maxWidth: "500px" }}>
        <button 
          onClick={onClose} 
          style={{
            position: "absolute", top: "16px", right: "16px",
            background: "transparent", border: "none", color: "var(--text-sec)",
            cursor: "pointer", fontSize: "1.1rem"
          }}
        >
          ✕
        </button>

        {!selectedDiscovered ? (
          <>
            <h2 className="modal-title">Connect to UFM Device</h2>
            <p className="modal-desc">
              Ensure Ultimate File Manager is running its Remote Server or Pairing mode on your Android Phone or Android TV.
            </p>

            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "16px" }}>
              <span style={{ fontWeight: 700, fontSize: "0.85rem", color: "var(--text-sec)" }}>PAIRED DEVICES</span>
              <button 
                onClick={onSearch} 
                className="btn-primary" 
                style={{ padding: "6px 12px", fontSize: "0.75rem" }}
                disabled={isSearching}
              >
                {isSearching ? <RefreshCw className="status-dot pulsing" style={{ width: 14, height: 14 }} /> : <Search style={{ width: 14, height: 14 }} />}
                Scan LAN
              </button>
            </div>

            <div className="device-list">
              {paired.length === 0 ? (
                <div style={{ padding: "16px", color: "var(--text-hint)", fontSize: "0.8rem" }}>
                  No paired devices yet. Run a Scan to discover active devices.
                </div>
              ) : (
                paired.map((dev) => (
                  <div 
                    key={dev.id} 
                    className={`device-item ${active?.id === dev.id ? "active" : ""}`}
                    style={{
                      borderLeft: active?.id === dev.id ? "4px solid var(--primary)" : "1px solid var(--glass-border)",
                      background: active?.id === dev.id ? "rgba(0, 137, 123, 0.08)" : ""
                    }}
                    onClick={() => {
                      onSelect(dev);
                      onClose();
                    }}
                  >
                    <div className="device-info">
                      <div className="device-name" style={{ display: "flex", alignItems: "center", gap: "6px" }}>
                        {dev.is_tv ? <Tv style={{ width: 16, height: 16 }} /> : <Smartphone style={{ width: 16, height: 16 }} />}
                        {dev.name}
                      </div>
                      <span className="device-ip">{dev.ip}</span>
                    </div>
                    <div style={{ display: "flex", alignItems: "center", gap: "10px" }} onClick={(e) => e.stopPropagation()}>
                      <span className={`device-badge ${dev.is_tv ? "tv" : ""}`}>
                        {dev.is_tv ? "TV" : "PHONE"}
                      </span>
                      <button 
                        onClick={() => onUnpair(dev.id)} 
                        style={{ background: "transparent", border: "none", color: "var(--danger)", cursor: "pointer" }}
                      >
                        <Trash2 style={{ width: 16, height: 16 }} />
                      </button>
                    </div>
                  </div>
                ))
              )}
            </div>

            <h3 style={{ fontWeight: 700, fontSize: "0.85rem", color: "var(--text-sec)", textAlign: "left", marginBottom: "12px" }}>
              DISCOVERED DEVICES ({discovered.length})
            </h3>

            <div className="device-list" style={{ maxHeight: "150px" }}>
              {isSearching ? (
                <div style={{ padding: "16px", color: "var(--text-sec)", fontSize: "0.8rem", display: "flex", alignItems: "center", gap: "8px", justifyContent: "center" }}>
                  <RefreshCw className="status-dot pulsing" style={{ width: 14, height: 14 }} /> Scanning local network...
                </div>
              ) : discovered.length === 0 ? (
                <div style={{ padding: "16px", color: "var(--text-hint)", fontSize: "0.8rem" }}>
                  No new UFM servers detected. Check that WiFi is on.
                </div>
              ) : (
                discovered.map((dev) => (
                  <div 
                    key={dev.id} 
                    className="device-item"
                    onClick={() => setSelectedDiscovered(dev)}
                  >
                    <div className="device-info">
                      <span className="device-name" style={{ display: "flex", alignItems: "center", gap: "6px" }}>
                        {dev.is_tv ? <Tv style={{ width: 16, height: 16 }} /> : <Smartphone style={{ width: 16, height: 16 }} />}
                        {dev.name}
                      </span>
                      <span className="device-ip">{dev.ip}</span>
                    </div>
                    <div style={{ display: "flex", alignItems: "center", gap: "10px" }}>
                      <span className={`device-badge ${dev.is_tv ? "tv" : ""}`}>
                        {dev.is_tv ? "TV" : "PHONE"}
                      </span>
                      <span style={{ fontSize: "0.75rem", color: "var(--primary-light)", display: "flex", alignItems: "center", gap: "4px" }}>
                        <Lock style={{ width: 12, height: 12 }} /> Pair
                      </span>
                    </div>
                  </div>
                ))
              )}
            </div>
            
            <div style={{ marginTop: "20px", borderTop: "1px solid var(--glass-border)", paddingTop: "16px", textAlign: "left" }}>
              <h3 style={{ fontWeight: 700, fontSize: "0.85rem", color: "var(--text-sec)", marginBottom: "8px" }}>
                CONNECT MANUALLY
              </h3>
              <div style={{ display: "flex", gap: "8px" }}>
                <input
                  type="text"
                  placeholder="Enter IP (e.g. 192.168.1.100)"
                  value={manualIp}
                  onChange={(e) => setManualIp(e.target.value)}
                  style={{
                    flex: 1, padding: "6px 12px", background: "rgba(0,0,0,0.2)",
                    border: "1px solid var(--glass-border)", borderRadius: "4px",
                    color: "white", fontSize: "0.8rem", outline: "none"
                  }}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") handleManualSubmit();
                  }}
                />
                <button
                  onClick={handleManualSubmit}
                  className="btn-primary"
                  style={{ padding: "6px 12px", fontSize: "0.75rem" }}
                >
                  Pair Directly
                </button>
              </div>
            </div>
          </>
        ) : (
          <>
            <h2 className="modal-title">Pair with {selectedDiscovered.name}</h2>
            <p className="modal-desc">
              Enter the 4-digit PIN displayed on your {selectedDiscovered.is_tv ? "Android TV" : "Android Phone"}.
            </p>

            <div className="pin-input-container">
              {pinDigits.map((digit, idx) => (
                <input
                  key={idx}
                  id={`pin-${idx}`}
                  type="text"
                  pattern="[0-9]*"
                  inputMode="numeric"
                  className="pin-digit"
                  value={digit}
                  onChange={(e) => handleDigitChange(idx, e.target.value)}
                  onKeyDown={(e) => handleKeyDown(idx, e)}
                  disabled={isConnecting}
                  autoComplete="off"
                />
              ))}
            </div>

            {pairError && <div className="pin-error">{pairError}</div>}

            <div style={{ display: "flex", gap: "12px", marginTop: "24px" }}>
              <button 
                onClick={() => { setSelectedDiscovered(null); setPairError(null); }} 
                className="btn-secondary" 
                style={{ flex: 1, justifyContent: "center" }}
                disabled={isConnecting}
              >
                Back
              </button>
              <button 
                onClick={handlePairSubmit} 
                className="btn-primary" 
                style={{ flex: 1, justifyContent: "center" }}
                disabled={isConnecting}
              >
                {isConnecting ? "Connecting..." : "Confirm PIN"}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
};
