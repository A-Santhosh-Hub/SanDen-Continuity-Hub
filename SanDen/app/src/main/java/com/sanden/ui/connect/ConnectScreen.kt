package com.sanden.ui.connect

import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.sanden.data.db.SyncMessageEntity
import com.sanden.domain.model.VaultFile
import com.sanden.ui.components.AuroraGlassBackground
import com.sanden.ui.components.LiquidGlassCard
import com.sanden.ui.components.formatFileSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConnectScreen(
    onNavigateBack: () -> Unit,
    onOpenFile: (VaultFile) -> Unit = {},
    showBackButton: Boolean = true,
    viewModel: ConnectViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isRunning by viewModel.isRunning.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val connectedClients by viewModel.connectedClients.collectAsState()
    val discoveredPeers by viewModel.discoveredPeers.collectAsState()
    val selectedTargetId by viewModel.selectedTargetId.collectAsState()
    val currentChannel by viewModel.currentChannel.collectAsState()
    val channels by viewModel.channels.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val activeTransfers by viewModel.activeTransfers.collectAsState()
    val channelTransfers = remember(activeTransfers, currentChannel) {
        activeTransfers.filter { it.channel == currentChannel }
    }

    val textFieldState = rememberTextFieldState()
    var pendingLargeSnippet by remember { mutableStateOf<String?>(null) }
    var pendingAttachments by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showNewBranchDialog by remember { mutableStateOf(false) }
    var newBranchName by remember { mutableStateOf("") }
    var showAddPeerDialog by remember { mutableStateOf(false) }
    var peerIpInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val uiScope = rememberCoroutineScope()
    val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)

    LaunchedEffect(textFieldState.text) {
        val current = textFieldState.text.toString()
        if (current.length > 2000) {
            pendingLargeSnippet = current
            textFieldState.clearText()
        }
    }

    val receiveContentListener = remember {
        ReceiveContentListener { transferableContent: TransferableContent ->
            val capturedUris = mutableListOf<Uri>()
            val remaining = transferableContent.consume { item ->
                val uri = item.uri
                if (uri != null) {
                    capturedUris.add(uri)
                    true
                } else {
                    false
                }
            }
            if (capturedUris.isNotEmpty()) {
                pendingAttachments = (pendingAttachments + capturedUris).distinct()
            }
            remaining
        }
    }

    // Inline WhatsApp-style Audio Player state
    var playingMsgId by remember { mutableLongStateOf(-1L) }
    var isAudioPlaying by remember { mutableStateOf(false) }
    var audioProgress by remember { mutableFloatStateOf(0f) }
    var audioPositionMs by remember { mutableLongStateOf(0L) }
    var audioDurationMs by remember { mutableLongStateOf(0L) }
    val mediaPlayer = remember { MediaPlayer() }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                if (mediaPlayer.isPlaying) mediaPlayer.stop()
                mediaPlayer.release()
            }
        }
    }

    LaunchedEffect(playingMsgId, isAudioPlaying) {
        while (playingMsgId != -1L && isAudioPlaying) {
            runCatching {
                if (mediaPlayer.isPlaying) {
                    val dur = mediaPlayer.duration.coerceAtLeast(1)
                    val pos = mediaPlayer.currentPosition
                    audioDurationMs = dur.toLong()
                    audioPositionMs = pos.toLong()
                    audioProgress = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                }
            }
            delay(200)
        }
    }

    fun toggleAudio(msg: SyncMessageEntity) {
        if (playingMsgId == msg.id) {
            if (isAudioPlaying) {
                runCatching { mediaPlayer.pause() }
                isAudioPlaying = false
            } else {
                runCatching { mediaPlayer.start() }
                isAudioPlaying = true
            }
            return
        }

        val file = File(msg.filePath)
        if (!file.exists()) {
            Toast.makeText(context, "Audio file not found", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            mediaPlayer.reset()
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.setOnCompletionListener {
                isAudioPlaying = false
                audioProgress = 0f
                audioPositionMs = 0L
            }
            mediaPlayer.prepare()
            mediaPlayer.start()
            playingMsgId = msg.id
            audioDurationMs = mediaPlayer.duration.toLong().coerceAtLeast(1L)
            audioPositionMs = 0L
            audioProgress = 0f
            isAudioPlaying = true
        }.onFailure {
            Toast.makeText(context, "Cannot play audio", Toast.LENGTH_SHORT).show()
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.sendFiles(uris)
        }
    }

    LaunchedEffect(messages.size, imeBottomPx) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    AuroraGlassBackground(modifier = modifier) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = Color.Transparent
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .statusBarsPadding()
                    .imePadding()
            ) {
                // Ultra-Compact WhatsApp-style Header Strip (Replaces bloated TopAppBar + Separate IP Card)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (showBackButton) {
                            IconButton(onClick = onNavigateBack, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    if (isRunning && serverUrl.isNotEmpty()) {
                                        viewModel.copyToClipboard(serverUrl)
                                        Toast.makeText(context, "Copied: $serverUrl", Toast.LENGTH_SHORT).show()
                                    } else {
                                        viewModel.toggleHub()
                                    }
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    text = "Continuity Hub",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (isRunning) Color(0xFF22C55E) else MaterialTheme.colorScheme.error)
                                )
                                Text(
                                    text = if (isRunning) "${discoveredPeers.size} peer${if (discoveredPeers.size == 1) "" else "s"}" else "Offline",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        color = if (isRunning) Color(0xFF22C55E) else MaterialTheme.colorScheme.error
                                    )
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = if (isRunning && serverUrl.isNotEmpty()) serverUrl.removePrefix("http://") else "Tap to start Wi-Fi Hub",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.5.sp,
                                        fontFamily = if (isRunning) FontFamily.Monospace else FontFamily.Default,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isRunning && serverUrl.isNotEmpty()) {
                                    Icon(
                                        imageVector = Icons.Rounded.ContentCopy,
                                        contentDescription = "Copy IP",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(11.dp)
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconButton(
                            onClick = {
                                viewModel.syncMeshNow()
                                Toast.makeText(context, "Syncing missed messages & files across mesh…", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Sync,
                                contentDescription = "Sync Mesh",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.toggleHub() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PowerSettingsNew,
                                contentDescription = "Toggle Hub",
                                tint = if (isRunning) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.clearHistory() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Rounded.DeleteSweep,
                                contentDescription = "Clear Stream",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Slim Device Target Row (All Group + Peers + Peer IP)
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    item {
                        val allSelected = selectedTargetId == null
                        Row(
                            modifier = Modifier
                                .height(24.dp)
                                .clip(CircleShape)
                                .background(
                                    if (allSelected) MaterialTheme.colorScheme.secondary
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                                )
                                .border(
                                    0.8.dp,
                                    if (allSelected) MaterialTheme.colorScheme.secondary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                    CircleShape
                                )
                                .clickable { viewModel.selectTargetPeer(null) }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Group,
                                contentDescription = null,
                                tint = if (allSelected) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = if (discoveredPeers.isEmpty()) "All (Scan…)" else "All Group (${discoveredPeers.size})",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (allSelected) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }
                    }

                    items(discoveredPeers, key = { it.id }) { peer ->
                        val isPicked = selectedTargetId == peer.id
                        Row(
                            modifier = Modifier
                                .height(24.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isPicked) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                                )
                                .border(
                                    0.8.dp,
                                    if (isPicked) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                    CircleShape
                                )
                                .clickable { viewModel.selectTargetPeer(peer.id) }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (peer.deviceType == "PC") Icons.Rounded.Laptop else Icons.Rounded.PhoneAndroid,
                                contentDescription = null,
                                tint = if (isPicked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = peer.name,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isPicked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                ),
                                maxLines = 1
                            )
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier
                                .height(24.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f))
                                .border(
                                    0.8.dp,
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f),
                                    CircleShape
                                )
                                .clickable {
                                    peerIpInput = ""
                                    showAddPeerDialog = true
                                }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AddLink,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = "+ Peer IP",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }
                }

                // Slim Project Branches Row (General + Custom Projects + New Project)
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    items(channels) { branch ->
                        val selected = branch == currentChannel
                        Row(
                            modifier = Modifier
                                .height(24.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                                )
                                .border(
                                    0.8.dp,
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                    CircleShape
                                )
                                .clickable { viewModel.selectChannel(branch) }
                                .padding(horizontal = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FolderSpecial,
                                contentDescription = null,
                                tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = branch,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier
                                .height(24.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f))
                                .border(
                                    0.8.dp,
                                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f),
                                    CircleShape
                                )
                                .clickable {
                                    newBranchName = ""
                                    showNewBranchDialog = true
                                }
                                .padding(horizontal = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "New Project Branch",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = "New Project",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            )
                        }
                    }
                }

                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = Color.White.copy(alpha = 0.08f),
                    modifier = Modifier.padding(top = 2.dp)
                )

                val selectedPeerObj = remember(selectedTargetId, discoveredPeers) {
                    selectedTargetId?.let { tid -> discoveredPeers.firstOrNull { it.id == tid } }
                }
                if (selectedPeerObj != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Private 1-on-1 with ${selectedPeerObj.name} • Hidden from Laptop & others",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "Group",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { viewModel.selectTargetPeer(null) }
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }

                // Live WhatsApp-style Rich Media Chat Stream + Top/Bottom Jump Buttons
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 10.dp,
                            bottom = 16.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (messages.isEmpty() && channelTransfers.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 56.dp, horizontal = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (selectedPeerObj != null)
                                            "Private peer-to-peer chat with ${selectedPeerObj.name}.\nMessages & files sent here stay only between this phone and ${selectedPeerObj.name} (hidden from Laptop & other devices)."
                                        else
                                            "Send messages, clipboard snippets, photos, videos, or audio between your Phone & PC.\nOpen SanDen-PC on your laptop — it connects automatically!",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                            }
                        } else {
                            items(messages, key = { it.id }) { msg ->
                                WhatsAppStyleBubble(
                                    message = msg,
                                    isPlayingThisAudio = playingMsgId == msg.id && isAudioPlaying,
                                    audioProgress = if (playingMsgId == msg.id) audioProgress else 0f,
                                    audioPositionMs = if (playingMsgId == msg.id) audioPositionMs else 0L,
                                    audioDurationMs = if (playingMsgId == msg.id) audioDurationMs else 0L,
                                    onToggleAudio = { toggleAudio(msg) },
                                    onSeekAudio = { fraction ->
                                        if (playingMsgId == msg.id && audioDurationMs > 0) {
                                            val target = (fraction * audioDurationMs).toInt()
                                            runCatching { mediaPlayer.seekTo(target) }
                                            audioProgress = fraction
                                        }
                                    },
                                    onOpenMedia = {
                                        viewModel.openFileInVault(
                                            path = msg.filePath,
                                            onFound = onOpenFile,
                                            onNotFound = {
                                                Toast.makeText(context, "File not found in vault", Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    },
                                    onCopyText = {
                                        viewModel.copyToClipboard(msg.textContent)
                                        Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }

                            items(channelTransfers, key = { it.id }) { tx ->
                                LiveTransferBubble(transfer = tx)
                            }
                        }
                    }

                    if (messages.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 12.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f), CircleShape)
                                    .clickable {
                                        uiScope.launch { listState.animateScrollToItem(0) }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ArrowUpward,
                                    contentDescription = "Jump to Top",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f), CircleShape)
                                    .clickable {
                                        uiScope.launch {
                                            listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ArrowDownward,
                                    contentDescription = "Jump to Bottom",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // Pending large paste chip (prevents composer lag on 5000+ line pastes)
                pendingLargeSnippet?.let { large ->
                    val lines = large.count { it == '\n' } + 1
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f))
                            .border(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Large snippet ready ($lines lines, ${large.length} chars) — tap Send",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Clear snippet",
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { pendingLargeSnippet = null }
                        )
                    }
                }

                // Pending Keyboard-Pasted Attachments Strip (Images / GIFs / Files)
                if (pendingAttachments.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(pendingAttachments) { uri ->
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                        RoundedCornerShape(12.dp)
                                    )
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(uri)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = "Pasted attachment",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(3.dp)
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.7f))
                                        .clickable {
                                            pendingAttachments = pendingAttachments.filterNot { it == uri }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Remove attachment",
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Bottom Slim WhatsApp-style Composer Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LiquidGlassCard(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    val sent = viewModel.sendClipboard()
                                    Toast.makeText(
                                        context,
                                        if (sent) "Clipboard sent!" else "Clipboard is empty",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.ContentPaste,
                                    contentDescription = "Send Clipboard",
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (textFieldState.text.isEmpty()) {
                                    Text(
                                        text = "Message",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 14.sp
                                    )
                                }
                                BasicTextField(
                                    state = textFieldState,
                                    textStyle = TextStyle(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 14.sp
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    keyboardOptions = KeyboardOptions(
                                        capitalization = KeyboardCapitalization.Sentences
                                    ),
                                    lineLimits = TextFieldLineLimits.MultiLine(
                                        minHeightInLines = 1,
                                        maxHeightInLines = 4
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .contentReceiver(receiveContentListener)
                                )
                            }

                            IconButton(
                                onClick = { filePicker.launch(arrayOf("*/*")) },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.AttachFile,
                                    contentDescription = "Attach File",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable {
                                val filesToSend = pendingAttachments
                                val large = pendingLargeSnippet
                                val typed = textFieldState.text.toString()

                                if (filesToSend.isNotEmpty()) {
                                    viewModel.sendFiles(filesToSend)
                                    pendingAttachments = emptyList()
                                }
                                if (!large.isNullOrBlank()) {
                                    viewModel.sendText(large)
                                    pendingLargeSnippet = null
                                }
                                if (typed.isNotBlank()) {
                                    viewModel.sendText(typed)
                                    textFieldState.clearText()
                                }
                                if (filesToSend.isEmpty() && large.isNullOrBlank() && typed.isBlank()) {
                                    filePicker.launch(arrayOf("*/*"))
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "Send",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }

    if (showNewBranchDialog) {
        AlertDialog(
            onDismissRequest = { showNewBranchDialog = false },
            title = { Text("New Project Branch") },
            text = {
                OutlinedTextField(
                    value = newBranchName,
                    onValueChange = { newBranchName = it },
                    placeholder = { Text("e.g. SanTube, ClientApp, API Keys") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createChannel(newBranchName)
                        showNewBranchDialog = false
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewBranchDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAddPeerDialog) {
        AlertDialog(
            onDismissRequest = { showAddPeerDialog = false },
            title = { Text("Connect Nearby Mobile by IP") },
            text = {
                OutlinedTextField(
                    value = peerIpInput,
                    onValueChange = { peerIpInput = it },
                    placeholder = { Text("e.g. 192.168.1.42 or http://192.168.1.42:54321") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ip = peerIpInput.trim()
                        showAddPeerDialog = false
                        viewModel.addManualPeer(ip) { res ->
                            res.onSuccess { p ->
                                Toast.makeText(context, "Paired with ${p.name}", Toast.LENGTH_SHORT).show()
                            }.onFailure { err ->
                                Toast.makeText(context, "Could not reach peer: ${err.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddPeerDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private enum class MediaKind { IMAGE, VIDEO, AUDIO, DOCUMENT }

private fun detectMediaKind(msg: SyncMessageEntity): MediaKind {
    val mime = msg.mimeType.lowercase()
    val name = msg.fileName.lowercase()
    return when {
        mime.startsWith("image/") || name.endsWithAny(".jpg", ".jpeg", ".png", ".webp", ".gif", ".heic") -> MediaKind.IMAGE
        mime.startsWith("video/") || name.endsWithAny(".mp4", ".mkv", ".webm", ".mov", ".avi") -> MediaKind.VIDEO
        mime.startsWith("audio/") || name.endsWithAny(".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".flac") -> MediaKind.AUDIO
        else -> MediaKind.DOCUMENT
    }
}

private fun String.endsWithAny(vararg exts: String): Boolean = exts.any { this.endsWith(it) }

@Composable
private fun WhatsAppStyleBubble(
    message: SyncMessageEntity,
    isPlayingThisAudio: Boolean,
    audioProgress: Float,
    audioPositionMs: Long,
    audioDurationMs: Long,
    onToggleAudio: () -> Unit,
    onSeekAudio: (Float) -> Unit,
    onOpenMedia: () -> Unit,
    onCopyText: () -> Unit
) {
    val isPhone = message.sender == "PHONE" || message.sender == "SELF"
    val senderLabel = when (message.sender) {
        "PHONE", "SELF" -> "YOU"
        "LAPTOP" -> "WINDOWS PC"
        else -> message.sender
    }
    val isDark = isSystemInDarkTheme()
    val bubbleColor = if (isPhone) {
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.24f else 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isDark) 0.78f else 0.90f)
    }
    val bubbleShape = if (isPhone) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
    }
    val timeStr = remember(message.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isPhone) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 120.dp, max = 304.dp)
                .clip(bubbleShape)
                .background(bubbleColor)
                .border(
                    0.8.dp,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                    bubbleShape
                )
                .padding(if (message.type == "FILE") 5.dp else 9.dp)
        ) {
            if (message.type == "FILE") {
                if (!isPhone) {
                    Text(
                        text = senderLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.5.sp
                        ),
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
                when (detectMediaKind(message)) {
                    MediaKind.IMAGE -> {
                        ImageMediaBubble(message = message, timeStr = timeStr, onClick = onOpenMedia)
                    }
                    MediaKind.VIDEO -> {
                        VideoMediaBubble(message = message, timeStr = timeStr, onClick = onOpenMedia)
                    }
                    MediaKind.AUDIO -> {
                        AudioMediaBubble(
                            message = message,
                            timeStr = timeStr,
                            isPlaying = isPlayingThisAudio,
                            progress = audioProgress,
                            positionMs = audioPositionMs,
                            durationMs = audioDurationMs,
                            onTogglePlay = onToggleAudio,
                            onSeek = onSeekAudio
                        )
                    }
                    MediaKind.DOCUMENT -> {
                        DocumentMediaBubble(message = message, timeStr = timeStr, onClick = onOpenMedia)
                    }
                }
            } else {
                val fullText = message.textContent
                val isLarge = fullText.length > 600 || fullText.count { it == '\n' } > 12
                var expanded by remember(message.id) { mutableStateOf(false) }
                val displayedText = remember(fullText, isLarge, expanded) {
                    if (!isLarge || expanded) fullText
                    else {
                        val lines = fullText.lines()
                        if (lines.size > 10) lines.take(10).joinToString("\n") + "\n…"
                        else fullText.take(500) + "…"
                    }
                }

                // Compact Header (Sender + single subtle Copy action)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (message.type == "CLIPBOARD") "CLIPBOARD • $senderLabel" else senderLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (message.type == "CLIPBOARD") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.5.sp
                        )
                    )
                    CopyButtonChip(onClick = onCopyText)
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = displayedText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 18.sp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp),
                    horizontalArrangement = if (isLarge) Arrangement.SpaceBetween else Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isLarge) {
                        Text(
                            text = if (expanded) "Show less" else "Expand all (${fullText.lines().size} lines)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            ),
                            modifier = Modifier.clickable { expanded = !expanded }
                        )
                    }
                    Text(
                        text = timeStr,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 9.5.sp
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun CopyButtonChip(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.ContentCopy,
            contentDescription = "Copy",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = "Copy",
            style = MaterialTheme.typography.labelSmall.copy(
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp
            )
        )
    }
}

@Composable
private fun ImageMediaBubble(
    message: SyncMessageEntity,
    timeStr: String,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.25f))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(File(message.filePath))
                .crossfade(true)
                .build(),
            contentDescription = message.fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))
                    )
                )
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.fileName,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = timeStr,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                )
            }
        }
    }
}

@Composable
private fun VideoMediaBubble(
    message: SyncMessageEntity,
    timeStr: String,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF121622))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(File(message.filePath))
                .decoderFactory(VideoFrameDecoder.Factory())
                .videoFrameMillis(500L)
                .crossfade(true)
                .build(),
            contentDescription = message.fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // Top-left crisp VIDEO pill badge
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(7.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Videocam,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(11.dp)
            )
            Text(
                text = formatFileSize(message.fileSize),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
            )
        }
        // Slim frosted Play button overlay
        Box(
            modifier = Modifier
                .size(44.dp)
                .align(Alignment.Center)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.56f))
                .border(1.dp, Color.White.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Play Video",
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                    )
                )
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.fileName,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = timeStr,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp)
                )
            }
        }
    }
}

