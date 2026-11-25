using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using SanDen.Desktop.Models;

namespace SanDen.Desktop.Services;

public sealed partial class ContinuityHubService : IDisposable
{
    public const int DefaultPort = 54321;
    public const int DiscoveryUdpPort = 54322;

    public string SaveDirectory { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
        "Downloads",
        "SanDen"
    );

    public string ThumbCacheDirectory { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "SanDen",
        "ThumbCache"
    );

    public MeshStoreService Store { get; }
    public string PcName { get; } = string.IsNullOrWhiteSpace(Environment.MachineName) ? "Windows PC" : Environment.MachineName;
    public string PcId => $"pc_{PcName}";

    private readonly HttpClient _http;
    private readonly CancellationTokenSource _cts = new();
    private ClientWebSocket? _ws;
    private string _wsConnectedUrl = "";
    private readonly SemaphoreSlim _wsLock = new(1, 1);

    public event Action<PeerDevice>? PeerDiscoveredOrUpdated;
    public event Action<List<string>>? ChannelsUpdated;
    public event Action<List<SyncMessageViewModel>>? MessagesFetched;
    public event Action<SyncMessageViewModel>? LiveMessagePushed;
    public event Action<List<VaultFileItem>>? VaultFilesFetched;
    public event Action<string, bool>? ToastRequested;

    public ContinuityHubService()
    {
        Directory.CreateDirectory(SaveDirectory);
        Directory.CreateDirectory(ThumbCacheDirectory);
        Store = new MeshStoreService(SaveDirectory);

        var handler = new SocketsHttpHandler
        {
            PooledConnectionLifetime = TimeSpan.FromMinutes(2),
            ConnectTimeout = TimeSpan.FromSeconds(3.5)
        };
        _http = new HttpClient(handler)
        {
            Timeout = TimeSpan.FromMinutes(15)
        };
        var key = GetConfiguredPasskey();
        if (!string.IsNullOrEmpty(key))
        {
            _http.DefaultRequestHeaders.TryAddWithoutValidation("X-SanDen-Key", key);
        }
    }

