# GlobalVPN — 自架 10 國 WireGuard VPN（Android，免 root）

一個 Android VPN app ＋ 一鍵架設伺服器腳本。你在想要的國家各租一台雲端主機（VPS），
跑一行指令就變成你自己的 VPN 節點，app 裡點國家即可連線。

- **10 個國家**：🇺🇸 美國、🇯🇵 日本、🇩🇪 德國、🇫🇷 法國、🇸🇪 瑞典、🇬🇧 英國、🇫🇮 芬蘭、🇪🇸 西班牙、🇮🇹 義大利、🇳🇴 挪威
- **免 root**：使用 Android 內建 `VpnService`（Android 8.0 以上）
- **無時間限制**：伺服器是你自己的，沒有試用期或每日上限（只受 VPS 方案的流量額度影響）
- **全程加密**：WireGuard（ChaCha20-Poly1305 加密、Curve25519 金鑰交換、另加預共享金鑰），手機所有 IPv4 / IPv6 流量都走加密通道
- **速度**：WireGuard 是目前最輕量的 VPN 協定，加密的額外負擔通常只有幾個百分比；實際速度主要取決於你和伺服器的距離與 VPS 頻寬（從台灣連日本最快，歐洲延遲較高）
- **防洩漏**：支援 Android「永久連線 VPN ＋ 封鎖未使用 VPN 的連線」，開機自動連線、斷線時不會有未加密流量外洩

---

## 1. 租伺服器（每個國家一台）

選 **KVM 型**、系統選 **Ubuntu 24.04** 或 **Debian 12**，最小方案（1 vCPU / 1 GB RAM）就夠用。

| 國家 | 代碼 | 建議主機商（機房） |
|---|---|---|
| 美國 | `us` | Vultr（Los Angeles 或 Seattle，離台灣較近） |
| 日本 | `jp` | Vultr（Tokyo / Osaka） |
| 德國 | `de` | Vultr（Frankfurt）或 Hetzner（Nuremberg / Falkenstein） |
| 法國 | `fr` | Vultr（Paris） |
| 瑞典 | `se` | Vultr（Stockholm） |
| 英國 | `uk` | Vultr（London / Manchester） |
| 芬蘭 | `fi` | Hetzner（Helsinki）或 UpCloud（Helsinki） |
| 西班牙 | `es` | Vultr（Madrid） |
| 義大利 | `it` | Vultr（Milan） |
| 挪威 | `no` | EDIS Global、Virtono 或 UltaHost（Oslo） |

> 費用大約每台每月 US$5–7，10 國全開約 US$50–70／月。不需要 10 國全開，用到哪國租哪國即可。
> 主機商的機房與價格會變動，下單前請在官網確認。

## 2. 架設伺服器（每台一行指令）

用 SSH 登入 VPS（手機可用 Termius 等 SSH app），執行（把 `jp` 換成該國代碼）：

```bash
curl -fsSLO https://github.com/<你的GitHub帳號>/GlobalVPN/releases/download/latest/install.sh
sudo bash install.sh jp
```

腳本會自動：安裝 WireGuard → 設定加密金鑰與 NAT 轉送 → 開機自動啟動 → 印出手機用的設定檔與 QR code，
並存成 `/root/globalvpn/jp-phone.conf`。

> ⚠ 如果主機商有「防火牆 / 安全群組」（例如 Vultr Firewall、Hetzner Firewall、Azure NSG），請開放 **UDP 51820** 入站。

其他指令：

```bash
sudo bash install.sh jp tablet     # 同一台伺服器再加一台裝置（每台裝置用自己的設定檔）
sudo bash install.sh list          # 列出裝置
sudo bash install.sh show phone    # 再次顯示設定檔與 QR code
sudo bash install.sh remove tablet # 刪除裝置
sudo bash install.sh status        # 查看連線狀態
sudo bash install.sh uninstall     # 完整移除
WG_PORT=443 sudo -E bash install.sh jp   # 改用其他 UDP 連接埠（51820 被封鎖時）
```

## 3. 安裝 app 並匯入設定

1. 下載 `GlobalVPN.apk` 安裝（第一次會要求允許「安裝未知來源的應用程式」）
2. 開啟 app，**點國家 → 貼上設定文字**，把終端機印出的 `[Interface]` 到最後一行整段貼上
   - 或把多個 `.conf` 檔傳到手機，用 **「批次匯入設定檔」** 一次選取（檔名含國家代碼即可自動對應，例如 `jp-phone.conf`）
3. 點國家即可連線，第一次會跳出系統的 VPN 授權視窗，按「確定」
4. 連線後畫面會顯示**出口 IP 與出口位置**，確認流量真的從該國出去

### 開啟防洩漏（建議）
App 內按「🛡 防洩漏設定」→ 系統 VPN 設定 → GlobalVPN 的齒輪 → 開啟「永久連線的 VPN」與「封鎖未使用 VPN 的連線」。

---

## 從原始碼建置 APK

- 推送到 GitHub 後，`.github/workflows/build.yml` 會自動建置並把 APK 發佈到 Releases 的 `latest`
- 本機建置：用 Android Studio 開啟此資料夾，或安裝 Gradle 8.11+ 後執行 `gradle :app:assembleRelease`
- 正式簽章（可選）：在 repo 的 Secrets 設定 `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`；未設定時以 debug 金鑰簽章

## 專案結構

```
app/src/main/java/com/globalvpn/app/
  MainActivity.kt   介面：國家清單、連線按鈕、即時速度/流量、設定匯入
  VpnManager.kt     WireGuard GoBackend 連線管理、流量統計、出口 IP 檢查
  ConfigStore.kt    各國設定檔儲存（app 私有空間，不進雲端備份）
  Countries.kt      10 國清單與檔名辨識
server/install.sh   伺服器一鍵安裝 / 管理腳本
```

## 注意事項

- 本專案只提供技術工具。請遵守你所在地與伺服器所在國的法律，以及主機商的使用條款。
- 設定檔內含私鑰，請勿分享；外流時用 `install.sh remove <裝置>` 讓它失效再重新產生。
- iOS 版需要 Apple 開發者帳號（US$99／年）與 Network Extension 權限，無法直接側載；iPhone 可改用官方 WireGuard app 匯入同樣的設定檔或掃描 QR code。
