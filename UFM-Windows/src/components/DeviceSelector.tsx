import React, { useState, useEffect } from "react";
import { Smartphone, Tv, Search, Lock, Trash2, RefreshCw } from "lucide-react";
import { DiscoveredDevice, PairedDevice } from "../hooks/useUfmApi";
import { TranslationSet } from "../hooks/translations";

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
  t: TranslationSet;
  initialPairDevice?: DiscoveredDevice | null;
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
  t,
  initialPairDevice,
}) => {
  const [selectedDiscovered, setSelectedDiscovered] = useState<DiscoveredDevice | null>(initialPairDevice || null);

  useEffect(() => {
    if (initialPairDevice) {
      setSelectedDiscovered(initialPairDevice);
    }
  }, [initialPairDevice]);

  const [pinDigits, setPinDigits] = useState<string[]>(["", "", "", ""]);
  const [pairError, setPairError] = useState<string | null>(null);
  const [manualIp, setManualIp] = useState("");

  const handleManualSubmit = () => {
    const rawInput = manualIp.trim();
    let ip = rawInput;
    let port = 8444;

    if (rawInput.includes(":")) {
      const parts = rawInput.split(":");
      ip = parts[0];
      const parsedPort = parseInt(parts[1], 10);
      if (!isNaN(parsedPort)) {
        port = parsedPort;
      }
    }

    const ipPattern = /^(\d{1,3}\.){3}\d{1,3}$/;
    if (!ipPattern.test(ip)) {
      alert(t.invalid_ip);
      return;
    }
    const manualDevice: DiscoveredDevice = {
      ip: ip,
      id: "manual-" + ip.replace(/\./g, "-"),
      name: t.manual_device_name,
      port: port,
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
      setPairError(t.enter_4_digit_pin);
      return;
    }

    setPairError(null);
    const success = await onPair(selectedDiscovered, pin);
    if (success) {
      setSelectedDiscovered(null);
      setPinDigits(["", "", "", ""]);
    } else {
      setPairError(t.invalid_pin);
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
            <h2 className="modal-title">{t.connect_device}</h2>
            <p className="modal-desc">
              {t.open_ufm_pro} {t.follow_instructions}
            </p>

            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "16px" }}>
              <span style={{ fontWeight: 700, fontSize: "0.85rem", color: "var(--text-sec)" }}>{t.pair.toUpperCase()}D {t.name.toUpperCase()}S</span>
              <button 
                onClick={onSearch} 
                className="btn-primary" 
                style={{ padding: "6px 12px", fontSize: "0.75rem" }}
                disabled={isSearching}
              >
                {isSearching ? <RefreshCw className="status-dot pulsing" style={{ width: 14, height: 14 }} /> : <Search style={{ width: 14, height: 14 }} />}
                {t.search}
              </button>
            </div>

            <div className="device-list">
              {paired.length === 0 ? (
                <div style={{ padding: "16px", color: "var(--text-hint)", fontSize: "0.8rem" }}>
                  {t.no_device_connected}
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
                        {dev.is_tv ? t.tv : t.mobile.toUpperCase()}
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
              {t.search.toUpperCase()}ED {t.name.toUpperCase()}S ({discovered.length})
            </h3>

            <div className="device-list" style={{ maxHeight: "150px" }}>
              {isSearching ? (
                <div style={{ padding: "16px", color: "var(--text-sec)", fontSize: "0.8rem", display: "flex", alignItems: "center", gap: "8px", justifyContent: "center" }}>
                  <RefreshCw className="status-dot pulsing" style={{ width: 14, height: 14 }} /> {t.search}...
                </div>
              ) : discovered.length === 0 ? (
                <div style={{ padding: "16px", color: "var(--text-hint)", fontSize: "0.8rem" }}>
                  {t.no_device_connected}
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
                        {dev.is_tv ? t.tv : t.mobile.toUpperCase()}
                      </span>
                      <span style={{ fontSize: "0.75rem", color: "var(--primary-light)", display: "flex", alignItems: "center", gap: "4px" }}>
                        <Lock style={{ width: 12, height: 12 }} /> {t.pair}
                      </span>
                    </div>
                  </div>
                ))
              )}
            </div>
            
            <div style={{ marginTop: "20px", borderTop: "1px solid var(--glass-border)", paddingTop: "16px", textAlign: "left" }}>
              <h3 style={{ fontWeight: 700, fontSize: "0.85rem", color: "var(--text-sec)", marginBottom: "8px" }}>
                {t.connect_device.toUpperCase()} ({t.mobile.toUpperCase()})
              </h3>
              <div style={{ display: "flex", gap: "8px" }}>
                <input
                  type="text"
                  placeholder={`${t.ip_address} (e.g. 192.168.1.100)`}
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
                  {t.pair}
                </button>
              </div>
            </div>
          </>
        ) : (
          <>
            <h2 className="modal-title">{t.pair} - {selectedDiscovered.name}</h2>
            <p className="modal-desc">
              {t.enter_pin}
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
                {t.cancel}
              </button>
              <button 
                onClick={handlePairSubmit} 
                className="btn-primary" 
                style={{ flex: 1, justifyContent: "center" }}
                disabled={isConnecting}
              >
                {isConnecting ? `${t.connect_device}...` : t.pair}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
};
