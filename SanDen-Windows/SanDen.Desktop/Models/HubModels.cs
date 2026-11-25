using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Text.Json.Serialization;
using System.Windows.Media;

namespace SanDen.Desktop.Models;

public abstract class ObservableObject : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;

    protected void OnPropertyChanged([CallerMemberName] string? name = null)
        => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));

    protected bool SetField<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return false;
        field = value;
        OnPropertyChanged(name);
        return true;
    }
}

public sealed class PeerDevice : ObservableObject
{
    private string _id = "";
    private string _name = "Android Phone";
    private string _ip = "";
    private int _port = 54321;
    private string _url = "";
    private string _deviceType = "ANDROID";
    private bool _isUsb;
    private int _batteryPct = -1;
    private long _vaultUsedBytes;
    private int _vaultFileCount;
    private long _storageTotalBytes;
    private long _storageFreeBytes;
    private DateTime _lastSeen = DateTime.UtcNow;

    public string Id { get => _id; set => SetField(ref _id, value); }
    public string Name { get => _name; set => SetField(ref _name, value); }
    public string Ip { get => _ip; set { if (SetField(ref _ip, value)) OnPropertyChanged(nameof(EndpointDisplay)); } }
    public int Port { get => _port; set { if (SetField(ref _port, value)) OnPropertyChanged(nameof(EndpointDisplay)); } }
    public string Url { get => _url; set => SetField(ref _url, value); }
    public string DeviceType { get => _deviceType; set => SetField(ref _deviceType, value); }
    public bool IsUsb { get => _isUsb; set { if (SetField(ref _isUsb, value)) OnPropertyChanged(nameof(ConnectionBadge)); } }
    public int BatteryPct { get => _batteryPct; set { if (SetField(ref _batteryPct, value)) OnPropertyChanged(nameof(BatteryDisplay)); } }
    public long VaultUsedBytes { get => _vaultUsedBytes; set => SetField(ref _vaultUsedBytes, value); }
    public int VaultFileCount { get => _vaultFileCount; set => SetField(ref _vaultFileCount, value); }
    public long StorageTotalBytes { get => _storageTotalBytes; set => SetField(ref _storageTotalBytes, value); }
    public long StorageFreeBytes { get => _storageFreeBytes; set => SetField(ref _storageFreeBytes, value); }
    public DateTime LastSeen { get => _lastSeen; set => SetField(ref _lastSeen, value); }

    public string EndpointDisplay => string.IsNullOrWhiteSpace(Ip)
        ? Url.Replace("http://", "").Replace("https://", "")
        : $"{Ip}:{Port}";

    public string BatteryDisplay => BatteryPct is >= 0 and <= 100 ? $"{BatteryPct}%" : "Live";
    public string ConnectionBadge => IsUsb ? "USB Bridge" : "Connected";
}

public sealed class SyncMessageViewModel : ObservableObject
{
    private bool _isExpanded;
    private ImageSource? _thumbnail;
    private string? _localCachedPath;
    private string _videoDurationText = "";
    private int _syncedPeerCount;
    private bool _isZeroCopyOrigin;

    public long Id { get; set; }
    public string MsgUid { get; set; } = "";
    public string Channel { get; set; } = "General";
    public string Branch { get; set; } = "main";
    public string Sender { get; set; } = "PHONE";
    public string TargetPeerId { get; set; } = "";
    public string Type { get; set; } = "TEXT"; // TEXT, CLIPBOARD, FILE
    public string TextContent { get; set; } = "";
    public string FileName { get; set; } = "";
    public long FileSize { get; set; }
    public string FileHash { get; set; } = "";
    public string MimeType { get; set; } = "";
    public long Timestamp { get; set; }

    public bool IsOutgoing =>
        Sender.Equals("LAPTOP", StringComparison.OrdinalIgnoreCase) ||
        Sender.Equals("PC", StringComparison.OrdinalIgnoreCase) ||
        Sender.Equals(Environment.MachineName, StringComparison.OrdinalIgnoreCase);

    public bool IsIncoming => !IsOutgoing;
    public bool IsFile => Type.Equals("FILE", StringComparison.OrdinalIgnoreCase);
    public bool IsClipboard => Type.Equals("CLIPBOARD", StringComparison.OrdinalIgnoreCase);
    public bool IsImageFile => IsFile && FileFormatHelper.IsImage(FileName, MimeType);
    public bool IsVideoFile => IsFile && FileFormatHelper.IsVideo(FileName, MimeType);
    public bool IsMediaFile => IsImageFile || IsVideoFile;
    public bool IsNonMediaFile => IsFile && !IsMediaFile;

