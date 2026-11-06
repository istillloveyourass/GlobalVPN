package com.globalvpn.app

import android.content.Context
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Owns the WireGuard userspace backend (GoBackend). Only one country tunnel is up at a time;
 * GoBackend switches tunnels for us when a different one is brought UP.
 *
 * All public functions must be called on the main thread. Backend calls run on [worker].
 */
object VpnManager {

    enum class Status { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

    data class State(
        val status: Status = Status.DISCONNECTED,
        val country: Country? = null,
        val rxBytes: Long = 0,
        val txBytes: Long = 0,
        val rxRate: Long = 0,
        val txRate: Long = 0,
        val exitIp: String? = null,
        val exitIso: String? = null,
        val checking: Boolean = false,
        val warning: String? = null,
        val error: String? = null,
        val connectedSince: Long = 0,
    )

    fun interface Listener {
        fun onVpnState(state: State)
    }

    private class CountryTunnel(val country: Country) : Tunnel {
        // WireGuard tunnel names must match [a-zA-Z0-9_=+.-]{1,15}
        override fun getName(): String = "gv-" + country.code

        override fun onStateChange(newState: Tunnel.State) {
            val self = this
            if (newState == Tunnel.State.DOWN) {
                VpnManager.main.post { VpnManager.onTunnelDown(self) }
            }
        }
    }

    private lateinit var appContext: Context
    private val backend: GoBackend by lazy { GoBackend(appContext) }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private val tunnels = HashMap<String, CountryTunnel>()
    private var current: CountryTunnel? = null
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastSample = 0L
    private var checkGeneration = 0

    var state = State()
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        // Fired when Android starts our VPN service itself ("Always-on VPN", e.g. after reboot).
        GoBackend.setAlwaysOnCallback { main.post { connectLast() } }
    }

    // ---------------------------------------------------------------- listeners

    fun addListener(l: Listener) {
        listeners.add(l)
        l.onVpnState(state)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun publish(newState: State) {
        state = newState
        for (l in listeners.toList()) l.onVpnState(newState)
    }

    // ---------------------------------------------------------------- config helpers

    fun parseConfig(text: String): Config = Config.parse(BufferedReader(StringReader(text)))

    /** Returns null when the text is a valid WireGuard config, otherwise a readable reason. */
    fun validate(text: String): String? {
        if (!text.contains("[Interface]") || !text.contains("[Peer]")) {
            return "這不是 WireGuard 設定檔（需要包含 [Interface] 和 [Peer]）"
        }
        return try {
            val cfg = parseConfig(text)
            if (cfg.peers.isEmpty()) "設定檔裡沒有 [Peer] 伺服器資料"
            else if (cfg.peers[0].endpoint.isPresent.not()) "設定檔缺少 Endpoint（伺服器位址）"
            else null
        } catch (e: Exception) {
            "設定檔格式錯誤" + (e.message?.let { "：$it" } ?: "")
        }
    }

    fun endpointOf(text: String): String? = try {
        parseConfig(text).peers.firstOrNull()?.endpoint?.orElse(null)?.let { "${it.host}:${it.port}" }
    } catch (e: Exception) {
        null
    }

    // ---------------------------------------------------------------- connect / disconnect

    val isBusy: Boolean
        get() = state.status == Status.CONNECTING || state.status == Status.DISCONNECTING

    fun connectLast() {
        if (isBusy || state.status == Status.CONNECTED) return
        val country = Countries.byCode(ConfigStore.lastCountry)?.takeIf { ConfigStore.has(it.code) }
            ?: Countries.ALL.firstOrNull { ConfigStore.has(it.code) }
            ?: return
        connect(country)
    }

    /** Caller must have obtained VPN consent (VpnService.prepare) first. */
    fun connect(country: Country) {
        if (isBusy) return
        val text = ConfigStore.get(country.code)
        if (text == null) {
            publish(state.copy(error = "尚未匯入${country.nameZh}的設定檔"))
            return
        }
        val config = try {
            parseConfig(text)
        } catch (e: Exception) {
            publish(state.copy(error = "${country.nameZh}的設定檔無效，請重新匯入"))
            return
        }
        if (VpnService.prepare(appContext) != null) {
            publish(state.copy(error = "需要先允許本 app 建立 VPN 連線"))
            return
        }

        val tunnel = tunnels.getOrPut(country.code) { CountryTunnel(country) }
        val previous = current
        ConfigStore.lastCountry = country.code
        checkGeneration++
        stopStats()
        publish(State(status = Status.CONNECTING, country = country))

        worker.execute {
            try {
                backend.setState(tunnel, Tunnel.State.UP, config)
                main.post { onConnected(tunnel) }
            } catch (e: Exception) {
                // On a failed switch GoBackend restores the previous tunnel.
                val restored = previous != null &&
                        runCatching { backend.getState(previous) }.getOrNull() == Tunnel.State.UP
                val message = describeBackendError(e)
                main.post {
                    if (restored && previous != null) {
                        onConnected(previous)
                        publish(state.copy(error = message))
                    } else {
                        current = null
                        publish(State(status = Status.DISCONNECTED, country = country, error = message))
                    }
                }
            }
        }
    }

    fun disconnect() {
        val tunnel = current ?: return
        if (isBusy) return
        checkGeneration++
        stopStats()
        publish(state.copy(status = Status.DISCONNECTING, rxRate = 0, txRate = 0))
        worker.execute {
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
            } catch (_: Exception) {
            }
            main.post {
                current = null
                publish(State(status = Status.DISCONNECTED, country = tunnel.country))
            }
        }
    }

