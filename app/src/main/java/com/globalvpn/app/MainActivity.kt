package com.globalvpn.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.globalvpn.app.VpnManager.Status
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale

class MainActivity : Activity(), VpnManager.Listener {

    private lateinit var btnPower: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtWarning: TextView
    private lateinit var txtDown: TextView
    private lateinit var txtUp: TextView
    private lateinit var txtIp: TextView
    private lateinit var txtLoc: TextView
    private lateinit var txtDuration: TextView
    private lateinit var txtTraffic: TextView
    private lateinit var countryList: LinearLayout
    private val rows = HashMap<String, View>()

    private var selected: Country? = null
    private var pendingConnect: Country? = null
    private var pendingImport: Country? = null
    private var lastRowsKey = ""

    companion object {
        private const val REQ_VPN = 1
        private const val REQ_FILE = 2
        private const val MAX_CONFIG_BYTES = 64 * 1024
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnPower = findViewById(R.id.btnPower)
        txtStatus = findViewById(R.id.txtStatus)
        txtWarning = findViewById(R.id.txtWarning)
        txtDown = findViewById(R.id.txtDown)
        txtUp = findViewById(R.id.txtUp)
        txtIp = findViewById(R.id.txtIp)
        txtLoc = findViewById(R.id.txtLoc)
        txtDuration = findViewById(R.id.txtDuration)
        txtTraffic = findViewById(R.id.txtTraffic)
        countryList = findViewById(R.id.countryList)

        selected = Countries.byCode(ConfigStore.lastCountry)
            ?: Countries.ALL.firstOrNull { ConfigStore.has(it.code) }
            ?: Countries.ALL[0]

        buildRows()

        btnPower.setOnClickListener { onPowerClicked() }
        findViewById<View>(R.id.btnImport).setOnClickListener { pickFiles(null) }
        findViewById<View>(R.id.btnKillSwitch).setOnClickListener { showKillSwitchHelp() }
        findViewById<View>(R.id.btnHelp).setOnClickListener { showHelp() }
    }

    override fun onStart() {
        super.onStart()
        VpnManager.addListener(this)
    }

    override fun onStop() {
        VpnManager.removeListener(this)
        super.onStop()
    }

    // ---------------------------------------------------------------- country list

    private fun buildRows() {
        countryList.removeAllViews()
        rows.clear()
        val inflater = LayoutInflater.from(this)
        for (c in Countries.ALL) {
            val row = inflater.inflate(R.layout.item_country, countryList, false)
            row.findViewById<TextView>(R.id.rowFlag).text = c.flag
            row.findViewById<TextView>(R.id.rowName).text = getString(R.string.country_name, c.nameZh, c.nameEn)
            row.setOnClickListener { onCountryClicked(c) }
            row.setOnLongClickListener {
                showConfigDialog(c)
                true
            }
            countryList.addView(row)
            rows[c.code] = row
        }
        refreshRows()
    }

    private fun refreshRows() {
        val st = VpnManager.state
        for (c in Countries.ALL) {
            val row = rows[c.code] ?: continue
            val sub = row.findViewById<TextView>(R.id.rowSub)
            val mark = row.findViewById<TextView>(R.id.rowMark)
            val cfg = ConfigStore.get(c.code)
            sub.text = if (cfg == null) {
                getString(R.string.row_not_configured)
            } else {
                getString(R.string.row_configured, VpnManager.endpointOf(cfg) ?: "")
            }
            val isActive = st.country?.code == c.code && st.status != Status.DISCONNECTED
            val isSelected = selected?.code == c.code
            when {
                isActive && st.status == Status.CONNECTED -> {
                    mark.text = getString(R.string.mark_connected)
                    mark.setTextColor(getColor(R.color.green))
                }
                isActive -> {
                    mark.text = getString(R.string.mark_busy)
                    mark.setTextColor(getColor(R.color.amber))
                }
                isSelected -> {
                    mark.text = "●"
                    mark.setTextColor(getColor(R.color.accent))
                }
                else -> mark.text = ""
            }
            row.setBackgroundResource(if (isSelected) R.drawable.bg_row_selected else R.drawable.bg_row)
        }
    }

    private fun onCountryClicked(c: Country) {
        selected = c
        if (!ConfigStore.has(c.code)) {
            refreshRows()
            onVpnState(VpnManager.state)
            showConfigDialog(c)
            return
        }
        ConfigStore.lastCountry = c.code
        val st = VpnManager.state
        refreshRows()
        onVpnState(st)
        if (VpnManager.isBusy) {
            toast(getString(R.string.please_wait))
            return
        }
        if (st.status == Status.CONNECTED && st.country?.code == c.code) return
        requestConnect(c)
    }

    // ---------------------------------------------------------------- connect