    public string SenderDisplay => IsOutgoing ? "LAPTOP (You)" : Sender;

    public string TimeDisplay
    {
        get
        {
            if (Timestamp <= 0) return DateTime.Now.ToString("HH:mm");
            var dt = DateTimeOffset.FromUnixTimeMilliseconds(Timestamp).LocalDateTime;
            return dt.ToString("HH:mm");
        }
    }

    public int TotalLines => string.IsNullOrEmpty(TextContent)
        ? 1
        : TextContent.Split('\n').Length;

    public bool CanExpand => !IsFile && (TextContent.Length > 340 || TotalLines > 6);

    public bool IsExpanded
    {
        get => _isExpanded;
        set
        {
            if (SetField(ref _isExpanded, value))
            {
                OnPropertyChanged(nameof(DisplayedText));
                OnPropertyChanged(nameof(ExpandButtonLabel));
            }
        }
    }

    public string DisplayedText
    {
        get
        {
            if (!CanExpand || IsExpanded) return TextContent;
            var lines = TextContent.Split('\n');
            if (lines.Length > 5)
                return string.Join('\n', lines.Take(5)).TrimEnd() + "…";
            return TextContent[..Math.Min(320, TextContent.Length)].TrimEnd() + "…";
        }
    }

    public string ExpandButtonLabel => IsExpanded
        ? "Show less"
        : $"Expand all ({TotalLines} lines)";

    public string FileSizeDisplay => FileFormatHelper.FormatSize(FileSize);

    public ImageSource? Thumbnail
    {
        get => _thumbnail;
        set { if (SetField(ref _thumbnail, value)) OnPropertyChanged(nameof(HasThumbnail)); }
    }

    public bool HasThumbnail => _thumbnail != null;

    public string? LocalCachedPath
    {
        get => _localCachedPath;
        set
        {
            if (SetField(ref _localCachedPath, value))
            {
                OnPropertyChanged(nameof(HasLocalCopy));
                OnPropertyChanged(nameof(StorageBadgeText));
            }
        }
    }

    public int SyncedPeerCount
    {
        get => _syncedPeerCount;
        set
        {
            if (SetField(ref _syncedPeerCount, value))
            {
                OnPropertyChanged(nameof(StorageBadgeText));
            }
        }
    }

    public bool IsZeroCopyOrigin
    {
        get => _isZeroCopyOrigin;
        set
        {
            if (SetField(ref _isZeroCopyOrigin, value))
            {
                OnPropertyChanged(nameof(StorageBadgeText));
            }
        }
    }

    public bool HasLocalCopy => !string.IsNullOrEmpty(_localCachedPath);

    public string StorageBadgeText
    {
        get
        {
            if (!IsFile) return "";
            if (_isZeroCopyOrigin) return "Zero-Copy Source";
            if (!string.IsNullOrEmpty(_localCachedPath)) return "On Disk • Deduplicated";
            return _syncedPeerCount > 0 ? $"Available on {_syncedPeerCount} peer(s)" : "Click Open to sync";
        }
    }

    public string VideoDurationText
    {
        get => _videoDurationText;
        set
        {
            if (SetField(ref _videoDurationText, value))
            {
                OnPropertyChanged(nameof(MediaBadgeText));
            }
        }
    }

    public string MediaBadgeText => IsVideoFile
        ? (string.IsNullOrEmpty(VideoDurationText)
            ? $"VIDEO • {FileSizeDisplay}"
            : $"{VideoDurationText} • {FileSizeDisplay}")
        : $"{FileFormatHelper.GetExtensionBadge(FileName)} • {FileSizeDisplay}";

    public string FileExtBadge => FileFormatHelper.GetExtensionBadge(FileName);
}

public sealed class VaultFileItem : ObservableObject
{
    private ImageSource? _thumbnail;

    public long Id { get; set; }
    public string Name { get; set; } = "";
    public long Size { get; set; }
    public string Category { get; set; } = "OTHER";
    public bool IsDirectory { get; set; }
    public long LastModified { get; set; }
    public string SourceDeviceName { get; set; } = "Phone";

