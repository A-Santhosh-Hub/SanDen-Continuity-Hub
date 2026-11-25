using System.Collections.Concurrent;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using SanDen.Desktop.Models;

namespace SanDen.Desktop.Services;

public sealed partial class ContinuityHubService
{
    private readonly ConcurrentDictionary<string, PeerDevice> _knownPeers = new(StringComparer.OrdinalIgnoreCase);
    private readonly SemaphoreSlim _meshSyncLock = new(1, 1);
    private TcpListener? _meshListener;

    public int LocalHttpPort { get; private set; }
    public string LocalPeerUrl { get; private set; } = "";

    private async Task RunEmbeddedMeshServerAsync(CancellationToken ct)
    {
        int[] candidatePorts = [DefaultPort, 54323, 54324, 54325, 54326, 54327, 54328, 54329, 54330];
        TcpListener? listener = null;
        int boundPort = 0;

        foreach (var port in candidatePorts)
        {
            try
            {
                var l = new TcpListener(IPAddress.Any, port);
                l.Start();
                listener = l;
                boundPort = port;
                break;
            }
            catch { }
        }

        if (listener == null) return;
        _meshListener = listener;
        LocalHttpPort = boundPort;
        var lanIp = GetActiveLocalIPv4Addresses().FirstOrDefault()?.ToString() ?? "127.0.0.1";
        LocalPeerUrl = $"http://{lanIp}:{boundPort}";

        try
        {
            while (!ct.IsCancellationRequested)
            {
                var client = await listener.AcceptTcpClientAsync(ct).ConfigureAwait(false);
                _ = Task.Run(() => HandleIncomingPeerConnectionAsync(client, ct), ct);
            }
        }
        catch { }
        finally
        {
            try { listener.Stop(); } catch { }
        }
    }

