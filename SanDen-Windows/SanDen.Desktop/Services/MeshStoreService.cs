using System.IO;
using System.Security.Cryptography;
using System.Text.Json;

namespace SanDen.Desktop.Services;

public sealed class StoredMeshMessage
{
    public long Id { get; set; }
    public string MsgUid { get; set; } = "";
    public string Channel { get; set; } = "General";
    public string Sender { get; set; } = "PHONE";
    public string TargetPeerId { get; set; } = "";
    public string Type { get; set; } = "TEXT";
    public string TextContent { get; set; } = "";
    public string FileName { get; set; } = "";
    public long FileSize { get; set; }
    public string FileHash { get; set; } = "";
    public string MimeType { get; set; } = "";
    public long Timestamp { get; set; }
    public List<string> SyncedPeers { get; set; } = [];
}

internal sealed class HashIndexData
{
    public Dictionary<string, string> HashToPath { get; set; } = new(StringComparer.OrdinalIgnoreCase);
    public Dictionary<string, string> PathStatToHash { get; set; } = new(StringComparer.OrdinalIgnoreCase);
}

internal sealed class MeshStoreData
{
    public bool HiddenVaultMode { get; set; }
    public long NextLocalId { get; set; } = 10_000;
    public HashSet<string> Channels { get; set; } = new(StringComparer.OrdinalIgnoreCase) { "General" };
    public List<StoredMeshMessage> Messages { get; set; } = [];
}

/// <summary>
/// Thread-safe persistent SHA-256 content index and idempotent mesh message store
/// so large videos are never duplicated on disk and offline peers catch up cleanly.
/// </summary>
public sealed class MeshStoreService
{
    private static readonly JsonSerializerOptions JsonOpts = new() { WriteIndented = false };
    private readonly object _sync = new();
    private readonly string _appDataDir;
    private readonly string _hashIndexPath;
    private readonly string _meshStorePath;
    private readonly string _saveDir;
    private readonly string _hiddenVaultDir;
    private HashIndexData _hashData = new();
    private MeshStoreData _meshData = new();
    private bool _dirty;

    public string SaveDirectory => _saveDir;
    public string HiddenVaultDirectory => _hiddenVaultDir;

    public bool HiddenVaultMode
    {
        get { lock (_sync) return _meshData.HiddenVaultMode; }
        set
        {
            lock (_sync)
            {
                if (_meshData.HiddenVaultMode != value)
                {
                    _meshData.HiddenVaultMode = value;
                    EnsureHiddenVaultAttributes();
                    PersistMeshLocked();
                }
            }
        }
    }

    public string ActiveReceiveDirectory
    {
        get
        {
            lock (_sync)
            {
                if (_meshData.HiddenVaultMode)
                {
                    EnsureHiddenVaultAttributes();
                    return _hiddenVaultDir;
                }
                Directory.CreateDirectory(_saveDir);
                return _saveDir;
            }
        }
    }

    public MeshStoreService(string saveDirectory)
    {
        _saveDir = saveDirectory;
        _hiddenVaultDir = Path.Combine(saveDirectory, ".Vault");
        _appDataDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "SanDen");
        _hashIndexPath = Path.Combine(_appDataDir, "hash_index.json");
        _meshStorePath = Path.Combine(_appDataDir, "mesh_store.json");

