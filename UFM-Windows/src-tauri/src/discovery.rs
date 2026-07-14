use std::net::{UdpSocket, SocketAddr};
use std::time::Duration;

#[derive(serde::Serialize, Clone, Debug)]
pub struct DiscoveredDevice {
    pub ip: String,
    pub id: String,
    pub name: String,
    pub port: u16,
    pub is_tv: bool,
}

pub fn discover(timeout_ms: u64) -> Vec<DiscoveredDevice> {
    let mut devices = Vec::new();
    let socket = match UdpSocket::bind("0.0.0.0:0") {
        Ok(s) => s,
        Err(_) => return devices,
    };
    let _ = socket.set_broadcast(true);
    let _ = socket.set_read_timeout(Some(Duration::from_millis(150)));

    // Broadcast address: 255.255.255.255:8086
    let broadcast_addr: SocketAddr = "255.255.255.255:8086".parse().unwrap();
    let msg = b"UFM_DISCOVER:";
    
    // Broadcast a few times for network robustness
    for _ in 0..3 {
        let _ = socket.send_to(msg, broadcast_addr);
        std::thread::sleep(Duration::from_millis(50));
    }

    let mut buf = [0u8; 1024];
    let start_time = std::time::Instant::now();
    while start_time.elapsed() < Duration::from_millis(timeout_ms) {
        match socket.recv_from(&mut buf) {
            Ok((amt, src)) => {
                let reply = String::from_utf8_lossy(&buf[..amt]);
                if reply.starts_with("UFM_RESPONSE:") {
                    let payload = reply.trim_start_matches("UFM_RESPONSE:");
                    let parts: Vec<&str> = payload.split(':').collect();
                    if parts.len() >= 4 {
                        let id = parts[0].to_string();
                        let port = parts[1].parse::<u16>().unwrap_or(8085);
                        let is_tv = parts[2].parse::<bool>().unwrap_or(false);
                        let name = parts[3..].join(":"); // Re-join in case device name contains colons
                        let ip = src.ip().to_string();
                        
                        if !devices.iter().any(|d: &DiscoveredDevice| d.id == id) {
                            devices.push(DiscoveredDevice {
                                ip,
                                id,
                                name,
                                port,
                                is_tv,
                            });
                        }
                    }
                }
            }
            Err(_) => {
                // If it is a timeout error, we just continue checking until timeout_ms elapsed
                std::thread::sleep(Duration::from_millis(50));
            }
        }
    }
    devices
}
