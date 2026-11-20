package com.example.snorecam

import android.content.Context
import java.util.Locale

/**
 * 界面文字(简体中文 / 繁體中文 / English)。
 * 语言跟随手机:简体中文 → 简体,繁體中文(台/港/澳或 Hant)→ 繁體,其他一律英文。
 * 也可以在首页手动选择语言(自动 / 简体 / 繁體 / English)。
 * 诊断日志(监测日志)统一用英文,方便排查。
 */
object Str {
    @Volatile private var lang = "en"

    fun init(ctx: Context) {
        val pref = Prefs.lang(ctx)
        lang = if (pref == "auto") detect() else pref
    }

    /** 手机系统语言 → "zh-Hans" / "zh-Hant" / "en" */
    fun detect(): String {
        val l = Locale.getDefault()
        if (l.language == "zh") {
            val script = l.script
            return when {
                script.equals("Hant", true) -> "zh-Hant"
                script.equals("Hans", true) -> "zh-Hans"
                l.country == "TW" || l.country == "HK" || l.country == "MO" -> "zh-Hant"
                else -> "zh-Hans"
            }
        }
        return "en"
    }

    fun current(): String = lang

    fun locale(): Locale = when (lang) {
        "zh-Hans" -> Locale.SIMPLIFIED_CHINESE
        "zh-Hant" -> Locale.TRADITIONAL_CHINESE
        else -> Locale.ENGLISH
    }

    private fun p(hans: String, hant: String, en: String): String = when (lang) {
        "zh-Hans" -> hans
        "zh-Hant" -> hant
        else -> en
    }

    // ===== 通用 =====
    fun back() = p("‹ 返回", "‹ 返回", "‹ Back")
    fun close() = p("关闭", "關閉", "Close")
    fun cancel() = p("取消", "取消", "Cancel")
    fun delete() = p("删除", "刪除", "Delete")
    fun clear() = p("清空", "清空", "Clear")
    fun noData() = p("暂无数据", "暫無資料", "No data")
    fun all() = p("全部", "全部", "All")

    fun labelName(l: String) = when (l) {
        "side" -> p("侧睡", "側睡", "Side")
        "supine" -> p("仰睡", "仰睡", "Back")
        else -> p("未标记", "未標記", "Unlabeled")
    }
    fun autoParen() = p("(自动)", "(自動)", " (auto)")
    fun autoShort() = p("·自动", "·自動", " · auto")

    // ===== 语言选择 =====
    fun langTitle() = p("语言 / Language", "語言 / Language", "Language")
    fun langAuto() = p("自动", "自動", "Auto")

    // ===== 日志页 / 导出 =====
    fun logTitle() = p("监测日志", "監測日誌", "Monitor log")
    fun logEmpty() = p(
        "还没有日志。开始监测后,这里会记录每个鼾声、触发、保存和失败原因,以及每分钟的环境声统计。",
        "還沒有日誌。開始監測後,這裡會記錄每個鼾聲、觸發、儲存和失敗原因,以及每分鐘的環境聲統計。",
        "No log yet. Once monitoring starts, this records every snore, trigger, save and failure reason, plus per-minute ambient sound stats."
    )
    fun shareVideo() = p("分享录像", "分享錄影", "Share video")

