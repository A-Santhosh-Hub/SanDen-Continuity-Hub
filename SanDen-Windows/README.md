# SanDen — Continuity Hub (Native Windows Desktop)

A fast, zero-cloud, native Windows desktop application built with **.NET / WPF (`SanDen.Desktop`)** that replaces the legacy PyInstaller script with instant startup, hardware-accelerated Crimson-Obsidian UI, and automatic LAN + USB phone synchronization.

## Project Structure

```text
SanDen-Windows/
├── SanDen.Windows.sln              # Visual Studio 2022 / VS 18 Solution
├── Publish-Portable.bat            # One-click portable single-file .exe builder
├── Publish/
│   └── SanDen-Desktop.exe          # Standalone portable Windows executable
└── SanDen.Desktop/                 # Native WPF C# Desktop Project
    ├── SanDen.Desktop.csproj
    ├── App.xaml / App.xaml.cs      # Crimson-Obsidian design tokens & Win32 AppID
    ├── MainWindow.xaml             # Full Continuity Hub UI (Chat, Vault, Transfer, Devices, Projects, Branches)
    ├── MainWindow.xaml.cs          # Interactive handlers, Ctrl+V rich paste, Ctrl+K search, drag & drop
    ├── Assets/favicon.ico          # Application icon
    ├── Models/HubModels.cs         # ViewModels & formatting helpers
    └── Services/
        └── ContinuityHubService.cs # Multi-NIC UDP 54322 discovery, /24 TCP sweep, ADB bridge, WebSocket /ws, streaming HTTP
```

## Key Features
- **Instant Native Startup**: Compiled native `.NET` WPF desktop binary with zero Python runtime unpack delay.
- **Automatic Multi-Path Discovery**:
  - Listens and broadcasts on **UDP `54322`** across all active Wi-Fi / Ethernet / Mobile Hotspot interfaces.
  - Performs fast asynchronous **`/24` subnet sweeps** on port `54321` when routers drop UDP broadcast packets.
  - Automatically checks **ADB (`wlan0` IP & `adb forward` USB bridge)** if a phone is plugged in via USB.
- **Real-Time Continuity**:
  - Live **WebSocket (`ws://<phone-ip>:54321/ws`)** push stream for instant incoming messages, code snippets, and files.
  - Outgoing `LAPTOP (You)` crimson-glow cards with inline `Expand all (N lines)` and one-click `Copy`.
  - Rich `Ctrl + V` support: paste screenshots directly from Windows Snipping Tool, files from Explorer, or multi-line code.
  - Streaming multi-GB file uploads and Vault downloads with live progress tracking.

## Opening & Building in Visual Studio
1. Open `SanDen.Windows.sln` in **Visual Studio** (with `.NET desktop development` workload installed).
2. Press **F5** to build and run `SanDen.Desktop`.
3. To produce a standalone portable `.exe`, run `Publish-Portable.bat` or execute:
   ```powershell
   dotnet publish SanDen.Desktop\SanDen.Desktop.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o .\Publish
   ```
