/*
 * SettingsActivity.kt — 服务器设置页（应用内设置）。
 * 入口：① 长按桌面图标「服务器设置」快捷方式；② 错误页「服务器设置」按钮；③ 遥控设置键。
 *
 * 2026-11-19 起地址全部由用户填写，代码里不再有写死的预设：
 *   · 内网地址：可填多个，一行一个；下方「使用地址」下拉选定在家时用哪个
 *   · 外网地址：单个，手动输入
 *   · 家里 WiFi：名称列表（逗号分隔），用于自动判断在家/外出；可一键读取当前 WiFi
 *   · 网络模式：自动（读 WiFi）/ 强制在家 / 强制外出
 *   · 不做网络探测：在家用选中的内网地址，外出用外网地址，失败走离线副本/错误页
 */
package com.sunhuip.ha3dapp

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.zxing.integration.android.IntentIntegrator

class SettingsActivity : Activity() {

    private lateinit var etLocal: EditText
    private lateinit var spLocal: Spinner
    private lateinit var etRemote: EditText
    private lateinit var etHomeSsid: EditText
    private lateinit var spMode: Spinner
    private lateinit var tvStatus: TextView
    private lateinit var tvConnected: TextView

    private val modes = listOf("自动（按 WiFi 判断）", "强制在家（内网）", "强制外出（外网）")
    private val modeValues = listOf(
        Config.NETWORK_MODE_AUTO, Config.NETWORK_MODE_HOME, Config.NETWORK_MODE_AWAY
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val d = resources.displayMetrics.density
        val pad = (18 * d).toInt()

        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 13f; setTextColor(Color.rgb(100, 115, 135))
            setPadding(0, (12 * d).toInt(), 0, (6 * d).toInt())
        }
        fun hintText(t: String) = TextView(this).apply {
            text = t; textSize = 11f; setTextColor(Color.rgb(150, 160, 175))
            setPadding(0, (2 * d).toInt(), 0, 0)
        }

        /* 内网地址：一行一个；下面下拉框选定在家时用哪个 */
        etLocal = EditText(this).apply {
            setText(Prefs.localRaw(this@SettingsActivity) ?: "")
            hint = "http://192.168.1.10:8099/index.html\n（一行一个，可填多个）"
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSingleLine(false); minLines = 3; textSize = 14f
        }