    // ===== 首页 =====
    fun countdown(n: Int) = p("$n 秒后屏幕变黑,请放好手机", "$n 秒後螢幕變黑,請放好手機", "Screen goes dark in $n s — place your phone")
    fun needPerms() = p("需要相机和麦克风权限才能监测", "需要相機和麥克風權限才能監測", "Camera and microphone permissions are required")
    fun homeSubtitle() = p(
        "睡前点一下,打呼时自动录像,早上对比侧睡和仰睡哪个更响",
        "睡前點一下,打呼時自動錄影,早上對比側睡和仰睡哪個更響",
        "Tap before bed. Snoring is recorded automatically; compare side vs. back sleeping in the morning."
    )
    fun liveTitle() = p("当前环境声音", "目前環境聲音", "Ambient sound now")
    fun grantMic() = p("授权麦克风,显示实时分贝", "授權麥克風,顯示即時分貝", "Allow microphone to show live dB")
    fun camera() = p("摄像头", "鏡頭", "Camera")
    fun backCam() = p("后置摄像头", "後置鏡頭", "Rear camera")
    fun frontCamLight() = p("前置 · 屏幕补光", "前置 · 螢幕補光", "Front · screen light")
    fun sensHint() = p(
        "数值越小越灵敏。不知道设多少?做个测试,我来推荐。",
        "數值越小越靈敏。不知道設多少?做個測試,我來推薦。",
        "Smaller = more sensitive. Not sure what to pick? Run the test and I'll recommend a value."
    )
    fun testBtn() = p("🎤📷  测试声音灵敏度 + 人脸画面", "🎤📷  測試聲音靈敏度 + 人臉畫面", "🎤📷  Test sensitivity + face view")
    fun noDelay() = p("不延迟", "不延遲", "No delay")
    fun minutes(n: Int) = p("${n}分钟", "${n}分鐘", "$n min")
    fun delayHint() = p(
        "延迟期间不听声音、不录像,几乎不耗电。比音乐时长多留 5~10 分钟更稳。",
        "延遲期間不聽聲音、不錄影,幾乎不耗電。比音樂時長多留 5~10 分鐘更穩。",
        "While delayed, nothing is listened to or recorded and battery use is minimal. Add 5–10 min beyond your music length."
    )
    fun fillHint() = p(
        "屏幕会先渐亮到设定亮度,亮了之后才开始录像,所以每段视频一开始就是亮的。",
        "螢幕會先漸亮到設定亮度,亮了之後才開始錄影,所以每段影片一開始就是亮的。",
        "The screen fades up to the set brightness first, then recording starts, so every clip begins lit."
    )
    fun startMonitoring() = p("开始监测", "開始監測", "Start monitoring")
    fun viewRecords() = p("📊 查看记录", "📊 查看記錄", "📊 Records")
    fun viewLog() = p("📝 监测日志", "📝 監測日誌", "📝 Monitor log")
    fun checklistTitle() = p("睡前小清单", "睡前小清單", "Bedtime checklist")
    fun tip1() = p("🔌  接上充电器", "🔌  接上充電器", "🔌  Plug in the charger")
    fun tip2() = p("📱  手机立在床头,摄像头对准上半身", "📱  手機立在床頭,鏡頭對準上半身", "📱  Stand the phone by the bed, camera aimed at your upper body")
    fun tip3() = p("🔕  开启勿扰,睡着后不要按电源键", "🔕  開啟勿擾,睡著後不要按電源鍵", "🔕  Turn on Do Not Disturb; don't press the power button")
    fun tip4() = p("💡  用前置补光时,屏幕要朝向你", "💡  使用前置補光時,螢幕要朝向你", "💡  With front-camera light, face the screen toward you")
    fun version(vc: Long) = p("SnoreCam 版本 v$vc", "SnoreCam 版本 v$vc", "SnoreCam version v$vc")
    fun monitoring() = p("监测中", "監測中", "Monitoring")
    fun armNow() = p("立即开始检测", "立即開始檢測", "Start detecting now")
    fun stopMonitoring() = p("停止监测", "停止監測", "Stop monitoring")
    fun panelHint() = p("再点一下屏幕空白处收起", "再點一下螢幕空白處收起", "Tap an empty area to dismiss")
    fun sensLabel(m: Int) = p(
        "灵敏度:高于环境噪声 $m dB 才算鼾声",
        "靈敏度:高於環境噪音 $m dB 才算鼾聲",
        "Sensitivity: sounds $m dB above ambient noise count as snoring"
    )
    fun delayOn(d: Int) = p(
        "🎵 点开始后,等 $d 分钟才开始检测(适合睡前听音乐)",
        "🎵 點開始後,等 $d 分鐘才開始檢測(適合睡前聽音樂)",
        "🎵 After Start, wait $d min before detecting (for bedtime music)"
    )
    fun delayOff() = p("🎵 延迟开始检测:不延迟(点开始立刻检测)", "🎵 延遲開始檢測:不延遲(點開始立刻檢測)", "🎵 Delayed start: off (detects immediately)")
    fun startWithDelay(d: Int) = p("开始监测($d 分钟后开始检测)", "開始監測($d 分鐘後開始檢測)", "Start monitoring (detect after $d min)")
    fun fillMaxLabel(pct: Int) = p("补光最大亮度:$pct%", "補光最大亮度:$pct%", "Max light brightness: $pct%")
    fun rampLabel(s: Int) = p("渐亮时长:$s 秒(越长越不刺眼)", "漸亮時長:$s 秒(越長越不刺眼)", "Fade-in time: $s s (longer is gentler)")
    fun needMicShort() = p("需要麦克风权限", "需要麥克風權限", "Microphone permission needed")
    fun stateSnore() = p("🔴 像鼾声", "🔴 像鼾聲", "🔴 Sounds like snoring")
    fun stateLoud() = p("🟠 够响,但不像鼾声", "🟠 夠響,但不像鼾聲", "🟠 Loud, but not snore-like")
    fun stateSound() = p("有声音", "有聲音", "Some sound")
    fun stateQuiet() = p("安静", "安靜", "Quiet")
    fun threshInfo(noise: Int, margin: Int, thr: Int) = p(
        "环境噪声 $noise dB + 灵敏度 $margin → 超过 $thr dB 且像鼾声才触发",
        "環境噪音 $noise dB + 靈敏度 $margin → 超過 $thr dB 且像鼾聲才觸發",
        "Ambient $noise dB + sensitivity $margin → triggers above $thr dB and only if it sounds like snoring"
    )
    fun placePhone() = p("手机放好,摄像头对准自己", "手機放好,鏡頭對準自己", "Place the phone with the camera facing you")
    fun stoppedToast() = p("已停止。到「查看记录」看今晚的结果", "已停止。到「查看記錄」看今晚的結果", "Stopped. Open “Records” to see tonight's results")
    fun waitingTitle(t: String) = p("等待中 · $t 后开始检测", "等待中 · $t 後開始檢測", "Waiting · detection starts in $t")
    fun waitingInfo() = p("此期间不听声音、不录像", "此期間不聽聲音、不錄影", "Not listening or recording during this time")
    fun panelInfo(snores: Int, clips: Int) = p("鼾声 $snores 次 · 已录 $clips 段", "鼾聲 $snores 次 · 已錄 $clips 段", "$snores snores · $clips clips recorded")
    fun recStatus(n: Int, peak: Double) = p(
        "● 录像中  鼾声 $n 次 · 本次峰值 %.1f dB".format(peak),
        "● 錄影中  鼾聲 $n 次 · 本次峰值 %.1f dB".format(peak),
        "● Recording  $n snores · peak %.1f dB".format(peak)
    )
    fun snoreDetected(db: Double) = p("检测到鼾声 %.1f dB".format(db), "偵測到鼾聲 %.1f dB".format(db), "Snore detected %.1f dB".format(db))
    fun recording1min() = p("● 录像中(1 分钟)", "● 錄影中(1 分鐘)", "● Recording (1 min)")
    fun savedStatus(n: Int, avg: Double) = p(
        "已保存:鼾声 $n 次 · 平均峰值 %.1f dB".format(avg),
        "已儲存:鼾聲 $n 次 · 平均峰值 %.1f dB".format(avg),
        "Saved: $n snores · avg peak %.1f dB".format(avg)
    )

