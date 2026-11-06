#!/usr/bin/env bash
# =====================================================================
#  GlobalVPN — WireGuard 伺服器一鍵安裝腳本（Ubuntu 20.04+ / Debian 11+）
#
#  用法：
#    sudo bash install.sh <國家代碼> [裝置名稱]
#        國家代碼：us jp de fr se uk fi es it no
#        例：sudo bash install.sh jp          → 產生 /root/globalvpn/jp-phone.conf
#            sudo bash install.sh jp tablet   → 再加一台裝置 jp-tablet.conf
#
#    sudo bash install.sh list                → 列出所有裝置
#    sudo bash install.sh show <裝置名稱>     → 再次顯示某裝置的設定與 QR code
#    sudo bash install.sh remove <裝置名稱>   → 刪除某裝置（該設定檔立即失效）
#    sudo bash install.sh status              → 顯示連線狀態
#    sudo bash install.sh uninstall           → 完整移除
#
#  可選環境變數：
#    WG_PORT=51820      UDP 連接埠（有些網路封鎖 51820 時可改 443 等）
#    WG_ENDPOINT=1.2.3.4  手動指定公網 IP（自動偵測失敗時使用）
# =====================================================================
set -euo pipefail

VALID_CC="us jp de fr se uk fi es it no"
WG_IF=wg0
WG_DIR=/etc/wireguard
WG_CONF="$WG_DIR/$WG_IF.conf"
STATE_DIR="$WG_DIR/globalvpn"
OUT_DIR=/root/globalvpn
NET4=10.66.66
NET6=fd66:66:66

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
yellow(){ printf '\033[33m%s\033[0m\n' "$*"; }
info()  { printf '\033[36m==>\033[0m %s\n' "$*"; }
die()   { red "錯誤：$*"; exit 1; }

usage() {
  cat <<'EOF'
用法：
  sudo bash install.sh <國家代碼> [裝置名稱]
      國家代碼：us jp de fr se uk fi es it no
      例：sudo bash install.sh jp
  sudo bash install.sh list | show <名稱> | remove <名稱> | status | uninstall
EOF
  exit 1
}

is_installed() { [[ -f "$WG_CONF" && -f "$STATE_DIR/server.pub" ]]; }

load_state() {
  CC=$(cat "$STATE_DIR/country")
  PORT=$(cat "$STATE_DIR/port")
  ENDPOINT=$(cat "$STATE_DIR/endpoint")
  HAS_V6=$(cat "$STATE_DIR/ipv6")
}

require_root() {
  [[ ${EUID:-$(id -u)} -eq 0 ]] || die "請用 root 執行，例如：sudo bash install.sh jp"
}

check_os() {
  local id="" pretty="未知"
  if [[ -r /etc/os-release ]]; then
    id=$(. /etc/os-release && echo "${ID:-}")
    pretty=$(. /etc/os-release && echo "${PRETTY_NAME:-未知}")
  fi
  case "$id" in
    ubuntu|debian) ;;
    *) die "目前只支援 Ubuntu 20.04+ / Debian 11+（偵測到：$pretty）" ;;
  esac
}

install_packages() {
  info "安裝 WireGuard 套件…"
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -y -qq
  apt-get install -y -qq wireguard-tools iptables qrencode curl ca-certificates iproute2 >/dev/null
}

check_kernel_support() {
  modprobe wireguard 2>/dev/null || true
  if ip link add gvtest0 type wireguard 2>/dev/null; then
    ip link del gvtest0
  else
    die "這台主機不支援 WireGuard（常見於 OpenVZ / LXC 容器型 VPS）。請改租 KVM 型 VPS。"
  fi
}

detect_network() {
  NIC=$(ip -4 route show default 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="dev"){print $(i+1); exit}}')
  [[ -n "$NIC" ]] || die "找不到預設網路介面"

  ENDPOINT="${WG_ENDPOINT:-}"
  if [[ -z "$ENDPOINT" ]]; then
    ENDPOINT=$(curl -4 -fsS --max-time 8 https://api.ipify.org 2>/dev/null \
            || curl -4 -fsS --max-time 8 https://ifconfig.me 2>/dev/null \
            || true)
  fi
  if [[ -z "$ENDPOINT" ]]; then
    ENDPOINT=$(ip -4 addr show dev "$NIC" | awk '/inet /{print $2}' | cut -d/ -f1 | head -n1)
  fi
  [[ "$ENDPOINT" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] \
    || die "偵測不到公網 IPv4，請用 WG_ENDPOINT=你的IP sudo -E bash install.sh $CC"

  HAS_V6=0
  if ip -6 route show default 2>/dev/null | grep -q default; then HAS_V6=1; fi
}

