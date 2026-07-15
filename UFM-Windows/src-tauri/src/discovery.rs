use std::net::{UdpSocket, SocketAddr, Ipv4Addr};
use std::time::Duration;
use get_if_addrs::get_if_addrs;

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
    
    // Find all subnet broadcast addresses from active network interfaces
    let mut broadcast_addrs = Vec::new();
    if let Ok(interfaces) = get_if_addrs() {
        for iface in interfaces {
            if !iface.is_loopback() {
                if let get_if_addrs::IfAddr::V4(ifv4) = iface.addr {
                    if let Some(broad) = ifv4.broadcast {
                        broadcast_addrs.push(SocketAddr::new(std::net::IpAddr::V4(broad), 8086));
                    }
                }
            }
        }
    }
    
    // Always fallback to the global broadcast address if no local broadcasts found
    if broadcast_addrs.is_empty() {
        broadcast_addrs.push(SocketAddr::new(std::net::IpAddr::V4(Ipv4Addr::new(255, 255, 255, 255)), 8086));
    }

    let socket = match UdpSocket::bind("0.0.0.0:0") {
        Ok(s) => s,
        Err(_) => return devices,
    };
    let _ = socket.set_broadcast(true);
    let _ = socket.set_read_timeout(Some(Duration::from_millis(150)));

    let msg = b"UFM_DISCOVER:";
    
    // Send discovery broadcast to each subnet broadcast address
    for _ in 0..3 {
        for &addr in &broadcast_addrs {
            let _ = socket.send_to(msg, addr);
        }
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