    // ===== 测试页 =====
    fun fillInfo(pct: Int, bright: Int, max: Int) = p(
        "补光 $pct% · 屏幕亮度 $bright%(上限 $max%)",
        "補光 $pct% · 螢幕亮度 $bright%(上限 $max%)",
        "Light $pct% · screen brightness $bright% (max $max%)"
    )
    fun keepQuiet(left: Long) = p("保持安静…还剩 $left 秒", "保持安靜…還剩 $left 秒", "Stay quiet… $left s left")
    fun mimicProgress(left: Long, n: Int) = p(
        "请模仿打呼…还剩 $left 秒\n已识别到 $n 次类似鼾声",
        "請模仿打呼…還剩 $left 秒\n已識別到 $n 次類似鼾聲",
        "Mimic snoring… $left s left\n$n snore-like sounds detected"
    )
    fun testTitle() = p("测试", "測試", "Test")
    fun testSub() = p(
        "看看摄像头能不能拍到你的脸,再测声音灵敏度。",
        "看看鏡頭能不能拍到你的臉,再測聲音靈敏度。",
        "Check that the camera can see your face, then test sound sensitivity."
    )
    fun liveSound() = p("实时声音", "即時聲音", "Live sound")
    fun camCardTitle() = p("📷 实时画面 · 人脸检测", "📷 即時畫面 · 人臉偵測", "📷 Live view · face detection")
    fun segBack() = p("后置", "後置", "Rear")
    fun segFront() = p("前置", "前置", "Front")
    fun waitingCam() = p("等待相机…", "等待相機…", "Waiting for camera…")
    fun simLight() = p("☀ 模拟屏幕补光(从黑渐亮)", "☀ 模擬螢幕補光(從黑漸亮)", "☀ Simulate screen light (fade up from black)")
    fun simLightHint() = p(
        "前置摄像头时,屏幕就是补光灯。点上面按钮会按你设置的亮度上限和渐亮时长模拟一遍,看看脸什么时候能被检测到。",
        "前置鏡頭時,螢幕就是補光燈。點上面按鈕會按你設定的亮度上限和漸亮時長模擬一遍,看看臉什麼時候能被偵測到。",
        "With the front camera the screen is the light. The button above replays it using your brightness limit and fade-in time so you can see when your face becomes detectable."
    )
    fun needCamPerm() = p("需要相机权限", "需要相機權限", "Camera permission needed")
    fun detectingFace() = p("正在检测人脸…", "正在偵測人臉…", "Detecting face…")
    fun camStartFailed(msg: String?) = p("相机启动失败:$msg", "相機啟動失敗:$msg", "Camera failed to start: $msg")
    fun lumaVeryDark() = p("画面很暗", "畫面很暗", "very dark")
    fun lumaDark() = p("画面偏暗", "畫面偏暗", "rather dark")
    fun lumaOk() = p("亮度足够", "亮度足夠", "bright enough")
    fun noFace() = p("✗ 没检测到人脸", "✗ 沒偵測到人臉", "✗ No face detected")
    fun brightnessLine(luma: Int, txt: String) = p("画面亮度 $luma/255($txt)", "畫面亮度 $luma/255($txt)", "Brightness $luma/255 ($txt)")
    fun tooDarkAdvice() = p(
        "太暗了:点下面「模拟屏幕补光」,或把补光亮度上限调高;也可以把手机挪近一点。",
        "太暗了:點下面「模擬螢幕補光」,或把補光亮度上限調高;也可以把手機挪近一點。",
        "Too dark: tap “Simulate screen light” below, raise the light brightness limit, or move the phone closer."
    )
    fun noFaceAdvice() = p(
        "亮度没问题但没找到脸:调整手机角度,让脸和上半身完整出现在画面里。",
        "亮度沒問題但沒找到臉:調整手機角度,讓臉和上半身完整出現在畫面裡。",
        "Brightness is fine but no face found: adjust the angle so your face and upper body are in frame."
    )
    fun faceFound(frontal: Boolean, yaw: Int) = p(
        "✓ 检测到人脸 · ${if (frontal) "正脸" else "侧脸"}(转角 $yaw°)",
        "✓ 偵測到人臉 · ${if (frontal) "正臉" else "側臉"}(轉角 $yaw°)",
        "✓ Face detected · ${if (frontal) "frontal" else "profile"} (angle $yaw°)"
    )
    fun posePrediction(footend: Boolean, label: String): String {
        val pos = if (footend) p("床尾", "床尾", "foot of bed") else p("床边", "床邊", "bedside")
        return p(
            "按当前「镜头位置:$pos」设置,这个姿势会被自动判为:$label",
            "按目前「鏡頭位置:$pos」設定,這個姿勢會被自動判為:$label",
            "With the current camera position “$pos”, this posture is classified as: $label"
        )
    }
    fun stopSim() = p("■ 结束补光测试", "■ 結束補光測試", "■ End light test")
    fun verifyHit(db: Double, n: Int) = p(
        "✓ 检测到鼾声 %.0f dB(第 %d 次)".format(db, n),
        "✓ 偵測到鼾聲 %.0f dB(第 %d 次)".format(db, n),
        "✓ Snore detected %.0f dB (#%d)".format(db, n)
    )
    fun step1Title() = p("灵敏度测试 · 第 1 步:测量环境噪声", "靈敏度測試 · 第 1 步:測量環境噪音", "Sensitivity test · Step 1: measure ambient noise")
    fun step1Body() = p(
        "把手机放到你睡觉时放的位置(床头/枕边),保持安静 6 秒。\n空调、风扇保持你平时睡觉时的状态。",
        "把手機放到你睡覺時放的位置(床頭/枕邊),保持安靜 6 秒。\n空調、風扇保持你平時睡覺時的狀態。",
        "Put the phone where it sits when you sleep (bedside/pillow) and stay quiet for 6 s.\nKeep the AC/fan as it is when you sleep."
    )
    fun startMeasure() = p("开始测量", "開始測量", "Start measuring")
    fun step2Title() = p("灵敏度测试 · 第 2 步:模仿打呼", "靈敏度測試 · 第 2 步:模仿打呼", "Sensitivity test · Step 2: mimic snoring")
    fun step2Body(noise: Int) = p(
        "环境噪声约 $noise dB ✓\n\n现在躺到平时睡觉的姿势,用喉咙发出低沉的「呼噜」声,一吸一呼,重复约 12 秒。\n声音大小尽量接近你真实打呼的程度。",
        "環境噪音約 $noise dB ✓\n\n現在躺到平時睡覺的姿勢,用喉嚨發出低沉的「呼嚕」聲,一吸一呼,重複約 12 秒。\n聲音大小盡量接近你真實打呼的程度。",
        "Ambient noise ≈ $noise dB ✓\n\nNow lie in your usual sleeping position and make a low “snoring” sound with your throat, in and out, for about 12 s.\nTry to match how loud you really snore."
    )
    fun startMimic() = p("开始模仿", "開始模仿", "Start mimicking")
    fun retest() = p("重新测试", "重新測試", "Test again")
    fun resultTitle() = p("灵敏度测试结果", "靈敏度測試結果", "Sensitivity test result")
    fun noSnoreHeard() = p(
        "没有识别到像鼾声的声音 😕\n\n试试:\n① 手机放近一点\n② 声音再低沉、再响一点(像「呼——噜」)\n③ 用喉咙而不是哼歌",
        "沒有辨識到像鼾聲的聲音 😕\n\n試試:\n① 手機放近一點\n② 聲音再低沉、再響一點(像「呼——嚕」)\n③ 用喉嚨而不是哼歌",
        "No snore-like sound heard 😕\n\nTry:\n① Move the phone closer\n② A lower, louder sound (like “hhhh-rrrr”)\n③ Use your throat rather than humming"
    )
    fun testResult(noise: Int, s: Int, gap: Int, n: Int, rec: Int, noisy: Boolean, close: Boolean): String {
        val sb = StringBuilder()
        sb.append(p(
            "环境噪声:约 $noise dB\n你的打呼声:约 $s dB(比环境高 $gap dB)\n识别到 $n 次类似鼾声\n\n⭐ 推荐灵敏度:$rec\n意思是:声音比环境噪声高出 $rec dB 以上、并且像鼾声,才会触发录像。\n\n真正睡着后的鼾声通常比模仿时轻,所以我按你模仿声的一半左右来设,宁可灵敏一点。",
            "環境噪音:約 $noise dB\n你的打呼聲:約 $s dB(比環境高 $gap dB)\n辨識到 $n 次類似鼾聲\n\n⭐ 推薦靈敏度:$rec\n意思是:聲音比環境噪音高出 $rec dB 以上、並且像鼾聲,才會觸發錄影。\n\n真正睡著後的鼾聲通常比模仿時輕,所以我按你模仿聲的一半左右來設,寧可靈敏一點。",
            "Ambient noise: ≈ $noise dB\nYour snoring: ≈ $s dB ($gap dB above ambient)\n$n snore-like sounds detected\n\n⭐ Recommended sensitivity: $rec\nMeaning: a sound at least $rec dB above ambient noise that also sounds like snoring triggers recording.\n\nReal sleeping snores are usually softer than mimicked ones, so I set it to about half of your mimic — better a bit too sensitive."
        ))
        if (noisy) sb.append(p(
            "\n\n⚠ 环境噪声偏大,可能影响检测。若可以,关掉风扇/空调后再测一次。",
            "\n\n⚠ 環境噪音偏大,可能影響檢測。若可以,關掉風扇/空調後再測一次。",
            "\n\n⚠ Ambient noise is high and may hurt detection. If possible, turn off the fan/AC and test again."
        ))
        if (close) sb.append(p(
            "\n\n⚠ 你的模仿声和环境差别不大,手机再靠近一点会更准。",
            "\n\n⚠ 你的模仿聲和環境差別不大,手機再靠近一點會更準。",
            "\n\n⚠ Your mimic is close to the ambient level; moving the phone closer will be more accurate."
        ))
        return sb.toString()
    }
    fun applyRec(rec: Int) = p("采用推荐值 $rec", "採用推薦值 $rec", "Use recommended value $rec")
    fun savedVerify() = p("已保存 ✓ 再验证一下", "已儲存 ✓ 再驗證一下", "Saved ✓ Verify once more")
    fun verifyBody(rec: Int) = p(
        "灵敏度已设为 $rec。\n现在再模仿几次打呼:橙色触发线是触发门槛,检测到鼾声会在下面显示 ✓。\n如果老是不触发,回首页把灵敏度数值调小一点。",
        "靈敏度已設為 $rec。\n現在再模仿幾次打呼:橙色觸發線是觸發門檻,偵測到鼾聲會在下面顯示 ✓。\n如果老是不觸發,回首頁把靈敏度數值調小一點。",
        "Sensitivity set to $rec.\nMimic snoring a few more times: the orange line is the trigger threshold and detected snores show a ✓ below.\nIf it rarely triggers, lower the sensitivity number on the home page."
    )
    fun waitSnore() = p("等待鼾声…", "等待鼾聲…", "Waiting for snore…")
    fun doneBack() = p("完成,返回首页", "完成,返回首頁", "Done, back to home")

