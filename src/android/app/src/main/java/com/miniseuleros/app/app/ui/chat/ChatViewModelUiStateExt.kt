package com.miniseuleros.app.ui.chat

// [T-android-split-chat] Small UI-state toggle methods extracted from
// ChatViewModel as extension functions (verbatim): tool-detail sheet,
// browser sheet, memory sheet, attachment list. The 4 backing state fields
// were flipped private->internal. No logic change.

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import com.miniseuleros.app.agent.Level
import com.miniseuleros.app.agent.ToolLoopDetector
import com.miniseuleros.app.browser.BrowserActionInput
import com.miniseuleros.app.browser.BrowserTabPool
import com.miniseuleros.app.data.db.MessageEntity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import com.miniseuleros.app.data.BPETokenizer
import com.miniseuleros.app.data.ContextOffload
import com.miniseuleros.app.data.ContextPolicy
import com.miniseuleros.app.logging.AppLogger
import com.miniseuleros.app.data.FileMentionIndex
import com.miniseuleros.app.data.db.CompactMarkerEntity
import com.miniseuleros.app.data.model.AgentContentPart
import com.miniseuleros.app.data.model.AgentToolDefinition
import com.miniseuleros.app.data.model.LLMMessage
import com.miniseuleros.app.data.model.LLMModel
import com.miniseuleros.app.data.model.LLMStreamChunk
import com.miniseuleros.app.data.model.LLMUsage
import com.miniseuleros.app.data.model.ModelGroup
import com.miniseuleros.app.data.model.ThinkingLevel
import com.miniseuleros.app.R
import com.miniseuleros.app.data.repository.ChatRepository
import com.miniseuleros.app.data.repository.MemoryRepository
import com.miniseuleros.app.data.repository.ProviderRepository
import com.miniseuleros.app.provider.ImageBudget
import com.miniseuleros.app.provider.LLMProvider
import com.miniseuleros.app.provider.ProviderFactory
import com.miniseuleros.app.sandbox.ExecutionCoordinator
import com.miniseuleros.app.terminal.MinisOpenUrlBroker
import com.miniseuleros.app.terminal.MinisUrlMarker
import com.miniseuleros.app.tools.AgentTools
import com.miniseuleros.app.tools.FileEditTool
import com.miniseuleros.app.tools.FileReadTool
import com.miniseuleros.app.tools.FileWriteTool
import com.miniseuleros.app.tools.MemoryTools
import com.miniseuleros.app.tools.ReadImageTool
import com.miniseuleros.app.tools.ToolExecutionResult
import com.miniseuleros.app.offload.OffloadPermissionManager
import com.miniseuleros.app.service.SessionActivityTracker
import com.miniseuleros.app.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.ByteArrayOutputStream

internal fun ChatViewModel.openToolDetail(toolBlockId: String) {
    _selectedToolDetailId.value = toolBlockId
}

internal fun ChatViewModel.closeToolDetail() {
    _selectedToolDetailId.value = null
}

internal fun ChatViewModel.toggleBrowserSheet() {
    val opening = !_showBrowserSheet.value
    if (opening) browserTabPool.ensureTabForUI()
    _showBrowserSheet.value = opening
}

internal fun ChatViewModel.dismissBrowserSheet() {
    _showBrowserSheet.value = false
}

/**
 * Open the session browser sheet, focused on the tab whose URL matches
 * [url]. If no pool tab currently has that URL, a new tab is created and
 * loaded. Used by the tool-call preview's globe button so the agent's
 * existing browser_use page is reused when available instead of spawning
 * a duplicate tab.
 */
internal fun ChatViewModel.openBrowserSheetForUrl(url: String) {
    if (url.isBlank()) {
        browserTabPool.ensureTabForUI()
    } else {
        browserTabPool.selectOrCreateTabForURL(url)
    }
    _showBrowserSheet.value = true
}

internal fun ChatViewModel.toggleMemorySheet() {
    _showMemorySheet.value = !_showMemorySheet.value
}

internal fun ChatViewModel.dismissMemorySheet() {
    _showMemorySheet.value = false
}

internal fun ChatViewModel.addAttachment(attachment: InputAttachment) {
    _attachments.value = _attachments.value + attachment
}

internal fun ChatViewModel.removeAttachment(id: String) {
    _attachments.value = _attachments.value.filter { it.id != id }
}

internal fun ChatViewModel.clearAttachments() {
    _attachments.value = emptyList()
}