    public string SizeDisplay => FileFormatHelper.FormatSize(Size);
    public string SubtitleDisplay => $"{SizeDisplay} • from {SourceDeviceName}";
    public bool IsImage => Category.Equals("IMAGE", StringComparison.OrdinalIgnoreCase)
                           || FileFormatHelper.IsImage(Name, "");
    public bool IsVideo => Category.Equals("VIDEO", StringComparison.OrdinalIgnoreCase)
                           || FileFormatHelper.IsVideo(Name, "");
    public bool IsMedia => IsImage || IsVideo;
    public string ShortBadge => IsDirectory ? "DIR" : FileFormatHelper.GetExtensionBadge(Name);

    public ImageSource? Thumbnail
    {
        get => _thumbnail;
        set { if (SetField(ref _thumbnail, value)) OnPropertyChanged(nameof(HasThumbnail)); }
    }

    public bool HasThumbnail => _thumbnail != null;
}

public sealed class TransferProgressItem : ObservableObject
{
    private double _progress;
    private string _statusText = "Starting…";
    private bool _isCompleted;
    private bool _isFailed;
    private string? _savedPath;

    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string FileName { get; set; } = "";
    public string Direction { get; set; } = "UPLOAD"; // UPLOAD or DOWNLOAD
    public long TotalBytes { get; set; }
    public string PeerName { get; set; } = "Phone";
    public DateTime StartedAt { get; set; } = DateTime.Now;

    public double Progress { get => _progress; set { if (SetField(ref _progress, value)) OnPropertyChanged(nameof(PercentText)); } }
    public string PercentText => $"{(int)Math.Round(Progress * 100)}%";
    public string StatusText { get => _statusText; set => SetField(ref _statusText, value); }
    public bool IsCompleted { get => _isCompleted; set => SetField(ref _isCompleted, value); }
    public bool IsFailed { get => _isFailed; set => SetField(ref _isFailed, value); }
    public string? SavedPath { get => _savedPath; set => SetField(ref _savedPath, value); }
}

public sealed class ProjectItem : ObservableObject
{
    private bool _isSelected;
    public string Name { get; set; } = "General";
    public string IconKind { get; set; } = "chat"; // chat, video, code, folder
    public bool IsSelected { get => _isSelected; set => SetField(ref _isSelected, value); }
}

public sealed class BranchItem : ObservableObject
{
    private bool _isSelected;
    public string Name { get; set; } = "main";
    public bool IsSelected { get => _isSelected; set => SetField(ref _isSelected, value); }
}

public static class FileFormatHelper
{
    public static string FormatSize(long bytes)
    {
        if (bytes <= 0) return "0 B";
        string[] units = ["B", "KB", "MB", "GB", "TB"];
        int idx = 0;
        double val = bytes;
        while (val >= 1024.0 && idx < units.Length - 1)
        {
            val /= 1024.0;
            idx++;
        }
        return idx == 0 ? $"{bytes} B" : $"{val:F1} {units[idx]}";
    }

    public static bool IsImage(string fileName, string mime)
    {
        var m = (mime ?? "").ToLowerInvariant();
        var n = (fileName ?? "").ToLowerInvariant();
        return m.StartsWith("image/") ||
               n.EndsWith(".jpg") || n.EndsWith(".jpeg") || n.EndsWith(".png") ||
               n.EndsWith(".webp") || n.EndsWith(".gif") || n.EndsWith(".bmp");
    }

    public static bool IsVideo(string fileName, string mime)
    {
        var m = (mime ?? "").ToLowerInvariant();
        var n = (fileName ?? "").ToLowerInvariant();
        return m.StartsWith("video/") ||
               n.EndsWith(".mp4") || n.EndsWith(".mkv") || n.EndsWith(".mov") ||
               n.EndsWith(".webm") || n.EndsWith(".avi") || n.EndsWith(".3gp") || n.EndsWith(".m4v");
    }

    public static string GetExtensionBadge(string fileName)
    {
        var ext = System.IO.Path.GetExtension(fileName ?? "").TrimStart('.').ToUpperInvariant();
        return string.IsNullOrWhiteSpace(ext) ? "FILE" : ext;
    }

    public static string FormatDurationMs(long ms)
    {
        if (ms <= 0) return "";
        var ts = TimeSpan.FromMilliseconds(ms);
        return ts.TotalHours >= 1
            ? $"{(int)ts.TotalHours}:{ts.Minutes:D2}:{ts.Seconds:D2}"
            : $"{ts.Minutes}:{ts.Seconds:D2}";
    }
}
