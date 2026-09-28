package com.example.numberhint

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private val requestProjection = 1001
    private lateinit var fields: List<EditText>
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = getSharedPreferences("board", MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 35, 28, 20) }
        root.addView(TextView(this).apply { text = "数字提示器 · 7×7"; textSize = 24f })
        root.addView(TextView(this).apply { text = "设置棋盘外框占整屏的百分比。截图示例约为：左15、上34、右85、下77。需选择“整个屏幕”分享。仅本机识别，不上传图像。"; textSize = 16f })
        val names = listOf("左 %", "上 %", "右 %", "下 %")
        val defaults = listOf(15, 34, 85, 77)
        fields = names.mapIndexed { index, name ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply { text = name; width = 130; textSize = 17f })
            val edit = EditText(this).apply { inputType = 2; setText(p.getInt("b$index", defaults[index]).toString()) }
            row.addView(edit, LinearLayout.LayoutParams(0, -2, 1f)); root.addView(row); edit
        }
        root.addView(Button(this).apply { text = "开始识别并显示悬浮提示"; setOnClickListener { start() } })
        root.addView(TextView(this).apply { text = "悬浮条：− / + 调整目标数字；圆圈指向找到的格子；× 停止。游戏重排后会重新识别。识别不稳时可调整棋盘边界。"; textSize = 15f })
        setContentView(ScrollView(this).apply { addView(root) })
    }
    private fun start() {
        val b = fields.map { it.text.toString().toIntOrNull() ?: -1 }
        if (b.any { it !in 0..100 } || b[0] >= b[2] || b[1] >= b[3]) { Toast.makeText(this, "请输入正确的棋盘边界", Toast.LENGTH_LONG).show(); return }
        getSharedPreferences("board", MODE_PRIVATE).edit().apply { b.forEachIndexed { i, n -> putInt("b$i", n) } }.apply()
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "请允许悬浮窗，返回后再按开始", Toast.LENGTH_LONG).show(); return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), requestProjection)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestProjection && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java).putExtra("resultCode", resultCode).putExtra("projectionData", data)
            startForegroundService(i); Toast.makeText(this, "切回游戏，按悬浮 + 进入下一数", Toast.LENGTH_LONG).show()
        }
    }
}