enable_forwarding() {
  info "開啟封包轉送…"
  cat > /etc/sysctl.d/99-globalvpn.conf <<EOF
net.ipv4.ip_forward = 1
net.ipv6.conf.all.forwarding = 1
# keep receiving IPv6 router advertisements on the uplink while forwarding
net.ipv6.conf.$NIC.accept_ra = 2
EOF
  sysctl -q -p /etc/sysctl.d/99-globalvpn.conf || true
}

build_firewall_rules() {
  # Rules added when wg0 comes up; PostDown removes the same rules.
  local up=""
  up+="iptables -I INPUT -p udp --dport $PORT -j ACCEPT; "
  up+="iptables -I FORWARD -i %i -j ACCEPT; "
  up+="iptables -I FORWARD -o %i -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT; "
  up+="iptables -t nat -A POSTROUTING -s $NET4.0/24 -o $NIC -j MASQUERADE; "
  up+="iptables -t mangle -A FORWARD -o %i -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu"
  if [[ "$HAS_V6" == 1 ]]; then
    up+="; ip6tables -I FORWARD -i %i -j ACCEPT; "
    up+="ip6tables -I FORWARD -o %i -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT; "
    up+="ip6tables -t nat -A POSTROUTING -s $NET6::/64 -o $NIC -j MASQUERADE"
  fi
  local down="${up//-I /-D }"
  down="${down//-A /-D }"
  POSTUP="$up"
  POSTDOWN="$down"
}

setup_server() {
  info "建立 WireGuard 伺服器（國家：$CC，連接埠 UDP $PORT）…"
  umask 077
  mkdir -p "$STATE_DIR" "$OUT_DIR"
  chmod 700 "$WG_DIR" "$STATE_DIR" "$OUT_DIR"

  local priv pub addr
  priv=$(wg genkey)
  pub=$(printf '%s' "$priv" | wg pubkey)
  printf '%s\n' "$pub"      > "$STATE_DIR/server.pub"
  printf '%s\n' "$CC"       > "$STATE_DIR/country"
  printf '%s\n' "$PORT"     > "$STATE_DIR/port"
  printf '%s\n' "$ENDPOINT" > "$STATE_DIR/endpoint"
  printf '%s\n' "$HAS_V6"   > "$STATE_DIR/ipv6"

  addr="$NET4.1/24"
  [[ "$HAS_V6" == 1 ]] && addr="$addr, $NET6::1/64"
  build_firewall_rules

  cat > "$WG_CONF" <<EOF
# GlobalVPN server — country: $CC
[Interface]
Address = $addr
ListenPort = $PORT
PrivateKey = $priv
PostUp = $POSTUP
PostDown = $POSTDOWN
EOF
  chmod 600 "$WG_CONF"

  if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
    ufw allow "$PORT/udp" >/dev/null && info "已在 ufw 開放 UDP $PORT"
  fi

  systemctl enable --now "wg-quick@$WG_IF" >/dev/null 2>&1 \
    || die "WireGuard 啟動失敗，請執行 journalctl -u wg-quick@$WG_IF 查看原因"
}

client_exists() { grep -qxF "# client: $1" "$WG_CONF"; }

add_client() {
  local name=$1
  [[ "$name" =~ ^[A-Za-z0-9_-]{1,20}$ ]] || die "裝置名稱只能用英文、數字、- 和 _（最多 20 字）"
  if client_exists "$name"; then
    yellow "裝置「$name」已經存在，以下是它的設定："
    show_client "$name"
    return
  fi

  local n="" i
  for i in $(seq 2 254); do
    if ! grep -qF "AllowedIPs = $NET4.$i/32" "$WG_CONF"; then n=$i; break; fi
  done
  [[ -n "$n" ]] || die "裝置數量已達上限（253 台）"

  umask 077
  local cpriv cpub psk spub allowed caddr dns file
  cpriv=$(wg genkey)
  cpub=$(printf '%s' "$cpriv" | wg pubkey)
  psk=$(wg genpsk)
  spub=$(cat "$STATE_DIR/server.pub")
  allowed="$NET4.$n/32"
  caddr="$NET4.$n/32"
  dns="1.1.1.1, 1.0.0.1"
  if [[ "$HAS_V6" == 1 ]]; then
    allowed="$allowed, $NET6::$n/128"
    caddr="$caddr, $NET6::$n/128"
    dns="$dns, 2606:4700:4700::1111"
  fi

  cat >> "$WG_CONF" <<EOF

# client: $name
[Peer]
PublicKey = $cpub
PresharedKey = $psk
AllowedIPs = $allowed
EOF

  mkdir -p "$OUT_DIR"
  file="$OUT_DIR/$CC-$name.conf"
  cat > "$file" <<EOF
[Interface]
PrivateKey = $cpriv
Address = $caddr
DNS = $dns
MTU = 1420

[Peer]
PublicKey = $spub
PresharedKey = $psk
Endpoint = $ENDPOINT:$PORT
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25
EOF
  chmod 600 "$file"
  reload_wg
  green "已新增裝置：$name"
  show_client "$name"
}

