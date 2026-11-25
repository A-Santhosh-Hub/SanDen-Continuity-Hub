package com.sanden.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sanden.data.db.SyncMessageDao
import com.sanden.data.db.SyncMessageEntity
import com.sanden.data.transfer.LanTransferHub
import com.sanden.domain.model.FileCategory
import com.sanden.domain.model.StorageMode
import com.sanden.domain.model.VaultFile
import com.sanden.domain.repository.VaultRepository
import com.sanden.domain.usecase.GetCategoryStatsUseCase
import com.sanden.domain.usecase.ImportFilesUseCase
import com.sanden.domain.usecase.ScanVaultUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class RecentActivityItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val timestampMs: Long,
    val vaultFile: VaultFile? = null,
    val isFromSync: Boolean = false
)

data class DeviceStorageSummary(
    val totalDeviceBytes: Long = 0L,
    val availableDeviceBytes: Long = 0L
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val getCategoryStatsUseCase: GetCategoryStatsUseCase,
    private val scanVaultUseCase: ScanVaultUseCase,
    private val importFilesUseCase: ImportFilesUseCase,
    private val vaultRepository: VaultRepository,
    private val lanTransferHub: LanTransferHub,
    private val syncMessageDao: SyncMessageDao
) : ViewModel() {

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _deviceStorage = MutableStateFlow(queryDeviceStorage())
    val deviceStorage: StateFlow<DeviceStorageSummary> = _deviceStorage.asStateFlow()

    val isHubRunning: StateFlow<Boolean> = lanTransferHub.isRunning
    val serverUrl: StateFlow<String> = lanTransferHub.serverUrl
    val connectedClients: StateFlow<Int> = lanTransferHub.connectedClients
    val discoveredPeers: StateFlow<List<LanTransferHub.PeerDevice>> = lanTransferHub.discoveredPeers
    val localDeviceName: String get() = lanTransferHub.localDeviceName

    val categoryStats: StateFlow<Map<FileCategory, Pair<Int, Long>>> =
        getCategoryStatsUseCase()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = FileCategory.entries.associateWith { 0 to 0L }
            )

    val recentActivity: StateFlow<List<RecentActivityItem>> = combine(
        syncMessageDao.observeAll(),
        vaultRepository.getRecentFiles(5)
    ) { syncMessages, vaultFiles ->
        val syncItems = syncMessages
            .sortedByDescending { it.timestamp }
            .take(5)
            .map { msg -> msg.toActivityItem() }

        val vaultItems = vaultFiles.map { vf ->
            RecentActivityItem(
                id = "vault_${vf.id}",
                title = vf.name,
                subtitle = "Vault • ${com.sanden.ui.components.formatFileSize(vf.size)}",
                timestampMs = vf.lastModified,
                vaultFile = vf,
                isFromSync = false
            )
        }

        (syncItems + vaultItems)
            .sortedByDescending { it.timestampMs }
            .distinctBy { it.title }
            .take(5)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val vaultPath: String
        get() = vaultRepository.getVaultRootPath()

    val storageMode: StorageMode
        get() = vaultRepository.getStorageMode()

    init {
        rescan()
    }

    fun rescan() {
        viewModelScope.launch {
            _isScanning.value = true
            _error.value = null
            try {
                scanVaultUseCase()
                _deviceStorage.value = withContext(Dispatchers.IO) { queryDeviceStorage() }
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Rescan failed", e)
                _error.value = e.message
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun toggleHub() {
        if (lanTransferHub.isRunning.value) {
            lanTransferHub.stop()
        } else {
            lanTransferHub.start()
        }
    }

    fun sendTextQuick(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            if (!lanTransferHub.isRunning.value) {
                lanTransferHub.start()
            }
            lanTransferHub.sendPhoneMessage(trimmed, type = "TEXT", channel = "General")
        }
    }

    fun sendClipboardQuick(): Boolean {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
        if (clipText.isNullOrEmpty()) return false
        viewModelScope.launch {
            if (!lanTransferHub.isRunning.value) {
                lanTransferHub.start()
            }
            lanTransferHub.sendPhoneMessage(clipText, type = "CLIPBOARD", channel = "General")
        }
        return true
    }

    fun copyText(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun addManualPeer(ipOrUrl: String, onResult: (Result<LanTransferHub.PeerDevice>) -> Unit) {
        if (ipOrUrl.isBlank()) return
        viewModelScope.launch {
            if (!lanTransferHub.isRunning.value) {
                lanTransferHub.start()
            }
            val result = lanTransferHub.addManualPeer(ipOrUrl)
            onResult(result)
        }
    }

    fun importFiles(uris: List<Uri>) {
        viewModelScope.launch {
            _isScanning.value = true
            _error.value = null
            try {
                importFilesUseCase(uris, "").collect { op ->
                    if (op.error != null) {
                        _error.value = op.error
                    }
                }
                scanVaultUseCase()
                _deviceStorage.value = withContext(Dispatchers.IO) { queryDeviceStorage() }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun sendFilesToPcAndVault(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isScanning.value = true
            _error.value = null
            try {
                if (!lanTransferHub.isRunning.value) {
                    lanTransferHub.start()
                }
                for (uri in uris) {
                    vaultRepository.importFile(uri, "").onSuccess { vf ->
                        val f = File(vf.path)
                        if (f.exists()) {
                            lanTransferHub.sendPhoneFile(
                                filePath = vf.path,
                                fileName = vf.name,
                                fileSize = vf.size,
                                mimeType = vf.mimeType ?: "application/octet-stream",
                                channel = "General"
                            )
                        }
                    }.onFailure {
                        _error.value = it.message ?: "Failed to import ${resolveDisplayName(uri)}"
                    }
                }
                scanVaultUseCase()
                _deviceStorage.value = withContext(Dispatchers.IO) { queryDeviceStorage() }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isScanning.value = false
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "file"
    }

    private fun queryDeviceStorage(): DeviceStorageSummary {
        return try {
            val target = Environment.getExternalStorageDirectory()
            val stat = StatFs(target.absolutePath)
            DeviceStorageSummary(
                totalDeviceBytes = stat.totalBytes,
                availableDeviceBytes = stat.availableBytes
            )
        } catch (_: Exception) {
            DeviceStorageSummary()
        }
    }

    private fun SyncMessageEntity.toActivityItem(): RecentActivityItem {
        val directionLabel = if (sender == "LAPTOP") "Received from PC" else "Sent from Phone"
        return when (type) {
            "FILE" -> RecentActivityItem(
                id = "sync_$id",
                title = fileName.ifBlank { "Shared File" },
                subtitle = "$directionLabel • ${com.sanden.ui.components.formatFileSize(fileSize)}",
                timestampMs = timestamp,
                isFromSync = true
            )
            "CLIPBOARD" -> RecentActivityItem(
                id = "sync_$id",
                title = textContent.take(40).ifBlank { "Clipboard Sync" },
                subtitle = "$directionLabel • Clipboard",
                timestampMs = timestamp,
                isFromSync = true
            )
            else -> RecentActivityItem(
                id = "sync_$id",
                title = textContent.take(40).ifBlank { "Message" },
                subtitle = "$directionLabel • Text",
                timestampMs = timestamp,
                isFromSync = true
            )
        }
    }
}