    private fun onPowerClicked() {
        when (VpnManager.state.status) {
            Status.CONNECTED -> VpnManager.disconnect()
            Status.DISCONNECTED -> {
                val c = selected ?: Countries.ALL[0]
                if (ConfigStore.has(c.code)) requestConnect(c) else showConfigDialog(c)
            }
            else -> toast(getString(R.string.please_wait))
        }
    }

    private fun requestConnect(c: Country) {
        val consent = VpnService.prepare(this)
        if (consent != null) {
            pendingConnect = c
            try {
                startActivityForResult(consent, REQ_VPN)
            } catch (e: Exception) {
                toast(getString(R.string.vpn_consent_failed))
            }
        } else {
            VpnManager.connect(c)
        }
    }

    @Deprecated("Framework Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_VPN -> {
                val c = pendingConnect
                pendingConnect = null
                if (resultCode == RESULT_OK && c != null) VpnManager.connect(c)
                else toast(getString(R.string.vpn_consent_denied))
            }
            REQ_FILE -> {
                if (resultCode != RESULT_OK || data == null) return
                val uris = ArrayList<Uri>()
                data.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                }
                if (uris.isEmpty()) data.data?.let { uris.add(it) }
                if (uris.isEmpty()) return
                val target = pendingImport
                pendingImport = null
                if (target != null) importOne(uris[0], target) else importBatch(uris)
            }
        }
    }

    // ---------------------------------------------------------------- VPN state -> UI

    override fun onVpnState(state: VpnManager.State) {
        val sel = selected
        val country = state.country ?: sel
        val flag = country?.flag ?: "🌐"
        when (state.status) {
            Status.DISCONNECTED -> {
                btnPower.setBackgroundResource(R.drawable.bg_power_off)
                val f = sel?.flag ?: flag
                btnPower.text = getString(R.string.power_connect, f)
                txtStatus.text = if (sel != null && ConfigStore.has(sel.code)) {
                    getString(R.string.status_ready, sel.nameZh)
                } else {
                    getString(R.string.status_need_config)
                }
            }
            Status.CONNECTING -> {
                btnPower.setBackgroundResource(R.drawable.bg_power_busy)
                btnPower.text = getString(R.string.power_connecting, flag)
                txtStatus.text = getString(R.string.status_connecting, country?.nameZh ?: "")
            }
            Status.CONNECTED -> {
                btnPower.setBackgroundResource(R.drawable.bg_power_on)
                btnPower.text = getString(R.string.power_connected, flag)
                txtStatus.text = getString(R.string.status_connected, country?.nameZh ?: "")
            }
            Status.DISCONNECTING -> {
                btnPower.setBackgroundResource(R.drawable.bg_power_busy)
                btnPower.text = getString(R.string.power_disconnecting, flag)
                txtStatus.text = getString(R.string.status_disconnecting)
            }
        }

        val connected = state.status == Status.CONNECTED
        txtDown.text = if (connected) fmtRate(state.rxRate) else "—"
        txtUp.text = if (connected) fmtRate(state.txRate) else "—"
        txtIp.text = when {
            state.exitIp != null -> state.exitIp
            connected && state.checking -> getString(R.string.checking)
            else -> "—"
        }
        txtLoc.text = state.exitIso?.let { iso ->
            Countries.byIso(iso)?.let { "${it.flag} ${it.nameZh}" } ?: iso
        } ?: "—"
        txtDuration.text = if (connected && state.connectedSince > 0) {
            fmtDuration((System.currentTimeMillis() - state.connectedSince) / 1000)
        } else "—"
        txtTraffic.text = if (connected) {
            "↓${fmtBytes(state.rxBytes)} ↑${fmtBytes(state.txBytes)}"
        } else "—"

        val err = state.error
        val warn = state.warning
        when {
            err != null -> {
                txtWarning.visibility = View.VISIBLE
                txtWarning.text = err
                txtWarning.setTextColor(getColor(R.color.red))
            }
            warn != null -> {
                txtWarning.visibility = View.VISIBLE
                txtWarning.text = warn
                txtWarning.setTextColor(getColor(R.color.amber))
            }
            else -> txtWarning.visibility = View.GONE
        }

        // Rows only need refreshing when status / country / selection change (not every stats tick).
        val key = "${state.status}|${state.country?.code}|${sel?.code}"
        if (key != lastRowsKey) {
            lastRowsKey = key
            refreshRows()
        }
    }

    // ---------------------------------------------------------------- config import

    private fun showConfigDialog(c: Country) {
        val has = ConfigStore.has(c.code)
        val items = if (has) {
            arrayOf(getString(R.string.cfg_paste), getString(R.string.cfg_file), getString(R.string.cfg_delete))
        } else {
            arrayOf(getString(R.string.cfg_paste), getString(R.string.cfg_file))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cfg_title, c.flag, c.nameZh))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showPasteDialog(c)
                    1 -> pickFiles(c)
                    2 -> confirmDelete(c)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showPasteDialog(c: Country) {
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 6
            maxLines = 14
            typeface = Typeface.MONOSPACE
            textSize = 12f
            hint = "[Interface]\nPrivateKey = …\n\n[Peer]\nEndpoint = …"
            setText(clipboardConfig() ?: ConfigStore.get(c.code) ?: "")
        }
        val wrap = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(edit)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.paste_title, c.flag, c.nameZh))
            .setView(wrap)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val err = saveConfig(c, edit.text.toString())
                if (err == null) dialog.dismiss() else edit.error = err
            }
        }
        dialog.show()
    }

    private fun clipboardConfig(): String? = try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null
        text?.takeIf { it.contains("[Interface]") }
    } catch (e: Exception) {
        null
    }

    /** Validates and stores a config. Returns an error message, or null on success. */
    private fun saveConfig(c: Country, raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return getString(R.string.err_empty)
        VpnManager.validate(text)?.let { return it }
        ConfigStore.put(c.code, text)
        selected = c
        ConfigStore.lastCountry = c.code
        refreshRows()
        onVpnState(VpnManager.state)
        toast(getString(R.string.saved, c.nameZh))
        // Already connected to this country? Reconnect so the new config takes effect.
        val st = VpnManager.state
        if (st.status == Status.CONNECTED && st.country?.code == c.code) requestConnect(c)
        return null
    }

    private fun confirmDelete(c: Country) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_title, c.nameZh))
            .setMessage(R.string.delete_msg)
            .setPositiveButton(R.string.delete) { _, _ ->
                val st = VpnManager.state
                if (st.status == Status.CONNECTED && st.country?.code == c.code) VpnManager.disconnect()
                ConfigStore.remove(c.code)
                refreshRows()
                onVpnState(VpnManager.state)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** [target] = null means batch import: the country is guessed from each file name. */
    private fun pickFiles(target: Country?) {
        pendingImport = target
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, target == null)
        }
        try {
            startActivityForResult(intent, REQ_FILE)
        } catch (e: Exception) {
            toast(getString(R.string.no_file_picker))
        }
    }

    private fun importOne(uri: Uri, c: Country) {
        val text = try {
            readText(uri)
        } catch (e: Exception) {
            toast(getString(R.string.read_failed, e.message ?: ""))
            return
        }
        val err = saveConfig(c, text)
        if (err != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.import_failed)
                .setMessage(err)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    private fun importBatch(uris: List<Uri>) {
        val ok = ArrayList<String>()
        val failed = ArrayList<String>()
        for (uri in uris) {
            val name = displayName(uri)
            val country = Countries.guessFromFileName(name)
            if (country == null) {
                failed.add(getString(R.string.batch_unknown_country, name))
                continue
            }
            val text = try {
                readText(uri).trim()
            } catch (e: Exception) {
                failed.add("$name：${e.message ?: "讀取失敗"}")
                continue
            }
            val err = VpnManager.validate(text)
            if (err != null) {
                failed.add("$name：$err")
                continue
            }
            ConfigStore.put(country.code, text)
            ok.add("${country.flag} ${country.nameZh}（$name）")
            if (selected == null || !ConfigStore.has(selected!!.code)) selected = country
        }
        refreshRows()
        onVpnState(VpnManager.state)
        val msg = StringBuilder()
        if (ok.isNotEmpty()) msg.append(getString(R.string.batch_ok, ok.size)).append('\n').append(ok.joinToString("\n"))
        if (failed.isNotEmpty()) {
            if (msg.isNotEmpty()) msg.append("\n\n")
            msg.append(getString(R.string.batch_failed, failed.size)).append('\n').append(failed.joinToString("\n"))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.batch_title)
            .setMessage(msg.toString())
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val n = cursor.getString(0)
                    if (!n.isNullOrEmpty()) return n
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "?"
    }

    private fun readText(uri: Uri): String {
        val input = contentResolver.openInputStream(uri) ?: throw IOException("無法開啟檔案")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_CONFIG_BYTES) throw IOException("檔案太大，不像設定檔")
                out.write(buf, 0, n)
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    // ---------------------------------------------------------------- help dialogs

    private fun showKillSwitchHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.kill_title)
            .setMessage(R.string.kill_msg)
            .setPositiveButton(R.string.kill_open) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                    } catch (_: Exception) {
                        toast(getString(R.string.no_settings))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.help_title)
            .setMessage(R.string.help_msg)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    // ---------------------------------------------------------------- formatting

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun fmtBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    private fun fmtRate(bytesPerSec: Long): String = fmtBytes(bytesPerSec) + "/s"

    private fun fmtDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }
}
