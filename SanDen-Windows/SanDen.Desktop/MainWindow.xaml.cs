using System.Collections.ObjectModel;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Microsoft.Win32;
using SanDen.Desktop.Models;
using SanDen.Desktop.Services;

namespace SanDen.Desktop;

public partial class MainWindow : Window
{
    private const string SanStudioUrl = "https://sanstudio-hub.github.io/in/";

    private readonly ContinuityHubService _hub = new();
    private readonly ObservableCollection<PeerDevice> _peers = [];
    private readonly ObservableCollection<ProjectItem> _projects = [];
    private readonly ObservableCollection<BranchItem> _branches = [];
    private readonly ObservableCollection<SyncMessageViewModel> _displayedMessages = [];
    private readonly ObservableCollection<SyncMessageViewModel> _clipboardMessages = [];
    private readonly ObservableCollection<VaultFileItem> _allVaultFiles = [];
    private readonly ObservableCollection<VaultFileItem> _recentVaultFiles = [];
    private readonly ObservableCollection<TransferProgressItem> _transfers = [];

    private List<SyncMessageViewModel> _rawChannelMessages = [];
    private string _activeBaseUrl = "";
    private string _activeChannel = "General";
    private string _activeBranch = "main";
    private string _searchFilter = "";
    private string _modalMode = ""; // "project" or "branch"
    private bool _isPrivateChatMode;
    private long _seenPrivateMaxId;
    private bool _toastSwitchesToPrivate;
    private readonly HashSet<long> _autoCopiedClipboardIds = [];
    private readonly HashSet<string> _thumbLoadingKeys = [];
    private readonly Dictionary<string, string> _localSentPaths = new(StringComparer.OrdinalIgnoreCase);
    private DispatcherTimer? _toastTimer;

    // In-App Media Lightbox State
    private List<LightboxEntry> _lightboxItems = [];
    private int _lightboxIndex = -1;
    private DispatcherTimer? _videoScrubTimer;
    private bool _isDraggingVideoSeek;
    private bool _isVideoPlaying;

    private sealed class LightboxEntry
    {
        public string Title { get; set; } = "";
        public string SizeDisplay { get; set; } = "";
        public long SizeBytes { get; set; }
        public bool IsVideo { get; set; }
        public string DownloadEndpoint { get; set; } = "";
        public string CacheFileName { get; set; } = "";
        public string? LocalPath { get; set; }
        public string FileHash { get; set; } = "";
        public ImageSource? Thumbnail { get; set; }
        public string SourceLabel { get; set; } = "";
    }

    public MainWindow()
    {
        InitializeComponent();

        ProjectsListControl.ItemsSource = _projects;
        BranchesListControl.ItemsSource = _branches;
        ChatMessagesControl.ItemsSource = _displayedMessages;
        ClipboardListControl.ItemsSource = _clipboardMessages;
        FullVaultItemsControl.ItemsSource = _allVaultFiles;
        RecentFilesSidebarControl.ItemsSource = _recentVaultFiles;
        TransfersListControl.ItemsSource = _transfers;
        DiscoveredPeersControl.ItemsSource = _peers;

        InitializeDefaults();
        UpdateHiddenVaultToggleUi();
        WireServiceEvents();

        DataObject.AddPastingHandler(ComposerTextBox, OnComposerPaste);

        Loaded += (_, _) =>
        {
            _hub.StartBackgroundEngine(() => _activeBaseUrl, () => _activeChannel);
            ComposerTextBox.Focus();

            var snapshotPath = Environment.GetEnvironmentVariable("SANDEN_SNAPSHOT_PATH");
            if (!string.IsNullOrWhiteSpace(snapshotPath))
            {
                _ = CaptureDiagnosticsSnapshotAsync(snapshotPath);
            }
        };

        Closed += (_, _) => _hub.Dispose();
    }

    private async Task CaptureDiagnosticsSnapshotAsync(string pngPath)
    {
        try
        {
            await Task.Delay(8000);
            if (Environment.GetEnvironmentVariable("SANDEN_OPEN_PRIVATE") == "1")
            {
                SwitchChatScope(true);
                await Task.Delay(500);
            }
            if (Environment.GetEnvironmentVariable("SANDEN_OPEN_LIGHTBOX") == "1")
            {
                var vidMsg = _displayedMessages.FirstOrDefault(m => m.IsVideoFile)
                             ?? _displayedMessages.FirstOrDefault(m => m.IsMediaFile);
                if (vidMsg != null)
                {
                    OpenMediaInLightbox(vidMsg);
                    await Task.Delay(3500);
                }
            }
            UpdateLayout();
            if (Content is FrameworkElement root && root.ActualWidth > 0 && root.ActualHeight > 0)
            {
                double dpiScale = 1.25;
                int pixelW = (int)Math.Round(root.ActualWidth * dpiScale);
                int pixelH = (int)Math.Round(root.ActualHeight * dpiScale);
                var rtb = new RenderTargetBitmap(pixelW, pixelH, 96 * dpiScale, 96 * dpiScale, PixelFormats.Pbgra32);
                rtb.Render(root);
                var encoder = new PngBitmapEncoder();
                encoder.Frames.Add(BitmapFrame.Create(rtb));
                Directory.CreateDirectory(Path.GetDirectoryName(pngPath)!);
                using (var fs = File.Create(pngPath))
                {
                    encoder.Save(fs);
                }

                var infoJson = System.Text.Json.JsonSerializer.Serialize(new
                {
                    connectedPhone = ConnectedPhoneNameText.Text,
                    endpoint = ConnectedPhoneIpText.Text,
                    badge = ConnectedBadgeLabel.Text,
                    liveStatus = LiveStatusText.Text,
                    peersCount = _peers.Count,
                    projects = _projects.Select(p => p.Name).ToArray(),
                    messagesCount = _displayedMessages.Count,
                    mediaMessagesCount = _displayedMessages.Count(m => m.IsMediaFile),
                    videoMessagesCount = _displayedMessages.Count(m => m.IsVideoFile),
                    mediaThumbsLoaded = _displayedMessages.Count(m => m.IsMediaFile && m.HasThumbnail),
                    videoThumbsLoaded = _displayedMessages.Count(m => m.IsVideoFile && m.HasThumbnail),
                    videoDurationSample = _displayedMessages.FirstOrDefault(m => m.IsVideoFile)?.MediaBadgeText ?? "",
                    isPrivateMode = _isPrivateChatMode,
                    privateMessagesCount = _rawChannelMessages.Count(m => !string.IsNullOrEmpty(m.TargetPeerId)),
                    groupMessagesCount = _rawChannelMessages.Count(m => string.IsNullOrEmpty(m.TargetPeerId)),
                    privateBadgeText = ScopePrivateText.Text,
                    vaultFilesCount = _allVaultFiles.Count,
                    recentThumbsLoaded = _recentVaultFiles.Count(f => f.HasThumbnail),
                    vaultSidebarStat = SidebarVaultStatText.Text
                }, new System.Text.Json.JsonSerializerOptions { WriteIndented = true });
                File.WriteAllText(pngPath + ".json", infoJson);

                // Optional second screenshot verifying WhatsApp-style in-app video/media Lightbox overlay
                var lightboxPng = Environment.GetEnvironmentVariable("SANDEN_LIGHTBOX_SNAPSHOT_PATH");
                var sampleVideo = _displayedMessages.LastOrDefault(m => m.IsVideoFile) ?? _displayedMessages.LastOrDefault(m => m.IsMediaFile);
                if (!string.IsNullOrWhiteSpace(lightboxPng) && sampleVideo != null)
                {
                    OpenMediaInLightbox(sampleVideo);
                    await Task.Delay(3500);
                    UpdateLayout();
                    var lbRtb = new RenderTargetBitmap(pixelW, pixelH, 96 * dpiScale, 96 * dpiScale, PixelFormats.Pbgra32);
                    lbRtb.Render(root);
                    var lbEnc = new PngBitmapEncoder();
                    lbEnc.Frames.Add(BitmapFrame.Create(lbRtb));
                    using var lbFs = File.Create(lightboxPng);
                    lbEnc.Save(lbFs);
                }
            }
        }
        catch (Exception ex)
        {
            try { File.WriteAllText(pngPath + ".err.txt", ex.ToString()); } catch { }
        }
        finally
        {
            Close();
        }
    }

    private void InitializeDefaults()
    {
        _projects.Add(new ProjectItem { Name = "General", IsSelected = true });
        _projects.Add(new ProjectItem { Name = "Development", IsSelected = false });

        _branches.Add(new BranchItem { Name = "main", IsSelected = true });
        _branches.Add(new BranchItem { Name = "feature/ui", IsSelected = false });
        _branches.Add(new BranchItem { Name = "feature/vault", IsSelected = false });

        EmptyChatPlaceholder.Visibility = Visibility.Visible;
    }

