package com.example.snorecam

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/** 监测日志查看页。 */
class LogActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.BG) }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(28), dp(16), dp(6))
        }
        top.addView(TextView(this).apply {
            text = "‹ 返回"; textSize = 16f; setTextColor(Theme.ACCENT)
            setPadding(dp(8), dp(8), dp(14), dp(8)); setOnClickListener { finish() }
        })
        top.addView(TextView(this).apply { text = "监测日志"; textSize = 18f; setTextColor(Color.WHITE) },
            LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(top)

        val tv = TextView(this).apply {
            textSize = 11f; setTextColor(Color.parseColor("#C9D4E0")); typeface = Typeface.MONOSPACE
            setPadding(dp(14), dp(6), dp(14), dp(20)); setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        fun load() {
            val t = MonLog.read(this@LogActivity)
            tv.text = if (t.isEmpty()) "还没有日志。开始监测后,这里会记录每个鼾声、触发、保存和失败原因,以及每分钟的环境声统计。"
            else t.takeLast(40000)
            scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
        top.addView(Button(this).apply {
            text = "清空"; isAllCaps = false
            setOnClickListener { MonLog.clear(this@LogActivity); load() }
        })
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)
        load()
    }
}