    // ===== 回放页 =====
    fun save() = p("💾 保存", "💾 儲存", "💾 Save")
    fun share() = p("📤 分享", "📤 分享", "📤 Share")
    fun statPos() = p("播放位置", "播放位置", "Position")
    fun statNow() = p("此刻 dB", "此刻 dB", "Now dB")
    fun statPeak() = p("本段峰值 dB", "本段峰值 dB", "Clip peak dB")
    fun legendQuiet() = p("安静 <50", "安靜 <50", "Quiet <50")
    fun legendLight() = p("轻微 50-60", "輕微 50-60", "Light 50-60")
    fun legendLoud() = p("响亮 60-70", "響亮 60-70", "Loud 60-70")
    fun legendEpic() = p("超响 ≥70", "超響 ≥70", "Epic ≥70")
    fun back5() = p("⏪ 5秒", "⏪ 5秒", "⏪ 5 s")
    fun fwd5() = p("5秒 ⏩", "5秒 ⏩", "5 s ⏩")
    fun play() = p("▶ 播放", "▶ 播放", "▶ Play")
    fun pause() = p("⏸ 暂停", "⏸ 暫停", "⏸ Pause")
    fun autoPosture() = p("自动识别姿势", "自動識別姿勢", "Auto-detect posture")
    fun faceFrontal() = p("识别到正脸", "識別到正臉", "Frontal face found")
    fun faceProfile() = p("识别到侧脸", "識別到側臉", "Profile face found")
    fun faceNone() = p("没识别到人脸(画面太暗或没拍到脸)", "沒識別到人臉(畫面太暗或沒拍到臉)", "No face found (too dark or not in frame)")
    fun faceUnknown() = p("未识别", "未識別", "Not analyzed")
    fun postureLine(faceT: String, lbl: String) = p("$faceT · 当前:$lbl", "$faceT · 目前:$lbl", "$faceT · current: $lbl")
    fun noStoragePerm() = p("没有存储权限,无法保存", "沒有儲存權限,無法儲存", "No storage permission, can't save")
    fun savedToGallery() = p("已保存到相册(Movies/SnoreCam)", "已儲存到相簿(Movies/SnoreCam)", "Saved to gallery (Movies/SnoreCam)")
    fun saveFailed() = p("保存失败", "儲存失敗", "Save failed")
    fun analyzing() = p("姿势识别中…", "姿勢識別中…", "Analyzing posture…")

