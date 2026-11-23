package io.max.paperplay.ui.app

/** A single option inside a settings page, so Settings search finds it and opens the page that holds it. */
internal class SettingHit(val title: String, val page: String, val extra: String = "")

private fun h(title: String, page: String, extra: String = "") = SettingHit(title, page, extra)

// chisle: written by hand; add a line here when a page gets a new option worth finding by name.
internal val settingHits = listOf(
    h("True black", "style", "amoled dark"), h("Reduce motion", "style", "animation"), h("Home animation", "style"),
    h("All corners", "style", "corner radius round shape"),
    h("Blur the background", "glass"), h("Refraction", "glass", "lens"), h("Tint", "glass"), h("Colour saturation", "glass"),
    h("Depth effect", "glass"), h("Colour fringing", "glass", "chromatic"), h("Edge line width", "glass", "edge brightness"),
    h("Outer shadow size", "glass"), h("Inner glow size", "glass"),
    h("Channels and chats", "channels"), h("Bots", "bots", "extra bot token api"), h("Your account", "account", "login phone qr"),
    h("Activity log", "log"), h("Log out", "telegram", "bot account delete data"),
    h("Scan library", "repair", "rescan reading speed messages per request"), h("Find duplicates", "dupes", "delete"),
    h("Sort General", "sort"), h("Fix compilation covers", "library"), h("Clear deleted songs", "library"),
    h("Fast song info", "library"), h("Fill in missing info in the background", "library", "metadata"),
    h("Song info left", "missing", "tags"), h("Music folders", "folders", "local"), h("Recover songs the bot cannot read", "recover"),
    h("Saved export lists", "exports"), h("Mixes and learning", "mixlearn", "taste clear learned"), h("Weather", "weather", "city mix"),
    h("Upload songs", "uploadhub"), h("Uploads at once", "uploads", "parallel"), h("Upload more slowly", "uploads"),
    h("Skip songs already in my library", "uploads"),
    h("Use the mpv audio engine", "listening", "mpv"), h("Bit-perfect", "usb_audio", "dac exclusive"), h("USB DAC", "usb_audio"),
    h("Allow EQ in USB exclusive mode", "usb_audio"), h("Listening statistics", "stats", "plays"), h("ListenBrainz", "listenbrainz", "scrobble"),
    h("Equalizer", "eq", "parametric autoeq"), h("Even out loudness", "listening", "replaygain normalize"),
    h("Earphone correction profile", "eq", "autoeq headphone"), h("Restore last session", "listening", "queue resume"),
    h("Taskbar player buttons", "listening"),
    h("Keep running in the background", "devices", "battery"), h("Permissions", "permissions"), h("Connection", "conn", "lan"),
    h("Network play", "netplay"),
    h("Cache limit", "storage", "size"), h("Cache manager", "cache"), h("Clear cache", "storage"), h("Shrink covers", "storage"),
    h("Save backup", "storage", "export"), h("Restore from file", "storage", "backup import"),
    h("Change log", "changelog", "version updates release notes"),
)