        /* 使用地址：从上面输入的内网地址里下拉选定一个（存 URL 原文，不存序号——
           列表增删行后按 URL 重新对位，对不上就回落第一个） */
        spLocal = Spinner(this)
        fun refreshLocalSpinner() {
            val items = etLocal.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
            spLocal.adapter = android.widget.ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, items
            )
            val cur = spLocal.selectedItem as? String
                ?: Prefs.localSelected(this@SettingsActivity)
            val idx = items.indexOf(cur)
            spLocal.setSelection(if (idx >= 0) idx else 0)
        }
        etLocal.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun afterTextChanged(s: android.text.Editable?) { refreshLocalSpinner() }
        })
        refreshLocalSpinner()

        /* 外网地址：单个，手动输入（代码里不写死） */
        etRemote = EditText(this).apply {
            setText(Prefs.remote(this@SettingsActivity))
            hint = "外网地址（如内网穿透域名），留空则外出时仍用内网地址"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true); textSize = 14f
        }

        /* 家里 WiFi 名称：逗号分隔 + 一键读取当前 WiFi */
        etHomeSsid = EditText(this).apply {
            setText(Prefs.homeSsids(this@SettingsActivity))
            hint = "多个用英文逗号分隔，如 Home,Home_5G"
            setSingleLine(true); textSize = 14f
        }
        val btnReadWifi = Button(this).apply { text = "读取当前 WiFi"; textSize = 13f }
        btnReadWifi.setOnClickListener { readCurrentWifi() }

        /* 网络模式：自动 / 强制在家 / 强制外出 */
        spMode = Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, modes
            )
            setSelection(modeValues.indexOf(Prefs.networkMode(this@SettingsActivity)).coerceAtLeast(0))
        }

        tvStatus = TextView(this).apply {
            textSize = 13f; setPadding(0, (14 * d).toInt(), 0, 0)
            setTextColor(Color.rgb(47, 111, 237))
            text = " 当前环境：${NetEnv.describe(this@SettingsActivity)}"
        }

        /* 当前实际连接的地址：以 Shell 真实加载的 URL 为准（在家=选中的内网地址，
           外出=外网地址）。null = 错误页/尚未连上。 */
        tvConnected = TextView(this).apply {
            textSize = 14f; setPadding(0, 0, 0, (4 * d).toInt())
            setTextColor(Color.rgb(30, 140, 70))
            text = connText()
        }

        val btnSave = Button(this).apply { text = "保存"; textSize = 14f }
        btnSave.setOnClickListener { save() }

        val note = TextView(this).apply {
            textSize = 12f; setTextColor(Color.rgb(150, 160, 175))
            text = "说明：连家里 WiFi 时用「使用地址」里选中的内网地址；\n" +
                "不在家时用「外网地址」（没填外网则仍用内网地址）。切 WiFi 自动切换内外网。\n" +
                "地址连不上不做探测，直接走离线缓存副本或错误页。\n" +
                "自动模式读 WiFi 名称需要定位权限，读不到时默认按在家处理。"
            setPadding(0, (14 * d).toInt(), 0, 0)
        }

        /* 屏幕常亮：切换即保存，返回页面立即生效 */
        val swKeep = android.widget.Switch(this).apply {
            text = "屏幕常亮（演示板常驻设备建议开）"
            textSize = 14f
            isChecked = Prefs.keepAwake(this@SettingsActivity)
            setPadding(0, (16 * d).toInt(), 0, 0)
        }
        swKeep.setOnCheckedChangeListener { _, checked ->
            Prefs.setKeepAwake(this@SettingsActivity, checked)
            Toast.makeText(this, if (checked) "已开启常亮，返回页面即生效" else "已关闭常亮，返回页面即生效",
                Toast.LENGTH_SHORT).show()
        }

        /* 运行诊断：老版本 WebView / 硬件加速没生效是「掉帧、白屏、闪退」的常见根因 */
        val diag = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(120, 132, 148))
            setTextIsSelectable(true)
            setPadding(0, (18 * d).toInt(), 0, (6 * d).toInt())
            text = "运行诊断\n${Shell.diagText()}\n${NetEnv.describe(this@SettingsActivity)}"
        }

        val btnClearCache = Button(this).apply { text = "清除离线缓存"; textSize = 13f }
        btnClearCache.setOnClickListener {
            WebCache.clear(this)
            diag.text = "运行诊断\n${Shell.diagText()}\n${NetEnv.describe(this@SettingsActivity)}"
            Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show()
        }

        /* 扫码授权：App 已登录 = 授权方，扫另一台设备登录页展示的一次性二维码，
           批准它登录（登录态落在对方，App 自己不变）。跨网络（对方外网、本机内网或反之）
           同样成立：授权请求打 App 自己已登录的服务地址，code 在服务端内存里命中同一条记录。 */
        val btnScanAuth = Button(this).apply { text = "扫码授权登录其他设备"; textSize = 13f }
        btnScanAuth.setOnClickListener {
            if (Prefs.authToken(this).isNullOrBlank()) {
                Toast.makeText(this, "请先在 App 登录，再来扫码授权", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "请扫描另一台设备登录页上的二维码", Toast.LENGTH_SHORT).show()
                /* 必须传 this：result 只回调给 startActivityForResult 的发起方。
                   用 MainActivity 起的话，它被本页压成 stopped 态，扫码结果会挂起
                   到退出设置页才投递（实测表现为 PC 端一直不跳转）。 */
                Shell.startScan(this)
            }
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(255, 255, 255))
            setPadding(pad, (24 * d).toInt(), pad, pad)
            addView(label("内网地址（一行一个，可填多个）"))
            addView(etLocal)
            addView(label("使用地址（连家里 WiFi 时用哪个）"))
            addView(spLocal)
            addView(label("外网地址（单个，手动输入）"))
            addView(etRemote)
            addView(label("家里 WiFi 名称（用于自动判断在家/外出）"))
            addView(etHomeSsid)
            addView(btnReadWifi)
            addView(label("网络模式"))
            addView(spMode)
            addView(btnSave, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (16 * d).toInt() })
            addView(btnScanAuth)
            addView(tvConnected)
            addView(tvStatus)
            addView(note)
            addView(swKeep)
            addView(diag)
            addView(btnClearCache)
        }
        setContentView(android.widget.ScrollView(this).apply {
            setBackgroundColor(Color.rgb(255, 255, 255))
            addView(box)
        })

        btnSave.setOnClickListener { save() }
    }

    /** 当前实际连接的地址（Shell 真实加载中的 URL；null = 错误页态/尚未连上） */
    private fun connText(): String {
        val base = Shell.currentBase
        return if (base.isNullOrBlank()) "当前连接：❌ 未连上（错误页或尚未加载）" else "当前连接：✅ $base"
    }

    override fun onResume() {
        super.onResume()
        /* 从主页面切回来时刷新连接状态 */
        if (::tvConnected.isInitialized) tvConnected.text = connText()
    }

    /**
     * 扫码结果转发给 Shell（不做转发 = 扫了码石沉大海，2026-11-19 踩坑修复）。
     * 扫到码后本页自动退出：授权 HTTP 在 Shell 后台线程进行、Toast 用应用级 context，
     * 退出不影响授权与提示，用户直接回主页看到「已授权」。
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        val consumed = Shell.handleScanResult(requestCode, resultCode, data)
        if (consumed) {
            val contents = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)?.contents
            if (!contents.isNullOrBlank()) finish()   // 取消扫码（contents 空）则留在本页
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    /** 一键读取当前 WiFi 名称，追加进家里名单输入框（读不到会提示，不改动原内容） */
    private fun readCurrentWifi() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                1002,
            )
            Toast.makeText(this, "已请求定位权限，请再点一次「读取当前 WiFi」", Toast.LENGTH_SHORT).show()
            return
        }
        val ssid = NetEnv.currentSsid(this)
        if (ssid == null) {
            Toast.makeText(this, "读不到 WiFi 名称（需打开定位开关），请手动填写", Toast.LENGTH_SHORT).show()
            return
        }
        val cur = etHomeSsid.text.toString().trim()
        val merged = if (cur.isEmpty()) ssid else if (cur.split(",").any { it.trim() == ssid }) cur else "$cur,$ssid"
        etHomeSsid.setText(merged)
        Toast.makeText(this, "已填入：$ssid", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1002 && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            readCurrentWifi()
        }
    }

    private fun save() {
        val locals = etLocal.text.toString().lines().map { it.trim() }
            .filter { it.isNotEmpty() }
        val remote = etRemote.text.toString().trim()
        val invalid = locals.firstOrNull { !it.startsWith("http://") && !it.startsWith("https://") }
        if (invalid != null) {
            tvStatus.text = "❌ 内网地址要以 http:// 或 https:// 开头：$invalid"
            return
        }
        if (remote.isNotEmpty() && !remote.startsWith("http://") && !remote.startsWith("https://")) {
            tvStatus.text = "❌ 外网地址要以 http:// 或 https:// 开头"
            return
        }

        Prefs.setLocalList(this, locals)
        /* 下拉选中的地址持久化（存 URL 原文）：列表被改过、选中项已不在列表里时回落第一个 */
        val sel = spLocal.selectedItem as? String
        Prefs.setLocalSelected(this, sel ?: locals.firstOrNull() ?: "")
        Prefs.setRemote(this, remote)
        Prefs.setHomeSsids(this, etHomeSsid.text.toString())
        Prefs.setNetworkMode(this, modeValues[spMode.selectedItemPosition.coerceIn(0, modeValues.lastIndex)])
        Toast.makeText(this, "已保存，返回页面即生效", Toast.LENGTH_SHORT).show()
        finish()
    }
}