    private void WireServiceEvents()
    {
        _hub.PeerDiscoveredOrUpdated += peer => Dispatcher.InvokeAsync(() => OnPeerUpdated(peer));
        _hub.ChannelsUpdated += channels => Dispatcher.InvokeAsync(() => OnChannelsUpdated(channels));
        _hub.MessagesFetched += messages => Dispatcher.InvokeAsync(() => OnMessagesUpdated(messages, scrollIfNew: false));
        _hub.LiveMessagePushed += msg => Dispatcher.InvokeAsync(() => OnLiveMessagePushed(msg));
        _hub.VaultFilesFetched += files => Dispatcher.InvokeAsync(() => OnVaultFilesUpdated(files));
        _hub.ToastRequested += (msg, isErr) => Dispatcher.InvokeAsync(() => ShowToast(msg, isErr));
    }

    private void OnPeerUpdated(PeerDevice incoming)
    {
        var existing = _peers.FirstOrDefault(p => p.Id == incoming.Id || p.Url == incoming.Url);
        if (existing == null)
        {
            _peers.Add(incoming);
            existing = incoming;
        }
        else
        {
            existing.Name = incoming.Name;
            existing.Ip = incoming.Ip;
            existing.Port = incoming.Port;
            existing.Url = incoming.Url;
            existing.IsUsb = incoming.IsUsb;
            if (incoming.BatteryPct >= 0) existing.BatteryPct = incoming.BatteryPct;
            if (incoming.VaultUsedBytes > 0 || incoming.StorageTotalBytes > 0)
            {
                existing.VaultUsedBytes = incoming.VaultUsedBytes;
                existing.VaultFileCount = incoming.VaultFileCount;
                existing.StorageTotalBytes = incoming.StorageTotalBytes;
                existing.StorageFreeBytes = incoming.StorageFreeBytes;
            }
            existing.LastSeen = DateTime.UtcNow;
        }

        if (string.IsNullOrEmpty(_activeBaseUrl))
        {
            _activeBaseUrl = existing.Url;
            ManualIpTextBox.Text = existing.Url;
            _ = Task.Run(async () =>
            {
                await _hub.FetchChannelsAsync(_activeBaseUrl);
                await _hub.FetchMessagesAsync(_activeBaseUrl, _activeChannel);
                await _hub.FetchVaultFilesAsync(_activeBaseUrl);
            });
        }

        if (_activeBaseUrl == existing.Url)
        {
            UpdateConnectedDeviceCard(existing);
        }

        AllDevicesSubText.Text = _isPrivateChatMode ? "🔒 Private 1-on-1 • " : $"All devices ({_peers.Count}) • ";
        PeerCountBadgeText.Text = _peers.Count.ToString();
        UpdateScopeIndicators();
    }

    private void UpdateConnectedDeviceCard(PeerDevice peer)
    {
        ConnectedPhoneNameText.Text = peer.Name;
        ConnectedPhoneIpText.Text = peer.EndpointDisplay;
        ConnectedPhoneBatteryText.Text = peer.BatteryDisplay;
        ConnectedBadgeLabel.Text = peer.ConnectionBadge;
        ConnectedBadgePill.Background = new SolidColorBrush(Color.FromRgb(0x0D, 0x3B, 0x26));
        ConnectedBadgeLabel.Foreground = new SolidColorBrush(Color.FromRgb(0x22, 0xC5, 0x5E));

        LiveDot.Fill = new SolidColorBrush(Color.FromRgb(0x22, 0xC5, 0x5E));
        LiveStatusText.Text = "Live";
        LiveStatusText.Foreground = new SolidColorBrush(Color.FromRgb(0x22, 0xC5, 0x5E));

        if (peer.StorageTotalBytes > 0)
        {
            SidebarVaultStatText.Text = $"{FileFormatHelper.FormatSize(peer.VaultUsedBytes)} / {FileFormatHelper.FormatSize(peer.StorageTotalBytes)}";
            double ratio = Math.Clamp((double)(peer.StorageTotalBytes - peer.StorageFreeBytes) / peer.StorageTotalBytes, 0.08, 1.0);
            SidebarVaultBarFill.Width = Math.Round(118 * ratio);
        }
        else if (peer.VaultUsedBytes > 0)
        {
            SidebarVaultStatText.Text = $"{FileFormatHelper.FormatSize(peer.VaultUsedBytes)} ({peer.VaultFileCount} files)";
            SidebarVaultBarFill.Width = 54;
        }
    }

    private void OnChannelsUpdated(List<string> names)
    {
        foreach (var ch in names)
        {
            if (_projects.All(p => !p.Name.Equals(ch, StringComparison.OrdinalIgnoreCase)))
            {
                _projects.Add(new ProjectItem
                {
                    Name = ch,
                    IsSelected = ch.Equals(_activeChannel, StringComparison.OrdinalIgnoreCase)
                });
            }
        }
        foreach (var p in _projects)
        {
            p.IsSelected = p.Name.Equals(_activeChannel, StringComparison.OrdinalIgnoreCase);
        }
    }

    private void OnMessagesUpdated(List<SyncMessageViewModel> incoming, bool scrollIfNew)
    {
        int prevCount = _rawChannelMessages.Count;
        long prevLastId = _rawChannelMessages.LastOrDefault()?.Id ?? -1;

        // Preserve expanded state & loaded thumbnails across polls
        var existingByUid = _rawChannelMessages
            .Where(m => !string.IsNullOrEmpty(m.MsgUid))
            .GroupBy(m => m.MsgUid, StringComparer.OrdinalIgnoreCase)
            .ToDictionary(g => g.Key, g => g.First(), StringComparer.OrdinalIgnoreCase);
        var existingById = _rawChannelMessages.ToDictionary(m => m.Id, m => m);
        SyncMessageViewModel? newIncomingPrivate = null;
        foreach (var m in incoming)
        {
            var old = (!string.IsNullOrEmpty(m.MsgUid) && existingByUid.TryGetValue(m.MsgUid, out var uOld))
                ? uOld
                : (existingById.TryGetValue(m.Id, out var idOld) ? idOld : null);
            if (old != null)
            {
                m.IsExpanded = old.IsExpanded;
                m.Thumbnail = old.Thumbnail;
                if (string.IsNullOrEmpty(m.LocalCachedPath)) m.LocalCachedPath = old.LocalCachedPath;
                if (!string.IsNullOrEmpty(old.VideoDurationText)) m.VideoDurationText = old.VideoDurationText;
            }
            else if (prevCount > 0 && !_isPrivateChatMode && m.IsIncoming && !string.IsNullOrEmpty(m.TargetPeerId))
            {
                newIncomingPrivate = m;
            }
            if (m.IsFile && string.IsNullOrEmpty(m.LocalCachedPath) && !string.IsNullOrEmpty(m.FileHash))
            {
                var localDedup = _hub.Store.ResolveExistingFile(m.FileHash, m.FileSize);
                if (!string.IsNullOrEmpty(localDedup))
                {
                    m.LocalCachedPath = localDedup;
                    m.IsZeroCopyOrigin = _hub.Store.IsZeroCopyOriginPath(localDedup);
                }
            }
            if (m.IsClipboard && m.IsIncoming && !string.IsNullOrEmpty(m.TextContent) && _autoCopiedClipboardIds.Add(m.Id))
            {
                try { Clipboard.SetText(m.TextContent); } catch { }
            }
            if (m.IsMediaFile && m.Thumbnail == null)
            {
                LoadMessageMediaThumbnailAsync(m);
            }
        }

        _rawChannelMessages = incoming;
        ApplyMessageAndVaultFilters();

        if (newIncomingPrivate != null)
        {
            ShowToast($"🔒 Private message from {newIncomingPrivate.SenderDisplay} — click to open Private 1-on-1", false, clickToPrivate: true);
        }

        long newLastId = incoming.LastOrDefault()?.Id ?? -1;
        if (scrollIfNew || incoming.Count > prevCount || newLastId != prevLastId)
        {
            ChatScrollViewer.ScrollToEnd();
        }
    }