@Composable
private fun AudioMediaBubble(
    message: SyncMessageEntity,
    timeStr: String,
    isPlaying: Boolean,
    progress: Float,
    positionMs: Long,
    durationMs: Long,
    onTogglePlay: () -> Unit,
    onSeek: (Float) -> Unit
) {
    // Deterministic lightweight waveform heights per audio file (WhatsApp / Telegram slim style)
    val bars = remember(message.fileName) {
        val seed = message.fileName.hashCode()
        FloatArray(28) { idx ->
            val h = abs(((seed * (idx + 7)) xor (idx * 31)) % 100) / 100f
            0.25f + (h * 0.75f)
        }
    }
    val activeAccent = MaterialTheme.colorScheme.primary
    val inactiveBarColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 5.dp, vertical = 3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(activeAccent)
                .clickable(onClick = onTogglePlay),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message.fileName,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            // Slim interactive waveform bar (replaces bulky Android Slider!)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .pointerInput(message.id) {
                        detectTapGestures { offset ->
                            if (size.width > 0) {
                                onSeek((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    }
                    .pointerInput(message.id) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            if (size.width > 0) {
                                onSeek((change.position.x / size.width.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    }
            ) {
                val count = bars.size
                val slotW = size.width / count
                val barW = (slotW * 0.56f).coerceAtLeast(2f)
                val maxH = size.height * 0.9f
                val clampedProg = progress.coerceIn(0f, 1f)
                for (i in 0 until count) {
                    val frac = i.toFloat() / count.toFloat()
                    val barH = (bars[i] * maxH).coerceAtLeast(3f)
                    val x = i * slotW + (slotW - barW) / 2f
                    val y = (size.height - barH) / 2f
                    drawRoundRect(
                        color = if (frac <= clampedProg && clampedProg > 0f) activeAccent else inactiveBarColor,
                        topLeft = Offset(x, y),
                        size = Size(barW, barH),
                        cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (durationMs > 0) "${fmtMs(positionMs)} / ${fmtMs(durationMs)}" else formatFileSize(message.fileSize),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.5.sp
                    )
                )
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.5.sp
                    )
                )
            }
        }
    }
}

@Composable
private fun DocumentMediaBubble(
    message: SyncMessageEntity,
    timeStr: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.InsertDriveFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message.fileName,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatFileSize(message.fileSize),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                )
            }
        }
    }
}

private fun fmtMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

@Composable
private fun LiveTransferBubble(transfer: com.sanden.data.transfer.LanTransferHub.ActiveTransfer) {
    val isSending = transfer.direction == "SENDING"
    val isDark = isSystemInDarkTheme()
    val bubbleColor = if (isSending) {
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.24f else 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isDark) 0.78f else 0.90f)
    }
    val pct = if (transfer.totalBytes > 0) (transfer.progress * 100).toInt() else -1
    val statusLine = buildString {
        append(if (isSending) "Sending" else "Receiving")
        if (pct >= 0) append(" • $pct%")
        if (transfer.bytesTransferred > 0) {
            append(" (${formatFileSize(transfer.bytesTransferred)}")
            if (transfer.totalBytes > 0) append(" / ${formatFileSize(transfer.totalBytes)}")
            append(")")
        }
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isSending) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 220.dp, max = 300.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(bubbleColor)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = transfer.fileName,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (pct >= 0) {
                LinearProgressIndicator(
                    progress = { transfer.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = statusLine,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}
