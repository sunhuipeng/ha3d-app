/*
 * SettingsActivity.kt — 服务器设置页（应用内设置）。
 * 入口：① 长按桌面图标「服务器设置」快捷方式；② 错误页「服务器设置」按钮。
 * 改完保存即持久化，返回壳页面后自动按新地址重新加载。
 * 2026-11-15 起移除外网入口：只有内网地址一栏，直连，不做外网回退。
 */
package com.sunhuip.ha3dapp

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var etLocal: EditText
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val d = resources.displayMetrics.density
        val pad = (18 * d).toInt()

        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 13f; setTextColor(Color.rgb(100, 115, 135))
            setPadding(0, (10 * d).toInt(), 0, (6 * d).toInt())
        }

        etLocal = EditText(this).apply {
            setText(Prefs.local(this@SettingsActivity))
            hint = "http://192.168.31.16:8099/index.html"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true); textSize = 14f
        }

        /* 内网入口：下拉选预设（带「正式 / 测试 / 调试」标签，选中即填入输入框），输入框随时可手改 */
        val presets = Config.PRESETS
        val curLocal = Prefs.local(this)
        val initialPos = presets.indexOfFirst { it.url == curLocal }.takeIf { it >= 0 } ?: presets.size
        /* Spinner 首次 layout 会白送一次 onItemSelected(0)，不能让它把用户手填的自定义地址冲掉 */
        var spinnerInit = false
        val spLocal = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                presets.map { it.label } + "自定义…",
            )
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    if (!spinnerInit) { spinnerInit = true; return }
                    if (pos < presets.size) etLocal.setText(presets[pos].url)
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        spLocal.setSelection(initialPos)
        tvStatus = TextView(this).apply {
            textSize = 13f; setPadding(0, (14 * d).toInt(), 0, 0)
            setTextColor(Color.rgb(47, 111, 237))
            text = " 当前：$curLocal"
        }

        val btnTest = Button(this).apply { text = "测试连通"; textSize = 14f }
        val btnSave = Button(this).apply { text = "保存"; textSize = 14f }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (16 * d).toInt(), 0, 0)
            addView(btnTest, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (8 * d).toInt() })
            addView(btnSave, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val note = TextView(this).apply {
            textSize = 12f; setTextColor(Color.rgb(150, 160, 175))
            text = "说明：默认直连内网正式地址 192.168.31.16:8099；连接失败会显示错误页，可重试。\n" +
                "清空输入框后保存 = 恢复默认地址。已移除外网回退（JMGLink 在手机端访问不通）。"
            setPadding(0, (14 * d).toInt(), 0, 0)
        }

        /* 运行诊断：老版本 WebView / 硬件加速没生效是「掉帧、白屏、闪退」的常见根因，
           放在设置页最下面，排障时截一张图就够了，不用接 adb。 */
        val diag = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(120, 132, 148))
            setTextIsSelectable(true)
            setPadding(0, (18 * d).toInt(), 0, (6 * d).toInt())
            text = "运行诊断\n" + Shell.diagText()
        }

        val btnClearCache = Button(this).apply { text = "清除离线缓存"; textSize = 13f }
        btnClearCache.setOnClickListener {
            WebCache.clear(this)
            diag.text = "运行诊断\n" + Shell.diagText()
            Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show()
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(255, 255, 255))
            setPadding(pad, (24 * d).toInt(), pad, pad)
            addView(label("内网入口（下拉选预设，或手动输入）"))
            addView(spLocal)
            addView(etLocal)
            addView(row)
            addView(tvStatus)
            addView(note)
            addView(diag)
            addView(btnClearCache)
        }
        /* 内容变多（预设 + 诊断 + 清缓存），小屏要能滚，否则底部按钮点不到 */
        setContentView(android.widget.ScrollView(this).apply {
            setBackgroundColor(Color.rgb(255, 255, 255))
            addView(box)
        })

        btnTest.setOnClickListener { test() }
        btnSave.setOnClickListener { save() }
    }

    private fun test() {
        tvStatus.text = "测试中…"
        val l = etLocal.text.toString().trim()
        Thread {
            val a = if (l.isNotEmpty()) MainActivity.reachable(l) else false
            runOnUiThread {
                tvStatus.text = if (l.isEmpty()) "（未填地址）"
                    else if (a) "✅ 连通：$l"
                    else "❌ 不通：$l"
            }
        }.start()
    }

    private fun save() {
        val l = etLocal.text.toString().trim()
        if (l.isNotEmpty() && !l.startsWith("http")) {
            tvStatus.text = "❌ 入口要以 http:// 或 https:// 开头"
            return
        }
        Prefs.set(this, l)
        Toast.makeText(this, "已保存，返回页面即生效", Toast.LENGTH_SHORT).show()
        finish()
    }
}
