package com.sanden.data.transfer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Base64
import android.util.Log
import com.sanden.data.db.SyncChannelEntity
import com.sanden.data.db.SyncMessageDao
import com.sanden.data.db.SyncMessageEntity
import com.sanden.data.db.VaultFileDao
import com.sanden.data.scanner.CategoryDetector
import com.sanden.data.storage.SafeFileOperations
import com.sanden.domain.repository.VaultRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Embedded LAN HTTP + WebSocket Continuity Hub inspired by LocalSend and KDE Connect.
 *
 * Serves the Liquid Glass Web Companion UI on port 53317, streams multi-GB uploads
 * directly into the active SanDen Vault, streams vault downloads to the laptop,
 * and pushes real-time chat & clipboard updates over WebSocket.
 */
@Singleton
class LanTransferHub @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncMessageDao: SyncMessageDao,
    private val vaultFileDao: VaultFileDao,
    private val vaultRepository: VaultRepository
) {

    companion object {
        private const val TAG = "LanTransferHub"
        const val DEFAULT_PORT = 54321
        const val DISCOVERY_UDP_PORT = 54322
        private const val SERVICE_TYPE = "_sanden._tcp."
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val BUFFER_SIZE = 65536
        private const val PEER_STALE_MS = 12_000L
    }

    data class ActiveTransfer(
        val id: String,
        val channel: String,
        val fileName: String,
        val direction: String, // "SENDING" or "RECEIVING"
        val bytesTransferred: Long,
        val totalBytes: Long
    ) {
        val progress: Float
            get() = if (totalBytes > 0L) (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    }

    data class PeerDevice(
        val id: String,
        val name: String,
        val ip: String,
        val port: Int,
        val url: String,
        val deviceType: String = "ANDROID", // "ANDROID" or "PC"
        val pinned: Boolean = false,
        val lastSeenMs: Long = System.currentTimeMillis()
    )

    val localDeviceName: String by lazy {
        val mfg = Build.MANUFACTURER.trim()
        val mdl = Build.MODEL.trim()
        if (mdl.startsWith(mfg, ignoreCase = true)) mdl else "$mfg $mdl".trim()
    }

    val localDeviceId: String by lazy {
        val prefs = context.getSharedPreferences("sanden_prefs", Context.MODE_PRIVATE)
        var stored = prefs.getString("install_device_id", null)
        if (stored.isNullOrBlank()) {
            val shortHex = UUID.randomUUID().toString().replace("-", "").take(8)
            stored = "android_$shortHex"
            prefs.edit().putString("install_device_id", stored).apply()
        }
        stored
    }

    private fun getPasskey(): String =
        context.getSharedPreferences("sanden_prefs", Context.MODE_PRIVATE)
            .getString("lan_passkey", "")
            ?.trim()
            .orEmpty()

    private fun isAllowedBrowserOrigin(origin: String?, hostHeader: String?): Boolean {
        if (origin.isNullOrBlank()) return true
        val parsedHost = runCatching { URL(origin.trim()).host.lowercase() }.getOrNull() ?: return false
        val expectedHost = hostHeader?.trim()?.lowercase()?.substringBefore(':').orEmpty()
        return parsedHost == "localhost" ||
            parsedHost == "127.0.0.1" ||
            (expectedHost.isNotEmpty() && parsedHost == expectedHost)
    }

    private fun isAuthorizedRequest(headers: Map<String, String>, query: String): Boolean {
        val expected = getPasskey()
        if (expected.isEmpty()) return true
        val rawQueryKey = query.split("&")
            .firstOrNull { it.startsWith("key=") }
            ?.substringAfter("key=")
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            .orEmpty()
        val provided = headers["x-sanden-key"]?.trim().orEmpty().ifEmpty { rawQueryKey.trim() }
        if (provided.isEmpty()) return false
        val md = MessageDigest.getInstance("SHA-256")
        return MessageDigest.isEqual(
            md.digest(expected.toByteArray(Charsets.UTF_8)),
            md.digest(provided.toByteArray(Charsets.UTF_8))
        )
    }

    private fun sanitizeFilenameHeader(name: String): String =
        name.replace(Regex("[\\r\\n\"\\\\/]"), "_").ifBlank { "download.bin" }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var udpBeaconJob: Job? = null
    private var udpListenerJob: Job? = null
    private var peerPruneJob: Job? = null
    private var udpSocket: DatagramSocket? = null
    private var udpListenSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val wsClients = CopyOnWriteArrayList<OutputStream>()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _connectedClients = MutableStateFlow(0)
    val connectedClients: StateFlow<Int> = _connectedClients.asStateFlow()

    private val transferMap = ConcurrentHashMap<String, ActiveTransfer>()
    private val _activeTransfers = MutableStateFlow<List<ActiveTransfer>>(emptyList())
    val activeTransfers: StateFlow<List<ActiveTransfer>> = _activeTransfers.asStateFlow()

    private val peersMap = ConcurrentHashMap<String, PeerDevice>()
    private val _discoveredPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val discoveredPeers: StateFlow<List<PeerDevice>> = _discoveredPeers.asStateFlow()

    // null means "All Devices (Group Mesh)"
    private val _selectedTargetId = MutableStateFlow<String?>(null)
    val selectedTargetId: StateFlow<String?> = _selectedTargetId.asStateFlow()

    private val _activeChannel = MutableStateFlow("General")
    val activeChannel: StateFlow<String> = _activeChannel.asStateFlow()

    fun selectChannel(name: String) {
        _activeChannel.value = name.ifBlank { "General" }
    }

    private val seenMsgUids = Collections.synchronizedSet(object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            if (size >= 500) {
                val it = iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
            return super.add(element)
        }
    })

    fun selectTargetPeer(peerId: String?) {
        _selectedTargetId.value = peerId
    }

    fun updateTransferProgress(transfer: ActiveTransfer) {
        transferMap[transfer.id] = transfer
        _activeTransfers.value = transferMap.values.toList()
    }

    fun finishTransfer(id: String) {
        transferMap.remove(id)
        _activeTransfers.value = transferMap.values.toList()
    }

    fun start(port: Int = DEFAULT_PORT) {
        if (_isRunning.value) return

        acceptJob = scope.launch {
            try {
                val ss = try {
                    ServerSocket(port)
                } catch (e: Exception) {
                    Log.w(TAG, "Port $port busy, picking free OS port", e)
                    ServerSocket(0)
                }
                serverSocket = ss
                val actualPort = ss.localPort
                val ip = getLocalIpAddress() ?: "127.0.0.1"
                _serverUrl.value = "http://$ip:$actualPort"
                _isRunning.value = true
                acquireMulticastLock()
                registerNsdService(actualPort)
                startNsdDiscovery()
                startUdpBeacon(actualPort)
                startUdpListener(actualPort)
                startPeerPruner()
                Log.i(TAG, "SanDen Continuity Hub started at ${_serverUrl.value} ($localDeviceName / $localDeviceId)")

                while (!ss.isClosed) {
                    val client = ss.accept()
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                if (_isRunning.value) {
                    Log.e(TAG, "Server error", e)
                }
            } finally {
                _isRunning.value = false
                _connectedClients.value = 0
                releaseMulticastLock()
            }
        }
    }

    fun stop() {
        _isRunning.value = false
        unregisterNsdService()
        stopNsdDiscovery()
        udpBeaconJob?.cancel()
        udpBeaconJob = null
        udpListenerJob?.cancel()
        udpListenerJob = null
        peerPruneJob?.cancel()
        peerPruneJob = null
        runCatching { udpSocket?.close() }
        udpSocket = null
        runCatching { udpListenSocket?.close() }
        udpListenSocket = null
        releaseMulticastLock()
        wsClients.forEach { runCatching { it.close() } }
        wsClients.clear()
        _connectedClients.value = 0
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        _serverUrl.value = ""
        Log.i(TAG, "SanDen Continuity Hub stopped")
    }

    private fun targetMeshPeers(): List<PeerDevice> {
        val selected = _selectedTargetId.value
        val peersWithUrl = _discoveredPeers.value.filter { it.url.startsWith("http") }
        return if (selected == null) {
            peersWithUrl
        } else {
            val picked = _discoveredPeers.value.firstOrNull { it.id == selected }
            if (picked != null && picked.url.startsWith("http")) listOf(picked) else emptyList()
        }
    }

    private fun computeSha256(file: File): String {
        if (!file.exists() || !file.isFile) return ""
        return runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(65536)
            BufferedInputStream(FileInputStream(file), 65536).use { fis ->
                while (true) {
                    val n = fis.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
    }

    private suspend fun findExistingFileByHash(sha256: String, excludePath: String = ""): File? {
        if (sha256.isBlank()) return null
        val vaultMatch = vaultFileDao.getBySha256(sha256)
        if (vaultMatch != null && vaultMatch.path != excludePath) {
            val f = File(vaultMatch.path)
            if (f.exists() && f.isFile) return f
        }
        val msgMatch = syncMessageDao.findByFileHash(sha256)
        if (msgMatch != null && msgMatch.filePath != excludePath) {
            val f = File(msgMatch.filePath)
            if (f.exists() && f.isFile) return f
        }
        // Lazily check existing Vault files with missing sha256
        val unhashed = vaultFileDao.getAll().filter { !it.isDirectory && it.sha256.isBlank() && it.path != excludePath }
        for (vf in unhashed) {
            val f = File(vf.path)
            if (!f.exists()) continue
            val h = computeSha256(f)
            if (h.isNotBlank()) {
                vaultFileDao.updateSha256(vf.id, h)
                if (h.equals(sha256, ignoreCase = true)) return f
            }
        }
        return null
    }

    private fun peerHasFileByHash(peerUrl: String, sha256: String): Boolean {
        if (sha256.isBlank()) return false
        return runCatching {
            val conn = (URL("$peerUrl/api/files/has?sha256=${URLEncoder.encode(sha256, "UTF-8")}").openConnection() as HttpURLConnection).apply {
                connectTimeout = 2000
                readTimeout = 2500
                requestMethod = "GET"
                getPasskey().takeIf { it.isNotEmpty() }?.let { setRequestProperty("X-SanDen-Key", it) }
            }
            if (conn.responseCode in 200..299) {
                val txt = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                JSONObject(txt).optBoolean("exists", false)
            } else {
                conn.disconnect()
                false
            }
        }.getOrDefault(false)
    }

    private fun pushFileLinkToPeer(
        peer: PeerDevice,
        channel: String,
        fileName: String,
        fileSize: Long,
        fileHash: String,
        mimeType: String,
        msgUid: String,
        targetPeerId: String
    ): Boolean {
        return runCatching {
            val payload = JSONObject().apply {
                put("channel", channel)
                put("sender", localDeviceName)
                put("originId", localDeviceId)
                put("targetPeerId", targetPeerId)
                put("msgUid", msgUid)
                put("fileName", fileName)
                put("fileSize", fileSize)
                put("fileHash", fileHash)
                put("mimeType", mimeType)
            }.toString().toByteArray(Charsets.UTF_8)
            val conn = (URL("${peer.url}/api/messages/link").openConnection() as HttpURLConnection).apply {
                connectTimeout = 3500
                readTimeout = 5000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                getPasskey().takeIf { it.isNotEmpty() }?.let { setRequestProperty("X-SanDen-Key", it) }
            }
            conn.outputStream.use { it.write(payload) }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        }.getOrDefault(false)
    }

    suspend fun syncAllPeersNow() = withContext(Dispatchers.IO) {
        val peers = _discoveredPeers.value.filter { it.url.startsWith("http") }
        for (p in peers) {
            syncFromPeer(p)
        }
    }

    suspend fun syncFromPeer(peer: PeerDevice) = withContext(Dispatchers.IO) {
        if (!peer.url.startsWith("http")) return@withContext
        runCatching {
            val conn = (URL("${peer.url}/api/sync/pull?since=0&requesterId=${URLEncoder.encode(localDeviceId, "UTF-8")}")
                .openConnection() as HttpURLConnection).apply {
                connectTimeout = 3500
                readTimeout = 8000
                requestMethod = "GET"
                getPasskey().takeIf { it.isNotEmpty() }?.let { setRequestProperty("X-SanDen-Key", it) }
            }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return@withContext
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val root = JSONObject(body)
            val chArr = root.optJSONArray("channels")
            if (chArr != null) {
                for (i in 0 until chArr.length()) {
                    val ch = chArr.optString(i).trim()
                    if (ch.isNotEmpty()) syncMessageDao.insertChannel(SyncChannelEntity(name = ch))
                }
            }
            val msgArr = root.optJSONArray("messages") ?: return@withContext
            for (i in 0 until msgArr.length()) {
                val obj = msgArr.optJSONObject(i) ?: continue
                val msgUid = obj.optString("msgUid", "").trim()
                if (msgUid.isBlank()) continue
                if (syncMessageDao.getByMsgUid(msgUid) != null || !seenMsgUids.add(msgUid)) continue

                val ch = obj.optString("channel", "General").ifBlank { "General" }
                val sender = obj.optString("sender", peer.name).ifBlank { peer.name }
                val targetPeer = obj.optString("targetPeerId", "")
                val storedScope = when {
                    targetPeer == localDeviceId -> peer.id
                    targetPeer.isBlank() -> ""
                    else -> continue
                }
                val type = obj.optString("type", "TEXT")
                val text = obj.optString("textContent", "")
                val fileName = obj.optString("fileName", "")
                val fileSize = obj.optLong("fileSize", 0L)
                val fileHash = obj.optString("fileHash", "").lowercase()
                val mime = obj.optString("mimeType", "")
                val ts = obj.optLong("timestamp", System.currentTimeMillis())

                var localPath = ""
                if (type.equals("FILE", ignoreCase = true) && fileName.isNotBlank()) {
                    val existing = findExistingFileByHash(fileHash)
                    if (existing != null) {
                        localPath = existing.absolutePath
                    } else if (fileHash.isNotBlank()) {
                        localPath = downloadBlobFromPeer(peer.url, fileHash, fileName).orEmpty()
                    }
                    if (localPath.isBlank()) continue
                }

                syncMessageDao.insertChannel(SyncChannelEntity(name = ch))
                syncMessageDao.insert(
                    SyncMessageEntity(
                        msgUid = msgUid,
                        channel = ch,
                        sender = sender,
                        targetPeerId = storedScope,
                        type = type,
                        textContent = text,
                        fileName = if (localPath.isNotBlank()) File(localPath).name else fileName,
                        filePath = localPath,
                        fileSize = if (localPath.isNotBlank()) File(localPath).length() else fileSize,
                        fileHash = fileHash,
                        mimeType = mime,
                        timestamp = ts
                    )
                )
            }
        }.onFailure {
            Log.d(TAG, "Mesh sync with ${peer.name} skipped: ${it.message}")
        }
    }

    private suspend fun downloadBlobFromPeer(peerUrl: String, sha256: String, fileName: String): String? {
        if (sha256.isBlank()) return null
        findExistingFileByHash(sha256)?.let { return it.absolutePath }
        return runCatching {
            val conn = (URL("$peerUrl/api/blob?sha256=${URLEncoder.encode(sha256, "UTF-8")}")
                .openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 300_000
                requestMethod = "GET"
                getPasskey().takeIf { it.isNotEmpty() }?.let { setRequestProperty("X-SanDen-Key", it) }
            }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return null
            }
            vaultRepository.ensureVaultReady()
            val vaultDir = File(vaultRepository.getActiveVaultPath()).also { it.mkdirs() }
            val safeName = fileName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "sync_${System.currentTimeMillis()}" }
            val destFile = SafeFileOperations.resolveNameCollision(File(vaultDir, safeName))
            val md = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { inp ->
                destFile.outputStream().buffered(BUFFER_SIZE).use { out ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = inp.read(buf)
                        if (n == -1) break
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                    out.flush()
                }
            }
            conn.disconnect()
            val actualHash = md.digest().joinToString("") { "%02x".format(it) }
            val dup = findExistingFileByHash(actualHash, excludePath = destFile.absolutePath)
            if (dup != null) {
                destFile.delete()
                return dup.absolutePath
            }
            vaultRepository.scanVault()
            vaultFileDao.getByPath(destFile.absolutePath)?.let { vaultFileDao.updateSha256(it.id, actualHash) }
            destFile.absolutePath
        }.getOrNull()
    }

    suspend fun addManualPeer(rawHostOrUrl: String): Result<PeerDevice> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = rawHostOrUrl.trim().removeSuffix("/")
            val targetUrl = when {
                clean.startsWith("http://") || clean.startsWith("https://") -> clean
                clean.contains(":") -> "http://$clean"
                else -> "http://$clean:$DEFAULT_PORT"
            }
            val conn = (URL("$targetUrl/api/info").openConnection() as HttpURLConnection).apply {
                connectTimeout = 3500
                readTimeout = 3500
                requestMethod = "GET"
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val info = JSONObject(body)
            val peerId = info.optString("deviceId").ifBlank { "manual_${targetUrl.hashCode()}" }
            if (peerId == localDeviceId) throw IllegalStateException("Cannot add this same device")
            val peerName = info.optString("deviceName", targetUrl)
            val peerUri = URL(targetUrl)
            val peer = PeerDevice(
                id = peerId,
                name = peerName,
                ip = peerUri.host,
                port = if (peerUri.port > 0) peerUri.port else DEFAULT_PORT,
                url = targetUrl,
                deviceType = "ANDROID",
                pinned = true,
                lastSeenMs = System.currentTimeMillis()
            )
            upsertPeer(peer)
            // Reverse announce so the other phone sees us immediately too
            val selfUrl = _serverUrl.value
            if (selfUrl.isNotEmpty()) {
                runCatching {
                    val selfUri = URL(selfUrl)
                    val announcePayload = JSONObject().apply {
                        put("deviceId", localDeviceId)
                        put("deviceName", localDeviceName)
                        put("ip", selfUri.host)
                        put("port", if (selfUri.port > 0) selfUri.port else DEFAULT_PORT)
                        put("url", selfUrl)
                        put("deviceType", "ANDROID")
                        put("pinned", true)
                    }.toString().toByteArray(Charsets.UTF_8)
                    val c2 = (URL("$targetUrl/api/peers/announce").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 2500
                        readTimeout = 2500
                        requestMethod = "POST"
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                    c2.outputStream.use { it.write(announcePayload) }
                    c2.responseCode
                    c2.disconnect()
                }
            }
            peer
        }
    }

    suspend fun sendPhoneMessage(text: String, type: String = "TEXT", channel: String = "General") =
        withContext(Dispatchers.IO) {
            val ch = channel.ifBlank { "General" }
            val msgUid = "${localDeviceId}_${UUID.randomUUID()}"
            seenMsgUids.add(msgUid)
            syncMessageDao.insertChannel(SyncChannelEntity(name = ch))

            val selectedTarget = _selectedTargetId.value
            val selectedPeer = selectedTarget?.let { tid -> _discoveredPeers.value.firstOrNull { it.id == tid } }
            val scopePeerId = selectedTarget.orEmpty()

            val entity = SyncMessageEntity(
                msgUid = msgUid,
                channel = ch,
                sender = "PHONE",
                targetPeerId = scopePeerId,
                type = type,
                textContent = text,
                timestamp = System.currentTimeMillis()
            )
            val id = syncMessageDao.insert(entity)
            val saved = entity.copy(id = id)

            // Only push over WebSocket to PC if broadcasting to All Group OR explicitly targeting a PC peer
            if (selectedPeer == null || selectedPeer.deviceType == "PC") {
                broadcastWebSocket(saved.toJson().toString())
            }
            // Push to target mesh peer(s)
            val peers = targetMeshPeers()
            for (peer in peers) {
                launch {
                    pushMessageToPeer(peer, ch, type, text, msgUid, scopePeerId)
                }
            }
        }

    suspend fun sendPhoneFile(
        filePath: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
        channel: String = "General"
    ) = withContext(Dispatchers.IO) {
        val ch = channel.ifBlank { "General" }
        val msgUid = "${localDeviceId}_${UUID.randomUUID()}"
        seenMsgUids.add(msgUid)
        syncMessageDao.insertChannel(SyncChannelEntity(name = ch))

        val file = File(filePath)
        val fileHash = computeSha256(file)
        if (fileHash.isNotBlank()) {
            vaultFileDao.getByPath(filePath)?.let { vaultFileDao.updateSha256(it.id, fileHash) }
        }

        val selectedTarget = _selectedTargetId.value
        val selectedPeer = selectedTarget?.let { tid -> _discoveredPeers.value.firstOrNull { it.id == tid } }
        val scopePeerId = selectedTarget.orEmpty()

        val entity = SyncMessageEntity(
            msgUid = msgUid,
            channel = ch,
            sender = "PHONE",
            targetPeerId = scopePeerId,
            type = "FILE",
            fileName = fileName,
            filePath = filePath,
            fileSize = fileSize,
            fileHash = fileHash,
            mimeType = mimeType,
            timestamp = System.currentTimeMillis()
        )
        val id = syncMessageDao.insert(entity)
        val saved = entity.copy(id = id)
        if (selectedPeer == null || selectedPeer.deviceType == "PC") {
            broadcastWebSocket(saved.toJson().toString())
        }
        // Stream (or zero-byte link if peer already has SHA-256!) to target mesh peers
        val peers = targetMeshPeers()
        if (peers.isNotEmpty() && file.exists()) {
            for (peer in peers) {
                launch {
                    streamFileToPeer(peer, file, fileName, ch, msgUid, scopePeerId, fileHash, mimeType)
                }
            }
        }
    }

    private fun pushMessageToPeer(
        peer: PeerDevice,
        channel: String,
        type: String,
        text: String,
        msgUid: String,
        targetPeerId: String
    ) {
        runCatching {
            val payload = JSONObject().apply {
                put("channel", channel)
                put("sender", localDeviceName)
                put("originId", localDeviceId)
                put("targetPeerId", targetPeerId)
                put("msgUid", msgUid)
                put("type", type)
                put("textContent", text)
            }.toString().toByteArray(Charsets.UTF_8)
            val conn = (URL("${peer.url}/api/messages").openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 6000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                getPasskey().takeIf { it.isNotEmpty() }?.let { setRequestProperty("X-SanDen-Key", it) }
            }
            conn.outputStream.use { it.write(payload) }
            val respCode = conn.responseCode
            conn.disconnect()
            Log.i(TAG, "Push message to ${peer.name} (${peer.url}) -> HTTP $respCode")
        }.onFailure {
            Log.w(TAG, "Push message to ${peer.name} (${peer.url}) failed: ${it.message}")
        }
    }

    private suspend fun streamFileToPeer(
        peer: PeerDevice,
        file: File,
        fileName: String,
        channel: String,
        msgUid: String,
        targetPeerId: String,
        fileHash: String = "",
        mimeType: String = "application/octet-stream"
    ) {
        val actualHash = fileHash.ifBlank { computeSha256(file) }
        if (actualHash.isNotBlank() && peerHasFileByHash(peer.url, actualHash)) {
            if (pushFileLinkToPeer(peer, channel, fileName, file.length(), actualHash, mimeType, msgUid, targetPeerId)) {
                Log.i(TAG, "Deduplicated file link to ${peer.name} ($fileName)")
                return
            }
        }
        val totalBytes = file.length()
        val txId = "p2p_tx_${peer.id}_${System.currentTimeMillis()}"
        updateTransferProgress(
            ActiveTransfer(
                id = txId,
                channel = channel,
                fileName = "${peer.name}: $fileName",
                direction = "SENDING",
                bytesTransferred = 0L,
                totalBytes = totalBytes
            )
        )
        try {
            val conn = (URL("${peer.url}/api/upload").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 300_000
                requestMethod = "POST"
                doOutput = true
                setFixedLengthStreamingMode(totalBytes)
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("X-File-Name", URLEncoder.encode(fileName, "UTF-8").replace("+", "%20"))
                setRequestProperty("X-Channel", URLEncoder.encode(channel, "UTF-8").replace("+", "%20"))
                setRequestProperty("X-Sender-Name", URLEncoder.encode(localDeviceName, "UTF-8").replace("+", "%20"))
                setRequestProperty("X-Origin-Id", localDeviceId)
                setRequestProperty("X-Target-Peer-Id", targetPeerId)
                setRequestProperty("X-Msg-Uid", msgUid)
                if (actualHash.isNotBlank()) setRequestProperty("X-File-Hash", actualHash)
                getPasskey().takeIf { it.isNotEmpty() }?.let { setRequestProperty("X-SanDen-Key", it) }
            }
            val buf = ByteArray(BUFFER_SIZE)
            var sent = 0L
            var lastEmit = 0L
            BufferedInputStream(FileInputStream(file), BUFFER_SIZE).use { fis ->
                conn.outputStream.use { out ->
                    while (true) {
                        val n = fis.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        sent += n
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 200L) {
                            lastEmit = now
                            updateTransferProgress(
                                ActiveTransfer(
                                    id = txId,
                                    channel = channel,
                                    fileName = "${peer.name}: $fileName",
                                    direction = "SENDING",
                                    bytesTransferred = sent,
                                    totalBytes = totalBytes
                                )
                            )
                        }
                    }
                    out.flush()
                }
            }
            conn.responseCode
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Stream file to ${peer.name} failed: ${e.message}")
        } finally {
            finishTransfer(txId)
        }
    }

    // ── HTTP / WebSocket Request Handling ────────────────────────────────

    private suspend fun handleClient(socket: Socket) {
        try {
            val input = BufferedInputStream(socket.getInputStream(), BUFFER_SIZE)
            val output = BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE)

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val rawTarget = parts[1]
            val path = rawTarget.substringBefore("?")
            val query = rawTarget.substringAfter("?", "")

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }

            if (!isAllowedBrowserOrigin(headers["origin"], headers["host"])) {
                sendJson(output, 403, "Forbidden", """{"error":"Untrusted browser origin"}""")
                return
            }

            if (path != "/" && path != "/api/info" && !isAuthorizedRequest(headers, query)) {
                sendJson(output, 401, "Unauthorized", """{"error":"Missing or invalid pairing passkey"}""")
                return
            }

            if (path == "/ws" && headers["upgrade"]?.equals("websocket", ignoreCase = true) == true) {
                handleWebSocket(socket, input, output, headers)
                return
            }

            when {
                method == "GET" && path == "/" -> serveCompanionHtml(output)
                method == "GET" && path == "/api/info" -> serveInfo(output)
                method == "GET" && path == "/api/peers" -> servePeers(output)
                method == "POST" && path == "/api/peers/announce" -> handlePeerAnnounce(input, output, headers)
                method == "GET" && path == "/api/channels" -> serveChannels(output)
                method == "POST" && path == "/api/channels" -> handleCreateChannel(input, output, headers)
                method == "GET" && path == "/api/messages" -> serveMessages(output, query)
                method == "POST" && path == "/api/messages" -> handlePostMessage(input, output, headers)
                method == "POST" && path == "/api/upload" -> handleUpload(input, output, headers)
                method == "GET" && path == "/api/vault/files" -> serveVaultFiles(output)
                method == "GET" && path == "/api/vault/download" -> serveVaultDownload(output, query)
                method == "GET" && path == "/api/vault/thumbnail" -> serveVaultThumbnail(output, query)
                method == "GET" && path == "/api/messages/download" -> serveMessageDownload(output, query)
                method == "GET" && path == "/api/messages/thumbnail" -> serveMessageThumbnail(output, query)
                method == "GET" && path == "/api/files/has" -> serveHasFileByHash(output, query)
                method == "GET" && path == "/api/blob" -> serveBlobByHash(output, query)
                method == "GET" && path == "/api/sync/pull" -> serveSyncPull(output, query)
                method == "POST" && path == "/api/messages/link" -> handleMessageLink(input, output, headers)
                else -> sendJson(output, 404, "Not Found", """{"error":"Not found"}""")
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client disconnected: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun serveCompanionHtml(output: OutputStream) {
        val bytes = context.assets.open("companion.html").use { it.readBytes() }
        writeResponse(output, 200, "OK", "text/html; charset=utf-8", bytes)
    }

    private fun readBatteryPercent(): Int {
        return runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } ?: -1
        }.getOrDefault(-1)
    }

    private suspend fun serveInfo(output: OutputStream) {
        val allFiles = runCatching { vaultFileDao.getAll() }.getOrDefault(emptyList())
        val vaultBytes = allFiles.filter { !it.isDirectory }.sumOf { it.size }
        val vaultCount = allFiles.count { !it.isDirectory }
        val stat = runCatching { StatFs(Environment.getExternalStorageDirectory().absolutePath) }.getOrNull()
        val json = JSONObject().apply {
            put("deviceId", localDeviceId)
            put("deviceName", localDeviceName)
            put("deviceType", "ANDROID")
            put("url", _serverUrl.value)
            put("vaultMode", vaultRepository.getStorageMode().name)
            put("batteryPct", readBatteryPercent())
            put("vaultUsedBytes", vaultBytes)
            put("vaultFileCount", vaultCount)
            put("storageTotalBytes", stat?.totalBytes ?: 0L)
            put("storageFreeBytes", stat?.availableBytes ?: 0L)
            put("authRequired", getPasskey().isNotEmpty())
        }.toString()
        sendJson(output, 200, "OK", json)
    }

    private fun servePeers(output: OutputStream) {
        val arr = JSONArray()
        for (p in _discoveredPeers.value) {
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("ip", p.ip)
                put("port", p.port)
                put("url", p.url)
                put("deviceType", p.deviceType)
                put("lastSeenMs", p.lastSeenMs)
            })
        }
        sendJson(output, 200, "OK", arr.toString())
    }

    private suspend fun handlePeerAnnounce(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>
    ) {
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0 || length > 32_000) {
            sendJson(output, 400, "Bad Request", """{"error":"Invalid body"}""")
            return
        }
        val bodyBytes = readExactBytes(input, length)
        val obj = JSONObject(String(bodyBytes, Charsets.UTF_8))
        val id = obj.optString("deviceId").ifBlank { obj.optString("id") }
        val name = obj.optString("deviceName").ifBlank { obj.optString("name", "SanDen Peer") }
        val ip = obj.optString("ip", "")
        val port = obj.optInt("port", DEFAULT_PORT)
        val url = obj.optString("url", "")
        val deviceType = obj.optString("deviceType", "ANDROID")
        val pinned = obj.optBoolean("pinned", false)
        if (id.isNotBlank() && id != localDeviceId) {
            upsertPeer(
                PeerDevice(
                    id = id,
                    name = name,
                    ip = ip,
                    port = port,
                    url = url,
                    deviceType = deviceType,
                    pinned = pinned,
                    lastSeenMs = System.currentTimeMillis()
                )
            )
        }
        sendJson(output, 200, "OK", """{"status":"ok","deviceId":"$localDeviceId"}""")
    }

    private suspend fun serveChannels(output: OutputStream) {
        syncMessageDao.insertChannel(SyncChannelEntity(name = "General", createdAt = 0L))
        val channels = syncMessageDao.getChannels()
        val arr = JSONArray()
        for (c in channels) arr.put(c.name)
        sendJson(output, 200, "OK", arr.toString())
    }

    private suspend fun handleCreateChannel(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>
    ) {
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0 || length > 10_000) {
            sendJson(output, 400, "Bad Request", """{"error":"Invalid body"}""")
            return
        }
        val bodyBytes = readExactBytes(input, length)
        val obj = JSONObject(String(bodyBytes, Charsets.UTF_8))
        val name = obj.optString("name", "").trim().take(40)
        if (name.isEmpty()) {
            sendJson(output, 400, "Bad Request", """{"error":"Empty channel name"}""")
            return
        }
        syncMessageDao.insertChannel(SyncChannelEntity(name = name))
        sendJson(output, 200, "OK", JSONObject().put("name", name).toString())
    }

    private suspend fun serveMessages(output: OutputStream, query: String) {
        val params = query.split("&")
        val rawChannel = params
            .firstOrNull { it.startsWith("channel=") }
            ?.substringAfter("channel=")
        val channel = rawChannel?.let {
            runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
        }?.ifBlank { "General" } ?: "General"

        val rawRequester = params
            .firstOrNull { it.startsWith("requesterId=") }
            ?.substringAfter("requesterId=")
        val requesterId = rawRequester?.let {
            runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
        }.orEmpty()

        val list = syncMessageDao.getRecentByChannelForRequester(channel, requesterId, 100).reversed()
        val arr = JSONArray()
        for (m in list) arr.put(m.toJson())
        sendJson(output, 200, "OK", arr.toString())
    }

    private suspend fun handlePostMessage(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>
    ) {
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0 || length > 2_000_000) {
            sendJson(output, 400, "Bad Request", """{"error":"Invalid body"}""")
            return
        }
        val bodyBytes = readExactBytes(input, length)
        val obj = JSONObject(String(bodyBytes, Charsets.UTF_8))
        val msgUid = obj.optString("msgUid", "").trim()
        if (msgUid.isNotBlank()) {
            syncMessageDao.getByMsgUid(msgUid)?.let { existing ->
                sendJson(output, 200, "OK", existing.toJson().toString())
                return
            }
            if (!seenMsgUids.add(msgUid)) {
                sendJson(output, 200, "OK", """{"status":"duplicate_ignored"}""")
                return
            }
        }
        val channel = obj.optString("channel", "General").ifBlank { "General" }
        val sender = obj.optString("sender", "LAPTOP").ifBlank { "LAPTOP" }
        val originId = obj.optString("originId", "")
        val reqTarget = obj.optString("targetPeerId", "")
        val type = obj.optString("type", "TEXT")
        val text = obj.optString("textContent", "")
        val effectiveUid = msgUid.ifBlank { "${localDeviceId}_${UUID.randomUUID()}" }

        // If another peer targeted us specifically, store it under that peer's private 1-on-1 scope
        val storedScope = when {
            reqTarget.isNotBlank() && originId.isNotBlank() && originId != localDeviceId -> originId
            reqTarget.isNotBlank() -> reqTarget
            else -> ""
        }

        syncMessageDao.insertChannel(SyncChannelEntity(name = channel))
        val entity = SyncMessageEntity(
            msgUid = effectiveUid,
            channel = channel,
            sender = sender,
            targetPeerId = storedScope,
            type = type,
            textContent = text,
            timestamp = System.currentTimeMillis()
        )
        val id = syncMessageDao.insert(entity)
        val saved = entity.copy(id = id)

        if (sender != "PHONE" && sender != localDeviceName && type == "CLIPBOARD" && text.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                runCatching {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("SanDen Sync", text))
                }
            }
        }

        // Only broadcast to Laptop WebSocket clients when this is a Group message or directly involving a PC
        val isPrivatePhoneToPhone = storedScope.isNotBlank() && !originId.startsWith("pc_") && !reqTarget.startsWith("pc_")
        if (!isPrivatePhoneToPhone) {
            broadcastWebSocket(saved.toJson().toString())
        }
        sendJson(output, 200, "OK", saved.toJson().toString())
    }

    private suspend fun handleUpload(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>
    ) {
        val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L
        val rawName = headers["x-file-name"] ?: "laptop_${System.currentTimeMillis()}"
        val fileName = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .ifBlank { "laptop_${System.currentTimeMillis()}" }

        val rawSender = headers["x-sender-name"] ?: "LAPTOP"
        val senderName = runCatching { URLDecoder.decode(rawSender, "UTF-8") }.getOrDefault(rawSender)
            .ifBlank { "LAPTOP" }
        val originId = headers["x-origin-id"] ?: ""
        val reqTarget = headers["x-target-peer-id"] ?: ""
        val msgUid = (headers["x-msg-uid"] ?: "").trim()
        val headerHash = (headers["x-file-hash"] ?: "").trim().lowercase()
        val effectiveUid = msgUid.ifBlank { "${localDeviceId}_${UUID.randomUUID()}" }

        if (msgUid.isNotBlank()) {
            val existingMsg = syncMessageDao.getByMsgUid(msgUid)
            if (existingMsg != null) {
                drainInput(input, contentLength)
                sendJson(output, 200, "OK", existingMsg.toJson().toString())
                return
            }
            seenMsgUids.add(msgUid)
        }

        // Fast zero-duplicate short-circuit if peer sent matching SHA-256 already present on disk
        val preExisting = if (headerHash.isNotBlank()) findExistingFileByHash(headerHash) else null
        if (preExisting != null) {
            drainInput(input, contentLength)
            val rawChannelFast = headers["x-channel"] ?: "General"
            val chFast = runCatching { URLDecoder.decode(rawChannelFast, "UTF-8") }.getOrDefault(rawChannelFast).ifBlank { "General" }
            val scopeFast = when {
                reqTarget.isNotBlank() && originId.isNotBlank() && originId != localDeviceId -> originId
                reqTarget.isNotBlank() -> reqTarget
                else -> ""
            }
            val mimeFast = CategoryDetector.detectMimeType(preExisting.name) ?: "application/octet-stream"
            syncMessageDao.insertChannel(SyncChannelEntity(name = chFast))
            val entFast = SyncMessageEntity(
                msgUid = effectiveUid,
                channel = chFast,
                sender = senderName,
                targetPeerId = scopeFast,
                type = "FILE",
                fileName = preExisting.name,
                filePath = preExisting.absolutePath,
                fileSize = preExisting.length(),
                fileHash = headerHash,
                mimeType = mimeFast,
                timestamp = System.currentTimeMillis()
            )
            val idFast = syncMessageDao.insert(entFast)
            val savedFast = entFast.copy(id = idFast)
            val isPrivP2P = scopeFast.isNotBlank() && !originId.startsWith("pc_") && !reqTarget.startsWith("pc_")
            if (!isPrivP2P) broadcastWebSocket(savedFast.toJson().toString())
            sendJson(output, 200, "OK", savedFast.toJson().toString())
            return
        }

        vaultRepository.ensureVaultReady()
        val vaultDir = File(vaultRepository.getActiveVaultPath()).also { it.mkdirs() }
        val destFile = SafeFileOperations.resolveNameCollision(File(vaultDir, fileName))

        if (contentLength > 0) {
            SafeFileOperations.ensureSufficientSpace(vaultDir, contentLength)
                .onFailure {
                    sendJson(output, 507, "Insufficient Storage", """{"error":"Low storage"}""")
                    return
                }
        }

        val rawChannel = headers["x-channel"] ?: "General"
        val channel = runCatching { URLDecoder.decode(rawChannel, "UTF-8") }.getOrDefault(rawChannel)
            .ifBlank { "General" }
        syncMessageDao.insertChannel(SyncChannelEntity(name = channel))

        val transferId = "rx_${System.currentTimeMillis()}_${fileName.hashCode()}"
        updateTransferProgress(
            ActiveTransfer(
                id = transferId,
                channel = channel,
                fileName = "${if (senderName == "LAPTOP") "PC" else senderName}: ${destFile.name}",
                direction = "RECEIVING",
                bytesTransferred = 0L,
                totalBytes = contentLength
            )
        )

        var copied = 0L
        var lastEmitMs = 0L
        val md = MessageDigest.getInstance("SHA-256")
        try {
            destFile.outputStream().buffered(BUFFER_SIZE).use { fileOut ->
                val buf = ByteArray(BUFFER_SIZE)
                if (contentLength >= 0) {
                    var remaining = contentLength
                    while (remaining > 0) {
                        val toRead = minOf(buf.size.toLong(), remaining).toInt()
                        val n = input.read(buf, 0, toRead)
                        if (n == -1) break
                        md.update(buf, 0, n)
                        fileOut.write(buf, 0, n)
                        copied += n
                        remaining -= n
                        val now = System.currentTimeMillis()
                        if (now - lastEmitMs >= 200L) {
                            lastEmitMs = now
                            updateTransferProgress(
                                ActiveTransfer(
                                    transferId,
                                    channel,
                                    "${if (senderName == "LAPTOP") "PC" else senderName}: ${destFile.name}",
                                    "RECEIVING",
                                    copied,
                                    contentLength
                                )
                            )
                        }
                    }
                } else {
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        md.update(buf, 0, n)
                        fileOut.write(buf, 0, n)
                        copied += n
                        val now = System.currentTimeMillis()
                        if (now - lastEmitMs >= 200L) {
                            lastEmitMs = now
                            updateTransferProgress(
                                ActiveTransfer(
                                    transferId,
                                    channel,
                                    "${if (senderName == "LAPTOP") "PC" else senderName}: ${destFile.name}",
                                    "RECEIVING",
                                    copied,
                                    -1L
                                )
                            )
                        }
                    }
                }
                fileOut.flush()
            }
        } finally {
            finishTransfer(transferId)
        }

        val actualHash = md.digest().joinToString("") { "%02x".format(it) }
        val existingDup = findExistingFileByHash(actualHash, excludePath = destFile.absolutePath)
        val finalFile = if (existingDup != null) {
            destFile.delete()
            existingDup
        } else {
            destFile
        }

        vaultRepository.scanVault()
        if (existingDup == null) {
            vaultFileDao.getByPath(finalFile.absolutePath)?.let { vaultFileDao.updateSha256(it.id, actualHash) }
        }
        val mime = CategoryDetector.detectMimeType(finalFile.name) ?: "application/octet-stream"
        val storedScope = when {
            reqTarget.isNotBlank() && originId.isNotBlank() && originId != localDeviceId -> originId
            reqTarget.isNotBlank() -> reqTarget
            else -> ""
        }
        val entity = SyncMessageEntity(
            msgUid = effectiveUid,
            channel = channel,
            sender = senderName,
            targetPeerId = storedScope,
            type = "FILE",
            fileName = finalFile.name,
            filePath = finalFile.absolutePath,
            fileSize = finalFile.length(),
            fileHash = actualHash,
            mimeType = mime,
            timestamp = System.currentTimeMillis()
        )
        val id = syncMessageDao.insert(entity)
        val saved = entity.copy(id = id)
        val isPrivatePhoneToPhone = storedScope.isNotBlank() && !originId.startsWith("pc_") && !reqTarget.startsWith("pc_")
        if (!isPrivatePhoneToPhone) {
            broadcastWebSocket(saved.toJson().toString())
        }
        sendJson(output, 200, "OK", saved.toJson().toString())
    }

    private fun drainInput(input: InputStream, contentLength: Long) {
        if (contentLength <= 0L) return
        val buf = ByteArray(BUFFER_SIZE)
        var rem = contentLength
        while (rem > 0L) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
            if (r == -1) break
            rem -= r
        }
    }

    private suspend fun serveHasFileByHash(output: OutputStream, query: String) {
        val sha = query.split("&").firstOrNull { it.startsWith("sha256=") }
            ?.substringAfter("sha256=")
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            ?.trim()?.lowercase().orEmpty()
        val f = findExistingFileByHash(sha)
        val obj = JSONObject().apply {
            put("exists", f != null)
            if (f != null) {
                put("fileName", f.name)
                put("fileSize", f.length())
                put("sha256", sha)
            }
        }
        sendJson(output, 200, "OK", obj.toString())
    }

    private suspend fun serveBlobByHash(output: OutputStream, query: String) {
        val sha = query.split("&").firstOrNull { it.startsWith("sha256=") }
            ?.substringAfter("sha256=")
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            ?.trim()?.lowercase().orEmpty()
        val file = findExistingFileByHash(sha)
        if (file == null || !file.exists() || !file.isFile) {
            sendJson(output, 404, "Not Found", """{"error":"Blob not found"}""")
            return
        }
        val mime = CategoryDetector.detectMimeType(file.name) ?: "application/octet-stream"
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $mime\r\n")
            append("Content-Length: ${file.length()}\r\n")
            append("Content-Disposition: attachment; filename=\"${sanitizeFilenameHeader(file.name)}\"\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        file.inputStream().buffered(BUFFER_SIZE).use { inp ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = inp.read(buf)
                if (n == -1) break
                output.write(buf, 0, n)
            }
        }
        output.flush()
    }

    private suspend fun serveSyncPull(output: OutputStream, query: String) {
        val params = query.split("&")
        val since = params.firstOrNull { it.startsWith("since=") }
            ?.substringAfter("since=")?.toLongOrNull() ?: 0L
        val requesterId = params.firstOrNull { it.startsWith("requesterId=") }
            ?.substringAfter("requesterId=")
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            .orEmpty()

        syncMessageDao.insertChannel(SyncChannelEntity(name = "General", createdAt = 0L))
        val chArr = JSONArray().apply {
            for (c in syncMessageDao.getChannels()) put(c.name)
        }
        val msgArr = JSONArray().apply {
            for (m in syncMessageDao.getMessagesSinceForRequester(since, requesterId, 500)) put(m.toJson())
        }
        val resp = JSONObject().apply {
            put("deviceId", localDeviceId)
            put("deviceName", localDeviceName)
            put("serverTimeMs", System.currentTimeMillis())
            put("channels", chArr)
            put("messages", msgArr)
        }
        sendJson(output, 200, "OK", resp.toString())
    }

    private suspend fun handleMessageLink(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>
    ) {
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0 || length > 64_000) {
            sendJson(output, 400, "Bad Request", """{"error":"Invalid body"}""")
            return
        }
        val obj = JSONObject(String(readExactBytes(input, length), Charsets.UTF_8))
        val msgUid = obj.optString("msgUid", "").trim()
        if (msgUid.isNotBlank()) {
            syncMessageDao.getByMsgUid(msgUid)?.let { existing ->
                sendJson(output, 200, "OK", existing.toJson().toString())
                return
            }
            seenMsgUids.add(msgUid)
        }
        val sha = obj.optString("fileHash", "").trim().lowercase()
        val existingFile = findExistingFileByHash(sha)
        if (existingFile == null) {
            sendJson(output, 404, "Not Found", """{"error":"Blob missing"}""")
            return
        }
        val ch = obj.optString("channel", "General").ifBlank { "General" }
        val sender = obj.optString("sender", "LAPTOP").ifBlank { "LAPTOP" }
        val originId = obj.optString("originId", "")
        val reqTarget = obj.optString("targetPeerId", "")
        val mime = obj.optString("mimeType", "").ifBlank {
            CategoryDetector.detectMimeType(existingFile.name) ?: "application/octet-stream"
        }
        val storedScope = when {
            reqTarget.isNotBlank() && originId.isNotBlank() && originId != localDeviceId -> originId
            reqTarget.isNotBlank() -> reqTarget
            else -> ""
        }
        syncMessageDao.insertChannel(SyncChannelEntity(name = ch))
        val entity = SyncMessageEntity(
            msgUid = msgUid.ifBlank { "${localDeviceId}_${UUID.randomUUID()}" },
            channel = ch,
            sender = sender,
            targetPeerId = storedScope,
            type = "FILE",
            fileName = existingFile.name,
            filePath = existingFile.absolutePath,
            fileSize = existingFile.length(),
            fileHash = sha,
            mimeType = mime,
            timestamp = System.currentTimeMillis()
        )
        val id = syncMessageDao.insert(entity)
        val saved = entity.copy(id = id)
        val isPrivP2P = storedScope.isNotBlank() && !originId.startsWith("pc_") && !reqTarget.startsWith("pc_")
        if (!isPrivP2P) broadcastWebSocket(saved.toJson().toString())
        sendJson(output, 200, "OK", saved.toJson().toString())
    }

    private suspend fun serveVaultFiles(output: OutputStream) {
        val files = vaultFileDao.getAll()
        val arr = JSONArray()
        for (f in files) {
            arr.put(JSONObject().apply {
                put("id", f.id)
                put("name", f.name)
                put("size", f.size)
                put("category", f.category.name)
                put("isDirectory", f.isDirectory)
                put("lastModified", f.lastModified)
            })
        }
        sendJson(output, 200, "OK", arr.toString())
    }

    private suspend fun serveVaultDownload(output: OutputStream, query: String) {
        val idParam = query.split("&")
            .firstOrNull { it.startsWith("id=") }
            ?.substringAfter("id=")
            ?.toLongOrNull()
        val entity = idParam?.let { vaultFileDao.getById(it) }
        val file = entity?.let { File(it.path) }

        if (entity == null || file == null || !file.exists() || file.isDirectory) {
            sendJson(output, 404, "Not Found", """{"error":"File not found"}""")
            return
        }

        val mime = entity.mimeType ?: "application/octet-stream"
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $mime\r\n")
            append("Content-Length: ${file.length()}\r\n")
            append("Content-Disposition: attachment; filename=\"${sanitizeFilenameHeader(file.name)}\"\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                output.write(buf, 0, n)
            }
        }
        output.flush()
    }

    private suspend fun serveVaultThumbnail(output: OutputStream, query: String) {
        val idParam = query.split("&")
            .firstOrNull { it.startsWith("id=") }
            ?.substringAfter("id=")
            ?.toLongOrNull()
        val entity = idParam?.let { vaultFileDao.getById(it) }
        val file = entity?.let { File(it.path) }
        if (entity == null || file == null || !file.exists() || file.isDirectory) {
            sendJson(output, 404, "Not Found", """{"error":"File not found"}""")
            return
        }
        serveMediaThumbnailFile(output, file, entity.mimeType ?: "", "vault_${entity.id}_${file.lastModified()}")
    }

    private suspend fun serveMessageThumbnail(output: OutputStream, query: String) {
        val idParam = query.split("&")
            .firstOrNull { it.startsWith("id=") }
            ?.substringAfter("id=")
            ?.toLongOrNull()
        val msg = idParam?.let { syncMessageDao.getById(it) }
        val file = msg?.filePath?.takeIf { it.isNotBlank() }?.let { File(it) }
        if (msg == null || file == null || !file.exists() || file.isDirectory) {
            sendJson(output, 404, "Not Found", """{"error":"File not found"}""")
            return
        }
        serveMediaThumbnailFile(output, file, msg.mimeType, "msg_${msg.id}_${file.lastModified()}")
    }

    private fun serveMediaThumbnailFile(output: OutputStream, file: File, mimeType: String, cacheKey: String) {
        val ext = file.extension.lowercase()
        val lowerMime = mimeType.lowercase()
        val isVideo = lowerMime.startsWith("video/") || ext in setOf("mp4", "mkv", "mov", "webm", "avi", "3gp", "m4v")
        val isImage = lowerMime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
        if (!isVideo && !isImage) {
            sendJson(output, 404, "Not Found", """{"error":"Not media"}""")
            return
        }

        val thumbDir = File(context.cacheDir, "hub_thumbs").apply { mkdirs() }
        val cachedJpg = File(thumbDir, "${cacheKey}.jpg")
        val cachedMeta = File(thumbDir, "${cacheKey}.dur")

        var durationMs = cachedMeta.takeIf { it.exists() }?.readText()?.toLongOrNull() ?: -1L
        var jpgBytes: ByteArray? = cachedJpg.takeIf { it.exists() && it.length() > 0 }?.readBytes()

        if (jpgBytes == null) {
            if (isVideo) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: -1L
                    val rawBmp = retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.getFrameAtTime(0L)
                    if (rawBmp != null) {
                        val scaled = scaleBitmapToMaxWidth(rawBmp, 540)
                        val baos = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 82, baos)
                        jpgBytes = baos.toByteArray()
                        if (scaled != rawBmp) scaled.recycle()
                        rawBmp.recycle()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Video thumb extract failed for ${file.name}: ${e.message}")
                } finally {
                    runCatching { retriever.release() }
                }
            } else {
                try {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, opts)
                    var sample = 1
                    while (opts.outWidth / sample > 640) sample *= 2
                    val decOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                    val bmp = BitmapFactory.decodeFile(file.absolutePath, decOpts)
                    if (bmp != null) {
                        val scaled = scaleBitmapToMaxWidth(bmp, 540)
                        val baos = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 82, baos)
                        jpgBytes = baos.toByteArray()
                        if (scaled != bmp) scaled.recycle()
                        bmp.recycle()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Image thumb extract failed for ${file.name}: ${e.message}")
                }
            }

            if (jpgBytes != null && jpgBytes.isNotEmpty()) {
                runCatching {
                    cachedJpg.writeBytes(jpgBytes)
                    if (durationMs >= 0) cachedMeta.writeText(durationMs.toString())
                }
            }
        }

        if (jpgBytes == null || jpgBytes.isEmpty()) {
            sendJson(output, 404, "Not Found", """{"error":"Thumbnail unavailable"}""")
            return
        }

        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: image/jpeg\r\n")
            append("Content-Length: ${jpgBytes.size}\r\n")
            if (durationMs >= 0) append("X-Video-Duration-Ms: $durationMs\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(jpgBytes)
        output.flush()
    }

    private fun scaleBitmapToMaxWidth(src: Bitmap, maxW: Int): Bitmap {
        if (src.width <= maxW || src.width <= 0) return src
        val ratio = maxW.toFloat() / src.width.toFloat()
        val targetH = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, maxW, targetH, true)
    }

    private suspend fun serveMessageDownload(output: OutputStream, query: String) {
        val idParam = query.split("&")
            .firstOrNull { it.startsWith("id=") }
            ?.substringAfter("id=")
            ?.toLongOrNull()
        val msg = idParam?.let { syncMessageDao.getById(it) }
        val file = msg?.filePath?.takeIf { it.isNotBlank() }?.let { File(it) }

        if (msg == null || file == null || !file.exists() || file.isDirectory) {
            sendJson(output, 404, "Not Found", """{"error":"File not found"}""")
            return
        }

        val mime = msg.mimeType.ifBlank { "application/octet-stream" }
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $mime\r\n")
            append("Content-Length: ${file.length()}\r\n")
            append("Content-Disposition: attachment; filename=\"${sanitizeFilenameHeader(file.name)}\"\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                output.write(buf, 0, n)
            }
        }
        output.flush()
    }

    // ── RFC 6455 WebSocket Support ───────────────────────────────────────

    private fun handleWebSocket(
        socket: Socket,
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>
    ) {
        val key = headers["sec-websocket-key"] ?: return
        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
        val handshake = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: $accept\r\n\r\n")
        }
        output.write(handshake.toByteArray(Charsets.UTF_8))
        output.flush()

        wsClients.add(output)
        _connectedClients.value = wsClients.size
        socket.soTimeout = 0

        try {
            // Keep reading frames until client disconnects
            while (!socket.isClosed && _isRunning.value) {
                val b0 = input.read()
                if (b0 == -1) break
                val opcode = b0 and 0x0F
                if (opcode == 0x8) break // Close frame

                val b1 = input.read()
                if (b1 == -1) break
                val masked = (b1 and 0x80) != 0
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) {
                    val hi = input.read()
                    val lo = input.read()
                    if (hi == -1 || lo == -1) break
                    len = ((hi shl 8) or lo).toLong()
                } else if (len == 127L) {
                    var l = 0L
                    for (i in 0 until 8) {
                        val v = input.read()
                        if (v == -1) return
                        l = (l shl 8) or v.toLong()
                    }
                    len = l
                }
                if (len < 0 || len > 1_048_576L) break
                if (masked) {
                    val mask = ByteArray(4)
                    if (readFully(input, mask) < 4) break
                }
                // Drain payload (control / ping)
                var skip = len
                val tmp = ByteArray(1024)
                while (skip > 0) {
                    val r = input.read(tmp, 0, minOf(tmp.size.toLong(), skip).toInt())
                    if (r == -1) return
                    skip -= r
                }
            }
        } finally {
            wsClients.remove(output)
            _connectedClients.value = wsClients.size
        }
    }

    private fun broadcastWebSocket(text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        val frame = buildWsTextFrame(payload)
        for (clientOut in wsClients) {
            try {
                synchronized(clientOut) {
                    clientOut.write(frame)
                    clientOut.flush()
                }
            } catch (e: Exception) {
                wsClients.remove(clientOut)
                _connectedClients.value = wsClients.size
            }
        }
    }

    private fun buildWsTextFrame(payload: ByteArray): ByteArray {
        val len = payload.size
        val header = when {
            len <= 125 -> byteArrayOf(0x81.toByte(), len.toByte())
            len <= 65535 -> byteArrayOf(
                0x81.toByte(),
                126,
                ((len ushr 8) and 0xFF).toByte(),
                (len and 0xFF).toByte()
            )
            else -> byteArrayOf(
                0x81.toByte(), 127,
                0, 0, 0, 0,
                ((len ushr 24) and 0xFF).toByte(),
                ((len ushr 16) and 0xFF).toByte(),
                ((len ushr 8) and 0xFF).toByte(),
                (len and 0xFF).toByte()
            )
        }
        return header + payload
    }

    // ── UDP Zero-Config Discovery Beacon & Listener (KDE Connect / LocalSend mesh) ──

    private fun acquireMulticastLock() {
        runCatching {
            val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifiMgr?.createMulticastLock("SanDenLanLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            multicastLock = lock
        }.onFailure {
            Log.w(TAG, "MulticastLock unavailable: ${it.message}")
        }
    }

    private fun releaseMulticastLock() {
        runCatching {
            multicastLock?.let { if (it.isHeld) it.release() }
        }
        multicastLock = null
    }

    private fun upsertPeer(peer: PeerDevice) {
        if (peer.id.isBlank() || peer.id == localDeviceId) return
        val existing = peersMap[peer.id]
        val merged = if (existing != null && existing.pinned && !peer.pinned) {
            peer.copy(pinned = true)
        } else {
            peer
        }
        peersMap[peer.id] = merged
        _discoveredPeers.value = peersMap.values.sortedWith(
            compareBy<PeerDevice> { it.deviceType != "ANDROID" }.thenBy { it.name.lowercase() }
        )
        val shouldSync = existing == null || (existing.url != merged.url && merged.url.startsWith("http"))
        if (shouldSync && merged.url.startsWith("http")) {
            scope.launch { syncFromPeer(merged) }
        }
    }

    private fun startPeerPruner() {
        peerPruneJob?.cancel()
        peerPruneJob = scope.launch {
            while (_isRunning.value) {
                kotlinx.coroutines.delay(4000)
                val now = System.currentTimeMillis()
                var changed = false
                val keys = peersMap.keys.toList()
                for (k in keys) {
                    val p = peersMap[k] ?: continue
                    if (!p.pinned && (now - p.lastSeenMs) > PEER_STALE_MS) {
                        peersMap.remove(k)
                        changed = true
                    }
                }
                if (changed) {
                    _discoveredPeers.value = peersMap.values.sortedWith(
                        compareBy<PeerDevice> { it.deviceType != "ANDROID" }.thenBy { it.name.lowercase() }
                    )
                }
            }
        }
    }

    private fun startUdpListener(localPort: Int) {
        udpListenerJob?.cancel()
        udpListenerJob = scope.launch {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(DISCOVERY_UDP_PORT))
                }
                udpListenSocket = sock
                val buf = ByteArray(8192)
                while (_isRunning.value && !sock.isClosed) {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val jsonStr = String(pkt.data, pkt.offset, pkt.length, Charsets.UTF_8)
                    runCatching {
                        val obj = JSONObject(jsonStr)
                        if (obj.optString("app") != "SanDen") return@runCatching
                        val peerId = obj.optString("deviceId").ifBlank {
                            "${obj.optString("ip")}:${obj.optInt("port", DEFAULT_PORT)}"
                        }
                        if (peerId == localDeviceId) return@runCatching
                        val peerIp = obj.optString("ip").ifBlank { pkt.address?.hostAddress ?: "" }
                        val peerPort = obj.optInt("port", DEFAULT_PORT)
                        val selfIp = getLocalIpAddress()
                        if (peerIp == selfIp && peerPort == localPort) return@runCatching
                        val devType = obj.optString("deviceType", "ANDROID")
                        val peerUrl = obj.optString("url").ifBlank {
                            if (peerIp.isNotBlank()) "http://$peerIp:$peerPort" else ""
                        }
                        upsertPeer(
                            PeerDevice(
                                id = peerId,
                                name = obj.optString("deviceName", "SanDen Device"),
                                ip = peerIp,
                                port = peerPort,
                                url = peerUrl,
                                deviceType = devType,
                                lastSeenMs = System.currentTimeMillis()
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "UDP listener stopped: ${e.message}")
            } finally {
                runCatching { sock?.close() }
            }
        }
    }

    private fun startUdpBeacon(httpPort: Int) {
        udpBeaconJob?.cancel()
        udpBeaconJob = scope.launch {
            try {
                val sock = DatagramSocket().apply { broadcast = true }
                udpSocket = sock
                while (_isRunning.value && !sock.isClosed) {
                    val ip = getLocalIpAddress()
                    if (ip != null) {
                        val newUrl = "http://$ip:$httpPort"
                        if (_serverUrl.value != newUrl) _serverUrl.value = newUrl
                        val payload = JSONObject().apply {
                            put("app", "SanDen")
                            put("deviceId", localDeviceId)
                            put("deviceName", localDeviceName)
                            put("deviceType", "ANDROID")
                            put("ip", ip)
                            put("port", httpPort)
                            put("url", newUrl)
                        }.toString().toByteArray(Charsets.UTF_8)

                        // Send to global broadcast and all interface broadcast addresses
                        val targets = mutableSetOf("255.255.255.255")
                        runCatching {
                            val intfs = NetworkInterface.getNetworkInterfaces()
                            while (intfs != null && intfs.hasMoreElements()) {
                                val ni = intfs.nextElement()
                                if (!ni.isUp || ni.isLoopback) continue
                                for (ia in ni.interfaceAddresses) {
                                    ia.broadcast?.hostAddress?.let { targets.add(it) }
                                }
                            }
                        }
                        for (t in targets) {
                            runCatching {
                                val addr = InetAddress.getByName(t)
                                sock.send(DatagramPacket(payload, payload.size, addr, DISCOVERY_UDP_PORT))
                            }
                        }
                    }
                    kotlinx.coroutines.delay(1500)
                }
            } catch (e: Exception) {
                Log.d(TAG, "UDP beacon stopped: ${e.message}")
            }
        }
    }

    // ── NSD / mDNS Discovery (KDE Connect MdnsDiscovery fallback) ──────────

    private fun registerNsdService(port: Int) {
        try {
            val mgr = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            nsdManager = mgr
            val info = NsdServiceInfo().apply {
                serviceName = "SanDen-${Build.MODEL}".take(32)
                serviceType = SERVICE_TYPE
                setPort(port)
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "mDNS registered: ${serviceInfo.serviceName}")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }
            registrationListener = listener
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS registration skipped: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun startNsdDiscovery() {
        try {
            val mgr = nsdManager ?: (context.getSystemService(Context.NSD_SERVICE) as? NsdManager) ?: return
            nsdManager = mgr
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (!serviceInfo.serviceType.contains("_sanden")) return
                    if (serviceInfo.serviceName == "SanDen-${Build.MODEL}".take(32)) return
                    runCatching {
                        mgr.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {}
                            override fun onServiceResolved(si: NsdServiceInfo) {
                                val host = si.host?.hostAddress ?: return
                                val port = si.port
                                val selfIp = getLocalIpAddress()
                                if (host == selfIp || host == "127.0.0.1") return
                                scope.launch {
                                    addManualPeer("http://$host:$port")
                                }
                            }
                        })
                    }
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            discoveryListener = listener
            mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS discovery skipped: ${e.message}")
        }
    }

    private fun stopNsdDiscovery() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (_: Exception) {
        } finally {
            discoveryListener = null
        }
    }

    private fun unregisterNsdService() {
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (_: Exception) {
        } finally {
            registrationListener = null
        }
    }

    // ── Utilities ────────────────────────────────────────────────────────

    private fun getLocalIpAddress(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (!ip.startsWith("169.254.")) return ip
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun sendJson(output: OutputStream, code: Int, status: String, json: String) {
        writeResponse(output, code, status, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))
    }

    private fun writeResponse(output: OutputStream, code: Int, status: String, contentType: String, body: ByteArray) {
        val header = buildString {
            append("HTTP/1.1 $code $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun readExactBytes(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        readFully(input, buf)
        return buf
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val r = input.read(buf, total, buf.size - total)
            if (r == -1) break
            total += r
        }
        return total
    }

    private fun SyncMessageEntity.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("msgUid", msgUid.ifBlank { "${localDeviceId}_row_$id" })
        put("channel", channel)
        put("sender", if (sender == "PHONE" || sender == "SELF") localDeviceName else sender)
        put("targetPeerId", targetPeerId)
        put("type", type)
        put("textContent", textContent)
        put("fileName", fileName)
        put("fileSize", fileSize)
        put("fileHash", fileHash)
        put("mimeType", mimeType)
        put("timestamp", timestamp)
    }
}
