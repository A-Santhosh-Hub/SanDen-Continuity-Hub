package com.sanden.ui.home

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sanden.domain.model.FileCategory
import com.sanden.domain.model.VaultFile
import com.sanden.ui.components.AuroraGlassBackground
import com.sanden.ui.components.CategoryCard
import com.sanden.ui.components.LiquidGlassCard
import com.sanden.ui.components.TotalItemsCard
import com.sanden.ui.components.formatFileSize
import com.sanden.ui.theme.BrandBlue
import com.sanden.ui.theme.BrandBlueDeep
import com.sanden.ui.theme.BrandCyan
import com.sanden.ui.theme.BrandEmerald
import com.sanden.ui.theme.BrandGreenOnline
import com.sanden.ui.theme.BrandOrange
import com.sanden.ui.theme.BrandPurpleDeep
import com.sanden.ui.theme.BrandRed
import com.sanden.ui.theme.BrandRedDark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onCategoryClick: (FileCategory) -> Unit,
    onBrowseFoldersClick: () -> Unit,
    onConnectClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onFileClick: (VaultFile) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val categoryStats by viewModel.categoryStats.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val isHubRunning by viewModel.isHubRunning.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val connectedClients by viewModel.connectedClients.collectAsState()
    val discoveredPeers by viewModel.discoveredPeers.collectAsState()
    val deviceStorage by viewModel.deviceStorage.collectAsState()
    val recentActivity by viewModel.recentActivity.collectAsState()
    val vaultPath = viewModel.vaultPath

    val totalFiles = categoryStats.values.sumOf { it.first }
    val totalSize = categoryStats.values.sumOf { it.second }

    var showSendTextDialog by remember { mutableStateOf(false) }
    var quickTextInput by remember { mutableStateOf("") }
    var showPairDialog by remember { mutableStateOf(false) }
    var peerIpInput by remember { mutableStateOf("") }
    var showMoreSheet by remember { mutableStateOf(false) }

    val sendFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.sendFilesToPcAndVault(uris)
            Toast.makeText(context, "Sending & importing ${uris.size} file(s)…", Toast.LENGTH_SHORT).show()
        }
    }

    val importOnlyLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.importFiles(uris)
            Toast.makeText(context, "Importing ${uris.size} file(s) to Vault…", Toast.LENGTH_SHORT).show()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "scanSpin")
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spinAngle"
    )

    val formattedEndpoint = remember(serverUrl) {
        if (serverUrl.isBlank()) {
            "Hub Offline"
        } else {
            serverUrl.removePrefix("http://").removePrefix("https://").replace(":", " : ")
        }
    }

    val hasActivePc = connectedClients > 0 || discoveredPeers.any { it.deviceType == "PC" }
    val heroTitle = when {
        !isHubRunning -> "Hub Offline"
        hasActivePc -> "Connected to PC"
        discoveredPeers.isNotEmpty() -> "${discoveredPeers.size} Peer(s) Nearby"
        else -> "Continuity Hub Live"
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
            ) {
                // ── 1. Top Header Bar ────────────────────────────────────────────
                HomeHeaderBar(
                    isOnline = isHubRunning,
                    isScanning = isScanning,
                    spinAngle = spinAngle,
                    onOnlineClick = {
                        if (isHubRunning) onConnectClick() else viewModel.toggleHub()
                    },
                    onDeviceClick = onConnectClick,
                    onRefreshClick = { viewModel.rescan() },
                    onSettingsClick = onSettingsClick
                )

                AnimatedVisibility(visible = isScanning) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = BrandBlue,
                        trackColor = Color.Transparent
                    )
                }

                // ── 2. Scrollable Dashboard Content ──────────────────────────────
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 2.dp,
                        bottom = 14.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    // Hero Connection Banner
                    item {
                        HeroConnectionBanner(
                            title = heroTitle,
                            endpointText = formattedEndpoint,
                            isOnline = isHubRunning,
                            onClick = onConnectClick
                        )
                    }

                    // Vault Storage Summary Card
                    item {
                        VaultStorageSummaryCard(
                            vaultSizeBytes = totalSize,
                            deviceTotalBytes = deviceStorage.totalDeviceBytes,
                            deviceAvailableBytes = deviceStorage.availableDeviceBytes,
                            onManageClick = onBrowseFoldersClick
                        )
                    }

                    // 3x2 Category Grid (Images, Audio, Videos / Documents, Others, Total Items)
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                listOf(
                                    FileCategory.IMAGE,
                                    FileCategory.AUDIO,
                                    FileCategory.VIDEO
                                ).forEach { category ->
                                    val (count, size) = categoryStats[category] ?: (0 to 0L)
                                    CategoryCard(
                                        category = category,
                                        count = count,
                                        totalSizeBytes = size,
                                        onClick = { onCategoryClick(category) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                listOf(
                                    FileCategory.DOCUMENT,
                                    FileCategory.OTHER
                                ).forEach { category ->
                                    val (count, size) = categoryStats[category] ?: (0 to 0L)
                                    CategoryCard(
                                        category = category,
                                        count = count,
                                        totalSizeBytes = size,
                                        onClick = { onCategoryClick(category) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                TotalItemsCard(
                                    totalCount = totalFiles,
                                    onClick = onBrowseFoldersClick,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // Phone <-> Laptop Sync Banner
                    item {
                        PhoneLaptopSyncBanner(onClick = onConnectClick)
                    }

                    // Quick Actions Section
                    item {
                        QuickActionsSection(
                            onSendFile = { sendFileLauncher.launch(arrayOf("*/*")) },
                            onSendText = { showSendTextDialog = true },
                            onClipboard = {
                                val sent = viewModel.sendClipboardQuick()
                                Toast.makeText(
                                    context,
                                    if (sent) "Clipboard synced to Hub" else "Clipboard is empty",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onScanQr = { showPairDialog = true },
                            onMore = { showMoreSheet = true }
                        )
                    }

                    // Recent Activity Section
                    item {
                        RecentActivitySection(
                            items = recentActivity,
                            vaultPath = vaultPath,
                            onViewAllClick = {
                                if (recentActivity.firstOrNull()?.isFromSync == true) {
                                    onConnectClick()
                                } else {
                                    onBrowseFoldersClick()
                                }
                            },
                            onItemClick = { item ->
                                if (item.vaultFile != null) {
                                    onFileClick(item.vaultFile)
                                } else {
                                    onConnectClick()
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // ── Quick Send Text Dialog ───────────────────────────────────────────────────
    if (showSendTextDialog) {
        AlertDialog(
            onDismissRequest = { showSendTextDialog = false },
            title = { Text("Send Text / Snippet") },
            text = {
                OutlinedTextField(
                    value = quickTextInput,
                    onValueChange = { quickTextInput = it },
                    placeholder = { Text("Type message, link, or code snippet…") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (quickTextInput.isNotBlank()) {
                            viewModel.sendTextQuick(quickTextInput)
                            Toast.makeText(context, "Sent to Continuity Hub", Toast.LENGTH_SHORT).show()
                        }
                        quickTextInput = ""
                        showSendTextDialog = false
                    }
                ) {
                    Text("Send")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSendTextDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ── Pair / Connect Peer Dialog ───────────────────────────────────────────────
    if (showPairDialog) {
        AlertDialog(
            onDismissRequest = { showPairDialog = false },
            title = { Text("Pair & Connect Device") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (isHubRunning && serverUrl.isNotEmpty()) {
                            "Phone Hub URL:\n$serverUrl"
                        } else {
                            "Hub is currently offline."
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = BrandCyan
                        )
                    )
                    if (isHubRunning && serverUrl.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                viewModel.copyText("SanDen Hub URL", serverUrl)
                                Toast.makeText(context, "Copied $serverUrl", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy Hub URL")
                        }
                    }
                    OutlinedTextField(
                        value = peerIpInput,
                        onValueChange = { peerIpInput = it },
                        label = { Text("Connect to Peer IP / URL") },
                        placeholder = { Text("e.g., 192.168.1.25:54321") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ip = peerIpInput.trim()
                        if (ip.isNotEmpty()) {
                            viewModel.addManualPeer(ip) { res ->
                                res.onSuccess { peer ->
                                    Toast.makeText(context, "Paired with ${peer.name}", Toast.LENGTH_SHORT).show()
                                }.onFailure { err ->
                                    Toast.makeText(context, "Failed: ${err.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        showPairDialog = false
                    }
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPairDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // ── More Actions Sheet ───────────────────────────────────────────────────────
    if (showMoreSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMoreSheet = false },
            containerColor = Color(0xFF111827)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Vault & Hub Actions",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                MoreActionRow(
                    icon = Icons.Rounded.FolderOpen,
                    title = "Import Files to Vault Only",
                    subtitle = "Save privately without sending to PC",
                    onClick = {
                        showMoreSheet = false
                        importOnlyLauncher.launch(arrayOf("*/*"))
                    }
                )
                MoreActionRow(
                    icon = Icons.Rounded.Refresh,
                    title = "Rescan Drop Folder",
                    subtitle = vaultPath,
                    onClick = {
                        showMoreSheet = false
                        viewModel.rescan()
                    }
                )
                MoreActionRow(
                    icon = Icons.Rounded.Wifi,
                    title = if (isHubRunning) "Stop Wi-Fi Continuity Hub" else "Start Wi-Fi Continuity Hub",
                    subtitle = if (isHubRunning) "Currently listening at $formattedEndpoint" else "Offline",
                    onClick = {
                        showMoreSheet = false
                        viewModel.toggleHub()
                    }
                )
                MoreActionRow(
                    icon = Icons.Rounded.Settings,
                    title = "Storage Engine & Settings",
                    subtitle = "Switch Drop Folder / Private Import modes",
                    onClick = {
                        showMoreSheet = false
                        onSettingsClick()
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun HomeHeaderBar(
    isOnline: Boolean,
    isScanning: Boolean,
    spinAngle: Float,
    onOnlineClick: () -> Unit,
    onDeviceClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Brand Icon + Title + Subtitle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(BrandRed, BrandRedDark)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Security,
                    contentDescription = "SanDen",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.padding(end = 4.dp)) {
                Text(
                    text = "SanDen",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        letterSpacing = (-0.3).sp
                    ),
                    maxLines = 1
                )
                Text(
                    text = "Private File Vault & Continuity Hub",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.5.sp,
                        letterSpacing = (-0.15).sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Right Status Pill + Action Circles
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xFF101B23))
                    .border(
                        1.dp,
                        if (isOnline) BrandGreenOnline.copy(alpha = 0.35f)
                        else Color.White.copy(alpha = 0.12f),
                        CircleShape
                    )
                    .clickable(onClick = onOnlineClick)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (isOnline) BrandGreenOnline else Color(0xFF64748B))
                )
                Text(
                    text = if (isOnline) "Online" else "Offline",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        fontSize = 10.5.sp
                    )
                )
            }

            HeaderCircleButton(
                icon = Icons.Rounded.Smartphone,
                contentDescription = "Continuity Hub",
                onClick = onDeviceClick
            )
            HeaderCircleButton(
                icon = Icons.Rounded.Refresh,
                contentDescription = "Rescan",
                iconModifier = if (isScanning) Modifier.rotate(spinAngle) else Modifier,
                onClick = onRefreshClick
            )
            HeaderCircleButton(
                icon = Icons.Rounded.Settings,
                contentDescription = "Settings",
                onClick = onSettingsClick
            )
        }
    }
}

@Composable
private fun HeaderCircleButton(
    icon: ImageVector,
    contentDescription: String,
    iconModifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color(0xFF131B2C))
            .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = iconModifier.size(16.dp)
        )
    }
}

@Composable
private fun HeroConnectionBanner(
    title: String,
    endpointText: String,
    isOnline: Boolean,
    onClick: () -> Unit
) {
    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        containerColor = Color.Transparent,
        borderWidth = 1.dp,
        onClick = onClick
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF0E1A36),
                            Color(0xFF171E4A),
                            Color(0xFF2B1C5C)
                        )
                    )
                )
        ) {
            // Subtle flowing wave accents on the right side
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                val wave1 = Path().apply {
                    moveTo(w * 0.42f, h)
                    cubicTo(w * 0.62f, h * 0.35f, w * 0.78f, h * 0.85f, w, h * 0.18f)
                    lineTo(w, h)
                    close()
                }
                drawPath(
                    path = wave1,
                    color = Color(0xFF6366F1).copy(alpha = 0.22f)
                )
                val wave2 = Path().apply {
                    moveTo(w * 0.55f, h)
                    cubicTo(w * 0.72f, h * 0.55f, w * 0.86f, h * 0.90f, w, h * 0.42f)
                    lineTo(w, h)
                    close()
                }
                drawPath(
                    path = wave2,
                    color = Color(0xFF8B5CF6).copy(alpha = 0.24f)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Glowing blue circle badge with phone icon
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(BrandBlue.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(BrandBlue, BrandBlueDeep)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Smartphone,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 15.5.sp
                            )
                        )
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isOnline) BrandGreenOnline else Color(0xFF64748B))
                        )
                    }

                    Text(
                        text = endpointText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color(0xFFCBD5E1),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.5.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(1.dp))

                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.28f))
                            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Wifi,
                            contentDescription = null,
                            tint = BrandCyan,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "Wi-Fi",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Color.White,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = "Open Continuity Hub",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun VaultStorageSummaryCard(
    vaultSizeBytes: Long,
    deviceTotalBytes: Long,
    deviceAvailableBytes: Long,
    onManageClick: () -> Unit
) {
    val usedDeviceBytes = (deviceTotalBytes - deviceAvailableBytes).coerceAtLeast(0L)
    val usedPercent = if (deviceTotalBytes > 0L) {
        ((usedDeviceBytes.toDouble() / deviceTotalBytes.toDouble()) * 100.0).toInt().coerceIn(0, 100)
    } else {
        0
    }
    val barFraction = if (deviceTotalBytes > 0L) {
        (usedDeviceBytes.toFloat() / deviceTotalBytes.toFloat()).coerceIn(0.04f, 1f)
    } else {
        0f
    }

    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        containerColor = Color(0xFF0F1625)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1A2338))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = Color(0xFFCBD5E1),
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Vault Storage",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                fontSize = 14.5.sp
                            )
                        )
                        Text(
                            text = "Your files, your privacy",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        )
                    }
                }

                // "Manage >" pill button
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF14223B))
                        .border(1.dp, BrandBlue.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                        .clickable(onClick = onManageClick)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        tint = Color(0xFF60A5FA),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Manage",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color(0xFF93C5FD),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.5.sp
                        )
                    )
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = Color(0xFF93C5FD),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // Large size stat
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = formatFileSize(vaultSizeBytes),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 21.sp
                    )
                )
                val capacityText = if (deviceTotalBytes > 0L) {
                    "/ ${formatFileSize(deviceTotalBytes)} device (${formatFileSize(deviceAvailableBytes)} free)"
                } else {
                    "in vault"
                }
                Text(
                    text = capacityText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.5.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }

            // Progress bar + %
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1C2538))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(barFraction)
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(BrandCyan, BrandBlueDeep)
                                )
                            )
                    )
                }
                Text(
                    text = "$usedPercent%",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Color(0xFFCBD5E1),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.5.sp
                    )
                )
            }
        }
    }
}