    private static string GetConfiguredPasskey()
    {
        var envKey = Environment.GetEnvironmentVariable("SANDEN_PASSKEY")?.Trim();
        if (!string.IsNullOrEmpty(envKey)) return envKey;
        try
        {
            var keyFile = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "SanDen",
                "passkey.txt");
            if (File.Exists(keyFile)) return File.ReadAllText(keyFile).Trim();
        }
        catch { }
        return "";
    }

    public void StartBackgroundEngine(Func<string> getActiveBaseUrl, Func<string> getActiveChannel)
    {
        _ = Task.Run(() => RunEmbeddedMeshServerAsync(_cts.Token));
        _ = Task.Run(() => RunUdpDiscoveryListenerAsync(_cts.Token));
        _ = Task.Run(() => RunPcAnnounceLoopAsync(getActiveBaseUrl, _cts.Token));
        _ = Task.Run(() => RunSyncAndWebSocketSupervisorAsync(getActiveBaseUrl, getActiveChannel, _cts.Token));
        _ = Task.Run(() => RunAutoDiscoveryAssistAsync(getActiveBaseUrl, _cts.Token));
    }

    public static string NormalizeUrl(string raw)
    {
        var s = (raw ?? "").Trim().TrimEnd('/');
        if (string.IsNullOrEmpty(s)) return "";
        if (!s.Contains("://", StringComparison.Ordinal))
        {
            s = "http://" + s;
        }
        if (Uri.TryCreate(s, UriKind.Absolute, out var uri) &&
            (uri.Scheme.Equals(Uri.UriSchemeHttp, StringComparison.OrdinalIgnoreCase) ||
             uri.Scheme.Equals(Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase)) &&
            uri.HostNameType != UriHostNameType.Unknown)
        {
            var port = uri.IsDefaultPort ? DefaultPort : uri.Port;
            return $"{uri.Scheme}://{uri.Host}:{port}";
        }
        return "";
    }

    private string LastPeerCacheFile { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "SanDen",
        "last_peer.txt"
    );

    private async Task RunAutoDiscoveryAssistAsync(Func<string> getActiveBaseUrl, CancellationToken ct)
    {
        // 1) Immediately probe cached last-known peer URL if available (< 50ms reconnect)
        try
        {
            if (File.Exists(LastPeerCacheFile))
            {
                var cachedUrl = (await File.ReadAllTextAsync(LastPeerCacheFile, ct).ConfigureAwait(false)).Trim();
                if (!string.IsNullOrEmpty(cachedUrl))
                {
                    _ = ProbeDeviceInfoAsync(cachedUrl, cachedUrl.Contains("127.0.0.1"), ct);
                }
            }
        }
        catch { }

        // 2) Immediately probe active ARP neighbor cache entries on port 54321 (< 100ms on Wi-Fi)
        _ = Task.Run(() => ProbeArpNeighborsAsync(ct), ct);

        // 3) Run ADB hint/bridge check concurrently so it never blocks Wi-Fi subnet discovery
        _ = Task.Run(() => TryDiscoverViaAdbHintOrBridgeAsync(silent: true, ct), ct);

        // 4) Wait 350ms for UDP / ARP / cache hit; if still not found, run fast non-blocking /24 sweep
        await Task.Delay(350, ct).ConfigureAwait(false);
        if (!string.IsNullOrEmpty(getActiveBaseUrl())) return;

        await ProbeLocalSubnetsAsync(ct).ConfigureAwait(false);
    }

    private async Task ProbeArpNeighborsAsync(CancellationToken ct)
    {
        try
        {
            var arpOut = await RunProcessOutputAsync("arp.exe", "-a", 1500, ct).ConfigureAwait(false);
            var ips = new HashSet<string>();
            foreach (var line in arpOut.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries))
            {
                var parts = line.Trim().Split(' ', StringSplitOptions.RemoveEmptyEntries);
                if (parts.Length >= 2 && IPAddress.TryParse(parts[0], out var ip) && ip.AddressFamily == AddressFamily.InterNetwork)
                {
                    var b = ip.GetAddressBytes();
                    if (b[0] is 224 or 239 or 255 || b[3] == 255 || b[3] == 0) continue;
                    ips.Add(ip.ToString());
                }
            }

            var tasks = ips.Select(ip => FastProbeHostAsync(ip, 500, ct));
            await Task.WhenAll(tasks).ConfigureAwait(false);
        }
        catch { }
    }

    private async Task FastProbeHostAsync(string candidateIp, int timeoutMs, CancellationToken ct)
    {
        Socket? sock = null;
        try
        {
            sock = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
            var connectTask = sock.ConnectAsync(candidateIp, DefaultPort);
            var completed = await Task.WhenAny(connectTask, Task.Delay(timeoutMs, ct)).ConfigureAwait(false);
            if (completed == connectTask && sock.Connected)
            {
                try { sock.Close(); } catch { }
                await ProbeDeviceInfoAsync($"http://{candidateIp}:{DefaultPort}", isUsb: false, ct).ConfigureAwait(false);
            }
        }
        catch { }
        finally
        {
            var s = sock;
            if (s != null) _ = Task.Run(() => { try { s.Dispose(); } catch { } });
        }
    }

    public async Task ProbeLocalSubnetsAsync(CancellationToken ct = default)
    {
        try
        {
            var localIps = GetActiveLocalIPv4Addresses();
            foreach (var localIp in localIps)
            {
                var bytes = localIp.GetAddressBytes();
                if (bytes.Length != 4) continue;
                var prefix = $"{bytes[0]}.{bytes[1]}.{bytes[2]}.";

                using var sem = new SemaphoreSlim(128);
                var tasks = new List<Task>(254);
                for (int host = 1; host <= 254; host++)
                {
                    if (host == bytes[3]) continue;
                    var candidateIp = prefix + host;
                    tasks.Add(Task.Run(async () =>
                    {
                        await sem.WaitAsync(ct).ConfigureAwait(false);
                        try
                        {
                            await FastProbeHostAsync(candidateIp, 450, ct).ConfigureAwait(false);
                        }
                        finally
                        {
                            sem.Release();
                        }
                    }, ct));
                }
                await Task.WhenAll(tasks).ConfigureAwait(false);
            }
        }
        catch
        {
            // Ignore sweep cancellation
        }
    }

    public async Task<bool> TryDiscoverViaAdbHintOrBridgeAsync(bool silent = false, CancellationToken ct = default)
    {
        var adb = FindAdbExecutable();
        if (string.IsNullOrEmpty(adb))
        {
            if (!silent) ToastRequested?.Invoke("ADB not found on system PATH or Android SDK.", true);
            return false;
        }

        try
        {
            var devicesOut = await RunProcessOutputAsync(adb, "devices", 3500, ct).ConfigureAwait(false);
            var serials = devicesOut
                .Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries)
                .Skip(1)
                .Where(l => l.Contains("\tdevice"))
                .Select(l => l.Split('\t')[0].Trim())
                .Where(s => !string.IsNullOrEmpty(s))
                .ToList();

            if (serials.Count == 0)
            {
                if (!silent) ToastRequested?.Invoke("No authorized Android phone detected over USB.", true);
                return false;
            }

            bool connectedAny = false;
            for (int i = 0; i < serials.Count; i++)
            {
                var ser = serials[i];

                // First check if the USB-connected phone is also reachable directly via Wi-Fi IP (gives true Wi-Fi IP!)
                if (silent)
                {
                    var routeOut = await RunProcessOutputAsync(adb, $"-s {ser} shell ip -4 addr show wlan0", 2000, ct).ConfigureAwait(false);
                    var wifiIp = ExtractInetIPv4(routeOut);
                    if (!string.IsNullOrEmpty(wifiIp))
                    {
                        var wifiPeer = await ProbeDeviceInfoAsync($"http://{wifiIp}:{DefaultPort}", isUsb: false, ct).ConfigureAwait(false);
                        if (wifiPeer != null)
                        {
                            connectedAny = true;
                            continue;
                        }
                    }
                }

                // Set up ADB TCP port forward bridge on dedicated USB range (never collide with local PC MeshServer on 54321)
                int localPort = 54400 + i;
                await RunProcessOutputAsync(adb, $"-s {ser} forward tcp:{localPort} tcp:{DefaultPort}", 3000, ct).ConfigureAwait(false);
                var usbUrl = $"http://127.0.0.1:{localPort}";
                var peer = await ProbeDeviceInfoAsync(usbUrl, isUsb: true, ct).ConfigureAwait(false);
                if (peer != null)
                {
                    connectedAny = true;
                    if (!silent) ToastRequested?.Invoke($"Connected to {peer.Name} via USB ADB Bridge.", false);
                }
            }
            if (!connectedAny && !silent)
            {
                ToastRequested?.Invoke("USB forward created, but SanDen Hub is not open on the phone yet.", true);
            }
            return connectedAny;
        }
        catch (Exception ex)
        {
            if (!silent) ToastRequested?.Invoke($"USB Bridge error: {ex.Message}", true);
            return false;
        }
    }

    private static string? ExtractInetIPv4(string text)
    {
        if (string.IsNullOrWhiteSpace(text)) return null;
        foreach (var line in text.Split(['\r', '\n']))
        {
            var trimmed = line.Trim();
            if (trimmed.StartsWith("inet ", StringComparison.Ordinal))
            {
                var parts = trimmed.Split(' ', StringSplitOptions.RemoveEmptyEntries);
                if (parts.Length >= 2)
                {
                    var ipSlash = parts[1];
                    var slashIdx = ipSlash.IndexOf('/');
                    return slashIdx > 0 ? ipSlash[..slashIdx] : ipSlash;
                }
            }
        }
        return null;
    }

    public async Task<PeerDevice?> ProbeDeviceInfoAsync(string rawUrl, bool isUsb = false, CancellationToken ct = default)
    {
        var baseUrl = NormalizeUrl(rawUrl);
        if (string.IsNullOrEmpty(baseUrl)) return null;

        try
        {
            using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeoutCts.CancelAfter(2500);
            using var resp = await _http.GetAsync($"{baseUrl}/api/info", timeoutCts.Token).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode) return null;

            var json = await resp.Content.ReadAsStringAsync(timeoutCts.Token).ConfigureAwait(false);
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;

            var uri = new Uri(baseUrl);
            var devId = root.TryGetProperty("deviceId", out var idEl) ? idEl.GetString() ?? baseUrl : baseUrl;
            if (devId.Equals(PcId, StringComparison.OrdinalIgnoreCase)) return null;
            var devType = root.TryGetProperty("deviceType", out var dtEl) ? dtEl.GetString() ?? "ANDROID" : "ANDROID";
            var devName = root.TryGetProperty("deviceName", out var nmEl) ? nmEl.GetString() ?? (devType == "PC" ? "Windows PC" : "Android Phone") : "Android Phone";
            var reportedUrl = root.TryGetProperty("url", out var urlEl) ? urlEl.GetString() ?? "" : "";

            string displayIp = uri.Host;
            int displayPort = uri.Port;
            if (isUsb && Uri.TryCreate(reportedUrl, UriKind.Absolute, out var repUri) && repUri.Host != "127.0.0.1")
            {
                displayIp = repUri.Host;
                displayPort = repUri.Port;
            }

            var peer = new PeerDevice
            {
                Id = devId,
                Name = devName,
                Ip = displayIp,
                Port = displayPort,
                Url = baseUrl,
                DeviceType = devType,
                IsUsb = isUsb || uri.Host == "127.0.0.1",
                BatteryPct = root.TryGetProperty("batteryPct", out var bEl) && bEl.TryGetInt32(out var bp) ? bp : -1,
                VaultUsedBytes = root.TryGetProperty("vaultUsedBytes", out var vuEl) && vuEl.TryGetInt64(out var vu) ? vu : 0L,
                VaultFileCount = root.TryGetProperty("vaultFileCount", out var vcEl) && vcEl.TryGetInt32(out var vc) ? vc : 0,
                StorageTotalBytes = root.TryGetProperty("storageTotalBytes", out var stEl) && stEl.TryGetInt64(out var st) ? st : 0L,
                StorageFreeBytes = root.TryGetProperty("storageFreeBytes", out var sfEl) && sfEl.TryGetInt64(out var sf) ? sf : 0L,
                LastSeen = DateTime.UtcNow
            };

            _knownPeers[peer.Id] = peer;

            if (devType.Equals("ANDROID", StringComparison.OrdinalIgnoreCase))
            {
                try
                {
                    Directory.CreateDirectory(Path.GetDirectoryName(LastPeerCacheFile)!);
                    File.WriteAllText(LastPeerCacheFile, baseUrl);
                }
                catch { }
            }

            PeerDiscoveredOrUpdated?.Invoke(peer);
            return peer;
        }
        catch
        {
            return null;
        }
    }

    private async Task RunUdpDiscoveryListenerAsync(CancellationToken ct)
    {
        Socket? sock = null;
        try
        {
            sock = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
            sock.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            sock.EnableBroadcast = true;
            sock.Bind(new IPEndPoint(IPAddress.Any, DiscoveryUdpPort));

            var buffer = new byte[8192];
            EndPoint remoteEp = new IPEndPoint(IPAddress.Any, 0);

            while (!ct.IsCancellationRequested)
            {
                var res = await sock.ReceiveFromAsync(buffer, SocketFlags.None, remoteEp, ct).ConfigureAwait(false);
                if (res.ReceivedBytes <= 0) continue;

                var json = Encoding.UTF8.GetString(buffer, 0, res.ReceivedBytes);
                try
                {
                    using var doc = JsonDocument.Parse(json);
                    var root = doc.RootElement;
                    if (!root.TryGetProperty("app", out var appEl) || appEl.GetString() != "SanDen") continue;
                    var devId = root.TryGetProperty("deviceId", out var didEl) ? didEl.GetString() ?? "" : "";
                    if (devId.Equals(PcId, StringComparison.OrdinalIgnoreCase)) continue;
                    var devType = root.TryGetProperty("deviceType", out var dtEl) ? dtEl.GetString() ?? "ANDROID" : "ANDROID";
                    if (!devType.Equals("ANDROID", StringComparison.OrdinalIgnoreCase) &&
                        !devType.Equals("PC", StringComparison.OrdinalIgnoreCase)) continue;

                    var url = root.TryGetProperty("url", out var uEl) ? uEl.GetString() ?? "" : "";
                    if (string.IsNullOrWhiteSpace(url) && res.RemoteEndPoint is IPEndPoint ipEp)
                    {
                        var p = root.TryGetProperty("port", out var pEl) && pEl.TryGetInt32(out var pv) ? pv : DefaultPort;
                        url = $"http://{ipEp.Address}:{p}";
                    }

                    if (!string.IsNullOrWhiteSpace(url))
                    {
                        _ = ProbeDeviceInfoAsync(url, isUsb: false, ct);
                    }
                }
                catch
                {
                    // Ignore malformed UDP packets
                }
            }
        }
        catch
        {
            // Listener stopped
        }
        finally
        {
            try { sock?.Dispose(); } catch { }
        }
    }

    private async Task RunPcAnnounceLoopAsync(Func<string> getActiveBaseUrl, CancellationToken ct)
    {
        using var udp = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp)
        {
            EnableBroadcast = true
        };

        while (!ct.IsCancellationRequested)
        {
            try
            {
                var localIp = GetActiveLocalIPv4Addresses().FirstOrDefault()?.ToString() ?? "";
                var myUrl = !string.IsNullOrEmpty(LocalPeerUrl)
                    ? LocalPeerUrl
                    : (LocalHttpPort > 0 && !string.IsNullOrEmpty(localIp) ? $"http://{localIp}:{LocalHttpPort}" : "");
                var payloadObj = new
                {
                    app = "SanDen",
                    deviceId = PcId,
                    deviceName = PcName,
                    deviceType = "PC",
                    ip = localIp,
                    port = LocalHttpPort,
                    url = myUrl
                };
                var raw = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(payloadObj));

                var broadcastTargets = GetBroadcastAddresses();
                foreach (var bcast in broadcastTargets)
                {
                    try
                    {
                        await udp.SendToAsync(raw, SocketFlags.None, new IPEndPoint(bcast, DiscoveryUdpPort), ct).ConfigureAwait(false);
                    }
                    catch { }
                }

                var activeUrl = NormalizeUrl(getActiveBaseUrl());
                if (!string.IsNullOrEmpty(activeUrl))
                {
                    using var content = new ByteArrayContent(raw);
                    content.Headers.TryAddWithoutValidation("Content-Type", "application/json");
                    using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
                    cts.CancelAfter(1800);
                    try
                    {
                        await _http.PostAsync($"{activeUrl}/api/peers/announce", content, cts.Token).ConfigureAwait(false);
                    }
                    catch { }
                }
            }
            catch { }

            await Task.Delay(3000, ct).ConfigureAwait(false);
        }
    }

    private async Task RunSyncAndWebSocketSupervisorAsync(
        Func<string> getActiveBaseUrl,
        Func<string> getActiveChannel,
        CancellationToken ct)
    {
        // Immediately hydrate UI from persistent offline mesh store
        var initialCh = getActiveChannel();
        var cached = Store.GetMessagesForChannel(initialCh, PcId).Select(ToViewModel).ToList();
        if (cached.Count > 0)
        {
            ChannelsUpdated?.Invoke(Store.GetChannels());
            MessagesFetched?.Invoke(cached);
        }

        int tick = 0;
        while (!ct.IsCancellationRequested)
        {
            var baseUrl = NormalizeUrl(getActiveBaseUrl());
            var channel = getActiveChannel();
            if (!string.IsNullOrEmpty(baseUrl))
            {
                _ = EnsureWebSocketConnectedAsync(baseUrl, ct);
                await FetchChannelsAsync(baseUrl, ct).ConfigureAwait(false);
                await FetchMessagesAsync(baseUrl, channel, ct).ConfigureAwait(false);

                if (tick % 2 == 0)
                {
                    await ProbeDeviceInfoAsync(baseUrl, baseUrl.Contains("127.0.0.1"), ct).ConfigureAwait(false);
                    await FetchVaultFilesAsync(baseUrl, ct).ConfigureAwait(false);
                }
            }

            if (tick % 2 == 1 && !_knownPeers.IsEmpty)
            {
                await SyncAllPeersOnceAsync(channel, manualTrigger: false, ct).ConfigureAwait(false);
            }
            tick++;
            await Task.Delay(2200, ct).ConfigureAwait(false);
        }
    }

    private async Task EnsureWebSocketConnectedAsync(string baseUrl, CancellationToken ct)
    {
        if (_ws != null && _ws.State == WebSocketState.Open && _wsConnectedUrl == baseUrl)
            return;

        if (!await _wsLock.WaitAsync(0, ct).ConfigureAwait(false))
            return;

        try
        {
            if (_ws != null && _ws.State == WebSocketState.Open && _wsConnectedUrl == baseUrl)
                return;

            try { _ws?.Dispose(); } catch { }
            _ws = new ClientWebSocket();
            var key = GetConfiguredPasskey();
            if (!string.IsNullOrEmpty(key))
            {
                _ws.Options.SetRequestHeader("X-SanDen-Key", key);
            }
            var wsUri = new Uri(baseUrl.Replace("http://", "ws://").Replace("https://", "wss://") + "/ws");
            using var connectCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            connectCts.CancelAfter(3000);
            await _ws.ConnectAsync(wsUri, connectCts.Token).ConfigureAwait(false);
            _wsConnectedUrl = baseUrl;

            _ = Task.Run(() => ReadWebSocketLoopAsync(_ws, ct), ct);
        }
        catch
        {
            // Will retry on next supervisor tick
        }
        finally
        {
            _wsLock.Release();
        }
    }

    private async Task ReadWebSocketLoopAsync(ClientWebSocket ws, CancellationToken ct)
    {
        var buf = new byte[32768];
        var sb = new StringBuilder();
        try
        {
            while (ws.State == WebSocketState.Open && !ct.IsCancellationRequested)
            {
                var res = await ws.ReceiveAsync(buf, ct).ConfigureAwait(false);
                if (res.MessageType == WebSocketMessageType.Close) break;
                sb.Append(Encoding.UTF8.GetString(buf, 0, res.Count));
                if (res.EndOfMessage)
                {
                    var msgJson = sb.ToString();
                    sb.Clear();
                    var parsed = ParseSingleMessageJson(msgJson);
                    if (parsed != null)
                    {
                        var sourcePeerId = _knownPeers.Values.FirstOrDefault(p => p.Url.Equals(_wsConnectedUrl, StringComparison.OrdinalIgnoreCase))?.Id;
                        var stored = Store.MergeOrUpdate(ToStoredMessage(parsed), sourcePeerId);
                        LiveMessagePushed?.Invoke(ToViewModel(stored));
                    }
                }
            }
        }
        catch
        {
            // Disconnected; supervisor reconnects automatically
        }
    }

    public async Task FetchChannelsAsync(string baseUrl, CancellationToken ct = default)
    {
        try
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(2000);
            var json = await _http.GetStringAsync($"{baseUrl}/api/channels", cts.Token).ConfigureAwait(false);
            var list = JsonSerializer.Deserialize<List<string>>(json);
            if (list != null && list.Count > 0)
            {
                ChannelsUpdated?.Invoke(list);
            }
        }
        catch { }
    }

    public async Task<bool> CreateChannelAsync(string baseUrl, string channelName, CancellationToken ct = default)
    {
        var cleanUrl = NormalizeUrl(baseUrl);
        if (string.IsNullOrEmpty(cleanUrl) || string.IsNullOrWhiteSpace(channelName)) return false;
        try
        {
            var payload = JsonSerializer.Serialize(new { name = channelName.Trim() });
            using var content = new StringContent(payload, Encoding.UTF8, "application/json");
            using var resp = await _http.PostAsync($"{cleanUrl}/api/channels", content, ct).ConfigureAwait(false);
            if (resp.IsSuccessStatusCode)
            {
                await FetchChannelsAsync(cleanUrl, ct).ConfigureAwait(false);
                return true;
            }
        }
        catch { }
        return false;
    }

    public async Task FetchMessagesAsync(string baseUrl, string channel, CancellationToken ct = default)
    {
        try
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(2500);
            var ch = string.IsNullOrWhiteSpace(channel) ? "General" : channel;
            var encodedCh = Uri.EscapeDataString(ch);
            var encodedReq = Uri.EscapeDataString(PcId);
            var json = await _http.GetStringAsync($"{baseUrl}/api/messages?channel={encodedCh}&requesterId={encodedReq}", cts.Token).ConfigureAwait(false);
            using var doc = JsonDocument.Parse(json);
            if (doc.RootElement.ValueKind != JsonValueKind.Array) return;

            var peerId = _knownPeers.Values.FirstOrDefault(p => p.Url.Equals(baseUrl, StringComparison.OrdinalIgnoreCase))?.Id;
            foreach (var el in doc.RootElement.EnumerateArray())
            {
                var vm = ParseMessageElement(el);
                if (vm != null && (string.IsNullOrEmpty(vm.TargetPeerId) || vm.TargetPeerId.Equals(PcId, StringComparison.OrdinalIgnoreCase)))
                {
                    Store.MergeOrUpdate(ToStoredMessage(vm), peerId);
                }
            }
            var merged = Store.GetMessagesForChannel(ch, PcId).Select(ToViewModel).ToList();
            MessagesFetched?.Invoke(merged);
        }
        catch { }
    }

    public async Task FetchVaultFilesAsync(string baseUrl, CancellationToken ct = default)
    {
        try
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(3000);
            var json = await _http.GetStringAsync($"{baseUrl}/api/vault/files", cts.Token).ConfigureAwait(false);
            using var doc = JsonDocument.Parse(json);
            if (doc.RootElement.ValueKind != JsonValueKind.Array) return;

            var files = new List<VaultFileItem>();
            foreach (var el in doc.RootElement.EnumerateArray())
            {
                bool isDir = el.TryGetProperty("isDirectory", out var dEl) && dEl.GetBoolean();
                if (isDir) continue;
                files.Add(new VaultFileItem
                {
                    Id = el.TryGetProperty("id", out var idEl) && idEl.TryGetInt64(out var idv) ? idv : 0,
                    Name = el.TryGetProperty("name", out var nEl) ? nEl.GetString() ?? "file" : "file",
                    Size = el.TryGetProperty("size", out var sEl) && sEl.TryGetInt64(out var sv) ? sv : 0,
                    Category = el.TryGetProperty("category", out var cEl) ? cEl.GetString() ?? "OTHER" : "OTHER",
                    IsDirectory = false,
                    LastModified = el.TryGetProperty("lastModified", out var mEl) && mEl.TryGetInt64(out var mv) ? mv : 0
                });
            }

            var sorted = files.OrderByDescending(f => f.LastModified).ToList();
            VaultFilesFetched?.Invoke(sorted);
        }
        catch { }
    }

    public async Task<bool> SendMessageAsync(string baseUrl, string channel, string text, string type = "TEXT", string targetPeerId = "", CancellationToken ct = default)
    {
        var ch = string.IsNullOrWhiteSpace(channel) ? "General" : channel;
        if (string.IsNullOrWhiteSpace(text)) return false;

        var msgUid = $"{PcId}_{Guid.NewGuid():N}";
        var stored = Store.MergeOrUpdate(new StoredMeshMessage
        {
            MsgUid = msgUid,
            Channel = ch,
            Sender = "LAPTOP",
            TargetPeerId = targetPeerId ?? "",
            Type = type,
            TextContent = text,
            Timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
        }, PcId);
        MessagesFetched?.Invoke(Store.GetMessagesForChannel(ch, PcId).Select(ToViewModel).ToList());

        bool isGroup = string.IsNullOrEmpty(targetPeerId);
        var targets = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var cleanUrl = NormalizeUrl(baseUrl);
        if (!string.IsNullOrEmpty(cleanUrl)) targets.Add(cleanUrl);
        if (isGroup)
        {
            foreach (var p in _knownPeers.Values)
            {
                var u = NormalizeUrl(p.Url);
                if (!string.IsNullOrEmpty(u)) targets.Add(u);
            }
        }

        if (targets.Count == 0)
        {
            ToastRequested?.Invoke("Saved offline — will auto-sync when peers come online.", false);
            return true;
        }

        var payload = new
        {
            channel = ch,
            sender = "LAPTOP",
            originId = PcId,
            targetPeerId = targetPeerId ?? "",
            msgUid,
            type,
            textContent = text
        };
        var json = JsonSerializer.Serialize(payload);
        bool anyOk = false;

        foreach (var peerUrl in targets)
        {
            try
            {
                using var content = new StringContent(json, Encoding.UTF8, "application/json");
                using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
                cts.CancelAfter(4500);
                using var resp = await _http.PostAsync($"{peerUrl}/api/messages", content, cts.Token).ConfigureAwait(false);
                if (resp.IsSuccessStatusCode)
                {
                    anyOk = true;
                    var pid = _knownPeers.Values.FirstOrDefault(p => p.Url.Equals(peerUrl, StringComparison.OrdinalIgnoreCase))?.Id;
                    if (!string.IsNullOrEmpty(pid)) Store.MergeOrUpdate(stored, pid);
                }
            }
            catch { }
        }
        return anyOk;
    }

    public async Task<bool> UploadFileAsync(
        string baseUrl,
        string channel,
        string filePath,
        TransferProgressItem progressItem,
        Action<Action> uiInvoke,
        string targetPeerId = "",
        CancellationToken ct = default)
    {
        if (!File.Exists(filePath)) return false;

        var ch = string.IsNullOrWhiteSpace(channel) ? "General" : channel;
        var info = new FileInfo(filePath);
        long total = info.Length;
        string name = info.Name;
        string msgUid = $"{PcId}_{Guid.NewGuid():N}";
        string fileHash = Store.ComputeOrGetSha256(filePath);
        if (!string.IsNullOrEmpty(fileHash))
        {
            Store.RegisterLocalFile(fileHash, filePath);
        }

        var stored = Store.MergeOrUpdate(new StoredMeshMessage
        {
            MsgUid = msgUid,
            Channel = ch,
            Sender = "LAPTOP",
            TargetPeerId = targetPeerId ?? "",
            Type = "FILE",
            FileName = name,
            FileSize = total,
            FileHash = fileHash,
            Timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
        }, PcId);
        MessagesFetched?.Invoke(Store.GetMessagesForChannel(ch, PcId).Select(ToViewModel).ToList());

        bool isGroup = string.IsNullOrEmpty(targetPeerId);
        var targets = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var cleanUrl = NormalizeUrl(baseUrl);
        if (!string.IsNullOrEmpty(cleanUrl)) targets.Add(cleanUrl);
        if (isGroup)
        {
            foreach (var p in _knownPeers.Values)
            {
                var u = NormalizeUrl(p.Url);
                if (!string.IsNullOrEmpty(u)) targets.Add(u);
            }
        }

        if (targets.Count == 0)
        {
            uiInvoke(() =>
            {
                progressItem.Progress = 1.0;
                progressItem.IsCompleted = true;
                progressItem.SavedPath = filePath;
                progressItem.StatusText = "Indexed (Zero-Copy) — ready to auto-sync";
            });
            return true;
        }

        bool anyOk = false;
        int dedupLinkedCount = 0;

        foreach (var peerUrl in targets)
        {
            try
            {
                if (!string.IsNullOrEmpty(fileHash) && await PeerHasHashAsync(peerUrl, fileHash, ct).ConfigureAwait(false))
                {
                    bool linked = await SendFileLinkToPeerAsync(peerUrl, ch, name, total, fileHash, "", msgUid, targetPeerId ?? "", ct).ConfigureAwait(false);
                    if (linked)
                    {
                        anyOk = true;
                        dedupLinkedCount++;
                        var pidLink = _knownPeers.Values.FirstOrDefault(p => p.Url.Equals(peerUrl, StringComparison.OrdinalIgnoreCase))?.Id;
                        if (!string.IsNullOrEmpty(pidLink)) Store.MergeOrUpdate(stored, pidLink);
                        continue;
                    }
                }

                await using var fs = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read, 262144, useAsync: true);
                var progressStream = new ProgressReadStream(fs, total, (sent, tot) =>
                {
                    double frac = tot > 0 ? (double)sent / tot : 1.0;
                    uiInvoke(() =>
                    {
                        progressItem.Progress = frac;
                        progressItem.StatusText = $"Uploading • {(int)(frac * 100)}% ({FileFormatHelper.FormatSize(sent)} / {FileFormatHelper.FormatSize(tot)})";
                    });
                });

                using var req = new HttpRequestMessage(HttpMethod.Post, $"{peerUrl}/api/upload")
                {
                    Content = new StreamContent(progressStream, 262144)
                };
                req.Content.Headers.ContentLength = total;
                req.Headers.TryAddWithoutValidation("X-File-Name", Uri.EscapeDataString(name));
                req.Headers.TryAddWithoutValidation("X-Channel", Uri.EscapeDataString(ch));
                req.Headers.TryAddWithoutValidation("X-Sender-Name", "LAPTOP");
                req.Headers.TryAddWithoutValidation("X-Origin-Id", PcId);
                if (!string.IsNullOrEmpty(targetPeerId))
                {
                    req.Headers.TryAddWithoutValidation("X-Target-Peer-Id", targetPeerId);
                }
                req.Headers.TryAddWithoutValidation("X-Msg-Uid", msgUid);
                if (!string.IsNullOrEmpty(fileHash))
                {
                    req.Headers.TryAddWithoutValidation("X-File-Hash", fileHash);
                }

                using var resp = await _http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
                if (resp.IsSuccessStatusCode)
                {
                    anyOk = true;
                    var pidUp = _knownPeers.Values.FirstOrDefault(p => p.Url.Equals(peerUrl, StringComparison.OrdinalIgnoreCase))?.Id;
                    if (!string.IsNullOrEmpty(pidUp)) Store.MergeOrUpdate(stored, pidUp);
                }
            }
            catch { }
        }

        if (anyOk)
        {
            uiInvoke(() =>
            {
                progressItem.Progress = 1.0;
                progressItem.IsCompleted = true;
                progressItem.SavedPath = filePath;
                progressItem.StatusText = dedupLinkedCount == targets.Count
                    ? $"Deduplicated (0 B sent) • {FileFormatHelper.FormatSize(total)}"
                    : $"Shared Zero-Copy • {FileFormatHelper.FormatSize(total)}";
            });
            if (!string.IsNullOrEmpty(cleanUrl))
            {
                await FetchVaultFilesAsync(cleanUrl, ct).ConfigureAwait(false);
            }
            MessagesFetched?.Invoke(Store.GetMessagesForChannel(ch, PcId).Select(ToViewModel).ToList());
            return true;
        }

        uiInvoke(() =>
        {
            progressItem.IsFailed = true;
            progressItem.StatusText = "No reachable peer right now (saved for sync)";
        });
        return false;
    }

    public async Task<string?> DownloadFileAsync(
        string baseUrl,
        string endpointPathAndQuery,
        string fileName,
        TransferProgressItem? progressItem = null,
        Action<Action>? uiInvoke = null,
        bool useThumbCache = false,
        string fileHash = "",
        long expectedSize = -1,
        CancellationToken ct = default)
    {
        if (!useThumbCache && !string.IsNullOrWhiteSpace(fileHash))
        {
            var existingLocal = Store.ResolveExistingFile(fileHash, expectedSize);
            if (!string.IsNullOrEmpty(existingLocal) && File.Exists(existingLocal))
            {
                if (progressItem != null && uiInvoke != null)
                {
                    uiInvoke(() =>
                    {
                        progressItem.Progress = 1.0;
                        progressItem.IsCompleted = true;
                        progressItem.SavedPath = existingLocal;
                        progressItem.StatusText = Store.IsZeroCopyOriginPath(existingLocal)
                            ? "Already on disk (Zero-Copy Source)"
                            : "Already on disk (Deduplicated)";
                    });
                }
                return existingLocal;
            }
        }

        var cleanUrl = NormalizeUrl(baseUrl);
        if (string.IsNullOrEmpty(cleanUrl)) return null;

        var safeName = Path.GetFileName(fileName);
        if (string.IsNullOrWhiteSpace(safeName)) safeName = $"file_{DateTimeOffset.UtcNow.ToUnixTimeSeconds()}";
        var targetDir = useThumbCache ? ThumbCacheDirectory : Store.ActiveReceiveDirectory;
        var destPath = useThumbCache
            ? Path.Combine(targetDir, safeName)
            : ResolveCollisionFreePath(Path.Combine(targetDir, safeName));

        try
        {
            if (useThumbCache && File.Exists(destPath) && new FileInfo(destPath).Length > 0)
                return destPath;

            using var resp = await _http.GetAsync($"{cleanUrl}{endpointPathAndQuery}", HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode) return null;

            long total = resp.Content.Headers.ContentLength ?? -1L;
            await using var netStream = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
            using var sha = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            long readTotal = 0;
            await using (var fileOut = new FileStream(destPath, FileMode.Create, FileAccess.Write, FileShare.Read, 262144, useAsync: true))
            {
                var buf = new byte[262144];
                long lastTick = 0;
                while (true)
                {
                    int n = await netStream.ReadAsync(buf, ct).ConfigureAwait(false);
                    if (n <= 0) break;
                    if (!useThumbCache) sha.AppendData(buf, 0, n);
                    await fileOut.WriteAsync(buf.AsMemory(0, n), ct).ConfigureAwait(false);
                    readTotal += n;

                    if (progressItem != null && uiInvoke != null)
                    {
                        long now = Environment.TickCount64;
                        if (now - lastTick >= 150 || readTotal == total)
                        {
                            lastTick = now;
                            double frac = total > 0 ? (double)readTotal / total : 0.5;
                            uiInvoke(() =>
                            {
                                progressItem.Progress = frac;
                                progressItem.StatusText = total > 0
                                    ? $"Downloading • {(int)(frac * 100)}% ({FileFormatHelper.FormatSize(readTotal)} / {FileFormatHelper.FormatSize(total)})"
                                    : $"Downloading • {FileFormatHelper.FormatSize(readTotal)}";
                            });
                        }
                    }
                }
            }

            string finalPath = destPath;
            if (!useThumbCache)
            {
                var actualHash = Convert.ToHexStringLower(sha.GetHashAndReset());
                var existingDup = Store.ResolveExistingFile(actualHash);
                if (!string.IsNullOrEmpty(existingDup) && !existingDup.Equals(destPath, StringComparison.OrdinalIgnoreCase))
                {
                    try { File.Delete(destPath); } catch { }
                    finalPath = existingDup;
                }
                else
                {
                    Store.RegisterLocalFile(actualHash, destPath);
                }
            }

            if (progressItem != null && uiInvoke != null)
            {
                uiInvoke(() =>
                {
                    progressItem.Progress = 1.0;
                    progressItem.IsCompleted = true;
                    progressItem.SavedPath = finalPath;
                    progressItem.StatusText = Store.HiddenVaultMode
                        ? $"Saved to Hidden Vault • {FileFormatHelper.FormatSize(readTotal)}"
                        : $"Saved to Downloads\\SanDen • {FileFormatHelper.FormatSize(readTotal)}";
                });
            }

            return finalPath;
        }
        catch
        {
            if (progressItem != null && uiInvoke != null)
            {
                uiInvoke(() =>
                {
                    progressItem.IsFailed = true;
                    progressItem.StatusText = "Download failed";
                });
            }
            return null;
        }
    }

    public async Task<(ImageSource? Thumb, string DurationText)> FetchServerThumbnailAsync(
        string baseUrl,
        string endpointPathAndQuery,
        string cacheKey,
        int decodeWidth = 420,
        CancellationToken ct = default)
    {
        var cleanUrl = NormalizeUrl(baseUrl);
        if (string.IsNullOrEmpty(cleanUrl)) return (null, "");

        var thumbJpg = Path.Combine(ThumbCacheDirectory, $"{cacheKey}_srv.jpg");
        var durMeta = Path.Combine(ThumbCacheDirectory, $"{cacheKey}_srv.dur");

        if (File.Exists(thumbJpg) && new FileInfo(thumbJpg).Length > 0)
        {
            var cachedDur = File.Exists(durMeta) ? File.ReadAllText(durMeta).Trim() : "";
            return (LoadThumbnailFrozen(thumbJpg, decodeWidth), cachedDur);
        }

        try
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(12000);
            using var resp = await _http.GetAsync($"{cleanUrl}{endpointPathAndQuery}", cts.Token).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode) return (null, "");

            string durText = "";
            if (resp.Headers.TryGetValues("X-Video-Duration-Ms", out var vals))
            {
                if (long.TryParse(vals.FirstOrDefault(), out var durMs) && durMs > 0)
                {
                    durText = FileFormatHelper.FormatDurationMs(durMs);
                }
            }

            var bytes = await resp.Content.ReadAsByteArrayAsync(cts.Token).ConfigureAwait(false);
            if (bytes.Length == 0) return (null, "");

            await File.WriteAllBytesAsync(thumbJpg, bytes, cts.Token).ConfigureAwait(false);
            if (!string.IsNullOrEmpty(durText))
            {
                await File.WriteAllTextAsync(durMeta, durText, cts.Token).ConfigureAwait(false);
            }

            return (LoadThumbnailFrozen(thumbJpg, decodeWidth), durText);
        }
        catch
        {
            return (null, "");
        }
    }

    public static BitmapImage? LoadThumbnailFrozen(string filePath, int decodeWidth = 240)
    {
        try
        {
            if (!File.Exists(filePath) || new FileInfo(filePath).Length == 0) return null;
            var bmp = new BitmapImage();
            bmp.BeginInit();
            bmp.CacheOption = BitmapCacheOption.OnLoad;
            bmp.DecodePixelWidth = decodeWidth;
            bmp.UriSource = new Uri(filePath, UriKind.Absolute);
            bmp.EndInit();
            bmp.Freeze();
            return bmp;
        }
        catch
        {
            return null;
        }
    }

    public BitmapSource? ExtractWindowsVideoThumbnail(string localVideoPath, string cacheKey, int width = 420, int height = 260)
    {
        if (string.IsNullOrEmpty(localVideoPath) || !File.Exists(localVideoPath)) return null;

        var cachedJpg = Path.Combine(ThumbCacheDirectory, $"{cacheKey}_win.jpg");
        if (File.Exists(cachedJpg) && new FileInfo(cachedJpg).Length > 0)
        {
            return LoadThumbnailFrozen(cachedJpg, width);
        }

        // Native Win32 IShellItemImageFactory extracts hardware-decoded video frames without external tools
        BitmapSource? result = null;
        var thread = new Thread(() =>
        {
            IntPtr hBmp = IntPtr.Zero;
            object? shellObj = null;
            try
            {
                var iid = typeof(IShellItemImageFactory).GUID;
                int hr = SHCreateItemFromParsingName(Path.GetFullPath(localVideoPath), IntPtr.Zero, ref iid, out shellObj);
                if (hr == 0 && shellObj is IShellItemImageFactory factory)
                {
                    hr = factory.GetImage(new SIZE(width, height), SIIGBF.ThumbnailOnly | SIIGBF.ResizeToFit, out hBmp);
                    if (hr != 0 || hBmp == IntPtr.Zero)
                    {
                        factory.GetImage(new SIZE(width, height), SIIGBF.ResizeToFit, out hBmp);
                    }
                    if (hBmp != IntPtr.Zero)
                    {
                        var bs = Imaging.CreateBitmapSourceFromHBitmap(
                            hBmp,
                            IntPtr.Zero,
                            Int32Rect.Empty,
                            BitmapSizeOptions.FromEmptyOptions());
                        bs.Freeze();
                        result = bs;
                        SaveFrozenBitmapToJpeg(bs, cachedJpg);
                    }
                }
            }
            catch { }
            finally
            {
                if (hBmp != IntPtr.Zero) DeleteObject(hBmp);
                if (shellObj != null && Marshal.IsComObject(shellObj)) Marshal.ReleaseComObject(shellObj);
            }
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.IsBackground = true;
        thread.Start();
        thread.Join(3500);
        return result;
    }

    private static void SaveFrozenBitmapToJpeg(BitmapSource bmp, string destPath)
    {
        try
        {
            var enc = new JpegBitmapEncoder { QualityLevel = 84 };
            enc.Frames.Add(BitmapFrame.Create(bmp));
            using var fs = File.Create(destPath);
            enc.Save(fs);
        }
        catch { }
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    private static extern int SHCreateItemFromParsingName(
        [MarshalAs(UnmanagedType.LPWStr)] string pszPath,
        IntPtr pbc,
        ref Guid riid,
        [MarshalAs(UnmanagedType.Interface)] out object ppv);

    [DllImport("gdi32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DeleteObject(IntPtr hObject);

    [StructLayout(LayoutKind.Sequential)]
    private struct SIZE
    {
        public int cx;
        public int cy;
        public SIZE(int cx, int cy) { this.cx = cx; this.cy = cy; }
    }

    [Flags]
    private enum SIIGBF
    {
        ResizeToFit = 0x00,
        BiggerSizeOk = 0x01,
        MemoryOnly = 0x02,
        IconOnly = 0x04,
        ThumbnailOnly = 0x08,
        InCacheOnly = 0x10
    }

    [ComImport]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    [Guid("bcc18b79-ba16-442f-80c4-8a59c30c463b")]
    private interface IShellItemImageFactory
    {
        [PreserveSig]
        int GetImage(SIZE size, SIIGBF flags, out IntPtr phbm);
    }

    private static SyncMessageViewModel? ParseSingleMessageJson(string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            return ParseMessageElement(doc.RootElement);
        }
        catch
        {
            return null;
        }
    }

    private static SyncMessageViewModel? ParseMessageElement(JsonElement el)
    {
        if (el.ValueKind != JsonValueKind.Object) return null;
        if (!el.TryGetProperty("id", out var idEl) || !idEl.TryGetInt64(out var id)) return null;

        return new SyncMessageViewModel
        {
            Id = id,
            MsgUid = el.TryGetProperty("msgUid", out var muEl) ? muEl.GetString() ?? "" : "",
            Channel = el.TryGetProperty("channel", out var chEl) ? chEl.GetString() ?? "General" : "General",
            Sender = el.TryGetProperty("sender", out var sEl) ? sEl.GetString() ?? "PHONE" : "PHONE",
            TargetPeerId = el.TryGetProperty("targetPeerId", out var tpEl) ? tpEl.GetString() ?? "" : "",
            Type = el.TryGetProperty("type", out var tEl) ? tEl.GetString() ?? "TEXT" : "TEXT",
            TextContent = el.TryGetProperty("textContent", out var tcEl) ? tcEl.GetString() ?? "" : "",
            FileName = el.TryGetProperty("fileName", out var fnEl) ? fnEl.GetString() ?? "" : "",
            FileSize = el.TryGetProperty("fileSize", out var fsEl) && fsEl.TryGetInt64(out var fsv) ? fsv : 0,
            FileHash = el.TryGetProperty("fileHash", out var fhEl) ? fhEl.GetString() ?? "" : "",
            MimeType = el.TryGetProperty("mimeType", out var mEl) ? mEl.GetString() ?? "" : "",
            Timestamp = el.TryGetProperty("timestamp", out var tsEl) && tsEl.TryGetInt64(out var tsv) ? tsv : 0
        };
    }

    public static List<IPAddress> GetActiveLocalIPv4Addresses()
    {
        var list = new List<IPAddress>();
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up ||
                    ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;

                foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                {
                    if (ua.Address.AddressFamily == AddressFamily.InterNetwork &&
                        !IPAddress.IsLoopback(ua.Address))
                    {
                        var s = ua.Address.ToString();
                        if (!s.StartsWith("169.254.", StringComparison.Ordinal)) list.Add(ua.Address);
                    }
                }
            }
        }
        catch { }
        return list;
    }

    private static List<IPAddress> GetBroadcastAddresses()
    {
        var set = new HashSet<IPAddress> { IPAddress.Broadcast };
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up ||
                    ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;

                foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != AddressFamily.InterNetwork || ua.IPv4Mask == null) continue;
                    var ipBytes = ua.Address.GetAddressBytes();
                    var maskBytes = ua.IPv4Mask.GetAddressBytes();
                    if (ipBytes.Length == 4 && maskBytes.Length == 4)
                    {
                        var bcast = new byte[4];
                        for (int i = 0; i < 4; i++) bcast[i] = (byte)(ipBytes[i] | ~maskBytes[i]);
                        set.Add(new IPAddress(bcast));
                    }
                }
            }
        }
        catch { }
        return set.ToList();
    }

    public static string? FindAdbExecutable()
    {
        try
        {
            var pathEnv = Environment.GetEnvironmentVariable("PATH") ?? "";
            foreach (var dir in pathEnv.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
            {
                var p = Path.Combine(dir.Trim(), "adb.exe");
                if (File.Exists(p)) return p;
            }
        }
        catch { }

        string localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        string[] candidates =
        [
            Path.Combine(Environment.GetEnvironmentVariable("ANDROID_HOME") ?? "", "platform-tools", "adb.exe"),
            Path.Combine(Environment.GetEnvironmentVariable("ANDROID_SDK_ROOT") ?? "", "platform-tools", "adb.exe"),
            Path.Combine(localAppData, "Android", "Sdk", "platform-tools", "adb.exe"),
            Path.Combine(localAppData, "Microsoft", "WinGet", "Packages", "Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe", "platform-tools", "adb.exe")
        ];
        return candidates.FirstOrDefault(File.Exists);
    }

    private static async Task<string> RunProcessOutputAsync(string fileName, string args, int timeoutMs, CancellationToken ct)
    {
        var psi = new ProcessStartInfo
        {
            FileName = fileName,
            Arguments = args,
            CreateNoWindow = true,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        };
        using var proc = Process.Start(psi) ?? throw new InvalidOperationException("Failed to start process");
        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeoutCts.CancelAfter(timeoutMs);
        var output = await proc.StandardOutput.ReadToEndAsync(timeoutCts.Token).ConfigureAwait(false);
        await proc.WaitForExitAsync(timeoutCts.Token).ConfigureAwait(false);
        return output;
    }

    private static readonly HashSet<string> BlockedDirectLaunchExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".exe", ".msi", ".msp", ".bat", ".cmd", ".ps1", ".vbs", ".vbe",
        ".js", ".jse", ".wsf", ".wsh", ".scr", ".hta", ".lnk", ".cpl",
        ".reg", ".com", ".pif", ".dll"
    };

    public static void OpenFileOrFolder(string path)
    {
        if (string.IsNullOrWhiteSpace(path)) return;
        try
        {
            if (Uri.TryCreate(path, UriKind.Absolute, out var webUri) &&
                (webUri.Scheme == Uri.UriSchemeHttp || webUri.Scheme == Uri.UriSchemeHttps))
            {
                Process.Start(new ProcessStartInfo
                {
                    FileName = webUri.AbsoluteUri,
                    UseShellExecute = true
                });
                return;
            }

            if (!Path.IsPathRooted(path)) return;
            var fullPath = Path.GetFullPath(path);

            if (File.Exists(fullPath))
            {
                var ext = Path.GetExtension(fullPath);
                if (BlockedDirectLaunchExtensions.Contains(ext))
                {
                    Process.Start(new ProcessStartInfo
                    {
                        FileName = "explorer.exe",
                        Arguments = $"/select,\"{fullPath}\"",
                        UseShellExecute = false
                    });
                    return;
                }
            }
            else if (!Directory.Exists(fullPath))
            {
                return;
            }

            Process.Start(new ProcessStartInfo
            {
                FileName = fullPath,
                UseShellExecute = true
            });
        }
        catch { }
    }

    public void Dispose()
    {
        _cts.Cancel();
        try { _meshListener?.Stop(); } catch { }
        try { _ws?.Dispose(); } catch { }
        try { _http.Dispose(); } catch { }
        _cts.Dispose();
    }
}

