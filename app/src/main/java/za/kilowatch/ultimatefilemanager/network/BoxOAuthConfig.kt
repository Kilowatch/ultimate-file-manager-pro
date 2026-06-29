package za.kilowatch.ultimatefilemanager.network

/**
 * rclone's built-in Box OAuth 2.0 credentials.
 *
 * These are rclone's public, open-source Box app credentials — the same
 * credentials used by `rclone authorize "box"` on the command line.
 * They are hardcoded in the rclone source repository and shared by all
 * rclone users. No user registration or Box Developer Console access is
 * required.
 *
 * The redirect URI (http://127.0.0.1:53682/) is registered with rclone's
 * Box app. On mobile, the WebView intercepts this redirect locally — no
 * actual HTTP server is needed. On TV, the device code flow is used
 * instead (no redirect URI required).
 */
object BoxOAuthConfig {
    const val CLIENT_ID = "d0374ba6pgmaguie02ge15sv1mllndho"
    const val CLIENT_SECRET = "grWXGU7zW6034GI54GuswaDQdE30QOfn"
    const val REDIRECT_URI = "http://127.0.0.1:53682/"
    const val SCOPE = "root_readwrite"
}