@Composable
private fun PhoneLaptopSyncBanner(onClick: () -> Unit) {
    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        containerColor = Color.Transparent,
        borderWidth = 1.dp,
        onClick = onClick
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF11182F),
                            Color(0xFF181F44),
                            Color(0xFF212257)
                        )
                    )
                )
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                val wave = Path().apply {
                    moveTo(w * 0.45f, h)
                    cubicTo(w * 0.65f, h * 0.40f, w * 0.80f, h * 0.85f, w, h * 0.25f)
                    lineTo(w, h)
                    close()
                }
                drawPath(
                    path = wave,
                    color = BrandBlue.copy(alpha = 0.16f)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(BrandPurpleDeep, Color(0xFF6366F1))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LaptopMac,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Phone ↔ Laptop Sync",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 15.sp
                        )
                    )
                    Text(
                        text = "Chat, clipboard & Wi-Fi file transfer",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color(0xFF94A3B8),
                            fontSize = 11.5.sp
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickActionsSection(
    onSendFile: () -> Unit,
    onSendText: () -> Unit,
    onClipboard: () -> Unit,
    onScanQr: () -> Unit,
    onMore: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Bolt,
                contentDescription = null,
                tint = Color(0xFF94A3B8),
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "Quick Actions",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    fontSize = 14.sp
                )
            )
        }

        LiquidGlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            containerColor = Color(0xFF0E1422)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                QuickActionItem(
                    icon = Icons.AutoMirrored.Rounded.Send,
                    label = "Send File",
                    color = BrandBlueDeep,
                    onClick = onSendFile,
                    modifier = Modifier.weight(1f)
                )
                QuickActionItem(
                    icon = Icons.Rounded.ChatBubbleOutline,
                    label = "Send Text",
                    color = Color(0xFF059669),
                    onClick = onSendText,
                    modifier = Modifier.weight(1f)
                )
                QuickActionItem(
                    icon = Icons.Rounded.ContentPaste,
                    label = "Clipboard",
                    color = BrandPurpleDeep,
                    onClick = onClipboard,
                    modifier = Modifier.weight(1f)
                )
                QuickActionItem(
                    icon = Icons.Rounded.QrCodeScanner,
                    label = "Scan QR",
                    color = BrandOrange,
                    onClick = onScanQr,
                    modifier = Modifier.weight(1f)
                )
                QuickActionItem(
                    icon = Icons.Rounded.MoreHoriz,
                    label = "More",
                    color = Color(0xFF1E293B),
                    onClick = onMore,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun QuickActionItem(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                color = Color(0xFFCBD5E1),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            ),
            maxLines = 1
        )
    }
}