    private void OnLiveMessagePushed(SyncMessageViewModel msg)
    {
        if (!string.IsNullOrEmpty(msg.TargetPeerId) && !msg.TargetPeerId.Equals(_hub.PcId, StringComparison.OrdinalIgnoreCase))
        {
            return;
        }

        bool isPrivateMsg = !string.IsNullOrEmpty(msg.TargetPeerId);

        if (msg.IsClipboard && msg.IsIncoming && !string.IsNullOrEmpty(msg.TextContent) && _autoCopiedClipboardIds.Add(msg.Id))
        {
            try
            {
                Clipboard.SetText(msg.TextContent);
                if (!isPrivateMsg || _isPrivateChatMode)
                {
                    ShowToast("Phone clipboard synced to Windows clipboard!", false);
                }
            }
            catch { }
        }

        if (!msg.Channel.Equals(_activeChannel, StringComparison.OrdinalIgnoreCase))
        {
            NotificationDot.Visibility = Visibility.Visible;
            return;
        }

        if (_rawChannelMessages.All(m => m.Id != msg.Id && (string.IsNullOrEmpty(msg.MsgUid) || !m.MsgUid.Equals(msg.MsgUid, StringComparison.OrdinalIgnoreCase))))
        {
            if (msg.IsMediaFile) LoadMessageMediaThumbnailAsync(msg);
            _rawChannelMessages.Add(msg);
            ApplyMessageAndVaultFilters();

            if (isPrivateMsg == _isPrivateChatMode)
            {
                ChatScrollViewer.ScrollToEnd();
            }
            else if (isPrivateMsg && msg.IsIncoming)
            {
                ShowToast($"🔒 Private message from {msg.SenderDisplay} — click to open Private 1-on-1", false, clickToPrivate: true);
            }
        }
    }

    private void OnVaultFilesUpdated(List<VaultFileItem> files)
    {
        var activePeerName = _peers.FirstOrDefault(p => p.Url == _activeBaseUrl)?.Name ?? "Phone";
        var oldThumbs = _allVaultFiles.Where(f => f.Thumbnail != null).ToDictionary(f => f.Id, f => f.Thumbnail);

        _allVaultFiles.Clear();
        _recentVaultFiles.Clear();

        long totalVaultBytes = 0;
        foreach (var f in files)
        {
            f.SourceDeviceName = activePeerName;
            totalVaultBytes += f.Size;
            if (oldThumbs.TryGetValue(f.Id, out var cachedThumb))
            {
                f.Thumbnail = cachedThumb;
            }
            _allVaultFiles.Add(f);
        }

        foreach (var recent in files.Take(5))
        {
            _recentVaultFiles.Add(recent);
            if (recent.IsMedia && recent.Thumbnail == null)
            {
                LoadVaultThumbnailAsync(recent);
            }
        }

        foreach (var mediaItem in files.Where(f => f.IsMedia && f.Thumbnail == null).Take(20))
        {
            LoadVaultThumbnailAsync(mediaItem);
        }

        VaultHeaderCountText.Text = $"{files.Count} private files ({FileFormatHelper.FormatSize(totalVaultBytes)}) on {activePeerName}";
        var activePeer = _peers.FirstOrDefault(p => p.Url == _activeBaseUrl);
        if (activePeer != null && activePeer.StorageTotalBytes == 0)
        {
            SidebarVaultStatText.Text = $"{FileFormatHelper.FormatSize(totalVaultBytes)} ({files.Count} files)";
            double estRatio = Math.Clamp(totalVaultBytes / (5.0 * 1024 * 1024 * 1024), 0.12, 1.0);
            SidebarVaultBarFill.Width = Math.Round(118 * estRatio);
        }
    }

    private void LoadMessageMediaThumbnailAsync(SyncMessageViewModel msg)
    {
        string key = $"msg_{msg.Id}";
        if (!_thumbLoadingKeys.Add(key) || string.IsNullOrEmpty(_activeBaseUrl)) return;

        var url = _activeBaseUrl;
        _ = Task.Run(async () =>
        {
            try
            {
                // 1) If uploaded from this PC or already saved/deduplicated locally, use instant native frame/image extraction
                string? knownLocal = msg.LocalCachedPath;
                if (string.IsNullOrEmpty(knownLocal) && !string.IsNullOrEmpty(msg.FileHash))
                {
                    knownLocal = _hub.Store.ResolveExistingFile(msg.FileHash, msg.FileSize);
                }
                if (string.IsNullOrEmpty(knownLocal) && _localSentPaths.TryGetValue(msg.FileName, out var sentPath) && File.Exists(sentPath))
                {
                    knownLocal = sentPath;
                }
                if (string.IsNullOrEmpty(knownLocal))
                {
                    var dlCopy = Path.Combine(_hub.SaveDirectory, msg.FileName);
                    if (File.Exists(dlCopy) && new FileInfo(dlCopy).Length == msg.FileSize)
                        knownLocal = dlCopy;
                }

                if (!string.IsNullOrEmpty(knownLocal) && File.Exists(knownLocal))
                {
                    ImageSource? localBmp = msg.IsVideoFile
                        ? _hub.ExtractWindowsVideoThumbnail(knownLocal, $"msg_{msg.Id}", 460, 290)
                        : ContinuityHubService.LoadThumbnailFrozen(knownLocal, 380);
                    if (localBmp != null)
                    {
                        await Dispatcher.InvokeAsync(() =>
                        {
                            msg.LocalCachedPath = knownLocal;
                            msg.Thumbnail = localBmp;
                        });
                        return;
                    }
                }

                // 2) Try Android server fast thumbnail endpoint (extracts video keyframe & duration on phone!)
                var (srvThumb, durText) = await _hub.FetchServerThumbnailAsync(
                    url,
                    $"/api/messages/thumbnail?id={msg.Id}",
                    $"msg_{msg.Id}",
                    decodeWidth: 420);

                if (srvThumb != null)
                {
                    await Dispatcher.InvokeAsync(() =>
                    {
                        msg.Thumbnail = srvThumb;
                        if (!string.IsNullOrEmpty(durText)) msg.VideoDurationText = durText;
                    });
                    return;
                }

                // 3) Fallback for older phone server or images: download into cache and extract locally on Windows
                if (msg.IsImageFile || msg.FileSize <= 80L * 1024 * 1024)
                {
                    var local = await _hub.DownloadFileAsync(url, $"/api/messages/download?id={msg.Id}", $"msg_{msg.Id}_{msg.FileName}", useThumbCache: true);
                    if (!string.IsNullOrEmpty(local))
                    {
                        ImageSource? bmp = msg.IsVideoFile
                            ? _hub.ExtractWindowsVideoThumbnail(local, $"msg_{msg.Id}", 460, 290)
                            : ContinuityHubService.LoadThumbnailFrozen(local, 380);
                        if (bmp != null)
                        {
                            await Dispatcher.InvokeAsync(() =>
                            {
                                msg.LocalCachedPath = local;
                                msg.Thumbnail = bmp;
                            });
                        }
                    }
                }
            }
            finally
            {
                _thumbLoadingKeys.Remove(key);
            }
        });
    }

    private void LoadVaultThumbnailAsync(VaultFileItem item)
    {
        string key = $"vault_{item.Id}";
        if (!_thumbLoadingKeys.Add(key) || string.IsNullOrEmpty(_activeBaseUrl)) return;

        var url = _activeBaseUrl;
        _ = Task.Run(async () =>
        {
            try
            {
                var (srvThumb, _) = await _hub.FetchServerThumbnailAsync(
                    url,
                    $"/api/vault/thumbnail?id={item.Id}",
                    $"vault_{item.Id}",
                    decodeWidth: 160);

                if (srvThumb != null)
                {
                    await Dispatcher.InvokeAsync(() => item.Thumbnail = srvThumb);
                    return;
                }

                if (item.IsImage)
                {
                    var local = await _hub.DownloadFileAsync(url, $"/api/vault/download?id={item.Id}", $"vault_{item.Id}_{item.Name}", useThumbCache: true);
                    if (!string.IsNullOrEmpty(local))
                    {
                        var bmp = ContinuityHubService.LoadThumbnailFrozen(local, 140);
                        if (bmp != null)
                        {
                            await Dispatcher.InvokeAsync(() => item.Thumbnail = bmp);
                        }
                    }
                }
            }
            finally
            {
                _thumbLoadingKeys.Remove(key);
            }
        });
    }

    private string GetOutgoingTargetPeerId()
    {
        if (!_isPrivateChatMode) return "";
        var peer = _peers.FirstOrDefault(p => p.Url == _activeBaseUrl);
        return !string.IsNullOrEmpty(peer?.Id) ? peer.Id : _hub.PcId;
    }

    private void ScopeGroupBtn_Click(object sender, RoutedEventArgs e) => SwitchChatScope(false);

    private void ScopePrivateBtn_Click(object sender, RoutedEventArgs e) => SwitchChatScope(true);

    private void SwitchChatScope(bool isPrivate)
    {
        _isPrivateChatMode = isPrivate;
        if (isPrivate)
        {
            long maxPrivId = _rawChannelMessages
                .Where(m => !string.IsNullOrEmpty(m.TargetPeerId))
                .Select(m => m.Id)
                .DefaultIfEmpty(0)
                .Max();
            if (maxPrivId > _seenPrivateMaxId) _seenPrivateMaxId = maxPrivId;
        }

        ScopeGroupBd.Background = new SolidColorBrush(isPrivate
            ? Colors.Transparent
            : Color.FromRgb(0x2B, 0x32, 0x47));
        ScopeGroupText.Foreground = new SolidColorBrush(isPrivate
            ? Color.FromRgb(0x94, 0xA3, 0xB8)
            : Colors.White);

        ScopePrivateBd.Background = new SolidColorBrush(isPrivate
            ? Color.FromRgb(0x4C, 0x1D, 0x24)
            : Colors.Transparent);
        ScopePrivateText.Foreground = new SolidColorBrush(isPrivate
            ? Color.FromRgb(0xFC, 0xA5, 0xA5)
            : Color.FromRgb(0x94, 0xA3, 0xB8));

        PrivateScopeBanner.Visibility = isPrivate ? Visibility.Visible : Visibility.Collapsed;
        AllDevicesSubText.Text = isPrivate ? "🔒 Private 1-on-1 • " : $"All devices ({_peers.Count}) • ";

        ApplyMessageAndVaultFilters();
        ChatScrollViewer.ScrollToEnd();
    }