    private fun onConnected(tunnel: CountryTunnel) {
        current = tunnel
        lastRx = 0
        lastTx = 0
        lastSample = 0
        publish(
            State(
                status = Status.CONNECTED,
                country = tunnel.country,
                checking = true,
                connectedSince = System.currentTimeMillis(),
            )
        )
        startStats()
        checkExit(tunnel.country)
    }

    /** The tunnel went down without us asking (revoked by system / another VPN app). */
    private fun onTunnelDown(tunnel: CountryTunnel) {
        if (current !== tunnel) return
        if (isBusy) return
        current = null
        checkGeneration++
        stopStats()
        publish(
            State(
                status = Status.DISCONNECTED,
                country = tunnel.country,
                error = "VPN 已被系統或其他 VPN app 關閉",
            )
        )
    }

    private fun describeBackendError(e: Throwable): String {
        if (e is BackendException) {
            return when (e.reason.name) {
                "VPN_NOT_AUTHORIZED" -> "尚未允許建立 VPN（請在系統對話框按「確定」）"
                "UNABLE_TO_START_VPN" -> "無法啟動 VPN 服務，請重新開啟 app 再試"
                "TUN_CREATION_ERROR" -> "無法建立 VPN 介面：可能有其他 VPN app 設為「永久連線」"
                "GO_ACTIVATION_ERROR_CODE" -> "WireGuard 啟動失敗，請確認設定檔正確"
                "DNS_RESOLUTION_FAILURE" -> "無法解析伺服器位址，請檢查網路"
                else -> "連線失敗（${e.reason.name}）"
            }
        }
        return "連線失敗：" + (e.message ?: e.javaClass.simpleName)
    }

    // ---------------------------------------------------------------- traffic statistics

    private val statsTick = object : Runnable {
        override fun run() {
            val tunnel = current ?: return
            worker.execute {
                val stats = try {
                    backend.getStatistics(tunnel)
                } catch (e: Exception) {
                    null
                }
                if (stats != null) {
                    val rx = stats.totalRx()
                    val tx = stats.totalTx()
                    main.post { onStats(tunnel, rx, tx) }
                }
            }
            main.postDelayed(this, 1000)
        }
    }

    private fun onStats(tunnel: CountryTunnel, rx: Long, tx: Long) {
        if (current !== tunnel || state.status != Status.CONNECTED) return
        val now = SystemClock.elapsedRealtime()
        var rxRate = 0L
        var txRate = 0L
        if (lastSample > 0) {
            val dt = (now - lastSample).coerceAtLeast(1)
            rxRate = (rx - lastRx).coerceAtLeast(0) * 1000 / dt
            txRate = (tx - lastTx).coerceAtLeast(0) * 1000 / dt
        }
        lastRx = rx
        lastTx = tx
        lastSample = now
        publish(state.copy(rxBytes = rx, txBytes = tx, rxRate = rxRate, txRate = txRate))
    }

    private fun startStats() {
        main.removeCallbacks(statsTick)
        main.post(statsTick)
    }

    private fun stopStats() {
        main.removeCallbacks(statsTick)
    }

    // ---------------------------------------------------------------- exit IP check

    /** Asks Cloudflare which IP / country our traffic now exits from. */
    private fun checkExit(country: Country) {
        val gen = ++checkGeneration
        Thread {
            var ip: String? = null
            var iso: String? = null
            var attempt = 0
            while (attempt < 4 && ip == null && gen == checkGeneration) {
                try {
                    val conn = URL("https://www.cloudflare.com/cdn-cgi/trace")
                        .openConnection() as HttpURLConnection
                    conn.connectTimeout = 6000
                    conn.readTimeout = 6000
                    conn.useCaches = false
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    for (line in body.lines()) {
                        if (line.startsWith("ip=")) ip = line.substring(3).trim()
                        if (line.startsWith("loc=")) iso = line.substring(4).trim()
                    }
                } catch (e: Exception) {
                    try {
                        Thread.sleep(1500)
                    } catch (_: InterruptedException) {
                    }
                }
                attempt++
            }
            val foundIp = ip
            val foundIso = iso
            main.post { onExitChecked(gen, country, foundIp, foundIso) }
        }.start()
    }

    private fun onExitChecked(gen: Int, country: Country, ip: String?, iso: String?) {
        if (gen != checkGeneration || state.status != Status.CONNECTED) return
        if (ip == null) {
            publish(
                state.copy(
                    checking = false,
                    warning = "通道已建立，但連不上網路：請確認伺服器正在運作，且雲端防火牆已開放 UDP 連接埠",
                )
            )
            return
        }
        val warning = if (iso != null && !iso.equals(country.iso, ignoreCase = true)) {
            val where = Countries.byIso(iso)?.nameZh ?: iso
            "注意：出口 IP 被判定在「$where」，與所選國家不同（也可能是 IP 資料庫誤差）"
        } else null
        publish(state.copy(checking = false, exitIp = ip, exitIso = iso, warning = warning))
    }
}