    private async Task HandleIncomingPeerConnectionAsync(TcpClient client, CancellationToken ct)
    {
        using (client)
        {
            try
            {
                client.ReceiveTimeout = 30_000;
                client.SendTimeout = 120_000;
                await using var stream = client.GetStream();

                var headerBytes = await ReadHttpHeaderBlockAsync(stream, ct).ConfigureAwait(false);
                if (headerBytes.Length == 0) return;

                var headerText = Encoding.UTF8.GetString(headerBytes);
                var lines = headerText.Split(["\r\n"], StringSplitOptions.None);
                if (lines.Length == 0) return;

                var reqParts = lines[0].Split(' ');
                if (reqParts.Length < 2) return;
                var method = reqParts[0].ToUpperInvariant();
                var rawTarget = reqParts[1];
                int qIdx = rawTarget.IndexOf('?', StringComparison.Ordinal);
                var path = qIdx >= 0 ? rawTarget[..qIdx] : rawTarget;
                var query = qIdx >= 0 ? rawTarget[(qIdx + 1)..] : "";

                var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                for (int i = 1; i < lines.Length; i++)
                {
                    if (string.IsNullOrEmpty(lines[i])) break;
                    int c = lines[i].IndexOf(':', StringComparison.Ordinal);
                    if (c > 0)
                    {
                        headers[lines[i][..c].Trim()] = lines[i][(c + 1)..].Trim();
                    }
                }

                // Origin CSRF protection
                if (headers.TryGetValue("Origin", out var origin) && !IsTrustedOrigin(origin, headers.GetValueOrDefault("Host")))
                {
                    await WriteJsonResponseAsync(stream, 403, "Forbidden", """{"error":"Untrusted origin"}""", ct).ConfigureAwait(false);
                    return;
                }

                // Optional Passkey verification
                var configuredKey = GetConfiguredPasskey();
                if (!string.IsNullOrEmpty(configuredKey) && !path.Equals("/api/info", StringComparison.OrdinalIgnoreCase))
                {
                    var provided = headers.GetValueOrDefault("X-SanDen-Key") ?? ParseQueryParam(query, "key");
                    if (!FixedTimeEqualsSha256(configuredKey, provided))
                    {
                        await WriteJsonResponseAsync(stream, 401, "Unauthorized", """{"error":"Invalid passkey"}""", ct).ConfigureAwait(false);
                        return;
                    }
                }

                switch (method, path)
                {
                    case ("GET", "/api/info"):
                        var infoJson = JsonSerializer.Serialize(new
                        {
                            deviceId = PcId,
                            deviceName = PcName,
                            deviceType = "PC",
                            url = LocalPeerUrl,
                            vaultMode = Store.HiddenVaultMode ? "HIDDEN_VAULT" : "ZERO_COPY_LOCAL",
                            batteryPct = -1,
                            vaultFileCount = Store.GetAllKnownHashes().Count,
                            authRequired = !string.IsNullOrEmpty(configuredKey)
                        });
                        await WriteJsonResponseAsync(stream, 200, "OK", infoJson, ct).ConfigureAwait(false);
                        break;

                    case ("GET", "/api/channels"):
                        await WriteJsonResponseAsync(stream, 200, "OK", JsonSerializer.Serialize(Store.GetChannels()), ct).ConfigureAwait(false);
                        break;

                    case ("GET", "/api/messages"):
                    {
                        var ch = ParseQueryParam(query, "channel");
                        if (string.IsNullOrWhiteSpace(ch)) ch = "General";
                        var reqId = ParseQueryParam(query, "requesterId");
                        var msgs = Store.GetMessagesForChannel(ch, reqId).TakeLast(100).Select(ToWireMessage).ToList();
                        await WriteJsonResponseAsync(stream, 200, "OK", JsonSerializer.Serialize(msgs), ct).ConfigureAwait(false);
                        break;
                    }

                    case ("GET", "/api/sync/pull"):
                    {
                        long.TryParse(ParseQueryParam(query, "since"), out var since);
                        var reqId = ParseQueryParam(query, "requesterId");
                        var pullObj = new
                        {
                            deviceId = PcId,
                            deviceName = PcName,
                            serverTimeMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                            channels = Store.GetChannels(),
                            messages = Store.GetMessagesSince(since, reqId).Select(ToWireMessage).ToList()
                        };
                        await WriteJsonResponseAsync(stream, 200, "OK", JsonSerializer.Serialize(pullObj), ct).ConfigureAwait(false);
                        break;
                    }

                    case ("GET", "/api/files/has"):
                    {
                        var sha = ParseQueryParam(query, "sha256").Trim().ToLowerInvariant();
                        var existing = Store.ResolveExistingFile(sha);
                        if (!string.IsNullOrEmpty(existing) && File.Exists(existing))
                        {
                            var fi = new FileInfo(existing);
                            var hasJson = JsonSerializer.Serialize(new { exists = true, fileName = fi.Name, fileSize = fi.Length, sha256 = sha });
                            await WriteJsonResponseAsync(stream, 200, "OK", hasJson, ct).ConfigureAwait(false);
                        }
                        else
                        {
                            await WriteJsonResponseAsync(stream, 200, "OK", """{"exists":false}""", ct).ConfigureAwait(false);
                        }
                        break;
                    }

                    case ("GET", "/api/blob"):
                    {
                        var sha = ParseQueryParam(query, "sha256").Trim().ToLowerInvariant();
                        var filePath = Store.ResolveExistingFile(sha);
                        if (string.IsNullOrEmpty(filePath) || !File.Exists(filePath))
                        {
                            await WriteJsonResponseAsync(stream, 404, "Not Found", """{"error":"Blob not found"}""", ct).ConfigureAwait(false);
                        }
                        else
                        {
                            await StreamFileResponseAsync(stream, filePath, ct).ConfigureAwait(false);
                        }
                        break;
                    }

                    case ("GET", "/api/messages/download"):
                    {
                        long.TryParse(ParseQueryParam(query, "id"), out var msgId);
                        var msg = Store.GetMessageById(msgId);
                        var filePath = msg != null ? Store.ResolveExistingFile(msg.FileHash, msg.FileSize) : null;
                        if (string.IsNullOrEmpty(filePath) || !File.Exists(filePath))
                        {
                            await WriteJsonResponseAsync(stream, 404, "Not Found", """{"error":"File not found"}""", ct).ConfigureAwait(false);
                        }
                        else
                        {
                            await StreamFileResponseAsync(stream, filePath, ct).ConfigureAwait(false);
                        }
                        break;
                    }

                    case ("POST", "/api/peers/announce"):
                    {
                        var body = await ReadBodyBytesAsync(stream, headers, 32_000, ct).ConfigureAwait(false);
                        if (body.Length > 0)
                        {
                            using var doc = JsonDocument.Parse(body);
                            var r = doc.RootElement;
                            var url = r.TryGetProperty("url", out var uEl) ? uEl.GetString() ?? "" : "";
                            if (!string.IsNullOrWhiteSpace(url))
                                _ = ProbeDeviceInfoAsync(url, isUsb: false, ct);
                        }
                        await WriteJsonResponseAsync(stream, 200, "OK", JsonSerializer.Serialize(new { status = "ok", deviceId = PcId }), ct).ConfigureAwait(false);
                        break;
                    }

                    case ("POST", "/api/messages"):
                    {
                        var body = await ReadBodyBytesAsync(stream, headers, 2_000_000, ct).ConfigureAwait(false);
                        if (body.Length == 0)
                        {
                            await WriteJsonResponseAsync(stream, 400, "Bad Request", """{"error":"Empty body"}""", ct).ConfigureAwait(false);
                            break;
                        }
                        using var doc = JsonDocument.Parse(body);
                        var r = doc.RootElement;
                        var ch = r.TryGetProperty("channel", out var chEl) ? chEl.GetString() ?? "General" : "General";
                        var sender = r.TryGetProperty("sender", out var sEl) ? sEl.GetString() ?? "Peer" : "Peer";
                        var originId = r.TryGetProperty("originId", out var oEl) ? oEl.GetString() ?? "" : "";
                        var reqTarget = r.TryGetProperty("targetPeerId", out var tEl) ? tEl.GetString() ?? "" : "";
                        var msgUid = r.TryGetProperty("msgUid", out var muEl) ? muEl.GetString() ?? "" : "";
                        var type = r.TryGetProperty("type", out var tyEl) ? tyEl.GetString() ?? "TEXT" : "TEXT";
                        var text = r.TryGetProperty("textContent", out var txEl) ? txEl.GetString() ?? "" : "";

                        if (!string.IsNullOrWhiteSpace(reqTarget) && !reqTarget.Equals(PcId, StringComparison.OrdinalIgnoreCase))
                        {
                            await WriteJsonResponseAsync(stream, 200, "OK", """{"status":"ignored_other_target"}""", ct).ConfigureAwait(false);
                            break;
                        }

                        var stored = Store.MergeOrUpdate(new StoredMeshMessage
                        {
                            MsgUid = msgUid,
                            Channel = string.IsNullOrWhiteSpace(ch) ? "General" : ch,
                            Sender = string.IsNullOrWhiteSpace(sender) ? "Peer" : sender,
                            TargetPeerId = string.IsNullOrWhiteSpace(reqTarget) ? "" : originId,
                            Type = type,
                            TextContent = text,
                            Timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
                        }, originId);

                        var vm = ToViewModel(stored);
                        LiveMessagePushed?.Invoke(vm);
                        await WriteJsonResponseAsync(stream, 200, "OK", JsonSerializer.Serialize(ToWireMessage(stored)), ct).ConfigureAwait(false);
                        break;
                    }

                    case ("POST", "/api/messages/link"):
                    {
                        var body = await ReadBodyBytesAsync(stream, headers, 64_000, ct).ConfigureAwait(false);
                        if (body.Length == 0)
                        {
                            await WriteJsonResponseAsync(stream, 400, "Bad Request", """{"error":"Empty body"}""", ct).ConfigureAwait(false);
                            break;
                        }
                        using var doc = JsonDocument.Parse(body);
                        var r = doc.RootElement;
                        var ch = r.TryGetProperty("channel", out var chEl) ? chEl.GetString() ?? "General" : "General";
                        var sender = r.TryGetProperty("sender", out var sEl) ? sEl.GetString() ?? "Peer" : "Peer";
                        var originId = r.TryGetProperty("originId", out var oEl) ? oEl.GetString() ?? "" : "";
                        var reqTarget = r.TryGetProperty("targetPeerId", out var tEl) ? tEl.GetString() ?? "" : "";
                        var msgUid = r.TryGetProperty("msgUid", out var muEl) ? muEl.GetString() ?? "" : "";
                        var fileHash = r.TryGetProperty("fileHash", out var fhEl) ? (fhEl.GetString() ?? "").Trim().ToLowerInvariant() : "";
                        var fileName = r.TryGetProperty("fileName", out var fnEl) ? fnEl.GetString() ?? "file" : "file";
                        var fileSize = r.TryGetProperty("fileSize", out var fsEl) && fsEl.TryGetInt64(out var fsv) ? fsv : 0L;
                        var mime = r.TryGetProperty("mimeType", out var mEl) ? mEl.GetString() ?? "" : "";

                        var existingLocal = Store.ResolveExistingFile(fileHash, fileSize);
                        if (string.IsNullOrEmpty(existingLocal))
                        {
                            await WriteJsonResponseAsync(stream, 404, "Not Found", """{"error":"Blob missing locally"}""", ct).ConfigureAwait(false);
                            break;
                        }

                        var fi = new FileInfo(existingLocal);
                        var stored = Store.MergeOrUpdate(new StoredMeshMessage
                        {
                            MsgUid = msgUid,
                            Channel = string.IsNullOrWhiteSpace(ch) ? "General" : ch,
                            Sender = string.IsNullOrWhiteSpace(sender) ? "Peer" : sender,
                            TargetPeerId = string.IsNullOrWhiteSpace(reqTarget) ? "" : originId,
                            Type = "FILE",
                            FileName = fi.Name,
                            FileSize = fi.Length,
                            FileHash = fileHash,
                            MimeType = mime,
                            Timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
                        }, originId);

                        LiveMessagePushed?.Invoke(ToViewModel(stored));
                        await WriteJsonResponseAsync(stream, 200, "OK", JsonSerializer.Serialize(ToWireMessage(stored)), ct).ConfigureAwait(false);
                        break;
                    }

                    case ("POST", "/api/upload"):
                    {
                        await HandleIncomingUploadAsync(stream, headers, ct).ConfigureAwait(false);
                        break;
                    }

                    default:
                        await WriteJsonResponseAsync(stream, 404, "Not Found", """{"error":"Not found"}""", ct).ConfigureAwait(false);
                        break;
                }
            }
            catch { }
        }
    }

