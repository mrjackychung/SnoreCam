package com.example.snorecam

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/** 早上起来看:列出每段录像,看视频判断姿势后标记侧睡/仰睡,顶部自动汇总两种姿势的鼾声分贝。 */
class ReviewActivity : AppCompatActivity() {
    private lateinit var summary: TextView
    private lateinit var list: ListView
    private var sessions = mutableListOf<Session>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 16) }
        summary = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE); setPadding(0, 0, 0, 24) }
        list = ListView(this)
        root.addView(summary, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(list, ViewGroup.LayoutParams.MATCH_PARENT, 0)
        (list.layoutParams as LinearLayout.LayoutParams).weight = 1f
        setContentView(root)
        list.setOnItemClickListener { _, _, i, _ -> showActions(sessions[i]) }
        refresh()
    }

    private fun name(l: String) = when (l) { "side" -> "侧睡"; "supine" -> "仰睡"; else -> "未标记" }

    private fun refresh() {
        sessions = EventStore.load(this).sortedByDescending { it.id }.toMutableList()
        fun line(label: String): String {
            val s = sessions.filter { it.label == label }
            if (s.isEmpty()) return "${name(label)}:暂无数据"
            val avg = s.map { it.avgPeakDb }.average()
            val max = s.maxOf { it.peakDb }
            return "${name(label)}:${s.size} 段 · 平均鼾声峰值 %.1f dB · 最大 %.1f dB".format(avg, max)
        }
        summary.text = line("side") + "\n" + line("supine") + "\n\n点击某段 → 播放 / 标记姿势"
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, sessions.map {
            "${fmt.format(Date(it.id))}  [${name(it.label)}]\n鼾声 ${it.count} 次 · 平均峰值 %.1f dB · 最大 %.1f dB"
                .format(it.avgPeakDb, it.peakDb)
        })
    }

    private fun showActions(s: Session) {
        AlertDialog.Builder(this)
            .setItems(arrayOf("标记为侧睡", "标记为仰睡", "播放视频", "删除")) { _, which ->
                when (which) {
                    0 -> update(s, "side")
                    1 -> update(s, "supine")
                    2 -> play(s)
                    3 -> { File(s.file).delete()
                        EventStore.save(this, EventStore.load(this).filter { it.id != s.id }); refresh() }
                }
            }.show()
    }

    private fun update(s: Session, label: String) {
        val all = EventStore.load(this)
        all.find { it.id == s.id }?.label = label
        EventStore.save(this, all); refresh()
    }

    private fun play(s: Session) {
        val file = File(s.file)
        if (!file.exists()) { Toast.makeText(this, "视频文件不存在", Toast.LENGTH_SHORT).show(); return }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "没有可播放的应用", Toast.LENGTH_SHORT).show()
        }
    }
}
