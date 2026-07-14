import { useState, useEffect } from "react";
import { invoke } from "@tauri-apps/api/core";

export interface DiscoveredDevice {
  ip: string;
  id: string;
  name: string;
  port: number;
  is_tv: boolean;
}

export interface PairedDevice {
  ip: string;
  id: string;
  name: string;
  port: number;
  is_tv: boolean;
  auth_token: string;
  cert_fingerprint: string;
}

export interface UfmFile {
  name: string;
  path: string;
  is_dir: boolean;
  size: number;
  modified: number;
}

export interface UfmVolume {
  label: string;
  path: string;
  primary: boolean;
  removable: boolean;
  totalBytes: number;
  freeBytes: number;
  totalFormatted: string;
  freeFormatted: string;
  isNetwork: boolean;
}

export function useUfmApi() {
  const [discoveredDevices, setDiscoveredDevices] = useState<DiscoveredDevice[]>([]);
  const [pairedDevices, setPairedDevices] = useState<PairedDevice[]>([]);
  const [activeDevice, setActiveDevice] = useState<PairedDevice | null>(null);
  const [isSearching, setIsSearching] = useState(false);
  const [isConnecting, setIsConnecting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Load saved devices on mount
  useEffect(() => {
    loadSavedDevices();
  }, []);

  const loadSavedDevices = async () => {
    try {
      const saved = await invoke<PairedDevice[]>("load_paired_devices");
      setPairedDevices(saved || []);
      if (saved && saved.length > 0) {
        // Try auto-selecting the first device
        setActiveDevice(saved[0]);
      }
    } catch (e) {
      console.error("Failed to load paired devices:", e);
    }
  };

  const searchDevices = async () => {
    setIsSearching(true);
    setError(null);
    try {
      const discovered = await invoke<DiscoveredDevice[]>("discover_devices", { timeoutMs: 3000 });
      setDiscoveredDevices(discovered);
    } catch (e: any) {
      setError(e.toString() || "Discovery error");
    } finally {
      setIsSearching(false);
    }
  };

  const pairDevice = async (device: DiscoveredDevice, pin: string) => {
    setIsConnecting(true);
    setError(null);
    try {
      const authUrl = `https://${device.ip}:8444/api/auth`;
      const authBody = JSON.stringify({ pin });

      // Initial pairing: accept any cert (fingerprint will be stored from the response)
      const resStr = await invoke<string>("ufm_api_request", {
        method: "POST",
        url: authUrl,
        token: null,
        bodyJson: authBody,
        // No fingerprint yet — Rust backend will accept any cert during first-time pairing
        certFingerprint: "",
      });

      const resJson = JSON.parse(resStr);
      const token = resJson.token;
      // The Android UFM server returns its cert fingerprint in the auth response
      // so the Windows client can pin to it for all subsequent connections.
      const certFingerprint: string = resJson.certFingerprint ?? resJson.cert_fingerprint ?? "";

      if (!token) {
        throw new Error("Failed to receive authentication token.");
      }

      // Create new paired device config with the real cert fingerprint
      const newDevice: PairedDevice = {
        ...device,
        auth_token: token,
        cert_fingerprint: certFingerprint,
      };

      const updatedDevices = [...pairedDevices.filter((d) => d.id !== device.id), newDevice];
      await invoke("save_paired_devices", { devices: updatedDevices });
      setPairedDevices(updatedDevices);
      setActiveDevice(newDevice);
      return true;
    } catch (e: any) {
      const errMsg = e.toString() || "Authentication failed";
      setError(errMsg);
      return false;
    } finally {
      setIsConnecting(false);
    }
  };

  const unpairDevice = async (deviceId: string) => {
    try {
      const updated = pairedDevices.filter((d) => d.id !== deviceId);
      await invoke("save_paired_devices", { devices: updated });
      setPairedDevices(updated);
      if (activeDevice?.id === deviceId) {
        setActiveDevice(updated.length > 0 ? updated[0] : null);
      }
    } catch (e) {
      console.error("Failed to unpair device:", e);
    }
  };

  const selectDevice = (device: PairedDevice) => {
    setActiveDevice(device);
    setError(null);
  };

  // --- API Wrappers (Requires Connected/Active Device) ---

  const request = async (method: string, apiPath: string, bodyJson?: string): Promise<string> => {
    if (!activeDevice) throw new Error("No active device connected");
    const url = `https://${activeDevice.ip}:8444${apiPath}`;
    return await invoke<string>("ufm_api_request", {
      method,
      url,
      token: activeDevice.auth_token,
      bodyJson,
      certFingerprint: activeDevice.cert_fingerprint,
    });
  };

  const getVolumes = async (): Promise<UfmVolume[]> => {
    try {
      const res = await request("GET", "/api/volumes");
      return JSON.parse(res);
    } catch (e: any) {
      setError(e.toString() || "Failed to fetch storage volumes");
      return [];
    }
  };

  const browse = async (path: string): Promise<UfmFile[]> => {
    try {
      const res = await request("GET", `/api/browse?path=${encodeURIComponent(path)}`);
      const resJson = JSON.parse(res);
      const items = resJson.items || [];
      // Format to UfmFile shape
      return items.map((f: any) => ({
        name: f.name,
        path: f.path,
        is_dir: f.isDirectory,
        size: f.size || 0,
        modified: (f.lastModified || 0) / 1000, // UFM lastModified is in milliseconds, convert to seconds
      }));
    } catch (e: any) {
      setError(e.toString() || `Failed to read folder: ${path}`);
      return [];
    }
  };

  const mkdir = async (path: string, folderName: string): Promise<boolean> => {
    try {
      await request("POST", "/api/mkdir", JSON.stringify({ path, name: folderName }));
      return true;
    } catch (e: any) {
      setError(e.toString() || "Failed to create folder");
      return false;
    }
  };

  const deletePath = async (path: string): Promise<boolean> => {
    try {
      await request("POST", "/api/delete", JSON.stringify({ path }));
      return true;
    } catch (e: any) {
      setError(e.toString() || "Failed to delete item");
      return false;
    }
  };

  const rename = async (path: string, newName: string): Promise<boolean> => {
    try {
      await request("POST", "/api/rename", JSON.stringify({ path, newName }));
      return true;
    } catch (e: any) {
      setError(e.toString() || "Failed to rename item");
      return false;
    }
  };

  // Upload a local file or folder to remote Android directory
  const uploadLocalFile = async (
    localPath: string,
    remoteFolder: string,
    isDir: boolean
  ): Promise<boolean> => {
    if (!activeDevice) return false;
    try {
      if (isDir) {
        // Folder upload
        await invoke("upload_folder_to_android", {
          localPath,
          remoteIp: activeDevice.ip,
          token: activeDevice.auth_token,
          remoteParentFolder: remoteFolder,
          certFingerprint: activeDevice.cert_fingerprint,
        });
      } else {
        // File upload
        const lastSlashIdx = Math.max(localPath.lastIndexOf("\\"), localPath.lastIndexOf("/"));
        const fileName = lastSlashIdx !== -1 ? localPath.substring(lastSlashIdx + 1) : "file.bin";
        
        const remoteUrl = `https://${activeDevice.ip}:8444/api/upload?path=${encodeURIComponent(remoteFolder)}&filename=${encodeURIComponent(fileName)}`;
        
        await invoke("upload_file_to_android", {
          localPath,
          remoteUrl,
          token: activeDevice.auth_token,
          remoteFolder,
          certFingerprint: activeDevice.cert_fingerprint,
        });
      }
      return true;
    } catch (e: any) {
      setError(e.toString() || "Upload failed");
      return false;
    }
  };

  // Download remote Android file or folder to local PC directory
  const downloadRemoteFile = async (
    remoteFilePath: string,
    localPath: string,
    isDir: boolean
  ): Promise<boolean> => {
    if (!activeDevice) return false;
    try {
      if (isDir) {
        // Folder download
        await invoke("download_folder_from_android", {
          remotePath: remoteFilePath,
          localParentPath: localPath,
          token: activeDevice.auth_token,
          remoteIp: activeDevice.ip,
          certFingerprint: activeDevice.cert_fingerprint,
        });
      } else {
        // File download
        const ticketRes = await request("POST", "/api/download-ticket", JSON.stringify({ path: remoteFilePath }));
        const ticketJson = JSON.parse(ticketRes);
        const ticket = ticketJson.ticket;

        if (!ticket) {
          throw new Error("Failed to secure download ticket from remote");
        }

        const remoteUrl = `https://${activeDevice.ip}:8444/api/download?ticket=${ticket}&path=${encodeURIComponent(remoteFilePath)}`;
        await invoke("download_file_from_android", {
          remoteUrl,
          token: activeDevice.auth_token,
          localPath,
          certFingerprint: activeDevice.cert_fingerprint,
        });
      }
      return true;
    } catch (e: any) {
      setError(e.toString() || "Download failed");
      return false;
    }
  };

  // Install APK/XAPK remotely
  const sideloadPackage = async (
    localPath: string,
    filename: string,
    isXapk: boolean,
    onProgress?: (percent: number) => void
  ): Promise<boolean> => {
    if (!activeDevice) return false;
    try {
      if (onProgress) onProgress(10);
      const endpoint = isXapk ? "/api/install-xapk-remote" : "/api/install-remote";
      const remoteUrl = `https://${activeDevice.ip}:8444${endpoint}?file=${encodeURIComponent(filename)}`;

      if (onProgress) onProgress(40);
      // Upload directly to the installation endpoint
      await invoke("upload_file_to_android", {
        localPath,
        remoteUrl,
        token: activeDevice.auth_token,
        remoteFolder: "apk_install",
        certFingerprint: activeDevice.cert_fingerprint,
      });

      if (onProgress) onProgress(100);
      return true;
    } catch (e: any) {
      setError(e.toString() || "Remote installation failed");
      return false;
    }
  };

  return {
    discoveredDevices,
    pairedDevices,
    activeDevice,
    isSearching,
    isConnecting,
    error,
    searchDevices,
    pairDevice,
    unpairDevice,
    selectDevice,
    getVolumes,
    browse,
    mkdir,
    deletePath,
    rename,
    uploadLocalFile,
    downloadRemoteFile,
    sideloadPackage,
    setError,
  };
}