    private void UpdateScopeIndicators()
    {
        var activePeerName = _peers.FirstOrDefault(p => p.Url == _activeBaseUrl)?.Name;
        var phoneLabel = string.IsNullOrWhiteSpace(activePeerName) ? "Private" : activePeerName;

        int privCount = _rawChannelMessages.Count(m => !string.IsNullOrEmpty(m.TargetPeerId));
        long maxPrivId = _rawChannelMessages
            .Where(m => !string.IsNullOrEmpty(m.TargetPeerId))
            .Select(m => m.Id)
            .DefaultIfEmpty(0)
            .Max();

        if (_isPrivateChatMode && maxPrivId > _seenPrivateMaxId)
        {
            _seenPrivateMaxId = maxPrivId;
        }

        ScopePrivateText.Text = privCount > 0 ? $"🔒 {phoneLabel} ({privCount})" : $"🔒 {phoneLabel}";
        PrivateUnreadDot.Visibility = (!_isPrivateChatMode && maxPrivId > _seenPrivateMaxId)
            ? Visibility.Visible
            : Visibility.Collapsed;

        PrivateScopeBannerText.Text = $"🔒 Private 1-on-1 with {phoneLabel} • Hidden from All Group & other phones";
    }

    private void ApplyMessageAndVaultFilters()
    {
        var q = _searchFilter.Trim();
        var scoped = _rawChannelMessages.Where(m =>
            _isPrivateChatMode
                ? !string.IsNullOrEmpty(m.TargetPeerId)
                : string.IsNullOrEmpty(m.TargetPeerId));

        var filtered = string.IsNullOrEmpty(q)
            ? scoped.ToList()
            : scoped.Where(m =>
                m.TextContent.Contains(q, StringComparison.OrdinalIgnoreCase) ||
                m.FileName.Contains(q, StringComparison.OrdinalIgnoreCase) ||
                m.SenderDisplay.Contains(q, StringComparison.OrdinalIgnoreCase)).ToList();

        _displayedMessages.Clear();
        _clipboardMessages.Clear();
        foreach (var m in filtered)
        {
            _displayedMessages.Add(m);
            if (m.IsClipboard || (!m.IsFile && m.TextContent.Length > 0))
            {
                _clipboardMessages.Add(m);
            }
        }

        UpdateScopeIndicators();
        EmptyChatPlaceholder.Visibility = _displayedMessages.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    // ── Window Chrome Controls ───────────────────────────────────────────────

    private void MinimizeWindow_Click(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;

    private void MaximizeWindow_Click(object sender, RoutedEventArgs e)
        => WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;

    private void CloseWindow_Click(object sender, RoutedEventArgs e) => Close();

    private void Window_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (MediaLightboxOverlay.Visibility == Visibility.Visible)
        {
            if (e.Key == Key.Escape)
            {
                CloseLightbox();
                e.Handled = true;
                return;
            }
            if (e.Key == Key.Left)
            {
                StepLightbox(-1);
                e.Handled = true;
                return;
            }
            if (e.Key == Key.Right)
            {
                StepLightbox(1);
                e.Handled = true;
                return;
            }
            if (e.Key == Key.Space && _lightboxIndex >= 0 && _lightboxIndex < _lightboxItems.Count && _lightboxItems[_lightboxIndex].IsVideo)
            {
                ToggleLightboxVideoPlayback();
                e.Handled = true;
                return;
            }
        }

        if (e.Key == Key.K && (Keyboard.Modifiers & ModifierKeys.Control) == ModifierKeys.Control)
        {
            GlobalSearchBox.Focus();
            GlobalSearchBox.SelectAll();
            e.Handled = true;
        }
    }

    // ── Navigation Rail Switching ────────────────────────────────────────────

    private void NavTab_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button btn || btn.Tag is not string tab) return;
        SwitchWorkspaceTab(tab);
    }

    private void SwitchWorkspaceTab(string tab)
    {
        ChatWorkspaceView.Visibility = (tab is "Chat" or "Projects" or "Branches") ? Visibility.Visible : Visibility.Collapsed;
        VaultBrowserView.Visibility = tab == "Vault" ? Visibility.Visible : Visibility.Collapsed;
        TransfersView.Visibility = tab == "Transfer" ? Visibility.Visible : Visibility.Collapsed;
        ClipboardView.Visibility = tab == "Clipboard" ? Visibility.Visible : Visibility.Collapsed;
        DevicesSettingsView.Visibility = tab == "Devices" ? Visibility.Visible : Visibility.Collapsed;

        (Button Btn, TextBlock Lbl, string Key)[] navs =
        [
            (NavChatBtn, NavChatLbl, "Chat"),
            (NavVaultBtn, NavVaultLbl, "Vault"),
            (NavTransferBtn, NavTransferLbl, "Transfer"),
            (NavClipboardBtn, NavClipboardLbl, "Clipboard"),
            (NavDevicesBtn, NavDevicesLbl, "Devices"),
            (NavProjectsBtn, NavProjectsLbl, "Projects"),
            (NavBranchesBtn, NavBranchesLbl, "Branches")
        ];

        foreach (var (btn, lbl, key) in navs)
        {
            bool active = key == tab;
            btn.Uid = active ? "Active" : "";
            lbl.Foreground = new SolidColorBrush(active ? Colors.White : Color.FromRgb(0xCB, 0xD5, 0xE1));
            lbl.FontWeight = active ? FontWeights.SemiBold : FontWeights.Normal;
        }

        if (tab == "Vault" && !string.IsNullOrEmpty(_activeBaseUrl))
        {
            _ = _hub.FetchVaultFilesAsync(_activeBaseUrl);
        }
    }

    private void OpenPhoneVaultTab_Click(object sender, MouseButtonEventArgs e) => SwitchWorkspaceTab("Vault");
    private void BrowsePhoneQuick_Click(object sender, RoutedEventArgs e) => SwitchWorkspaceTab("Vault");
    private void OpenDevicesSettings_Click(object sender, RoutedEventArgs e) => SwitchWorkspaceTab("Devices");
    private void OpenPcSavedFolder_Click(object sender, RoutedEventArgs e) => ContinuityHubService.OpenFileOrFolder(_hub.SaveDirectory);
    private void SanStudioFooter_Click(object sender, MouseButtonEventArgs e) => ContinuityHubService.OpenFileOrFolder(SanStudioUrl);

    private void BellNotification_Click(object sender, RoutedEventArgs e)
    {
        NotificationDot.Visibility = Visibility.Collapsed;
        ShowToast(string.IsNullOrEmpty(_activeBaseUrl)
            ? "Searching Wi-Fi & USB for SanDen mobile devices…"
            : $"Connected live to {ConnectedPhoneNameText.Text} ({ConnectedPhoneIpText.Text})", false);
    }

    // ── Projects & Branches ──────────────────────────────────────────────────

    private void ProjectItem_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button btn || btn.Tag is not string name) return;
        _activeChannel = name;
        ActiveChannelTitle.Text = _activeBranch == "main" ? _activeChannel : $"{_activeChannel} / {_activeBranch}";
        foreach (var p in _projects) p.IsSelected = p.Name == name;

        _rawChannelMessages.Clear();
        _displayedMessages.Clear();
        if (!string.IsNullOrEmpty(_activeBaseUrl))
        {
            _ = _hub.FetchMessagesAsync(_activeBaseUrl, _activeChannel);
        }
    }

    private void BranchItem_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button btn || btn.Tag is not string name) return;
        _activeBranch = name;
        foreach (var b in _branches) b.IsSelected = b.Name == name;
        ActiveChannelTitle.Text = _activeBranch == "main" ? _activeChannel : $"{_activeChannel} / {_activeBranch}";
    }

    private void NewProjectBtn_Click(object sender, RoutedEventArgs e)
    {
        _modalMode = "project";
        ModalPromptTitle.Text = "New Project Channel";
        ModalPromptSubtitle.Text = "Synced immediately across your Android phone and PC";
        ModalPromptInput.Text = "";
        ModalPromptOverlay.Visibility = Visibility.Visible;
        ModalPromptInput.Focus();
    }

    private void NewBranchBtn_Click(object sender, RoutedEventArgs e)
    {
        _modalMode = "branch";
        ModalPromptTitle.Text = "New Workspace Branch";
        ModalPromptSubtitle.Text = "Tag messages & files under a dedicated feature branch";
        ModalPromptInput.Text = "feature/";
        ModalPromptOverlay.Visibility = Visibility.Visible;
        ModalPromptInput.Focus();
        ModalPromptInput.CaretIndex = ModalPromptInput.Text.Length;
    }

    private void ModalPromptCancel_Click(object sender, RoutedEventArgs e) => ModalPromptOverlay.Visibility = Visibility.Collapsed;

    private void ModalPromptInput_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter) ModalPromptConfirm_Click(sender, e);
        else if (e.Key == Key.Escape) ModalPromptOverlay.Visibility = Visibility.Collapsed;
    }

    private async void ModalPromptConfirm_Click(object sender, RoutedEventArgs e)
    {
        var val = ModalPromptInput.Text.Trim();
        ModalPromptOverlay.Visibility = Visibility.Collapsed;
        if (string.IsNullOrEmpty(val)) return;

        if (_modalMode == "project")
        {
            if (_projects.All(p => !p.Name.Equals(val, StringComparison.OrdinalIgnoreCase)))
            {
                _projects.Add(new ProjectItem { Name = val });
            }
            _activeChannel = val;
            foreach (var p in _projects) p.IsSelected = p.Name.Equals(val, StringComparison.OrdinalIgnoreCase);
            ActiveChannelTitle.Text = val;
            if (!string.IsNullOrEmpty(_activeBaseUrl))
            {
                await _hub.CreateChannelAsync(_activeBaseUrl, val);
                await _hub.FetchMessagesAsync(_activeBaseUrl, val);
            }
        }
        else if (_modalMode == "branch")
        {
            if (_branches.All(b => !b.Name.Equals(val, StringComparison.OrdinalIgnoreCase)))
            {
                _branches.Add(new BranchItem { Name = val });
            }
            _activeBranch = val;
            foreach (var b in _branches) b.IsSelected = b.Name.Equals(val, StringComparison.OrdinalIgnoreCase);
            ActiveChannelTitle.Text = _activeBranch == "main" ? _activeChannel : $"{_activeChannel} / {_activeBranch}";
        }
    }

    // ── Messaging, Rich Paste, Clipboard & File Transfers ───────────────────

    private string? _stagedLargeText;

    private void ComposerTextBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        ComposerPlaceholder.Visibility = string.IsNullOrEmpty(ComposerTextBox.Text)
            ? Visibility.Visible
            : Visibility.Collapsed;
    }

    private void GlobalSearchBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        _searchFilter = GlobalSearchBox.Text;
        SearchPlaceholder.Visibility = string.IsNullOrEmpty(_searchFilter)
            ? Visibility.Visible
            : Visibility.Collapsed;
        ApplyMessageAndVaultFilters();
    }

    private void ComposerTextBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && (Keyboard.Modifiers & ModifierKeys.Shift) == 0)
        {
            e.Handled = true;
            SendMessageBtn_Click(sender, e);
        }
    }

    private void OnComposerPaste(object sender, DataObjectPastingEventArgs e)
    {
        // 1) Support pasting copied files from Windows Explorer
        if (e.DataObject.GetDataPresent(DataFormats.FileDrop))
        {
            if (e.DataObject.GetData(DataFormats.FileDrop) is string[] files && files.Length > 0)
            {
                e.CancelCommand();
                _ = SendFilesListAsync(files);
                return;
            }
        }

        // 2) Support pasting screenshots / copied images directly (Win+Shift+S / PrtScn)!
        if (Clipboard.ContainsImage())
        {
            var bmpSource = Clipboard.GetImage();
            if (bmpSource != null)
            {
                e.CancelCommand();
                var tempPng = SaveBitmapSourceToPng(bmpSource);
                if (!string.IsNullOrEmpty(tempPng))
                {
                    ShowToast("Sending pasted screenshot to Phone Vault…", false);
                    _ = SendFilesListAsync([tempPng]);
                }
                return;
            }
        }

        // 3) Stage large code snippets (>1500 chars or >12 lines) cleanly without input lag
        if (e.DataObject.GetDataPresent(DataFormats.UnicodeText))
        {
            var text = e.DataObject.GetData(DataFormats.UnicodeText) as string;
            if (!string.IsNullOrEmpty(text) && (text.Length > 1500 || text.Count(c => c == '\n') > 12))
            {
                e.CancelCommand();
                _stagedLargeText = text;
                int lines = text.Count(c => c == '\n') + 1;
                LargePasteLabel.Text = $"⚡ Large snippet staged ({lines} lines, {text.Length:N0} chars) — press Enter or click Send";
                LargePasteBanner.Visibility = Visibility.Visible;
            }
        }
    }

    private static string? SaveBitmapSourceToPng(BitmapSource source)
    {
        try
        {
            var dir = Path.Combine(Path.GetTempPath(), "SanDenPaste");
            Directory.CreateDirectory(dir);
            var path = Path.Combine(dir, $"Screenshot_{DateTime.Now:yyyyMMdd_HHmmss}.png");
            using var fs = new FileStream(path, FileMode.Create, FileAccess.Write);
            var encoder = new PngBitmapEncoder();
            encoder.Frames.Add(BitmapFrame.Create(source));
            encoder.Save(fs);
            return path;
        }
        catch
        {
            return null;
        }
    }

    private void ClearLargePaste_Click(object sender, RoutedEventArgs e)
    {
        _stagedLargeText = null;
        LargePasteBanner.Visibility = Visibility.Collapsed;
    }

    private async void SendMessageBtn_Click(object sender, RoutedEventArgs e)
    {
        string textToSend;
        if (!string.IsNullOrEmpty(_stagedLargeText))
        {
            textToSend = _stagedLargeText;
            _stagedLargeText = null;
            LargePasteBanner.Visibility = Visibility.Collapsed;
        }
        else
        {
            textToSend = ComposerTextBox.Text.Trim();
            ComposerTextBox.Clear();
        }

        if (string.IsNullOrEmpty(textToSend)) return;
        if (_isPrivateChatMode && string.IsNullOrEmpty(_activeBaseUrl))
        {
            ShowToast("Select a peer device for Private 1-on-1 chat first.", true);
            return;
        }

        var payloadText = _activeBranch == "main" ? textToSend : $"[{_activeBranch}] {textToSend}";
        await _hub.SendMessageAsync(_activeBaseUrl, _activeChannel, payloadText, "TEXT", targetPeerId: GetOutgoingTargetPeerId());
    }

    private async void SendClipboardBtn_Click(object sender, RoutedEventArgs e)
    {
        if (_isPrivateChatMode && string.IsNullOrEmpty(_activeBaseUrl))
        {
            ShowToast("Select a peer device for Private 1-on-1 clipboard sync first.", true);
            return;
        }

        if (Clipboard.ContainsFileDropList())
        {
            var list = Clipboard.GetFileDropList().Cast<string>().Where(File.Exists).ToArray();
            if (list.Length > 0)
            {
                await SendFilesListAsync(list);
                return;
            }
        }

        if (Clipboard.ContainsImage())
        {
            var img = Clipboard.GetImage();
            if (img != null)
            {
                var png = SaveBitmapSourceToPng(img);
                if (!string.IsNullOrEmpty(png))
                {
                    ShowToast("Pushing clipboard image to Phone…", false);
                    await SendFilesListAsync([png]);
                    return;
                }
            }
        }

        if (Clipboard.ContainsText())
        {
            var text = Clipboard.GetText().Trim();
            if (!string.IsNullOrEmpty(text))
            {
                bool ok = await _hub.SendMessageAsync(_activeBaseUrl, _activeChannel, text, "CLIPBOARD", targetPeerId: GetOutgoingTargetPeerId());
                if (ok) ShowToast("Clipboard synced to phone!", false);
                return;
            }
        }

        ShowToast("Windows clipboard is empty.", true);
    }

    private void PasteClipboardQuick_Click(object sender, RoutedEventArgs e) => SendClipboardBtn_Click(sender, e);

    private void SendFilesBtn_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFileDialog
        {
            Title = "Select files to send to SanDen Phone Vault",
            Multiselect = true,
            Filter = "All Files (*.*)|*.*"
        };
        if (dlg.ShowDialog() == true && dlg.FileNames.Length > 0)
        {
            _ = SendFilesListAsync(dlg.FileNames);
        }
    }

    private void SendFilesBorder_Click(object sender, MouseButtonEventArgs e) => SendFilesBtn_Click(sender, new RoutedEventArgs());

    private void SendPhotosBtn_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFileDialog
        {
            Title = "Select images or videos to send",
            Multiselect = true,
            Filter = "Media Files|*.jpg;*.jpeg;*.png;*.webp;*.gif;*.mp4;*.mkv;*.mov|All Files (*.*)|*.*"
        };
        if (dlg.ShowDialog() == true && dlg.FileNames.Length > 0)
        {
            _ = SendFilesListAsync(dlg.FileNames);
        }
    }

    private async Task SendFilesListAsync(IEnumerable<string> paths)
    {
        if (_isPrivateChatMode && string.IsNullOrEmpty(_activeBaseUrl))
        {
            ShowToast("Select a peer device for Private 1-on-1 file sharing first.", true);
            return;
        }

        var valid = paths.Where(File.Exists).ToList();
        if (valid.Count == 0) return;

        var targetLabel = string.IsNullOrEmpty(_activeBaseUrl) ? "Mesh (Zero-Copy Queue)" : ConnectedPhoneNameText.Text;
        ShowToast($"Indexing & sharing {valid.Count} file(s) to {targetLabel}…", false);

        foreach (var filePath in valid)
        {
            var fi = new FileInfo(filePath);
            _localSentPaths[fi.Name] = filePath;
            var tx = new TransferProgressItem
            {
                FileName = fi.Name,
                Direction = "UPLOAD",
                TotalBytes = fi.Length,
                PeerName = ConnectedPhoneNameText.Text,
                StatusText = "Starting upload…"
            };
            _transfers.Insert(0, tx);

            await _hub.UploadFileAsync(
                _activeBaseUrl,
                _activeChannel,
                filePath,
                tx,
                action => Dispatcher.InvokeAsync(action),
                targetPeerId: GetOutgoingTargetPeerId());
        }
    }

    // ── Drag & Drop ──────────────────────────────────────────────────────────

    private void Window_DragOver(object sender, DragEventArgs e)
    {
        e.Effects = e.Data.GetDataPresent(DataFormats.FileDrop) ? DragDropEffects.Copy : DragDropEffects.None;
        e.Handled = true;
    }

    private void Window_Drop(object sender, DragEventArgs e)
    {
        if (e.Data.GetDataPresent(DataFormats.FileDrop) &&
            e.Data.GetData(DataFormats.FileDrop) is string[] files &&
            files.Length > 0)
        {
            _ = SendFilesListAsync(files);
        }
    }

    // ── Message & Vault Item Actions ─────────────────────────────────────────

    private void ToggleExpandMessage_Click(object sender, MouseButtonEventArgs e)
    {
        if (sender is FrameworkElement fe && fe.Tag is SyncMessageViewModel vm)
        {
            vm.IsExpanded = !vm.IsExpanded;
        }
    }

    private void CopyMessageText_Click(object sender, MouseButtonEventArgs e)
    {
        if (sender is FrameworkElement fe && fe.Tag is string txt && !string.IsNullOrEmpty(txt))
        {
            try
            {
                Clipboard.SetText(txt);
                ShowToast("Copied to Windows clipboard.", false);
            }
            catch { }
        }
    }

    private void CopyClipboardItemBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.Tag is string txt && !string.IsNullOrEmpty(txt))
        {
            try
            {
                Clipboard.SetText(txt);
                ShowToast("Copied to Windows clipboard.", false);
            }
            catch { }
        }
    }

    private void OpenMessageFile_Click(object sender, MouseButtonEventArgs e)
    {
        if (sender is FrameworkElement fe && fe.Tag is SyncMessageViewModel vm)
        {
            if (vm.IsMediaFile)
            {
                OpenMediaInLightbox(vm);
            }
            else
            {
                _ = DownloadAndOpenMessageFileAsync(vm);
            }
        }
    }

    private void OpenMessageFileBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.Tag is SyncMessageViewModel vm)
        {
            if (vm.IsMediaFile)
            {
                OpenMediaInLightbox(vm);
            }
            else
            {
                _ = DownloadAndOpenMessageFileAsync(vm);
            }
        }
    }

    private void SaveAndOpenExternalBtn_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (sender is Button btn && btn.Tag is SyncMessageViewModel vm)
        {
            _ = DownloadAndOpenMessageFileAsync(vm);
        }
    }

    private void VaultMediaItem_Click(object sender, MouseButtonEventArgs e)
    {
        if (sender is not FrameworkElement fe || fe.Tag is not VaultFileItem clicked) return;

        var mediaEntries = _allVaultFiles
            .Where(v => v.IsMedia)
            .Select(v => new LightboxEntry
            {
                Title = v.Name,
                SizeDisplay = v.SizeDisplay,
                SizeBytes = v.Size,
                IsVideo = v.IsVideo,
                DownloadEndpoint = $"/api/vault/download?id={v.Id}",
                CacheFileName = $"vault_{v.Id}_{v.Name}",
                Thumbnail = v.Thumbnail,
                SourceLabel = v.SourceDeviceName
            })
            .ToList();

        int idx = _allVaultFiles.Where(v => v.IsMedia).ToList().FindIndex(v => v.Id == clicked.Id);
        if (idx >= 0)
        {
            _lightboxItems = mediaEntries;
            _ = ShowLightboxEntryAsync(idx);
        }
    }

    private void OpenMediaInLightbox(SyncMessageViewModel clicked)
    {
        var mediaMessages = _displayedMessages.Where(m => m.IsMediaFile).ToList();
        _lightboxItems = mediaMessages.Select(m => new LightboxEntry
        {
            Title = m.FileName,
            SizeDisplay = m.FileSizeDisplay,
            SizeBytes = m.FileSize,
            IsVideo = m.IsVideoFile,
            DownloadEndpoint = $"/api/messages/download?id={m.Id}",
            CacheFileName = $"msg_{m.Id}_{m.FileName}",
            LocalPath = m.LocalCachedPath ?? _hub.Store.ResolveExistingFile(m.FileHash, m.FileSize),
            FileHash = m.FileHash,
            Thumbnail = m.Thumbnail,
            SourceLabel = m.SenderDisplay
        }).ToList();

        int idx = mediaMessages.FindIndex(m => m.Id == clicked.Id);
        if (idx < 0) idx = 0;
        _ = ShowLightboxEntryAsync(idx);
    }

    private async Task ShowLightboxEntryAsync(int index)
    {
        if (index < 0 || index >= _lightboxItems.Count) return;
        _lightboxIndex = index;
        var item = _lightboxItems[index];

        StopLightboxVideo();
        MediaLightboxOverlay.Visibility = Visibility.Visible;

        LightboxKindBadge.Text = item.IsVideo ? "VIDEO" : "PHOTO";
        LightboxTitleText.Text = item.Title;
        LightboxMetaText.Text = $"{index + 1} of {_lightboxItems.Count} • {item.SizeDisplay}" +
                                (string.IsNullOrEmpty(item.SourceLabel) ? "" : $" • {item.SourceLabel}");
        LightboxCopyBtn.Visibility = item.IsVideo ? Visibility.Collapsed : Visibility.Visible;
        LightboxPrevBtn.Visibility = index > 0 ? Visibility.Visible : Visibility.Collapsed;
        LightboxNextBtn.Visibility = index < _lightboxItems.Count - 1 ? Visibility.Visible : Visibility.Collapsed;

        LightboxImage.Visibility = Visibility.Collapsed;
        LightboxVideo.Visibility = Visibility.Collapsed;
        LightboxVideoOverlay.Visibility = Visibility.Collapsed;
        LightboxVideoBar.Visibility = item.IsVideo ? Visibility.Visible : Visibility.Collapsed;

        if (item.Thumbnail != null)
        {
            if (item.IsVideo)
            {
                LightboxPosterFallback.Source = item.Thumbnail;
                LightboxVideoOverlay.Visibility = Visibility.Visible;
            }
            else
            {
                LightboxImage.Source = item.Thumbnail;
                LightboxImage.Visibility = Visibility.Visible;
            }
        }

        // Resolve local path from cache, Downloads\SanDen, or sent map; otherwise download from phone
        string? local = item.LocalPath;
        if (string.IsNullOrEmpty(local) && _localSentPaths.TryGetValue(item.Title, out var sent) && File.Exists(sent))
        {
            local = sent;
        }
        if (string.IsNullOrEmpty(local))
        {
            var savedCopy = Path.Combine(_hub.SaveDirectory, item.Title);
            if (File.Exists(savedCopy) && new FileInfo(savedCopy).Length > 0)
                local = savedCopy;
        }
        if (string.IsNullOrEmpty(local))
        {
            var cachedCopy = Path.Combine(_hub.ThumbCacheDirectory, Path.GetFileName(item.CacheFileName));
            if (File.Exists(cachedCopy) && new FileInfo(cachedCopy).Length > 0)
                local = cachedCopy;
        }

        if (string.IsNullOrEmpty(local) && !string.IsNullOrEmpty(_activeBaseUrl))
        {
            LightboxLoadingPill.Visibility = Visibility.Visible;
            LightboxLoadingText.Text = $"Downloading {item.Title} from phone…";

            var tx = new TransferProgressItem
            {
                FileName = item.Title,
                Direction = "DOWNLOAD",
                TotalBytes = item.SizeBytes,
                StatusText = "Loading media preview…"
            };
            _transfers.Insert(0, tx);

            local = await _hub.DownloadFileAsync(
                _activeBaseUrl,
                item.DownloadEndpoint,
                item.CacheFileName,
                tx,
                act => Dispatcher.InvokeAsync(() =>
                {
                    act();
                    if (_lightboxIndex == index)
                    {
                        LightboxLoadingText.Text = $"Streaming preview • {tx.PercentText} ({FileFormatHelper.FormatSize(tx.TotalBytes)})";
                    }
                }),
                useThumbCache: true);

            if (_lightboxIndex != index) return;
        }

        LightboxLoadingPill.Visibility = Visibility.Collapsed;
        if (string.IsNullOrEmpty(local) || !File.Exists(local))
        {
            ShowToast("Could not load media file from phone.", true);
            return;
        }

        item.LocalPath = local;

        if (!item.IsVideo)
        {
            var fullBmp = ContinuityHubService.LoadThumbnailFrozen(local, 1920);
            if (fullBmp != null && _lightboxIndex == index)
            {
                LightboxImage.Source = fullBmp;
                LightboxImage.Visibility = Visibility.Visible;
            }
        }
        else if (_lightboxIndex == index)
        {
            LightboxVideo.Source = new Uri(local, UriKind.Absolute);
            LightboxVideo.Volume = LightboxVolumeSlider.Value;
            LightboxVideo.Visibility = Visibility.Visible;
            LightboxVideoOverlay.Visibility = Visibility.Collapsed;
            LightboxVideo.Play();
            _isVideoPlaying = true;
            StartVideoScrubTimer();
        }
    }

    private void StartVideoScrubTimer()
    {
        _videoScrubTimer?.Stop();
        _videoScrubTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(200) };
        _videoScrubTimer.Tick += (_, _) =>
        {
            if (_isDraggingVideoSeek || !LightboxVideo.NaturalDuration.HasTimeSpan) return;
            var total = LightboxVideo.NaturalDuration.TimeSpan;
            var pos = LightboxVideo.Position;
            if (total.TotalSeconds > 0)
            {
                LightboxSeekSlider.Maximum = total.TotalSeconds;
                LightboxSeekSlider.Value = pos.TotalSeconds;
                LightboxTimeText.Text = $"{FormatSpan(pos)} / {FormatSpan(total)}";
            }
        };
        _videoScrubTimer.Start();
    }

    private static string FormatSpan(TimeSpan ts)
        => ts.TotalHours >= 1 ? $"{(int)ts.TotalHours}:{ts.Minutes:D2}:{ts.Seconds:D2}" : $"{ts.Minutes}:{ts.Seconds:D2}";

    private void StopLightboxVideo()
    {
        _videoScrubTimer?.Stop();
        _isVideoPlaying = false;
        try
        {
            LightboxVideo.Stop();
            LightboxVideo.Source = null;
        }
        catch { }
    }

    private void CloseLightbox()
    {
        StopLightboxVideo();
        MediaLightboxOverlay.Visibility = Visibility.Collapsed;
        _lightboxIndex = -1;
    }

    private void StepLightbox(int delta)
    {
        int next = _lightboxIndex + delta;
        if (next >= 0 && next < _lightboxItems.Count)
        {
            _ = ShowLightboxEntryAsync(next);
        }
    }

    private void ToggleLightboxVideoPlayback()
    {
        if (LightboxVideo.Source == null) return;
        if (_isVideoPlaying)
        {
            LightboxVideo.Pause();
            _isVideoPlaying = false;
            LightboxPosterFallback.Source = null;
            LightboxVideoOverlay.Visibility = Visibility.Visible;
        }
        else
        {
            LightboxVideoOverlay.Visibility = Visibility.Collapsed;
            LightboxVideo.Play();
            _isVideoPlaying = true;
        }
    }

    private void LightboxVideo_MediaOpened(object sender, RoutedEventArgs e)
    {
        if (LightboxVideo.NaturalDuration.HasTimeSpan)
        {
            var dur = LightboxVideo.NaturalDuration.TimeSpan;
            LightboxSeekSlider.Maximum = Math.Max(1, dur.TotalSeconds);
            LightboxTimeText.Text = $"0:00 / {FormatSpan(dur)}";
        }
    }

    private void LightboxVideo_MediaEnded(object sender, RoutedEventArgs e)
    {
        _isVideoPlaying = false;
        LightboxVideo.Position = TimeSpan.Zero;
        LightboxVideo.Pause();
        LightboxVideoOverlay.Visibility = Visibility.Visible;
    }

    private void LightboxVideoSurface_Click(object sender, MouseButtonEventArgs e) => ToggleLightboxVideoPlayback();
    private void LightboxPlayPause_Click(object sender, MouseButtonEventArgs e) => ToggleLightboxVideoPlayback();
    private void LightboxPlayPause_BtnClick(object sender, RoutedEventArgs e) => ToggleLightboxVideoPlayback();
    private void LightboxClose_Click(object sender, RoutedEventArgs e) => CloseLightbox();
    private void LightboxPrev_Click(object sender, RoutedEventArgs e) => StepLightbox(-1);
    private void LightboxNext_Click(object sender, RoutedEventArgs e) => StepLightbox(1);

    private void LightboxSeek_DragStarted(object sender, System.Windows.Controls.Primitives.DragStartedEventArgs e)
        => _isDraggingVideoSeek = true;

    private void LightboxSeek_DragCompleted(object sender, System.Windows.Controls.Primitives.DragCompletedEventArgs e)
    {
        _isDraggingVideoSeek = false;
        if (LightboxVideo.Source != null)
        {
            LightboxVideo.Position = TimeSpan.FromSeconds(LightboxSeekSlider.Value);
        }
    }

    private void LightboxSeek_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_isDraggingVideoSeek && LightboxVideo.NaturalDuration.HasTimeSpan)
        {
            var pos = TimeSpan.FromSeconds(LightboxSeekSlider.Value);
            LightboxTimeText.Text = $"{FormatSpan(pos)} / {FormatSpan(LightboxVideo.NaturalDuration.TimeSpan)}";
        }
    }

    private void LightboxVolume_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (LightboxVideo != null)
        {
            LightboxVideo.Volume = e.NewValue;
        }
    }

    private void LightboxCopy_Click(object sender, RoutedEventArgs e)
    {
        if (_lightboxIndex < 0 || _lightboxIndex >= _lightboxItems.Count) return;
        var local = _lightboxItems[_lightboxIndex].LocalPath;
        if (!string.IsNullOrEmpty(local) && File.Exists(local))
        {
            try
            {
                var bmp = new BitmapImage(new Uri(local, UriKind.Absolute));
                Clipboard.SetImage(bmp);
                ShowToast("Image copied to Windows clipboard!", false);
            }
            catch { }
        }
    }

    private async void LightboxSaveOpen_Click(object sender, RoutedEventArgs e)
    {
        if (_lightboxIndex < 0 || _lightboxIndex >= _lightboxItems.Count) return;
        var item = _lightboxItems[_lightboxIndex];
        var existing = (!string.IsNullOrEmpty(item.LocalPath) && File.Exists(item.LocalPath))
            ? item.LocalPath
            : _hub.Store.ResolveExistingFile(item.FileHash, item.SizeBytes);
        if (!string.IsNullOrEmpty(existing))
        {
            ShowToast($"Opening existing local copy (0 duplicates): {Path.GetFileName(existing)}", false);
            ContinuityHubService.OpenFileOrFolder(existing);
            return;
        }
        var dest = await _hub.DownloadFileAsync(_activeBaseUrl, item.DownloadEndpoint, item.Title, fileHash: item.FileHash, expectedSize: item.SizeBytes);
        if (!string.IsNullOrEmpty(dest))
        {
            ShowToast($"Saved (Deduplicated): {Path.GetFileName(dest)}", false);
            ContinuityHubService.OpenFileOrFolder(dest);
        }
    }

    private async Task DownloadAndOpenMessageFileAsync(SyncMessageViewModel vm)
    {
        var existingLocal = (!string.IsNullOrEmpty(vm.LocalCachedPath) && File.Exists(vm.LocalCachedPath))
            ? vm.LocalCachedPath
            : _hub.Store.ResolveExistingFile(vm.FileHash, vm.FileSize);

        if (!string.IsNullOrEmpty(existingLocal))
        {
            vm.LocalCachedPath = existingLocal;
            vm.IsZeroCopyOrigin = _hub.Store.IsZeroCopyOriginPath(existingLocal);
            ShowToast($"Opened existing local copy (0 B downloaded): {Path.GetFileName(existingLocal)}", false);
            ContinuityHubService.OpenFileOrFolder(existingLocal);
            return;
        }

        var tx = new TransferProgressItem
        {
            FileName = vm.FileName,
            Direction = "DOWNLOAD",
            TotalBytes = vm.FileSize,
            StatusText = "Syncing from Mesh…"
        };
        _transfers.Insert(0, tx);

        string? path = null;
        if (!string.IsNullOrEmpty(vm.FileHash))
        {
            path = await _hub.DownloadBlobFromAnyPeerAsync(
                vm.FileHash,
                vm.FileName,
                vm.FileSize,
                tx,
                action => Dispatcher.InvokeAsync(action));
        }

        if (string.IsNullOrEmpty(path) && !string.IsNullOrEmpty(_activeBaseUrl))
        {
            path = await _hub.DownloadFileAsync(
                _activeBaseUrl,
                $"/api/messages/download?id={vm.Id}",
                vm.FileName,
                tx,
                action => Dispatcher.InvokeAsync(action),
                fileHash: vm.FileHash,
                expectedSize: vm.FileSize);
        }

        if (!string.IsNullOrEmpty(path))
        {
            vm.LocalCachedPath = path;
            vm.IsZeroCopyOrigin = _hub.Store.IsZeroCopyOriginPath(path);
            ContinuityHubService.OpenFileOrFolder(path);
        }
    }

    private async void DownloadVaultFileBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button btn || btn.Tag is not VaultFileItem item || string.IsNullOrEmpty(_activeBaseUrl)) return;

        ShowToast($"Downloading {item.Name}…", false);
        var tx = new TransferProgressItem
        {
            FileName = item.Name,
            Direction = "DOWNLOAD",
            TotalBytes = item.Size,
            StatusText = "Downloading from Vault…"
        };
        _transfers.Insert(0, tx);

        var path = await _hub.DownloadFileAsync(
            _activeBaseUrl,
            $"/api/vault/download?id={item.Id}",
            item.Name,
            tx,
            action => Dispatcher.InvokeAsync(action));

        if (!string.IsNullOrEmpty(path))
        {
            ShowToast($"Saved to Downloads\\SanDen\\{item.Name}", false);
            ContinuityHubService.OpenFileOrFolder(path);
        }
    }

    // ── Device Discovery & USB Bridge Actions ────────────────────────────────

    private async void ConnectManualIpBtn_Click(object sender, RoutedEventArgs e)
    {
        var raw = ManualIpTextBox.Text.Trim();
        if (string.IsNullOrEmpty(raw)) return;

        ShowToast($"Connecting to {raw}…", false);
        var peer = await _hub.ProbeDeviceInfoAsync(raw, isUsb: raw.Contains("127.0.0.1"));
        if (peer != null)
        {
            _activeBaseUrl = peer.Url;
            UpdateConnectedDeviceCard(peer);
            await _hub.FetchChannelsAsync(_activeBaseUrl);
            await _hub.FetchMessagesAsync(_activeBaseUrl, _activeChannel);
            await _hub.FetchVaultFilesAsync(_activeBaseUrl);
            SwitchWorkspaceTab("Chat");
            ShowToast($"Connected to {peer.Name}!", false);
        }
        else
        {
            ShowToast("Could not reach SanDen on that IP. Make sure SanDen is open on your phone.", true);
        }
    }

    private async void ConnectUsbBridgeBtn_Click(object sender, RoutedEventArgs e)
    {
        ShowToast("Setting up USB ADB Bridge…", false);
        bool ok = await _hub.TryDiscoverViaAdbHintOrBridgeAsync(silent: false);
        if (ok)
        {
            var first = _peers.FirstOrDefault();
            if (first != null)
            {
                _activeBaseUrl = first.Url;
                UpdateConnectedDeviceCard(first);
                await _hub.FetchChannelsAsync(_activeBaseUrl);
                await _hub.FetchMessagesAsync(_activeBaseUrl, _activeChannel);
                await _hub.FetchVaultFilesAsync(_activeBaseUrl);
                SwitchWorkspaceTab("Chat");
            }
        }
    }

    private void ConnectUsbOrProbe_Click(object sender, MouseButtonEventArgs e)
    {
        if (string.IsNullOrEmpty(_activeBaseUrl))
        {
            ConnectUsbBridgeBtn_Click(sender, new RoutedEventArgs());
        }
        else
        {
            SwitchWorkspaceTab("Devices");
        }
    }

    private async void SelectPeerDeviceBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button btn || btn.Tag is not PeerDevice peer) return;
        _activeBaseUrl = peer.Url;
        UpdateConnectedDeviceCard(peer);
        await _hub.FetchChannelsAsync(_activeBaseUrl);
        await _hub.FetchMessagesAsync(_activeBaseUrl, _activeChannel);
        await _hub.FetchVaultFilesAsync(_activeBaseUrl);
        SwitchWorkspaceTab("Chat");
    }

    private async void SyncMeshBtn_Click(object sender, RoutedEventArgs e)
    {
        ShowToast("Syncing missed messages & files across all peers…", false);
        await _hub.SyncMeshNowAsync(_activeChannel);
    }

    private void ToggleHiddenVault_Click(object sender, RoutedEventArgs e)
    {
        _hub.Store.HiddenVaultMode = !_hub.Store.HiddenVaultMode;
        UpdateHiddenVaultToggleUi();
        ShowToast(
            _hub.Store.HiddenVaultMode
                ? "Hidden Vault ON — new PC downloads save inside hidden .Vault folder (SHA-256 deduplicated)."
                : "Hidden Vault OFF — new PC downloads save to Downloads\\SanDen (SHA-256 deduplicated).",
            false);
    }

    private void UpdateHiddenVaultToggleUi()
    {
        bool hidden = _hub.Store.HiddenVaultMode;
        HiddenVaultToggleText.Text = hidden ? "🔒 Hidden Vault: On" : "🔓 Hidden Vault: Off";
        HiddenVaultToggleBd.Background = new SolidColorBrush(hidden
            ? Color.FromRgb(0x4C, 0x1D, 0x24)
            : Color.FromRgb(0x18, 0x1D, 0x2B));
        HiddenVaultToggleText.Foreground = new SolidColorBrush(hidden
            ? Color.FromRgb(0xFC, 0xA5, 0xA5)
            : Color.FromRgb(0xCB, 0xD5, 0xE1));
    }

    private async void RefreshNow_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrEmpty(_activeBaseUrl))
        {
            ShowToast("Scanning local network & USB for SanDen devices…", false);
            await _hub.TryDiscoverViaAdbHintOrBridgeAsync(silent: true);
            _ = _hub.ProbeLocalSubnetsAsync();
            await _hub.SyncMeshNowAsync(_activeChannel);
            return;
        }

        await _hub.ProbeDeviceInfoAsync(_activeBaseUrl, _activeBaseUrl.Contains("127.0.0.1"));
        await _hub.FetchChannelsAsync(_activeBaseUrl);
        await _hub.SyncMeshNowAsync(_activeChannel);
        await _hub.FetchVaultFilesAsync(_activeBaseUrl);
    }

    private void ShowToast(string text, bool isError, bool clickToPrivate = false)
    {
        _toastSwitchesToPrivate = clickToPrivate;
        InlineToastText.Text = text;
        InlineToastBanner.Background = new SolidColorBrush(isError
            ? Color.FromRgb(0x3B, 0x12, 0x19)
            : Color.FromRgb(0x11, 0x26, 0x22));
        InlineToastBanner.BorderBrush = new SolidColorBrush(isError
            ? Color.FromArgb(0x88, 0xEF, 0x44, 0x44)
            : Color.FromArgb(0x88, 0x22, 0xC5, 0x5E));
        InlineToastText.Foreground = new SolidColorBrush(isError
            ? Color.FromRgb(0xFC, 0xA5, 0xA5)
            : Color.FromRgb(0x86, 0xEF, 0xAC));
        InlineToastBanner.Visibility = Visibility.Visible;

        _toastTimer?.Stop();
        _toastTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(4) };
        _toastTimer.Tick += (_, _) =>
        {
            InlineToastBanner.Visibility = Visibility.Collapsed;
            _toastSwitchesToPrivate = false;
            _toastTimer.Stop();
        };
        _toastTimer.Start();
    }

    private void ToastBanner_Click(object sender, MouseButtonEventArgs e)
    {
        InlineToastBanner.Visibility = Visibility.Collapsed;
        _toastTimer?.Stop();
        if (_toastSwitchesToPrivate)
        {
            _toastSwitchesToPrivate = false;
            SwitchChatScope(true);
            SwitchWorkspaceTab("Chat");
        }
    }

    private void DismissToast_Click(object sender, RoutedEventArgs e)
    {
        InlineToastBanner.Visibility = Visibility.Collapsed;
        _toastSwitchesToPrivate = false;
        _toastTimer?.Stop();
    }
}