        Directory.CreateDirectory(_appDataDir);
        Directory.CreateDirectory(_saveDir);
        LoadFromDisk();
    }

    private void EnsureHiddenVaultAttributes()
    {
        try
        {
            var di = Directory.CreateDirectory(_hiddenVaultDir);
            di.Attributes |= FileAttributes.Hidden;
        }
        catch { }
    }

    private void LoadFromDisk()
    {
        lock (_sync)
        {
            try
            {
                if (File.Exists(_hashIndexPath))
                {
                    var json = File.ReadAllText(_hashIndexPath);
                    _hashData = JsonSerializer.Deserialize<HashIndexData>(json) ?? new HashIndexData();
                }
            }
            catch { _hashData = new HashIndexData(); }

            try
            {
                if (File.Exists(_meshStorePath))
                {
                    var json = File.ReadAllText(_meshStorePath);
                    _meshData = JsonSerializer.Deserialize<MeshStoreData>(json) ?? new MeshStoreData();
                    _meshData.Channels ??= new HashSet<string>(StringComparer.OrdinalIgnoreCase) { "General" };
                    _meshData.Messages ??= [];
                    if (!_meshData.Channels.Contains("General")) _meshData.Channels.Add("General");
                }
            }
            catch { _meshData = new MeshStoreData(); }
        }
    }

    private void PersistHashesLocked()
    {
        try
        {
            var tmp = _hashIndexPath + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(_hashData, JsonOpts));
            File.Move(tmp, _hashIndexPath, overwrite: true);
        }
        catch { }
    }

    private void PersistMeshLocked()
    {
        try
        {
            var tmp = _meshStorePath + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(_meshData, JsonOpts));
            File.Move(tmp, _meshStorePath, overwrite: true);
            _dirty = false;
        }
        catch { }
    }

    public static string BuildStatKey(string path, long length, long ticksUtc)
        => $"{path}|{length}|{ticksUtc}";

    public string ComputeOrGetSha256(string filePath)
    {
        if (string.IsNullOrWhiteSpace(filePath) || !File.Exists(filePath)) return "";
        try
        {
            var fi = new FileInfo(filePath);
            var statKey = BuildStatKey(fi.FullName, fi.Length, fi.LastWriteTimeUtc.Ticks);
            lock (_sync)
            {
                if (_hashData.PathStatToHash.TryGetValue(statKey, out var cached) &&
                    !string.IsNullOrWhiteSpace(cached))
                {
                    RegisterHashLocked(cached, fi.FullName, save: false);
                    return cached;
                }
            }

            string hash;
            using (var fs = new FileStream(fi.FullName, FileMode.Open, FileAccess.Read, FileShare.ReadWrite, 262144))
            using (var sha = SHA256.Create())
            {
                hash = Convert.ToHexStringLower(sha.ComputeHash(fs));
            }

            lock (_sync)
            {
                _hashData.PathStatToHash[statKey] = hash;
                RegisterHashLocked(hash, fi.FullName, save: true);
            }
            return hash;
        }
        catch
        {
            return "";
        }
    }

    public void RegisterLocalFile(string sha256, string filePath)
    {
        if (string.IsNullOrWhiteSpace(sha256) || string.IsNullOrWhiteSpace(filePath) || !File.Exists(filePath)) return;
        lock (_sync)
        {
            var fi = new FileInfo(filePath);
            var statKey = BuildStatKey(fi.FullName, fi.Length, fi.LastWriteTimeUtc.Ticks);
            _hashData.PathStatToHash[statKey] = sha256.Trim().ToLowerInvariant();
            RegisterHashLocked(sha256.Trim().ToLowerInvariant(), fi.FullName, save: true);
        }
    }

    private void RegisterHashLocked(string shaLower, string fullPath, bool save)
    {
        if (string.IsNullOrWhiteSpace(shaLower)) return;
        // Prefer preserving original non-Downloads source path (e.g. D:\RawFootage\clip.mp4) to avoid duplicate moves
        if (_hashData.HashToPath.TryGetValue(shaLower, out var existing) && File.Exists(existing))
        {
            bool existingInDownloads = existing.StartsWith(_saveDir, StringComparison.OrdinalIgnoreCase);
            bool newInDownloads = fullPath.StartsWith(_saveDir, StringComparison.OrdinalIgnoreCase);
            if (!existingInDownloads || newInDownloads)
            {
                if (save) PersistHashesLocked();
                return;
            }
        }
        _hashData.HashToPath[shaLower] = fullPath;
        if (save) PersistHashesLocked();
    }

    public bool IsZeroCopyOriginPath(string? path)
    {
        if (string.IsNullOrWhiteSpace(path)) return false;
        return !path.StartsWith(_saveDir, StringComparison.OrdinalIgnoreCase);
    }

    public string? ResolveExistingFile(string? sha256, long expectedSize = -1)
    {
        var key = (sha256 ?? "").Trim().ToLowerInvariant();
        if (string.IsNullOrEmpty(key)) return null;

        lock (_sync)
        {
            if (_hashData.HashToPath.TryGetValue(key, out var p) && File.Exists(p))
            {
                if (expectedSize <= 0 || new FileInfo(p).Length == expectedSize)
                    return p;
            }
        }

        // Lazy scan Downloads\SanDen & .Vault for matching size files not yet indexed
        foreach (var dir in new[] { _saveDir, _hiddenVaultDir })
        {
            if (!Directory.Exists(dir)) continue;
            try
            {
                foreach (var f in Directory.EnumerateFiles(dir))
                {
                    var fi = new FileInfo(f);
                    if (expectedSize > 0 && fi.Length != expectedSize) continue;
                    var h = ComputeOrGetSha256(f);
                    if (h.Equals(key, StringComparison.OrdinalIgnoreCase))
                        return f;
                }
            }
            catch { }
        }
        return null;
    }

    public string? ResolveUniqueNonDuplicateDestination(string safeName, string incomingHash, long expectedSize = -1)
    {
        if (!string.IsNullOrEmpty(incomingHash))
        {
            var existing = ResolveExistingFile(incomingHash, expectedSize);
            if (!string.IsNullOrEmpty(existing)) return existing;
        }
        return null;
    }

    public void AddChannel(string channel)
    {
        if (string.IsNullOrWhiteSpace(channel)) return;
        lock (_sync)
        {
            if (_meshData.Channels.Add(channel.Trim()))
                PersistMeshLocked();
        }
    }

    public List<string> GetChannels()
    {
        lock (_sync) return _meshData.Channels.OrderBy(c => c.Equals("General", StringComparison.OrdinalIgnoreCase) ? "" : c).ToList();
    }

    public StoredMeshMessage MergeOrUpdate(StoredMeshMessage incoming, string? sourcePeerId = null)
    {
        lock (_sync)
        {
            _meshData.Channels.Add(string.IsNullOrWhiteSpace(incoming.Channel) ? "General" : incoming.Channel);

            StoredMeshMessage? match = null;
            if (!string.IsNullOrWhiteSpace(incoming.MsgUid))
            {
                match = _meshData.Messages.FirstOrDefault(m =>
                    !string.IsNullOrWhiteSpace(m.MsgUid) &&
                    m.MsgUid.Equals(incoming.MsgUid, StringComparison.OrdinalIgnoreCase));
            }
            match ??= _meshData.Messages.FirstOrDefault(m =>
                m.Channel.Equals(incoming.Channel, StringComparison.OrdinalIgnoreCase) &&
                m.Sender.Equals(incoming.Sender, StringComparison.OrdinalIgnoreCase) &&
                Math.Abs(m.Timestamp - incoming.Timestamp) < 1500 &&
                m.Type.Equals(incoming.Type, StringComparison.OrdinalIgnoreCase) &&
                m.TextContent == incoming.TextContent &&
                m.FileName == incoming.FileName);

            if (match != null)
            {
                if (string.IsNullOrEmpty(match.FileHash) && !string.IsNullOrEmpty(incoming.FileHash))
                    match.FileHash = incoming.FileHash;
                if (!string.IsNullOrWhiteSpace(sourcePeerId) && !match.SyncedPeers.Contains(sourcePeerId))
                {
                    match.SyncedPeers.Add(sourcePeerId);
                    _dirty = true;
                }
                if (_dirty) PersistMeshLocked();
                return match;
            }

            if (incoming.Id <= 0)
            {
                incoming.Id = ++_meshData.NextLocalId;
            }
            else if (incoming.Id >= _meshData.NextLocalId)
            {
                _meshData.NextLocalId = incoming.Id + 1;
            }
            if (string.IsNullOrWhiteSpace(incoming.MsgUid))
            {
                incoming.MsgUid = $"local_{incoming.Id}_{incoming.Timestamp}";
            }
            if (!string.IsNullOrWhiteSpace(sourcePeerId) && !incoming.SyncedPeers.Contains(sourcePeerId))
            {
                incoming.SyncedPeers.Add(sourcePeerId);
            }

            _meshData.Messages.Add(incoming);
            if (_meshData.Messages.Count > 2500)
            {
                _meshData.Messages.RemoveRange(0, _meshData.Messages.Count - 2500);
            }
            PersistMeshLocked();
            return incoming;
        }
    }

    public List<StoredMeshMessage> GetMessagesForChannel(string channel, string requesterId)
    {
        lock (_sync)
        {
            return _meshData.Messages
                .Where(m => m.Channel.Equals(channel, StringComparison.OrdinalIgnoreCase) &&
                            (string.IsNullOrEmpty(m.TargetPeerId) ||
                             (!string.IsNullOrEmpty(requesterId) && m.TargetPeerId.Equals(requesterId, StringComparison.OrdinalIgnoreCase))))
                .OrderBy(m => m.Timestamp)
                .ToList();
        }
    }

    public List<StoredMeshMessage> GetMessagesSince(long sinceMs, string requesterId)
    {
        lock (_sync)
        {
            return _meshData.Messages
                .Where(m => m.Timestamp >= sinceMs &&
                            (string.IsNullOrEmpty(m.TargetPeerId) ||
                             (!string.IsNullOrEmpty(requesterId) && m.TargetPeerId.Equals(requesterId, StringComparison.OrdinalIgnoreCase))))
                .OrderBy(m => m.Timestamp)
                .Take(500)
                .ToList();
        }
    }

    public StoredMeshMessage? GetMessageById(long id)
    {
        lock (_sync) return _meshData.Messages.FirstOrDefault(m => m.Id == id);
    }

    public StoredMeshMessage? GetMessageByUid(string msgUid)
    {
        if (string.IsNullOrWhiteSpace(msgUid)) return null;
        lock (_sync) return _meshData.Messages.FirstOrDefault(m => m.MsgUid.Equals(msgUid, StringComparison.OrdinalIgnoreCase));
    }

    public StoredMeshMessage? FindByFileHash(string sha256)
    {
        if (string.IsNullOrWhiteSpace(sha256)) return null;
        lock (_sync) return _meshData.Messages.FirstOrDefault(m => m.FileHash.Equals(sha256, StringComparison.OrdinalIgnoreCase));
    }

    public List<string> GetAllKnownHashes()
    {
        lock (_sync) return _hashData.HashToPath.Where(kv => File.Exists(kv.Value)).Select(kv => kv.Key).ToList();
    }
}