    // ===== 记录页 =====
    fun recordsTitle() = p("鼾声记录", "鼾聲記錄", "Snore records")
    fun camPosBtn() = p("⚙ 镜头位置", "⚙ 鏡頭位置", "⚙ Camera position")
    fun sliderHint() = p(
        "拖动滑块(或直接在图上滑动)选择时间,会自动选中离得最近的一段录像",
        "拖動滑桿(或直接在圖上滑動)選擇時間,會自動選中離得最近的一段錄影",
        "Drag the slider (or swipe on the chart) to pick a time; the nearest recording is selected automatically"
    )
    fun playBtn() = p("▶ 回放", "▶ 回放", "▶ Play back")
    fun noRecords() = p(
        "还没有录像。\n睡前在首页点「开始监测」,打呼时会自动录像。",
        "還沒有錄影。\n睡前在首頁點「開始監測」,打呼時會自動錄影。",
        "No recordings yet.\nTap “Start monitoring” on the home page before bed; snoring is recorded automatically."
    )
    fun cardSub(n: Int, max: Double) = p(
        "平均峰值 dB · $n 段 · 最大 %.0f".format(max),
        "平均峰值 dB · $n 段 · 最大 %.0f".format(max),
        "Avg peak dB · $n clips · max %.0f".format(max)
    )
    fun scopeAll() = p("统计范围:全部录像", "統計範圍:全部錄影", "Range: all recordings")
    fun scopeNight(chip: String) = p("统计范围:$chip 晚", "統計範圍:$chip 晚", "Range: night of $chip")
    fun diffText(d: Double): String {
        val v = "%.1f".format(Math.abs(d))
        return if (d >= 0) p("仰睡比侧睡高 $v dB", "仰睡比側睡高 $v dB", "Back sleeping is $v dB louder than side")
        else p("仰睡比侧睡低 $v dB", "仰睡比側睡低 $v dB", "Back sleeping is $v dB quieter than side")
    }
    fun diffNone() = p("两种姿势都有记录后,这里显示分贝差", "兩種姿勢都有記錄後,這裡顯示分貝差", "Shown once both postures have recordings")
    fun listTitle(allScope: Boolean, n: Int): String {
        val w = if (allScope) p("全部录像", "全部錄影", "All recordings") else p("本晚录像", "本晚錄影", "This night")
        return p("$w($n 段,点击回放,长按删除)", "$w($n 段,點擊回放,長按刪除)", "$w ($n clips — tap to play, long-press to delete)")
    }
    fun selInfo(peak: Double, avg: Double, n: Int) = p(
        "峰值 %.1f dB · 平均峰值 %.1f dB · 鼾声 %d 次".format(peak, avg, n),
        "峰值 %.1f dB · 平均峰值 %.1f dB · 鼾聲 %d 次".format(peak, avg, n),
        "Peak %.1f dB · avg peak %.1f dB · %d snores".format(peak, avg, n)
    )
    fun analyzingProgress(i: Int, n: Int) = p("正在自动识别姿势 $i/$n …", "正在自動識別姿勢 $i/$n …", "Auto-detecting posture $i/$n …")
    fun camDialogTitle() = p("镜头位置(用于自动判断姿势)", "鏡頭位置(用於自動判斷姿勢)", "Camera position (used to infer posture)")
    fun camBedside() = p(
        "床边:从床侧拍,正脸朝镜头 = 侧睡,侧脸 = 仰睡",
        "床邊:從床側拍,正臉朝鏡頭 = 側睡,側臉 = 仰睡",
        "Bedside: filmed from the side; face toward camera = side sleeping, profile = back sleeping"
    )
    fun camFootend() = p(
        "床尾/枕边:正对脸拍,正脸 = 仰睡,侧脸 = 侧睡",
        "床尾/枕邊:正對臉拍,正臉 = 仰睡,側臉 = 側睡",
        "Foot/pillow: filmed facing you; frontal = back sleeping, profile = side sleeping"
    )
    fun confirmDelete(ts: String) = p("删除 $ts 这段录像?", "刪除 $ts 這段錄影?", "Delete the recording from $ts?")
    fun rowInfo(n: Int, avg: Double) = p(
        "鼾声 $n 次 · 平均峰值 %.0f dB".format(avg),
        "鼾聲 $n 次 · 平均峰值 %.0f dB".format(avg),
        "$n snores · avg peak %.0f dB".format(avg)
    )
    fun chartEmpty() = p("这一晚没有记录", "這一晚沒有記錄", "No records this night")

    // ===== 小部件 =====
    fun meterNoise(n: Int) = p("环境 $n", "環境 $n", "Ambient $n")
    fun meterThr(n: Int) = p("触发线 $n", "觸發線 $n", "Trigger $n")
    fun noTimeline() = p("没有声轨数据", "沒有聲軌資料", "No waveform data")
}
