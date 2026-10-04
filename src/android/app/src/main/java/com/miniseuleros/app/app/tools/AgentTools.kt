package com.miniseuleros.app.tools

import com.miniseuleros.app.browser.BrowserAction
import com.miniseuleros.app.data.model.AgentToolDefinition
import com.miniseuleros.app.data.model.AgentToolParam
import org.json.JSONArray
import org.json.JSONObject

/**
 * Central registry of all agent tool definitions.
 * Returns provider-agnostic AgentToolDefinition list used by the agent loop.
 * Tool definitions aligned with iOS AIChatViewModel.makeAgentTools().
 */
object AgentTools {

    fun makeAgentTools(
        supportsImageInput: Boolean = true,
        // [T-android-vision-group / GH#182] When the main model can't natively
        // see images but the user has bound a Vision Group, still expose
        // read_image: ReadImageTool routes the image through a vision-capable
        // group member and returns a text description. Mirrors iOS makeAgentTools
        // visionGroupConfigured. Neither native vision nor a Vision Group → tool
        // stays absent (current behaviour).
        visionGroupConfigured: Boolean = false,
        // [T-memory-toggle-gates-injection-and-tools-android] When the
        // user has turned memory off (via /memory or
        // Settings/SessionMemorySheet), drop both memory_write and
        // memory_get from the schema entirely so the model can't even
        // attempt those calls. Mirrors the iOS gate at
        // AIChatViewModel.makeAgentTools(memoryEnabled:).
        memoryEnabled: Boolean = true,
        // [T-ubuntu-secondary-sandbox] Expose ubuntu_shell only when the
        // secondary Ubuntu 24.04 rootfs is installed (Settings → Ubuntu 沙箱).
        // Keeps the schema clean for users who never use the feature.
        ubuntuShellEnabled: Boolean = false,
        // [T-android-subagent-orchestration] Expose dispatch_subtasks only
        // when a sub-agent model group can be resolved (Settings → 模型组 →
        // 子代理, or any group the planner may name per-task). No groups →
        // the tool would only ever error, so it stays out of the schema.
        subAgentsEnabled: Boolean = false,
    ): List<AgentToolDefinition> = buildList {
        add(shellExecuteDefinition())
        if (ubuntuShellEnabled) {
            add(ubuntuShellDefinition())
        }
        add(FileReadTool.definition())
        add(FileWriteTool.definition())
        add(FileEditTool.definition())
        if (supportsImageInput || visionGroupConfigured) {
            add(ReadImageTool.definition())
        }
        add(browserUseDefinition())
        if (subAgentsEnabled) {
            add(dispatchSubtasksDefinition())
        }
        if (memoryEnabled) {
            add(memoryWriteDefinition())
            add(memoryGetDefinition())
        }
    }