    private async Task HandleIncomingUploadAsync(NetworkStream stream, Dictionary<string, string> headers, CancellationToken ct)
    {
        long.TryParse(headers.GetValueOrDefault("Content-Length"), out var contentLength);
        var rawName = Uri.UnescapeDataString(headers.GetValueOrDefault("X-File-Name") ?? $"peer_{DateTimeOffset.UtcNow.ToUnixTimeSeconds()}");
        var safeName = Path.GetFileName(rawName);
        if (string.IsNullOrWhiteSpace(safeName)) safeName = $"peer_{DateTimeOffset.UtcNow.ToUnixTimeSeconds()}";

        var ch = Uri.UnescapeDataString(headers.GetValueOrDefault("X-Channel") ?? "General");
        if (string.IsNullOrWhiteSpace(ch)) ch = "General";
        var sender = Uri.UnescapeDataString(headers.GetValueOrDefault("X-Sender-Name") ?? "Peer");
        var originId = headers.GetValueOrDefault("X-Origin-Id") ?? "";
        var reqTarget = headers.GetValueOrDefault("X-Target-Peer-Id") ?? "";
        var msgUid = headers.GetValueOrDefault("X-Msg-Uid") ?? "";
        var headerHash = (headers.GetValueOrDefault("X-File-Hash") ?? "").Trim().ToLowerInvariant();

        if (!string.IsNullOrWhiteSpace(msgUid) && Store.GetMessageByUid(msgUid) is { } existingMsg)
        {
            await DrainStreamAsync(stream, contentLength, ct).ConfigureAwait(false);
            await WriteJsonResponseAsync(stream, 200, "OK", JsonSerializer.Serialize(ToWireMessage(existingMsg)), ct).ConfigureAwait(false);
            return;
        }

        if (!string.IsNullOrEmpty(headerHash) && Store.ResolveExistingFile(headerHash, contentLength) is { } preExisting)
        {
            await DrainStreamAsync(stream, contentLength, ct).ConfigureAwait(false);
            var fiPre = new FileInfo(preExisting);
            var storedPre = Store.MergeOrUpdate(new StoredMeshMessage
            {
                MsgUid = msgUid,
                Channel = ch,
                Sender = sender,
                TargetPeerId = string.IsNullOrWhiteSpace(reqTarget) ? "" : originId,
                Type = "FILE",
                FileName = fiPre.Name,
                FileSize = fiPre.Length,
                FileHash = headerHash,
                Timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
            }, originId);
            LiveMessagePushed?.Invoke(ToViewModel(storedPre));
            await WriteJsonResponseAsync(stream, 200, "OK", JsonSerializer.Serialize(ToWireMessage(storedPre)), ct).ConfigureAwait(false);
            return;
        }

        var receiveDir = Store.ActiveReceiveDirectory;
        var destPath = ResolveCollisionFreePath(Path.Combine(receiveDir, safeName));
        string actualHash;
        using (var sha = IncrementalHash.CreateHash(HashAlgorithmName.SHA256))
        {
            await using (var fs = new FileStream(destPath, FileMode.Create, FileAccess.Write, FileShare.Read, 262144, useAsync: true))
            {
                var buf = new byte[262144];
                long remaining = contentLength;
                while (remaining > 0)
                {
                    int toRead = (int)Math.Min(buf.Length, remaining);
                    int n = await stream.ReadAsync(buf.AsMemory(0, toRead), ct).ConfigureAwait(false);
                    if (n <= 0) break;
                    sha.AppendData(buf, 0, n);
                    await fs.WriteAsync(buf.AsMemory(0, n), ct).ConfigureAwait(false);
                    remaining -= n;
                }
            }
            actualHash = Convert.ToHexStringLower(sha.GetHashAndReset());
        }

        var dup = Store.ResolveExistingFile(actualHash);
        string finalPath;
        if (!string.IsNullOrEmpty(dup) && !dup.Equals(destPath, StringComparison.OrdinalIgnoreCase))
        {
            try { File.Delete(destPath); } catch { }
            finalPath = dup;
        }
        else
        {
            Store.RegisterLocalFile(actualHash, destPath);
            finalPath = destPath;
        }

        var fi = new FileInfo(finalPath);
        var stored = Store.MergeOrUpdate(new StoredMeshMessage
        {
            MsgUid = msgUid,
            Channel = ch,
            Sender = sender,
            TargetPeerId = string.IsNullOrWhiteSpace(reqTarget) ? "" : originId,
            Type = "FILE",
            FileName = fi.Name,
            FileSize = fi.Length,
            FileHash = actualHash,
            Timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
        }, originId);

        LiveMessagePushed?.Invoke(ToViewModel(stored));
        await WriteJsonResponseAsync(stream, 200, "OK", JsonSerializer.Serialize(ToWireMessage(stored)), ct).ConfigureAwait(false);
    }