@Composable
private fun RecentActivitySection(
    items: List<RecentActivityItem>,
    vaultPath: String,
    onViewAllClick: () -> Unit,
    onItemClick: (RecentActivityItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Schedule,
                    contentDescription = null,
                    tint = Color(0xFF94A3B8),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Recent Activity",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                )
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onViewAllClick)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "View All",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                )
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = Color(0xFF94A3B8),
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        if (items.isEmpty()) {
            LiquidGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                containerColor = Color(0xFF0F1625),
                onClick = onViewAllClick
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1A2234)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FolderOpen,
                            contentDescription = null,
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Drop files to get started",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        )
                        Text(
                            text = vaultPath,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Color(0xFF94A3B8)
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        } else {
            val latest = items.first()
            LiquidGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                containerColor = Color(0xFF0F1625),
                onClick = { onItemClick(latest) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1A2234))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Description,
                            contentDescription = null,
                            tint = Color(0xFFCBD5E1),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = latest.title,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                fontSize = 13.5.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = latest.subtitle,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Color(0xFF94A3B8),
                                fontSize = 11.5.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = formatRelativeTime(latest.timestampMs),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(BrandEmerald),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF182235))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = BrandCyan,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = Color(0xFF94A3B8)
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = Color(0xFF94A3B8)
        )
    }
}

private fun formatRelativeTime(timestampMs: Long): String {
    if (timestampMs <= 0L) return "Just now"
    val diffMs = System.currentTimeMillis() - timestampMs
    return when {
        diffMs < 60_000L -> "Just now"
        diffMs < 3_600_000L -> "${diffMs / 60_000L}m ago"
        diffMs < 86_400_000L -> "${diffMs / 3_600_000L}h ago"
        else -> "${diffMs / 86_400_000L}d ago"
    }
}