    // [T-android-subagent-orchestration] Multi-model orchestration entry.
    // The main model plans, decomposes, and dispatches self-contained
    // subtask briefs with EXPLICIT permission scopes; the harness rejects
    // every out-of-scope request. Sub-agents return structured facts packs;
    // only the pack re-enters this context (SoL-Pi result cards / MAGE
    // budget packing), never the sub-agent transcript.
    private fun dispatchSubtasksDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "dispatch_subtasks",
        description = "Multi-model orchestration: dispatch a plan of subtasks to sub-agent models " +
            "(chosen from the user's configured model groups, Settings → 模型组). " +
            "Each subtask MUST be a SELF-CONTAINED brief — sub-agents cannot see this conversation. " +
            "Declare an explicit permission scope per subtask (read_paths / write_paths globs over " +
            "linux paths + allowed tools); requests beyond the scope are REJECTED by the harness. " +
            "Write-scoped subtasks whose declared write paths collide are scheduled in waves " +
            "(file-write lease); independent ones run concurrently. Results come back as a " +
            "structured facts pack (STATUS/SUMMARY/FACTS/FILES/ISSUES per subtask) — only the " +
            "pack enters your context, never the sub-agent transcripts. The bound sub-agent " +
            "group is a POOL: subtasks are spread across the group's available member models " +
            "by ordinal (extra members are fallback), so put several models in one group for " +
            "variety; pass group_id per subtask to route it to a specific group. PERSISTENCE " +
            "RULE [T-android-subagent-handoff]: require every subtask to persist its work " +
            "products as files inside its declared write scope — a result that exists only in " +
            "conversation is lost to later turns and compaction, wasting all compute spent. " +
            "The system also appends a fixed-format handoff ledger at " +
            "/var/minis-euleros/workspace/.collab/HANDOFF.md after EVERY dispatch (append-only, " +
            "survives compaction); read it and cite real artifact paths before planning " +
            "follow-up work. Workflow: " +
            "plan → dispatch → verify packs → re-dispatch failed subtasks with narrowed scope → integrate and submit.",
        parameters = mapOf(
            "plan" to AgentToolParam("string", "The complete plan summary (1-4 sentences): what the overall goal is and how the subtasks cover it. Shown to the user."),
            // [T-android-dispatch-args-schema] subtasks MUST carry an items
            // schema: an items-less array made models/gateways guess the shape
            // and some serialized it as a JSON string → optJSONArray() null →
            // misleading required-params error (five identical failures with
            // valid payloads). Nested string-array fields get their own items.
            "subtasks" to AgentToolParam("array", "2-8 subtask objects. MUST be a real JSON array of objects, never a string. Each: {id, title, instructions, read_paths[], write_paths[], tools[], group_id?, timeout_sec?, max_turns?}. instructions must be self-contained (goal, steps, output requirements); read_paths/write_paths are linux path globs (* segment, ** recursive, bare dir covers subtree) — empty write_paths forbids ALL file modification; tools ⊆ {file_read, file_write, file_edit, shell_execute}. DEFAULT (tools 留空): 当 read_paths 或 write_paths 非空时自动授予 {file_read, file_write, file_edit, shell_execute}（shell 用于 gradle/git/grep 等，PRoot 已限制在沙箱内）；仅当 read/write 全空才是纯推理任务（无工具）。显式 tools 会完全覆盖默认集。group_id optionally names a specific model group; timeout_sec ≤ 900; max_turns ≤ 24 (default 10; raise for long build/scan tasks). 重要：read_paths/write_paths 必须是绝对路径（以 /var/minis-euleros/ 开头），相对前缀会被路径守卫拒绝。",
                items = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("id", AgentToolParam("string", "Unique task id, e.g. t1.").toJson())
                        put("title", AgentToolParam("string", "Short task title.").toJson())
                        put("instructions", AgentToolParam("string", "SELF-CONTAINED brief: goal, concrete steps, file paths, exactly what to return. Sub-agents see nothing of this conversation.").toJson())
                        put("read_paths", AgentToolParam("array", "Linux path globs this subtask may read. Empty forbids ALL reads.", items = JSONObject().apply { put("type", "string") }).toJson())
                        put("write_paths", AgentToolParam("array", "Linux path globs this subtask may write. Empty forbids ALL writes.", items = JSONObject().apply { put("type", "string") }).toJson())
                        put("tools", AgentToolParam("array", "Allowed tools ⊆ {file_read, file_write, file_edit, shell_execute}.", items = JSONObject().apply { put("type", "string") }).toJson())
                        put("group_id", AgentToolParam("string", "Optional model group id to route this subtask to.").toJson())
                        put("timeout_sec", AgentToolParam("integer", "Per-subtask timeout, 30-900, default 300.").toJson())
                        put("max_turns", AgentToolParam("integer", "Per-subtask tool turns, 1-24, default 10; raise for long build/scan tasks.").toJson())
                    })
                    put("required", JSONArray(listOf("instructions")))
                }),
            "mode" to AgentToolParam("string", "waves (default: write-colliding subtasks serialized, independent ones parallel) | sequential | parallel."),
        ),
        required = listOf("plan", "subtasks"),
        propertyOrdering = listOf("plan", "subtasks", "mode"),
    )

    // Aligned with iOS AIChatViewModel.swift:4982-4993
    private fun shellExecuteDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "shell_execute",
        description = "Execute a command in an isolated Linux process (EulerOS Linux via PRoot). " +
            "The command runs via /bin/sh -c with stdout and stderr merged. " +
            "Each invocation spawns a fresh process — there is no shared terminal session. " +
            "Default timeout is 15 minutes.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Install Python data analysis packages', 'List files in home directory'). Use the same language as the user."),
            "command" to AgentToolParam("string", "The shell command to execute. Supports multi-line commands directly — no special escaping needed. Keep under 1000 chars; for longer scripts, write to a file with file_write first, then run it."),
            "timeout" to AgentToolParam("integer", "Timeout in seconds (default: 900). Use a larger value for long-running commands like package installs."),
            "delay" to AgentToolParam("integer", "Delay in seconds before execution begins. The tool blocks the agent flow during this wait WITHOUT occupying the shell, so other concurrent tasks can use it. Use this instead of sleep commands to avoid resource contention."),
        ),
        required = listOf("tool_title", "command"),
        propertyOrdering = listOf("tool_title", "command", "timeout", "delay"),
    )

    // Secondary Ubuntu 24.04 arm64 sandbox (apt ecosystem, gcc toolchain).
    // Shares the host workspace folder with the main sandbox: files written
    // via file_write under /var/minis-euleros/workspace appear at /workspace.
    private fun ubuntuShellDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "ubuntu_shell",
        description = "Run a command inside the secondary Ubuntu 24.04 (arm64) sandbox with the full " +
            "Ubuntu apt ecosystem (gcc, make, python3, etc.) under PRoot — the main EulerOS sandbox is NOT modified. " +
            "Use for tasks that need `apt install`, Ubuntu-native toolchains, or libraries unavailable in the main sandbox. " +
            "The host workspace folder is mounted at /workspace (the same directory the main sandbox sees as " +
            "/var/minis-euleros/workspace): write scripts with file_write first, then run them here. " +
            "Each invocation spawns a fresh process (bash -lc); no shared state between calls except the filesystem.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "command" to AgentToolParam("string", "Bash command to execute, e.g. \"apt-get update && apt-get install -y build-essential && gcc hello.c -o hello\". For long scripts, write to /workspace via file_write and run them here."),
            "cwd" to AgentToolParam("string", "Working directory inside the sandbox (default: /workspace)."),
            "timeout" to AgentToolParam("integer", "Timeout in seconds (default: 900, max: 1800)."),
        ),
        required = listOf("tool_title", "command"),
        propertyOrdering = listOf("tool_title", "command", "cwd", "timeout"),
    )

    // Aligned with iOS AIChatViewModel.swift browser_use definition
    private fun browserUseDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "browser_use",
        description = "Control a web browser with up to 3 tabs. " +
            "Do NOT use this tool for miniseuleros:// action URLs (open_terminal, views, settings) — those are app deep links, use Markdown links in chat instead. " +
            "The browser supports both web URLs and miniseuleros:// resource URLs. Use miniseuleros:// URLs to preview session files (e.g. navigate to miniseuleros://workspace/index.html). " +
            "Sub-resources (JS, CSS, images, fonts) referenced via miniseuleros:// absolute paths or relative paths within HTML pages resolve correctly. " +
            "Use navigate to open URLs, screenshot to see the page (returns an image), " +
            "click/type to interact with elements, get_text/get_readable to extract content, " +
            "scroll to navigate long pages, scroll_and_collect to scroll through infinite-scroll/virtual-rendered pages (like Twitter/X timelines) and accumulate unique content items across scroll positions in a single call, " +
            "find_elements to discover interactive elements, " +
            "get_page_info for page metadata, get_backbone to get a structural overview of the page DOM as a simplified tree, " +
            "fetch to download files/resources using the page's session (returns metadata and a miniseuleros:// URL), " +
            "new_tab to open an additional tab, close_tab to close a tab, and list_tabs to see all open tabs. " +
            "Use set_viewport with viewport_width + viewport_height to override the viewport for the current session (e.g. before screenshotting a 1920×1080 HTML composition that would otherwise be cropped to the phone viewport); pass reset=true to drop the session override and fall back to the global browser setting. " +
            "Use get_cookies to retrieve cookies for the current page URL / current site root domain only (including HttpOnly cookies). get_cookies supports optional 'keywords' (filter by cookie name) and 'fuzzy' (true=contains match, false=exact match, default true). It returns only a summary and an offload env file path — raw cookie values are NOT included in the tool response. To reuse cookies in shell commands: `. /var/minis-euleros/offloads/env_cookies_xxx.sh && command`. You may define alias variables when needed. " +
            "Use set_cookies to write cookies into the current page's cookie store via the native cookie store (so even HttpOnly cookies, which JS cannot set, land). Pass a 'cookies' array of objects, each with name + value (required) and optional domain (defaults to the current page host), path (defaults to '/'), secure, http_only, and expires (Unix timestamp in seconds; omit for a session cookie). " +
            "Use wait_for_dom_stable to wait until the page DOM stops changing (useful after navigation or interactions that trigger async data loading — polls every 0.5s, resolves when mutation rate gradient is stable for 3+ intervals, default timeout 10s). " +
            "Use tab_id to target a specific tab (defaults to the most recently used tab).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Open Wikipedia homepage', 'Take screenshot of current page'). Use the same language as the user."),
            "action" to AgentToolParam("string", "The browser action to perform",
                enumValues = BrowserAction.allValues),
            "url" to AgentToolParam("string", "URL to navigate to (for navigate action) or resource to download (for fetch action)"),
            "selector" to AgentToolParam("string", "CSS selector for targeting elements (click, type, get_text, scroll, hover, find_elements). For scroll: specify a scrollable container to scroll (e.g. 'div.timeline'); if omitted, auto-detects the best scrollable element."),
            "text" to AgentToolParam("string", "Text to type (for type action)"),
            "coordinate_x" to AgentToolParam("integer", "X coordinate for click (alternative to selector)"),
            "coordinate_y" to AgentToolParam("integer", "Y coordinate for click (alternative to selector)"),
            "direction" to AgentToolParam("string", "Scroll direction", enumValues = listOf("up", "down")),
            "amount" to AgentToolParam("integer", "Scroll amount in pixels (default: 500)"),
            "script" to AgentToolParam("string", "JavaScript code to execute (for execute_js action). The script runs inside an async function wrapper — `await` and top-level `return` are both supported (e.g. `var r = await fetch(url); return await r.json()`)."),
            "user_agent" to AgentToolParam("string", "User agent profile to switch to", enumValues = listOf("desktop_chrome", "mobile_chrome")),
            "max_depth" to AgentToolParam("integer", "Maximum tree depth for get_backbone (default: 5)"),
            "scroll_count" to AgentToolParam("integer", "Number of scroll steps for scroll_and_collect (default: 10, max: 20). Each step scrolls by 'amount' pixels and waits for new content."),
            "item_selector" to AgentToolParam("string", "CSS selector for individual content items in scroll_and_collect (e.g. 'article', '[data-testid=\"tweet\"]'). If omitted, auto-detects repeated elements."),
            "tab_id" to AgentToolParam("integer", "Target tab ID (optional, defaults to most recently used tab). Use list_tabs to see available tabs."),
            "keywords" to AgentToolParam("string", "Filter cookies by name (for get_cookies). A space-separated string or array of strings. With fuzzy=true (default), ALL keywords must appear in the cookie name (case-insensitive). With fuzzy=false, cookie name must exactly equal any one of the provided keywords (case-insensitive). Omit to return all cookies for the current site."),
            "fuzzy" to AgentToolParam("boolean", "Whether keyword matching is fuzzy (contains-all) or exact-any (for get_cookies, default: true)."),
            "cookies" to AgentToolParam("string", "For set_cookies: a JSON array of cookie objects to write. Pass it as a JSON array (a JSON-encoded string of the array is also accepted). Each object: {\"name\": str (required), \"value\": str (required), \"domain\": str (optional, defaults to current page host), \"path\": str (optional, defaults to \"/\"), \"secure\": bool (optional), \"http_only\": bool (optional — sets an HttpOnly cookie that JS cannot read/set), \"expires\": int (optional, Unix timestamp in seconds; omit for a session cookie)}. Field-name variants from common cookie exports are accepted: httpOnly (=http_only), expirationDate (=expires), sameSite, and case/camel variants — so you can paste cookies verbatim from browser extensions (EditThisCookie / Cookie-Editor) or Playwright/Puppeteer storage."),
            "timeout" to AgentToolParam("integer", "Timeout in seconds for wait_for_dom_stable (default: 10). The action polls every 0.5s and resolves when DOM mutation rate stabilizes."),
            "viewport_width" to AgentToolParam("integer", "Viewport width in CSS pixels for set_viewport (e.g. 1920). Required together with viewport_height unless reset=true."),
            "viewport_height" to AgentToolParam("integer", "Viewport height in CSS pixels for set_viewport (e.g. 1080). Required together with viewport_width unless reset=true."),
            "reset" to AgentToolParam("boolean", "For set_viewport: when true, clear the session-level viewport override and fall back to the global browser setting."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "tab_id", "url", "selector", "text", "coordinate_x", "coordinate_y", "direction", "amount", "scroll_count", "item_selector", "script", "user_agent", "max_depth", "keywords", "fuzzy", "cookies", "timeout", "viewport_width", "viewport_height", "reset"),
    )

    // Aligned with iOS AIChatViewModel.swift:5059-5067
    private fun memoryWriteDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "memory_write",
        description = "Write a memory entry to today's daily log (YYYY-MM-DD.md). Memories persist across all sessions. " +
            "Each entry is prepended with a timestamp. " +
            "Save: user preferences, recurring patterns, key facts, project conventions, reusable knowledge. " +
            "Avoid saving passwords, API keys, tokens, or secrets unless the user explicitly confirms after being warned. " +
            "Keep entries concise and general-purpose. GLOBAL.md is read-only (user-maintained via Settings).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Save user preference for Python', 'Note today's project context'). Use the same language as the user."),
            "content" to AgentToolParam("string", "The memory content to write. Use concise Markdown with a short heading (## Topic) and context about what was done/learned."),
        ),
        required = listOf("tool_title", "content"),
        propertyOrdering = listOf("tool_title", "content"),
    )

    // Aligned with iOS AIChatViewModel.swift:5069-5078
    private fun memoryGetDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "memory_get",
        description = "Retrieve memories from persistent storage. Supports keyword-based fuzzy search across memory files. " +
            "Returns matching lines with surrounding context. Use this to recall previous knowledge, user preferences, or past notes.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Recall user preferences', 'Search past notes'). Use the same language as the user."),
            "scope" to AgentToolParam("string", "Memory scope to search: 'daily' for daily logs only, 'all' for daily logs + GLOBAL.md.", enumValues = listOf("daily", "all")),
            "keywords" to AgentToolParam("string", "Space-separated keywords for fuzzy matching (e.g. 'python preference' or 'API key setup'). All keywords must appear in a line or its surrounding context for a match. Leave empty to return full memory files."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "scope", "keywords"),
    )
}
