package com.stats.baccaratoverlay

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

class AutoRobotOverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var robotView: View
    private lateinit var targetView: View
    private lateinit var robotParams: WindowManager.LayoutParams
    private lateinit var targetParams: WindowManager.LayoutParams

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val handler = Handler(Looper.getMainLooper())
    private val history = mutableListOf<String>()

    private var isAutoScanning = true
    private var isPanelPinned = true
    private var isTargetVisible = true
    private var isTableUnlocked = true

    private var lastSourceTag = "Auto Màn"
    private var latestDerivedRoad = DerivedRoadSummary(
        redCount = 0,
        blueCount = 0,
        trendLabel = "NEUTRAL",
        displayText = "🧭 Cầu Phụ (3 bảng): Đang tự quét vùng cầu phụ..."
    )

    // Chống đếm trùng
    private var lastCandidateSide: String? = null
    private var consecutiveMatches = 0
    private var cooldownUntilMs = 0L
    private var isOcrBusy = false

    private var stableRoadCandidate: List<String>? = null
    private var stableRoadHits = 0

    private val autoCollapseRunnable = Runnable {
        if (!isPanelPinned && ::robotView.isInitialized) {
            val panel = robotView.findViewById<LinearLayout>(R.id.miniReportPanel)
            val toggleBtn = robotView.findViewById<TextView>(R.id.btnTogglePanel)
            panel.visibility = View.GONE
            toggleBtn.text = "▲"
        }
    }

    private val scanLoopRunnable = object : Runnable {
        override fun run() {
            if (isAutoScanning && isTableUnlocked) {
                performFullScreenAndRoadScan()
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        isTableUnlocked = true
        history.clear()
        cooldownUntilMs = 0L

        startAndroid14ProjectionForeground()
        setupOverlayWindows()
        initMediaProjection(resultCode, resultData)

        handler.removeCallbacks(scanLoopRunnable)
        handler.postDelayed(scanLoopRunnable, 1000L)
        updateUI(autoPopup = true)

        return START_NOT_STICKY
    }

    private fun startAndroid14ProjectionForeground() {
        val channelId = "autorobot_v5_hybrid_channel"
        val channel = NotificationChannel(
            channelId,
            "AutoRobot Baccarat V5 Pro",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("🤖 AutoRobot V5 Pro đang soi cầu")
            .setContentText("Tự động đọc Cầu Chính, Cầu Phụ & Tâm Quét V2")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                5005,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(5005, notification)
        }
    }

    private fun initMediaProjection(resultCode: Int, resultData: Intent) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpManager.getMediaProjection(resultCode, resultData)
        mediaProjection = projection

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { stopSelf() }
            }
        }, handler)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "AutoRobotV5ScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun setupOverlayWindows() {
        if (::windowManager.isInitialized) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 1. Tâm Quét 🎯 V2 (Tùy chọn kéo vào chữ PLAYER/BANKER hoặc góc bảng cầu)
        targetView = LayoutInflater.from(this).inflate(R.layout.scan_target_layout, null)
        targetParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels / 2 - 90
            y = (resources.displayMetrics.heightPixels * 0.42).toInt()
        }
        windowManager.addView(targetView, targetParams)

        // 2. Con Robot Mini & Bảng Báo V2 Hybrid
        robotView = LayoutInflater.from(this).inflate(R.layout.robot_overlay_layout, null)
        robotParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 110
        }
        windowManager.addView(robotView, robotParams)

        attachDragListener(targetView, targetParams)
        attachDragListener(robotView.findViewById(R.id.robotHeader), robotParams)
        bindControls()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragListener(dragHandle: View, layoutParams: WindowManager.LayoutParams) {
        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f

        dragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = layoutParams.x
                    initY = layoutParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initX + (event.rawX - touchX).toInt()
                    layoutParams.y = initY + (event.rawY - touchY).toInt()
                    val root = if (dragHandle == targetView) targetView else robotView
                    windowManager.updateViewLayout(root, layoutParams)
                    true
                }
                else -> false
            }
        }
    }

    private fun bindControls() {
        val miniPanel = robotView.findViewById<LinearLayout>(R.id.miniReportPanel)
        val btnTogglePanel = robotView.findViewById<TextView>(R.id.btnTogglePanel)
        val btnCloseApp = robotView.findViewById<TextView>(R.id.btnCloseApp)
        val btnPinPanel = robotView.findViewById<TextView>(R.id.btnPinPanel)

        val btnToggleAuto = robotView.findViewById<Button>(R.id.btnToggleAuto)
        val btnToggleTarget = robotView.findViewById<Button>(R.id.btnToggleTarget)
        val btnResetShoe = robotView.findViewById<Button>(R.id.btnResetShoe)

        val btnManualP = robotView.findViewById<Button>(R.id.btnManualP)
        val btnManualB = robotView.findViewById<Button>(R.id.btnManualB)
        val btnManualUndo = robotView.findViewById<Button>(R.id.btnManualUndo)
        val btnOpenMainApp = robotView.findViewById<Button>(R.id.btnOpenMainApp)

        btnTogglePanel.setOnClickListener {
            if (miniPanel.visibility == View.VISIBLE) {
                miniPanel.visibility = View.GONE
                btnTogglePanel.text = "▲"
            } else {
                miniPanel.visibility = View.VISIBLE
                btnTogglePanel.text = "▼"
            }
        }

        btnCloseApp.setOnClickListener { stopSelf() }

        btnPinPanel.setOnClickListener {
            isPanelPinned = !isPanelPinned
            btnPinPanel.text = if (isPanelPinned) "📌 Ghim: BẬT" else "📌 Ghim: TẮT"
            if (isPanelPinned) handler.removeCallbacks(autoCollapseRunnable)
        }

        btnToggleAuto.setOnClickListener {
            isAutoScanning = !isAutoScanning
            val tvStatus = robotView.findViewById<TextView>(R.id.tvRobotStatus)
            if (isAutoScanning) {
                btnToggleAuto.text = "⏸ Dừng"
                tvStatus.text = "AUTO: SOI CẦU"
            } else {
                btnToggleAuto.text = "▶️ Chạy"
                tvStatus.text = "AUTO: TẠM DỪNG"
            }
        }

        btnToggleTarget.setOnClickListener {
            isTargetVisible = !isTargetVisible
            targetView.visibility = if (isTargetVisible) View.VISIBLE else View.GONE
        }

        btnResetShoe.setOnClickListener {
            // Chuyển bàn mới tốn 1 Xu cho bàn mới
            val ok = AccountCoinManager.consumeOneCoinForTable(this)
            if (ok) {
                isTableUnlocked = true
                history.clear()
                cooldownUntilMs = 0L
                lastCandidateSide = null
                consecutiveMatches = 0
                stableRoadCandidate = null
                stableRoadHits = 0
                lastSourceTag = "Bàn mới"
                Toast.makeText(this, "✅ Đã trừ 1 Xu mở Bàn Mới!", Toast.LENGTH_SHORT).show()
                updateUI(autoPopup = true)
            } else {
                isTableUnlocked = false
                Toast.makeText(
                    this,
                    "⚠️ Hết Xu! Vui lòng vào App liên hệ Admin nạp thêm Xu.",
                    Toast.LENGTH_LONG
                ).show()
                updateUI(autoPopup = true)
            }
        }

        btnManualP.setOnClickListener {
            if (!isTableUnlocked) return@setOnClickListener
            history.add("P")
            lastSourceTag = "Bấm tay"
            updateUI(autoPopup = true)
        }

        btnManualB.setOnClickListener {
            if (!isTableUnlocked) return@setOnClickListener
            history.add("B")
            lastSourceTag = "Bấm tay"
            updateUI(autoPopup = true)
        }

        btnManualUndo.setOnClickListener {
            if (history.isNotEmpty()) {
                history.removeAt(history.lastIndex)
                updateUI(autoPopup = true)
            }
        }

        btnOpenMainApp.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }
    }

    private fun performFullScreenAndRoadScan() {
        val reader = imageReader ?: return
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return

        var fullBitmap: Bitmap? = null
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val raw = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            raw.copyPixelsFromBuffer(buffer)
            fullBitmap = if (raw.width != image.width) {
                val cropped = Bitmap.createBitmap(raw, 0, 0, image.width, image.height)
                raw.recycle()
                cropped
            } else {
                raw
            }

            val bmp = fullBitmap ?: return

            // 1. TỰ ĐỘNG QUÉT VÙNG CẦU CHÍNH (CẦU 9) & CẦU PHỤ Ở NỬA DƯỚI MÀN HÌNH
            scanMainAndDerivedRoadsFromScreen(bmp)

            // 2. QUÉT TÂM 🎯 V2 (Nếu tâm quét đang bật)
            if (isTargetVisible && System.currentTimeMillis() >= cooldownUntilMs) {
                scanV2TargetBox(bmp)
            }

            // 3. QUÉT OCR BANNER KẾT QUẢ THẮNG Ở GIỮA MÀN HÌNH
            if (!isOcrBusy && System.currentTimeMillis() >= cooldownUntilMs) {
                runCenterBannerOcr(bmp)
            }
        } finally {
            fullBitmap?.recycle()
            image.close()
        }
    }

    /**
     * Tự động quét khu vực Bảng Cầu phía dưới màn hình:
     * - Nửa trái (4% -> 56% chiều ngang, 58% -> 92% chiều cao): Cầu Chính (Cầu 9)
     * - Nửa phải (56% -> 96% chiều ngang, 60% -> 92% chiều cao): 3 Bảng Cầu Phụ (Chấm Đỏ / Xanh)
     */
    private fun scanMainAndDerivedRoadsFromScreen(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        val hsv = FloatArray(3)

        // --- A. QUÉT CẦU PHỤ (DERIVED ROADS: ĐỎ THUẬN CẦU vs XANH ĐẢO NHỊP) ---
        val dLeft = (w * 0.56f).toInt()
        val dRight = (w * 0.96f).toInt()
        val dTop = (h * 0.60f).toInt()
        val dBottom = (h * 0.92f).toInt()

        var derivedRed = 0
        var derivedBlue = 0
        for (y in dTop until dBottom step 5) {
            for (x in dLeft until dRight step 5) {
                Color.colorToHSV(bitmap.getPixel(x, y), hsv)
                val hue = hsv[0]
                val sat = hsv[1]
                val valB = hsv[2]
                if (sat >= 0.52f && valB >= 0.38f) {
                    if (hue <= 14f || hue >= 342f) derivedRed++
                    else if (hue in 198f..246f) derivedBlue++
                }
            }
        }

        val dTotal = derivedRed + derivedBlue
        latestDerivedRoad = if (dTotal >= 12) {
            val redPct = (derivedRed * 100) / dTotal
            val bluePct = 100 - redPct
            when {
                redPct >= 58 -> DerivedRoadSummary(
                    redCount = derivedRed,
                    blueCount = derivedBlue,
                    trendLabel = "THUAN",
                    displayText = "🧭 Cầu Phụ: Đỏ $redPct% | Xanh $bluePct% (Thuận cầu)"
                )
                bluePct >= 58 -> DerivedRoadSummary(
                    redCount = derivedRed,
                    blueCount = derivedBlue,
                    trendLabel = "DAO",
                    displayText = "🧭 Cầu Phụ: Đỏ $redPct% | Xanh $bluePct% (Đảo nhịp)"
                )
                else -> DerivedRoadSummary(
                    redCount = derivedRed,
                    blueCount = derivedBlue,
                    trendLabel = "NEUTRAL",
                    displayText = "🧭 Cầu Phụ: Đỏ $redPct% | Xanh $bluePct% (Cân bằng)"
                )
            }
        } else {
            DerivedRoadSummary(
                redCount = 0,
                blueCount = 0,
                trendLabel = "NEUTRAL",
                displayText = "🧭 Cầu Phụ (3 bảng): Đang đợi bảng cầu hiển thị"
            )
        }

        // --- B. QUÉT CẦU CHÍNH (CẦU 9 - LƯỚI 6 HÀNG x 14 CỘT) ---
        val mLeft = (w * 0.04f).toInt()
        val mRight = (w * 0.56f).toInt()
        val mTop = (h * 0.58f).toInt()
        val mBottom = (h * 0.91f).toInt()

        val rows = 6
        val cols = 14
        val cellW = (mRight - mLeft).toFloat() / cols
        val cellH = (mBottom - mTop).toFloat() / rows

        val detectedBeads = mutableListOf<String>()
        for (c in 0 until cols) {
            var emptyCellsInCol = 0
            for (r in 0 until rows) {
                val cx = (mLeft + (c + 0.5f) * cellW).toInt()
                val cy = (mTop + (r + 0.5f) * cellH).toInt()
                val rx = (cellW * 0.26f).toInt().coerceAtLeast(2)
                val ry = (cellH * 0.26f).toInt().coerceAtLeast(2)

                var bVotes = 0
                var rVotes = 0
                var gVotes = 0
                var dots = 0

                for (dy in -ry..ry step 2) {
                    for (dx in -rx..rx step 2) {
                        val px = (cx + dx).coerceIn(0, w - 1)
                        val py = (cy + dy).coerceIn(0, h - 1)
                        Color.colorToHSV(bitmap.getPixel(px, py), hsv)
                        dots++
                        if (hsv[1] >= 0.52f && hsv[2] >= 0.38f) {
                            when {
                                hsv[0] in 198f..246f -> bVotes++
                                hsv[0] <= 14f || hsv[0] >= 342f -> rVotes++
                                hsv[0] in 88f..152f -> gVotes++
                            }
                        }
                    }
                }

                val minVotes = (dots * 0.26).toInt().coerceAtLeast(3)
                val cellOutcome = when {
                    rVotes >= minVotes && rVotes > bVotes * 1.5 && rVotes > gVotes * 1.5 -> "B"
                    bVotes >= minVotes && bVotes > rVotes * 1.5 && bVotes > gVotes * 1.5 -> "P"
                    gVotes >= minVotes && gVotes > rVotes * 1.5 && gVotes > bVotes * 1.5 -> "T"
                    else -> null
                }

                if (cellOutcome != null) {
                    detectedBeads.add(cellOutcome)
                } else {
                    emptyCellsInCol++
                }
            }
            if (emptyCellsInCol == rows) break
        }

        // Yêu cầu tối thiểu 4 chấm cầu hợp lệ và ổn định 2 nhịp liên tiếp để tránh nhận nhầm nền bàn
        if (detectedBeads.size >= 4) {
            if (detectedBeads == stableRoadCandidate) {
                stableRoadHits++
            } else {
                stableRoadCandidate = detectedBeads
                stableRoadHits = 1
            }

            if (stableRoadHits >= 2 && detectedBeads != history) {
                history.clear()
                history.addAll(detectedBeads)
                lastSourceTag = "Cầu Chính"
                cooldownUntilMs = System.currentTimeMillis() + 5_000L
                triggerSubtleVibration()
                updateUI(autoPopup = true)
            }
        }
    }

    private fun scanV2TargetBox(fullBitmap: Bitmap) {
        val loc = IntArray(2)
        targetView.getLocationOnScreen(loc)
        val startX = loc[0].coerceIn(0, fullBitmap.width - 1)
        val startY = loc[1].coerceIn(0, fullBitmap.height - 1)
        val boxW = targetView.width.coerceAtLeast(10).coerceAtMost(fullBitmap.width - startX)
        val boxH = targetView.height.coerceAtLeast(10).coerceAtMost(fullBitmap.height - startY)

        var blueCount = 0
        var redCount = 0
        var greenCount = 0
        var sampledPixels = 0
        val hsv = FloatArray(3)

        val margin = 6
        val xFrom = (startX + margin).coerceAtMost(startX + boxW - 1)
        val xTo = (startX + boxW - margin).coerceAtLeast(xFrom + 1)
        val yFrom = (startY + margin).coerceAtMost(startY + boxH - 1)
        val yTo = (startY + boxH - margin).coerceAtLeast(yFrom + 1)

        for (y in yFrom until yTo step 2) {
            for (x in xFrom until xTo step 2) {
                val pixel = fullBitmap.getPixel(x, y)
                Color.colorToHSV(pixel, hsv)
                sampledPixels++

                if (hsv[1] >= 0.48f && hsv[2] >= 0.36f) {
                    when {
                        hsv[0] in 196f..246f -> blueCount++
                        hsv[0] <= 14f || hsv[0] >= 342f -> redCount++
                        hsv[0] in 92f..152f -> greenCount++
                    }
                }
            }
        }

        if (sampledPixels > 0) {
            val threshold = (sampledPixels * 0.20).toInt().coerceAtLeast(8)
            val detected = when {
                redCount >= threshold && redCount > blueCount * 1.5 -> "B"
                blueCount >= threshold && blueCount > redCount * 1.5 -> "P"
                greenCount >= threshold && greenCount > redCount * 1.5 && greenCount > blueCount * 1.5 -> "T"
                else -> null
            }

            if (detected != null) {
                if (detected == lastCandidateSide) {
                    consecutiveMatches++
                } else {
                    lastCandidateSide = detected
                    consecutiveMatches = 1
                }

                if (consecutiveMatches >= 2) {
                    history.add(detected)
                    lastCandidateSide = null
                    consecutiveMatches = 0
                    lastSourceTag = "Tâm Quét"
                    cooldownUntilMs = System.currentTimeMillis() + 13_000L
                    triggerSubtleVibration()
                    updateUI(autoPopup = true)
                }
            } else {
                lastCandidateSide = null
                consecutiveMatches = 0
            }
        }
    }

    private fun runCenterBannerOcr(fullBitmap: Bitmap) {
        val top = (fullBitmap.height * 0.20).toInt()
        val bottom = (fullBitmap.height * 0.55).toInt()
        val left = (fullBitmap.width * 0.15).toInt()
        val right = (fullBitmap.width * 0.85).toInt()

        val cropW = (right - left).coerceAtLeast(32)
        val cropH = (bottom - top).coerceAtLeast(32)
        val crop = Bitmap.createBitmap(fullBitmap, left, top, cropW, cropH)

        isOcrBusy = true
        textRecognizer.process(InputImage.fromBitmap(crop, 0))
            .addOnSuccessListener { visionText ->
                val clean = Normalizer.normalize(visionText.text, Normalizer.Form.NFD)
                    .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
                    .uppercase(Locale.US)

                val outcome = clean.lines().map { it.trim() }.firstNotNullOfOrNull { line ->
                    when (line) {
                        "PLAYER WIN", "PLAYER WINS", "CON THANG", "TAY CON THANG" -> "P"
                        "BANKER WIN", "BANKER WINS", "CAI THANG", "NHA CAI THANG" -> "B"
                        "TIE WIN", "TIE WINS", "HOA THANG", "KET QUA HOA" -> "T"
                        else -> null
                    }
                }

                if (outcome != null && System.currentTimeMillis() >= cooldownUntilMs) {
                    cooldownUntilMs = System.currentTimeMillis() + 12_000L
                    history.add(outcome)
                    lastSourceTag = "OCR Thắng"
                    triggerSubtleVibration()
                    updateUI(autoPopup = true)
                }
            }
            .addOnCompleteListener {
                crop.recycle()
                isOcrBusy = false
            }
    }

    private fun triggerSubtleVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(85, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(85, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(85)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun updateUI(autoPopup: Boolean) {
        val coinsLeft = AccountCoinManager.getCoins(this)
        val tvCoinBadge = robotView.findViewById<TextView>(R.id.tvOverlayCoinBadge)
        val tvRobotStatus = robotView.findViewById<TextView>(R.id.tvRobotStatus)
        val tvLastCaptured = robotView.findViewById<TextView>(R.id.tvLastCaptured)
        val tvMainPrediction = robotView.findViewById<TextView>(R.id.tvMainPrediction)
        val tvMainRoadStatus = robotView.findViewById<TextView>(R.id.tvMainRoadStatus)
        val tvDerivedRoadStatus = robotView.findViewById<TextView>(R.id.tvDerivedRoadStatus)
        val tvRealWinRate = robotView.findViewById<TextView>(R.id.tvRealWinRate)
        val tvMarkovProb = robotView.findViewById<TextView>(R.id.tvMarkovProb)
        val tvRecentSequence = robotView.findViewById<TextView>(R.id.tvRecentSequence)

        if (!isTableUnlocked) {
            tvCoinBadge.text = "💰 Còn: $coinsLeft Xu | 🔒 Bàn bị khóa"
            tvRobotStatus.text = "HẾT XU"
            tvMainPrediction.text = "🔒 HẾT XU — BẤM [💰 NẠP XU] ĐỂ XEM TIẾP"
            tvMainPrediction.setTextColor(Color.parseColor("#FF5252"))
            return
        }

        tvCoinBadge.text = "💰 Còn: $coinsLeft Xu | Bàn đang mở (-1 Xu)"

        val report = BaccaratV5HybridEngine.evaluate(history, latestDerivedRoad, lastSourceTag)

        tvRobotStatus.text = report.robotBadgeText
        tvLastCaptured.text = report.lastCapturedLabel
        tvMainPrediction.text = report.mainRecommendation
        tvMainRoadStatus.text = report.mainRoadStatusText
        tvDerivedRoadStatus.text = report.derivedRoadStatusText
        tvRealWinRate.text = report.realDistributionText
        tvMarkovProb.text = report.markovProbabilityText
        tvRecentSequence.text = report.recentSequenceText

        when (report.recommendedSide) {
            "P" -> {
                tvMainPrediction.setTextColor(Color.parseColor("#4FC3F7"))
                tvRobotStatus.setTextColor(Color.parseColor("#4FC3F7"))
            }
            "B" -> {
                tvMainPrediction.setTextColor(Color.parseColor("#FF5252"))
                tvRobotStatus.setTextColor(Color.parseColor("#FF5252"))
            }
            else -> {
                tvMainPrediction.setTextColor(Color.parseColor("#FFD54F"))
                tvRobotStatus.setTextColor(Color.parseColor("#FFD54F"))
            }
        }

        if (autoPopup) {
            val miniPanel = robotView.findViewById<LinearLayout>(R.id.miniReportPanel)
            val btnTogglePanel = robotView.findViewById<TextView>(R.id.btnTogglePanel)
            miniPanel.visibility = View.VISIBLE
            btnTogglePanel.text = "▼"

            handler.removeCallbacks(autoCollapseRunnable)
            if (!isPanelPinned) {
                handler.postDelayed(autoCollapseRunnable, 12_000L)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(scanLoopRunnable)
        handler.removeCallbacks(autoCollapseRunnable)
        textRecognizer.close()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        try {
            if (::robotView.isInitialized) windowManager.removeView(robotView)
        } catch (_: Exception) {
        }
        try {
            if (::targetView.isInitialized) windowManager.removeView(targetView)
        } catch (_: Exception) {
        }
    }
}