    public async Task<int> SyncMeshNowAsync(string activeChannel, CancellationToken ct = default)
    {
        return await SyncAllPeersOnceAsync(activeChannel, manualTrigger: true, ct).ConfigureAwait(false);
    }

    private async Task<int> SyncAllPeersOnceAsync(string activeChannel, bool manualTrigger, CancellationToken ct)
    {
        if (!await _meshSyncLock.WaitAsync(0, ct).ConfigureAwait(false))
            return 0;

        int syncedUpdates = 0;
        try
        {
            var peers = _knownPeers.Values
                .Where(p => !string.IsNullOrWhiteSpace(p.Url) && !p.Id.Equals(PcId, StringComparison.OrdinalIgnoreCase))
                .ToList();

            foreach (var peer in peers)
            {
                try
                {
                    // 1) Pull missed channels & messages from peer
                    bool pulled = false;
                    try
                    {
                        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
                        cts.CancelAfter(4500);
                        var json = await _http.GetStringAsync($"{peer.Url}/api/sync/pull?since=0&requesterId={Uri.EscapeDataString(PcId)}", cts.Token).ConfigureAwait(false);
                        using var doc = JsonDocument.Parse(json);
                        if (doc.RootElement.TryGetProperty("channels", out var chArr) && chArr.ValueKind == JsonValueKind.Array)
                        {
                            foreach (var chEl in chArr.EnumerateArray())
                            {
                                var chName = chEl.GetString();
                                if (!string.IsNullOrWhiteSpace(chName)) Store.AddChannel(chName);
                            }
                        }
                        if (doc.RootElement.TryGetProperty("messages", out var msgArr) && msgArr.ValueKind == JsonValueKind.Array)
                        {
                            foreach (var el in msgArr.EnumerateArray())
                            {
                                var parsed = ParseMessageElement(el);
                                if (parsed == null) continue;
                                if (!string.IsNullOrEmpty(parsed.TargetPeerId) && !parsed.TargetPeerId.Equals(PcId, StringComparison.OrdinalIgnoreCase)) continue;
                                bool existed = Store.GetMessageByUid(parsed.MsgUid) != null;
                                Store.MergeOrUpdate(ToStoredMessage(parsed), peer.Id);
                                if (!existed) syncedUpdates++;
                            }
                        }
                        pulled = true;
                    }
                    catch { }

                    if (!pulled)
                    {
                        // Fallback for older phones without /api/sync/pull
                        await FetchMessagesAsync(peer.Url, activeChannel, ct).ConfigureAwait(false);
                    }

                    // 2) Push missed All-Group (or targeted-to-this-peer) messages from local Store to this peer
                    var localMsgs = Store.GetMessagesSince(0, peer.Id);
                    foreach (var m in localMsgs)
                    {
                        if (ct.IsCancellationRequested) break;
                        if (m.SyncedPeers.Contains(peer.Id, StringComparer.OrdinalIgnoreCase)) continue;
                        if (!string.IsNullOrEmpty(m.TargetPeerId) && !m.TargetPeerId.Equals(peer.Id, StringComparison.OrdinalIgnoreCase)) continue;

                        if (m.Type.Equals("FILE", StringComparison.OrdinalIgnoreCase))
                        {
                            var localFile = Store.ResolveExistingFile(m.FileHash, m.FileSize);
                            if (localFile != null && File.Exists(localFile))
                            {
                                bool pushed = await PushOrLinkExistingFileToPeerAsync(peer.Url, m, localFile, ct).ConfigureAwait(false);
                                if (pushed)
                                {
                                    Store.MergeOrUpdate(m, peer.Id);
                                    syncedUpdates++;
                                }
                            }
                        }
                        else if (!string.IsNullOrWhiteSpace(m.TextContent))
                        {
                            bool ok = await PushStoredTextToPeerAsync(peer.Url, m, ct).ConfigureAwait(false);
                            if (ok)
                            {
                                Store.MergeOrUpdate(m, peer.Id);
                                syncedUpdates++;
                            }
                        }
                    }
                }
                catch { }
            }

            ChannelsUpdated?.Invoke(Store.GetChannels());
            var mergedChannelMsgs = Store.GetMessagesForChannel(activeChannel, PcId).Select(ToViewModel).ToList();
            MessagesFetched?.Invoke(mergedChannelMsgs);

            if (manualTrigger)
            {
                ToastRequested?.Invoke(
                    syncedUpdates > 0
                        ? $"Mesh synced {syncedUpdates} missed update(s) across {peers.Count} peer(s)."
                        : $"All {peers.Count} peer(s) are already up to date (0 duplicates).",
                    false);
            }
        }
        finally
        {
            _meshSyncLock.Release();
        }
        return syncedUpdates;
    }

