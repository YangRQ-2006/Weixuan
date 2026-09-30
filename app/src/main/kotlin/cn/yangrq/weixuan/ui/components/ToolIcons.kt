package cn.yangrq.weixuan.ui.components

import cn.yangrq.weixuan.ui.design.XuanGlyphType

/**
 * 微玄：工具 → 爻线图形映射（原 Material 图标表）。
 *
 * 上游用 material-icons-extended 的 40 枚 Google 图标表达工具语义；
 * 微玄改用自绘「爻线」图形集 [XuanGlyphType]，保持功能可辨识的同时统一笔触。
 */
internal fun iconForTool(toolId: String): XuanGlyphType = when (toolId) {
    "observe", "observe_screen" -> XuanGlyphType.Monitor
    "click", "tap", "tap_element" -> XuanGlyphType.Tap
    "tap_area" -> XuanGlyphType.Location
    "long_press", "long_press_element" -> XuanGlyphType.Tap
    "swipe" -> XuanGlyphType.Move
    "scroll", "scroll_element" -> XuanGlyphType.Swap
    "clipboard", "paste_text", "get_clipboard", "set_clipboard",
    "search_clipboard_history" -> XuanGlyphType.Clipboard
    "input_text" -> XuanGlyphType.Keyboard
    "replace_text", "search_apps", "file_search", "file_search_call", "文件搜索" ->
        XuanGlyphType.Search
    "clear_text" -> XuanGlyphType.Close
    "wait", "wait_text", "wait_for_text", "wait_for_package",
    "set_alarm", "set_timer", "list_alarms", "list_active_timers",
    "recent_app_activity", "app_usage_summary", "search_calendar_events" ->
        XuanGlyphType.History
    "get_current_context", "device_status", "set_device_state", "get_device_environment" ->
        XuanGlyphType.Device
    "open_app", "launch_app" -> XuanGlyphType.Play
    "open_uri" -> XuanGlyphType.Link
    "browser_use", "网页浏览" -> XuanGlyphType.Browser
    "web_search", "web_search_call", "网页搜索" -> XuanGlyphType.Globe
    "browser_read" -> XuanGlyphType.Memory
    "browser_interact" -> XuanGlyphType.Tap
    "browser_screenshot", "computer", "computer_call", "计算机操作", "open_system_panel" ->
        XuanGlyphType.Monitor
    "code_interpreter", "code_interpreter_call", "代码执行",
    "terminal", "terminal_job", "run_command" -> XuanGlyphType.Terminal
    "image_generation", "image_generation_call", "图像生成",
    "read_image", "search_media", "search_qq_chat_images", "search_wechat_chat_images" ->
        XuanGlyphType.Image
    "mcp_call", "MCP 工具" -> XuanGlyphType.Mcp
    "memory_get", "memory_write", "search_coloros_memories" -> XuanGlyphType.Memory
    "press_key" -> XuanGlyphType.Command
    "skills_list", "skills_read", "skills_read_resource", "skills_list_curated",
    "skills_inspect_github", "skills_install_from_github",
    "top_memory_apps", "top_storage_apps" -> XuanGlyphType.Skills
    "network_info", "wifi_credentials" -> XuanGlyphType.Wifi
    "media_control", "set_volume", "search_audio" ->
        if (toolId == "search_audio") XuanGlyphType.Music else XuanGlyphType.Play
    "read_sms_code", "app_state_control" -> XuanGlyphType.Permission
    "recent_notifications", "search_notification_history" -> XuanGlyphType.Bell
    "get_setting", "set_setting" -> XuanGlyphType.Settings
    "get_logcat", "read_file", "write_file", "search_messages",
    "search_coloros_notes" -> XuanGlyphType.Note
    "get_current_location", "search_saved_places" -> XuanGlyphType.Location
    "get_health_summary" -> XuanGlyphType.Pulse
    "search_contacts", "search_call_history" -> XuanGlyphType.Contact
    "search_recordings", "search_coloros_recordings", "search_recording_summaries" ->
        XuanGlyphType.Mic
    "search_files", "list_directory" -> XuanGlyphType.Folder
    "search_downloads" -> XuanGlyphType.Download
    "search_personal_orders" -> XuanGlyphType.Bag
    else -> if (toolId.startsWith("mcp_")) XuanGlyphType.Mcp else XuanGlyphType.Tools
}
