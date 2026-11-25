# SanDen — Zero-Cloud Continuity Hub, Mesh File Sync & Private Vault

**SanDen** is a cross-platform **Android + Native Windows (.NET WPF)** zero-cloud local mesh suite for teams and personal workspaces. Connect phones, laptops, and desktops on a single Wi-Fi router (or USB ADB) to share raw videos, photos, code snippets, and clipboard items with **SHA-256 deduplication**, **offline catch-up sync**, **private 1-on-1 device routing**, and a **hidden on-device vault**.

---

## Key Capabilities

### Android App (`SanDen/` — Kotlin + Jetpack Compose)
- **Private Media Vault (`.nomedia`)**: Keeps files excluded from Android `MediaStore`, galleries, and media players in either **Drop Folder Mode** (`/storage/emulated/0/SanDen/`) or **Private Import Mode** (`context.filesDir/vault/`).
- **Continuity Hub (`LanTransferHub`)**: Local HTTP + RFC 6455 WebSocket server (`:54321`), UDP auto-discovery (`54322`), and mDNS/NSD mesh discovery.
- **Channel & Device Isolation**: Broadcast in **All Group** or switch to a **1-on-1 private device thread** (`targetPeerId` Room v5 isolation) so private messages/files stay hidden from other devices.
- **Built-in Media Experience**: Fullscreen zoomable photo viewer, Media3 ExoPlayer video player with `VideoFrameDecoder` previews, and compact waveform audio player.

### Native Windows Desktop (`SanDen-Windows/` — .NET 10 WPF)
- **Crimson-Obsidian Native Desktop UI**: Fast standalone WPF app (`SanDen.Desktop`) with in-app photo/video Lightbox playback, native Shell video thumbnails (`IShellItemImageFactory`), `Ctrl + V` image/file/large-code staging, `Ctrl + K` search, and drag-and-drop uploads.
- **Embedded Windows Peer Server**: Runs a non-admin `TcpListener` mesh peer (`:54321`, fallback `:54323+`; ADB USB bridge on `54400+`) so desktops can sync directly with other desktops and laptops even when phones are disconnected.
- **SHA-256 Deduplication & Zero-Copy Origin**: Indexes `SHA-256` hashes in `%LocalAppData%\SanDen\hash_index.json` so files shared from existing folders (e.g., `D:\RawFootage\clip.mp4`) are served in-place without duplicating into `Downloads\SanDen`, and re-shared files (`GET /api/files/has?sha256=...`) link instantly in zero bytes.
- **Offline Catch-Up Mesh (`↻ Sync`)**: Messages and file metadata persist in `mesh_store.json` with globally unique `msgUid`s. Powered-off PCs or phones catch up missed history and fetch blobs (`/api/blob?sha256=...`) from any online peer on reconnect without creating duplicates.
- **Hidden Vault Mode**: Optional toggle to store received desktop downloads inside a hidden `.Vault` folder (`FileAttributes.Hidden`) with full SHA-256 deduplication.

---

## Security Posture
- **No Hardcoded Signing Secrets**: Release keystore credentials load strictly from gitignored `local.properties` or `SANDEN_KEYSTORE_*` environment variables.
- **Origin CSRF Lock & Clean Headers**: Browser requests from untrusted `Origin`s are rejected (`403 Forbidden`), wildcard CORS headers are removed, and `X-Content-Type-Options: nosniff` + CRLF-sanitized `Content-Disposition` headers are enforced.
- **Companion UI XSS Hardening**: `companion.html` escapes sender metadata and avoids inline event-handler string interpolation.
- **Safe Windows File Launching**: `OpenFileOrFolder` restricts URL schemes to `http`/`https` and opens risky executable/script extensions (`.exe`, `.bat`, `.ps1`, `.vbs`, `.msi`, etc.) via `explorer.exe /select` instead of executing untrusted peer binaries directly.
- **Optional LAN Pairing Passkey**: Configure `lan_passkey` (Android `sanden_prefs`) and `SANDEN_PASSKEY` / `%LocalAppData%\SanDen\passkey.txt` (Windows) for constant-time SHA-256 `X-SanDen-Key` enforcement.

---

## Repository Structure

```text
SanDen/                 # Android Studio project (Kotlin, Compose, Hilt, Room v5, Media3, Coil)
├── app/                # Application source, resources, ProGuard rules, unit tests
├── gradle/             # Gradle 8.9 wrapper & version catalog (libs.versions.toml)
└── build.gradle.kts

SanDen-Windows/         # Visual Studio / .NET 10 WPF Desktop project
├── SanDen.Windows.sln
├── Publish-Portable.bat
└── SanDen.Desktop/     # Models, MeshStoreService, ContinuityHubService, XAML UI
```

---

## Getting Started

### 1. Android App (Android Studio)
1. Install **Android Studio** (Ladybug 2024.2.1+ or newer, JDK 17/21 bundled).
2. Extract/clone the repository and open the **`SanDen/`** folder in **Android Studio**.
3. Android Studio reads `gradle/wrapper/gradle-wrapper.properties` automatically, downloads Gradle 8.9 and dependencies, and creates `local.properties` with your local SDK path.
4. Connect an Android 8.0+ (`API 26+`) device or start an emulator and click **Run** (or `./gradlew :app:assembleDebug`).

### 2. Windows Desktop App (Visual Studio / .NET 10 SDK)
1. Install **.NET 10 SDK** (or **Visual Studio 2022/2026** with the *.NET desktop development* workload).
2. Open **`SanDen-Windows/SanDen.Windows.sln`** in Visual Studio and press **F5**, **or** run **`SanDen-Windows/Publish-Portable.bat`** to generate a self-contained single-file `SanDen-Desktop.exe` inside `SanDen-Windows/Publish/`.

---

## Author & License
Developed by **SanStudio** ([sanstudio-hub.github.io/in](https://sanstudio-hub.github.io/in/)).  
Released under the **MIT License** — see [LICENSE](LICENSE).