internal sealed class ProgressReadStream : Stream
{
    private readonly Stream _inner;
    private readonly long _total;
    private readonly Action<long, long> _onProgress;
    private long _bytesRead;
    private long _lastEmitTick;

    public ProgressReadStream(Stream inner, long total, Action<long, long> onProgress)
    {
        _inner = inner;
        _total = total;
        _onProgress = onProgress;
    }

    public override bool CanRead => _inner.CanRead;
    public override bool CanSeek => false;
    public override bool CanWrite => false;
    public override long Length => _total;
    public override long Position { get => _bytesRead; set => throw new NotSupportedException(); }
    public override void Flush() => _inner.Flush();
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();

    public override int Read(byte[] buffer, int offset, int count)
    {
        int n = _inner.Read(buffer, offset, count);
        Report(n);
        return n;
    }

    public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
    {
        int n = await _inner.ReadAsync(buffer, cancellationToken).ConfigureAwait(false);
        Report(n);
        return n;
    }

    private void Report(int n)
    {
        if (n <= 0) return;
        _bytesRead += n;
        long now = Environment.TickCount64;
        if (now - _lastEmitTick >= 150 || _bytesRead == _total)
        {
            _lastEmitTick = now;
            _onProgress(_bytesRead, _total);
        }
    }
}
