package com.sanden.ui.connect

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sanden.data.db.SyncChannelEntity
import com.sanden.data.db.SyncMessageDao
import com.sanden.data.db.SyncMessageEntity
import com.sanden.data.transfer.LanTransferHub
import com.sanden.domain.model.VaultFile
import com.sanden.domain.repository.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConnectViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hub: LanTransferHub,
    private val syncMessageDao: SyncMessageDao,
    private val vaultRepository: VaultRepository
) : ViewModel() {

    val isRunning: StateFlow<Boolean> = hub.isRunning
    val serverUrl: StateFlow<String> = hub.serverUrl
    val connectedClients: StateFlow<Int> = hub.connectedClients
    val activeTransfers: StateFlow<List<LanTransferHub.ActiveTransfer>> = hub.activeTransfers
    val discoveredPeers: StateFlow<List<LanTransferHub.PeerDevice>> = hub.discoveredPeers
    val selectedTargetId: StateFlow<String?> = hub.selectedTargetId
    val localDeviceName: String get() = hub.localDeviceName

    val currentChannel: StateFlow<String> = hub.activeChannel

    val channels: StateFlow<List<String>> = syncMessageDao.observeChannels()
        .map { list ->
            val names = list.map { it.name }.toMutableList()
            if (!names.contains("General")) {
                names.add(0, "General")
            }
            names
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = listOf("General")
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<SyncMessageEntity>> = combine(currentChannel, selectedTargetId) { ch, target ->
        ch to (target ?: "")
    }.flatMapLatest { (channel, targetPeerId) ->
        syncMessageDao.observeByChannelAndTarget(channel, targetPeerId)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        viewModelScope.launch {
            syncMessageDao.insertChannel(SyncChannelEntity(name = "General", createdAt = 0L))
        }
        if (!hub.isRunning.value) {
            hub.start()
        }
    }

    fun selectTargetPeer(peerId: String?) {
        hub.selectTargetPeer(peerId)
    }

    fun addManualPeer(ipOrUrl: String, onResult: (Result<LanTransferHub.PeerDevice>) -> Unit) {
        if (ipOrUrl.isBlank()) return
        viewModelScope.launch {
            val res = hub.addManualPeer(ipOrUrl)
            onResult(res)
        }
    }

    fun selectChannel(name: String) {
        hub.selectChannel(name)
    }

    fun createChannel(name: String) {
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return
        viewModelScope.launch {
            syncMessageDao.insertChannel(SyncChannelEntity(name = clean))
            hub.selectChannel(clean)
        }
    }

    fun toggleHub() {
        if (hub.isRunning.value) {
            hub.stop()
        } else {
            hub.start()
        }
    }

    fun syncMeshNow() {
        viewModelScope.launch {
            hub.syncAllPeersNow()
            vaultRepository.scanVault()
        }
    }

    fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val ch = currentChannel.value
        viewModelScope.launch {
            hub.sendPhoneMessage(trimmed, type = "TEXT", channel = ch)
        }
    }

    fun sendClipboard(): Boolean {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return false

        // Support copied images / files from clipboard first
        val uris = mutableListOf<Uri>()
        for (i in 0 until clip.itemCount) {
            clip.getItemAt(i).uri?.let { uris.add(it) }
        }
        if (uris.isNotEmpty()) {
            sendFiles(uris)
            return true
        }

        val clipText = clip.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
        if (clipText.isNullOrEmpty()) return false
        val ch = currentChannel.value
        viewModelScope.launch {
            hub.sendPhoneMessage(clipText, type = "CLIPBOARD", channel = ch)
        }
        return true
    }

    fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("SanDen", text))
    }

    fun sendFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val ch = currentChannel.value
        viewModelScope.launch {
            for (uri in uris) {
                val txId = "tx_${System.currentTimeMillis()}_${uri.hashCode()}"
                val displayName = resolveDisplayName(uri)
                hub.updateTransferProgress(
                    LanTransferHub.ActiveTransfer(
                        id = txId,
                        channel = ch,
                        fileName = displayName,
                        direction = "SENDING",
                        bytesTransferred = 0L,
                        totalBytes = -1L
                    )
                )
                try {
                    val res = vaultRepository.importFile(uri, "")
                    res.onSuccess { vaultFile ->
                        hub.updateTransferProgress(
                            LanTransferHub.ActiveTransfer(
                                id = txId,
                                channel = ch,
                                fileName = vaultFile.name,
                                direction = "SENDING",
                                bytesTransferred = vaultFile.size,
                                totalBytes = vaultFile.size
                            )
                        )
                        hub.sendPhoneFile(
                            filePath = vaultFile.path,
                            fileName = vaultFile.name,
                                fileSize = vaultFile.size,
                            mimeType = vaultFile.mimeType ?: "application/octet-stream",
                            channel = ch
                        )
                    }
                } finally {
                    hub.finishTransfer(txId)
                }
            }
            vaultRepository.scanVault()
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        return try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            } ?: uri.lastPathSegment ?: "File"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "File"
        }
    }

    fun clearHistory() {
        val ch = currentChannel.value
        val target = selectedTargetId.value ?: ""
        viewModelScope.launch {
            syncMessageDao.clearChannelAndTarget(ch, target)
        }
    }

    fun openFileInVault(path: String, onFound: (VaultFile) -> Unit, onNotFound: () -> Unit) {
        viewModelScope.launch {
            val file = vaultRepository.getFileByPath(path)
            if (file != null) {
                onFound(file)
            } else {
                onNotFound()
            }
        }
    }
}