    private async Task<bool> PushStoredTextToPeerAsync(string peerUrl, StoredMeshMessage m, CancellationToken ct)
    {
        try
        {
            var payload = new
            {
                channel = m.Channel,
                sender = m.Sender == PcName ? "LAPTOP" : m.Sender,
                originId = PcId,
                targetPeerId = m.TargetPeerId,
                msgUid = m.MsgUid,
                type = m.Type,
                textContent = m.TextContent
            };
            using var content = new StringContent(JsonSerializer.Serialize(payload), Encoding.UTF8, "application/json");
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(3500);
            using var resp = await _http.PostAsync($"{peerUrl}/api/messages", content, cts.Token).ConfigureAwait(false);
            return resp.IsSuccessStatusCode;
        }
        catch { return false; }
    }

    private async Task<bool> PushOrLinkExistingFileToPeerAsync(string peerUrl, StoredMeshMessage m, string filePath, CancellationToken ct)
    {
        try
        {
            var hash = string.IsNullOrEmpty(m.FileHash) ? Store.ComputeOrGetSha256(filePath) : m.FileHash;
            if (!string.IsNullOrEmpty(hash) && await PeerHasHashAsync(peerUrl, hash, ct).ConfigureAwait(false))
            {
                return await SendFileLinkToPeerAsync(peerUrl, m.Channel, m.FileName, new FileInfo(filePath).Length, hash, m.MimeType, m.MsgUid, m.TargetPeerId, ct).ConfigureAwait(false);
            }
            var fi = new FileInfo(filePath);
            await using var fs = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read, 262144, useAsync: true);
            using var req = new HttpRequestMessage(HttpMethod.Post, $"{peerUrl}/api/upload") { Content = new StreamContent(fs, 262144) };
            req.Content.Headers.ContentLength = fi.Length;
            req.Headers.TryAddWithoutValidation("X-File-Name", Uri.EscapeDataString(fi.Name));
            req.Headers.TryAddWithoutValidation("X-Channel", Uri.EscapeDataString(m.Channel));
            req.Headers.TryAddWithoutValidation("X-Sender-Name", "LAPTOP");
            req.Headers.TryAddWithoutValidation("X-Origin-Id", PcId);
            if (!string.IsNullOrEmpty(m.TargetPeerId)) req.Headers.TryAddWithoutValidation("X-Target-Peer-Id", m.TargetPeerId);
            req.Headers.TryAddWithoutValidation("X-Msg-Uid", m.MsgUid);
            if (!string.IsNullOrEmpty(hash)) req.Headers.TryAddWithoutValidation("X-File-Hash", hash);
            using var resp = await _http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
            return resp.IsSuccessStatusCode;
        }
        catch { return false; }
    }

    private async Task<bool> PeerHasHashAsync(string peerUrl, string sha256, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(sha256)) return false;
        try
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(2000);
            var json = await _http.GetStringAsync($"{peerUrl}/api/files/has?sha256={Uri.EscapeDataString(sha256)}", cts.Token).ConfigureAwait(false);
            using var doc = JsonDocument.Parse(json);
            return doc.RootElement.TryGetProperty("exists", out var exEl) && exEl.GetBoolean();
        }
        catch { return false; }
    }

    private async Task<bool> SendFileLinkToPeerAsync(string peerUrl, string channel, string fileName, long fileSize, string fileHash, string mimeType, string msgUid, string targetPeerId, CancellationToken ct)
    {
        try
        {
            var payload = JsonSerializer.Serialize(new
            {
                channel = string.IsNullOrWhiteSpace(channel) ? "General" : channel,
                sender = "LAPTOP",
                originId = PcId,
                targetPeerId = targetPeerId ?? "",
                msgUid,
                fileName,
                fileSize,
                fileHash,
                mimeType
            });
            using var content = new StringContent(payload, Encoding.UTF8, "application/json");
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(3500);
            using var resp = await _http.PostAsync($"{peerUrl}/api/messages/link", content, cts.Token).ConfigureAwait(false);
            return resp.IsSuccessStatusCode;
        }
        catch { return false; }
    }

    public async Task<string?> DownloadBlobFromAnyPeerAsync(string fileHash, string fileName, long expectedSize, TransferProgressItem? progressItem = null, Action<Action>? uiInvoke = null, CancellationToken ct = default)
    {
        if (!string.IsNullOrWhiteSpace(fileHash))
        {
            var local = Store.ResolveExistingFile(fileHash, expectedSize);
            if (!string.IsNullOrEmpty(local))
            {
                if (progressItem != null && uiInvoke != null)
                {
                    uiInvoke(() =>
                    {
                        progressItem.Progress = 1.0;
                        progressItem.IsCompleted = true;
                        progressItem.SavedPath = local;
                        progressItem.StatusText = "Already on disk (Deduplicated)";
                    });
                }
                return local;
            }
        }

        foreach (var peer in _knownPeers.Values.Where(p => !string.IsNullOrWhiteSpace(p.Url)))
        {
            if (await PeerHasHashAsync(peer.Url, fileHash, ct).ConfigureAwait(false))
            {
                var path = await DownloadFileAsync(peer.Url, $"/api/blob?sha256={Uri.EscapeDataString(fileHash)}", fileName, progressItem, uiInvoke, useThumbCache: false, fileHash: fileHash, expectedSize: expectedSize, ct: ct).ConfigureAwait(false);
                if (!string.IsNullOrEmpty(path)) return path;
            }
        }
        return null;
    }

    private SyncMessageViewModel ToViewModel(StoredMeshMessage s)
    {
        var resolved = s.Type.Equals("FILE", StringComparison.OrdinalIgnoreCase)
            ? Store.ResolveExistingFile(s.FileHash, s.FileSize)
            : null;
        return new SyncMessageViewModel
        {
            Id = s.Id,
            MsgUid = s.MsgUid,
            Channel = s.Channel,
            Sender = s.Sender,
            TargetPeerId = s.TargetPeerId,
            Type = s.Type,
            TextContent = s.TextContent,
            FileName = s.FileName,
            FileSize = s.FileSize,
            FileHash = s.FileHash,
            MimeType = s.MimeType,
            Timestamp = s.Timestamp,
            LocalCachedPath = resolved,
            IsZeroCopyOrigin = Store.IsZeroCopyOriginPath(resolved),
            SyncedPeerCount = s.SyncedPeers.Count
        };
    }

    private static StoredMeshMessage ToStoredMessage(SyncMessageViewModel vm) => new()
    {
        Id = vm.Id,
        MsgUid = vm.MsgUid,
        Channel = vm.Channel,
        Sender = vm.Sender,
        TargetPeerId = vm.TargetPeerId,
        Type = vm.Type,
        TextContent = vm.TextContent,
        FileName = vm.FileName,
        FileSize = vm.FileSize,
        FileHash = vm.FileHash,
        MimeType = vm.MimeType,
        Timestamp = vm.Timestamp
    };

    private static object ToWireMessage(StoredMeshMessage m) => new
    {
        id = m.Id,
        msgUid = m.MsgUid,
        channel = m.Channel,
        sender = m.Sender,
        targetPeerId = m.TargetPeerId,
        type = m.Type,
        textContent = m.TextContent,
        fileName = m.FileName,
        fileSize = m.FileSize,
        fileHash = m.FileHash,
        mimeType = m.MimeType,
        timestamp = m.Timestamp
    };

    private static string ResolveCollisionFreePath(string desiredPath)
    {
        if (!File.Exists(desiredPath)) return desiredPath;
        var dir = Path.GetDirectoryName(desiredPath) ?? "";
        var name = Path.GetFileNameWithoutExtension(desiredPath);
        var ext = Path.GetExtension(desiredPath);
        for (int i = 1; i <= 9999; i++)
        {
            var candidate = Path.Combine(dir, $"{name}_{i}{ext}");
            if (!File.Exists(candidate)) return candidate;
        }
        return Path.Combine(dir, $"{name}_{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}{ext}");
    }

    private static bool IsTrustedOrigin(string origin, string? hostHeader)
    {
        if (!Uri.TryCreate(origin, UriKind.Absolute, out var uri)) return false;
        var host = uri.Host.ToLowerInvariant();
        var expected = (hostHeader ?? "").Split(':')[0].ToLowerInvariant();
        return host == "localhost" || host == "127.0.0.1" || (!string.IsNullOrEmpty(expected) && host == expected);
    }

    private static bool FixedTimeEqualsSha256(string expected, string provided)
    {
        if (string.IsNullOrEmpty(provided)) return false;
        var a = SHA256.HashData(Encoding.UTF8.GetBytes(expected));
        var b = SHA256.HashData(Encoding.UTF8.GetBytes(provided.Trim()));
        return CryptographicOperations.FixedTimeEquals(a, b);
    }

    private static string ParseQueryParam(string query, string key)
    {
        if (string.IsNullOrEmpty(query)) return "";
        foreach (var part in query.Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var eq = part.IndexOf('=', StringComparison.Ordinal);
            if (eq > 0 && part[..eq].Equals(key, StringComparison.OrdinalIgnoreCase))
            {
                return Uri.UnescapeDataString(part[(eq + 1)..]);
            }
        }
        return "";
    }

    private static async Task<byte[]> ReadHttpHeaderBlockAsync(NetworkStream stream, CancellationToken ct)
    {
        var ms = new MemoryStream();
        var buf = new byte[1];
        int match = 0;
        while (ms.Length < 32_768)
        {
            int n = await stream.ReadAsync(buf.AsMemory(0, 1), ct).ConfigureAwait(false);
            if (n <= 0) break;
            byte b = buf[0];
            ms.WriteByte(b);
            match = (match, b) switch
            {
                (0, (byte)'\r') => 1,
                (1, (byte)'\n') => 2,
                (2, (byte)'\r') => 3,
                (3, (byte)'\n') => 4,
                (_, (byte)'\r') => 1,
                _ => 0
            };
            if (match == 4) break;
        }
        return ms.ToArray();
    }

    private static async Task<byte[]> ReadBodyBytesAsync(NetworkStream stream, Dictionary<string, string> headers, int maxBytes, CancellationToken ct)
    {
        if (!int.TryParse(headers.GetValueOrDefault("Content-Length"), out int len) || len <= 0 || len > maxBytes)
            return [];
        var buf = new byte[len];
        int read = 0;
        while (read < len)
        {
            int n = await stream.ReadAsync(buf.AsMemory(read, len - read), ct).ConfigureAwait(false);
            if (n <= 0) break;
            read += n;
        }
        return read == len ? buf : buf[..read];
    }

    private static async Task DrainStreamAsync(NetworkStream stream, long contentLength, CancellationToken ct)
    {
        if (contentLength <= 0) return;
        var buf = new byte[65536];
        long rem = contentLength;
        while (rem > 0)
        {
            int n = await stream.ReadAsync(buf.AsMemory(0, (int)Math.Min(buf.Length, rem)), ct).ConfigureAwait(false);
            if (n <= 0) break;
            rem -= n;
        }
    }

    private static async Task WriteJsonResponseAsync(NetworkStream stream, int code, string status, string json, CancellationToken ct)
    {
        var body = Encoding.UTF8.GetBytes(json);
        var hdr = Encoding.UTF8.GetBytes($"HTTP/1.1 {code} {status}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {body.Length}\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n");
        await stream.WriteAsync(hdr, ct).ConfigureAwait(false);
        await stream.WriteAsync(body, ct).ConfigureAwait(false);
        await stream.FlushAsync(ct).ConfigureAwait(false);
    }

    private static async Task StreamFileResponseAsync(NetworkStream stream, string filePath, CancellationToken ct)
    {
        var fi = new FileInfo(filePath);
        var safeFileName = fi.Name.Replace("\"", "_").Replace('\r', '_').Replace('\n', '_');
        var hdr = Encoding.UTF8.GetBytes($"HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: {fi.Length}\r\nContent-Disposition: attachment; filename=\"{safeFileName}\"\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n");
        await stream.WriteAsync(hdr, ct).ConfigureAwait(false);
        await using var fs = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read, 262144, useAsync: true);
        var buf = new byte[262144];
        while (true)
        {
            int n = await fs.ReadAsync(buf, ct).ConfigureAwait(false);
            if (n <= 0) break;
            await stream.WriteAsync(buf.AsMemory(0, n), ct).ConfigureAwait(false);
        }
        await stream.FlushAsync(ct).ConfigureAwait(false);
    }
}