reload_wg() {
  if systemctl is-active --quiet "wg-quick@$WG_IF"; then
    wg syncconf "$WG_IF" <(wg-quick strip "$WG_IF")
  fi
}

show_client() {
  local file="$OUT_DIR/$CC-$1.conf"
  [[ -f "$file" ]] || die "找不到 $file"
  echo
  green "================ 設定檔：$file ================"
  cat "$file"
  green "==============================================================="
  echo
  if command -v qrencode >/dev/null 2>&1; then
    qrencode -t ansiutf8 < "$file"
  fi
  echo
  echo "匯入方式："
  echo "  1) 複製上面 [Interface] 到最後一行 → GlobalVPN app 點「$(cc_name "$CC")」→ 貼上設定文字"
  echo "  2) 或把 $file 傳到手機 → app 的「批次匯入設定檔」"
  echo "  3) 也可以用官方 WireGuard app 掃描上方 QR code"
  echo
  yellow "提醒：如果雲端主機商有「防火牆 / 安全群組」，請開放 UDP $PORT 入站。"
}

cc_name() {
  case "$1" in
    us) echo 美國;; jp) echo 日本;; de) echo 德國;; fr) echo 法國;; se) echo 瑞典;;
    uk) echo 英國;; fi) echo 芬蘭;; es) echo 西班牙;; it) echo 義大利;; no) echo 挪威;;
    *) echo "$1";;
  esac
}

list_clients() {
  echo "伺服器國家：$(cc_name "$CC")（$CC）  位址：$ENDPOINT:$PORT"
  echo "裝置："
  grep '^# client: ' "$WG_CONF" | sed 's/^# client: /  - /' || echo "  （沒有）"
}

remove_client() {
  local name=$1
  client_exists "$name" || die "沒有名為「$name」的裝置"
  awk -v marker="# client: $name" '
    $0 == marker { skip = 1; next }
    skip && NF == 0 { skip = 0 }
    skip { next }
    { print }
  ' "$WG_CONF" > "$WG_CONF.tmp"
  mv "$WG_CONF.tmp" "$WG_CONF"
  chmod 600 "$WG_CONF"
  rm -f "$OUT_DIR/$CC-$name.conf"
  reload_wg
  green "已刪除裝置：$name（它的設定檔已失效）"
}

uninstall_all() {
  systemctl disable --now "wg-quick@$WG_IF" >/dev/null 2>&1 || true
  rm -rf "$WG_CONF" "$STATE_DIR" "$OUT_DIR" /etc/sysctl.d/99-globalvpn.conf
  green "GlobalVPN 伺服器已移除。"
}

# Allow tests to source this file without running anything.
if [[ "${GLOBALVPN_LIB:-0}" == 1 ]]; then return 0 2>/dev/null || exit 0; fi

main() {
  local cmd="${1:-}"
  [[ -n "$cmd" ]] || usage
  require_root

  case "$cmd" in
    list|show|remove|status|uninstall)
      is_installed || die "尚未安裝。請先執行：sudo bash install.sh <國家代碼>"
      load_state
      case "$cmd" in
        list)      list_clients ;;
        show)      [[ -n "${2:-}" ]] || usage; show_client "$2" ;;
        remove)    [[ -n "${2:-}" ]] || usage; remove_client "$2" ;;
        status)    wg show "$WG_IF" ;;
        uninstall) uninstall_all ;;
      esac
      return
      ;;
  esac

  CC=$(printf '%s' "$cmd" | tr 'A-Z' 'a-z')
  [[ "$CC" == gb ]] && CC=uk
  [[ " $VALID_CC " == *" $CC "* ]] || { red "不支援的國家代碼：$cmd"; usage; }
  local client="${2:-phone}"

  check_os
  if is_installed; then
    load_state_keep_cc
  else
    PORT="${WG_PORT:-51820}"
    [[ "$PORT" =~ ^[0-9]+$ ]] && (( PORT >= 1 && PORT <= 65535 )) || die "WG_PORT 不正確：$PORT"
    install_packages
    check_kernel_support
    detect_network
    enable_forwarding
    setup_server
  fi
  add_client "$client"
}

load_state_keep_cc() {
  local requested=$CC
  load_state
  if [[ "$requested" != "$CC" ]]; then
    yellow "這台伺服器已經安裝成「$(cc_name "$CC")（$CC）」，沿用原本設定。"
  fi
}

main "$@"
