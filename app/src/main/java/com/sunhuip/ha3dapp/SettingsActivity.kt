/*
 * SettingsActivity.kt — 服务器设置页（应用内设置）。
 * 入口：① 长按桌面图标「服务器设置」快捷方式；② 错误页「服务器设置」按钮。
 * 改完保存即持久化，返回壳页面后自动按新地址重新加载。
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
    private lateinit var etRemote: EditText
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
        etRemote = EditText(this).apply {
            setText(Prefs.remote(this@SettingsActivity))
            hint = "留空 = 不启用外网回退"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true); textSize = 14f
        }

        /* 内网入口：下拉选预设（选中即填入输入框），输入框随时可手改 */
        val presets = Config.PRESETS
        val spLocal = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                presets.map { it.removePrefix("http://").removeSuffix("/index.html") } + "自定义…",
            )
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    if (pos < presets.size) etLocal.setText(presets[pos])
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        val curLocal = Prefs.local(this)
        spLocal.setSelection(presets.indexOf(curLocal).takeIf { it >= 0 } ?: presets.size)
        tvStatus = TextView(this).apply {
            textSize = 13f; setPadding(0, (14 * d).toInt(), 0, 0)
            setTextColor(Color.rgb(47, 111, 237)); text = " "
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
            text = "说明：内网不通时自动切外网入口，两端都保存于本机，改完返回页面即生效。\n恢复默认：清空内网输入框后保存。"
            setPadding(0, (14 * d).toInt(), 0, 0)
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(255, 255, 255))
            setPadding(pad, (24 * d).toInt(), pad, pad)
            addView(label("内网入口（下拉选预设，或手动输入）"))
            addView(spLocal)
            addView(etLocal)
            addView(label("外网入口（内网探测不通时回退）"))
            addView(etRemote)
            addView(row)
            addView(tvStatus)
            addView(note)
        }
        setContentView(box)

        btnTest.setOnClickListener { test() }
        btnSave.setOnClickListener { save() }
    }

    private fun test() {
        tvStatus.text = "测试中…"
        val l = etLocal.text.toString().trim()
        val r = etRemote.text.toString().trim()
        Thread {
            val a = if (l.isNotEmpty()) MainActivity.reachable(l) else false
            val b = if (r.isNotEmpty()) MainActivity.reachable(r) else false
            runOnUiThread {
                tvStatus.text = "内网：${if (a) "✅ 可达" else if (l.isEmpty()) "（未填）" else "❌ 不通"}    " +
                    "外网：${if (b) "✅ 可达" else if (r.isEmpty()) "（未启用）" else "❌ 不通"}"
            }
        }.start()
    }

    private fun save() {
        val l = etLocal.text.toString().trim()
        if (l.isNotEmpty() && !l.startsWith("http")) {
            tvStatus.text = "❌ 内网入口要以 http:// 或 https:// 开头"
            return
        }
        Prefs.set(this, l, etRemote.text.toString().trim())
        Toast.makeText(this, "已保存，返回页面即生效", Toast.LENGTH_SHORT).show()
        finish()
    }
}
