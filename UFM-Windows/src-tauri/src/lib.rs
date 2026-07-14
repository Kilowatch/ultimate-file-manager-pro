mod discovery;
mod commands;

use commands::{
    get_system_roots, 
    list_local_directory, 
    discover_devices, 
    save_paired_devices, 
    load_paired_devices,
    ufm_api_request,
    upload_file_to_android,
    download_file_from_android,
    upload_folder_to_android,
    download_folder_from_android
};

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
            get_system_roots,
            list_local_directory,
            discover_devices,
            save_paired_devices,
            load_paired_devices,
            ufm_api_request,
            upload_file_to_android,
            download_file_from_android,
            upload_folder_to_android,
            download_folder_from_android
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
