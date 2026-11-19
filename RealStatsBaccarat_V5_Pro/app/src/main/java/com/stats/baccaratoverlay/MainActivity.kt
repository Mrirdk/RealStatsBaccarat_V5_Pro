package com.stats.baccaratoverlay

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val deducted = AccountCoinManager.consumeOneCoinForTable(this)
            if (!deducted) {
                Toast.makeText(
                    this,
                    "⚠️ Số dư không đủ 1 Xu! Vui lòng liên hệ Admin nạp Xu.",
                    Toast.LENGTH_LONG
                ).show()
                refreshDashboardState()
                return@registerForActivityResult
            }

            refreshDashboardState()
            Toast.makeText(
                this,
                "✅ Đã trừ 1 Xu mở bàn! Đang chạy Robot soi Cầu Chính & Cầu Phụ...",
                Toast.LENGTH_SHORT
            ).show()

            val serviceIntent = Intent(this, AutoRobotOverlayService::class.java).apply {
                putExtra(AutoRobotOverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AutoRobotOverlayService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            moveTaskToBack(true)
        } else {
            Toast.makeText(
                this,
                "Bạn cần cấp quyền quay màn hình để Robot xem cầu (Chưa trừ Xu)",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AccountCoinManager.ensureDefaultAccounts(this)
        bindAuthEvents()
        bindDashboardEvents()
        bindAdminEvents()
        refreshScreenVisibility()
        requestNotificationPermissionAndroid14()
    }

    override fun onResume() {
        super.onResume()
        refreshDashboardState()
    }

    private fun bindAuthEvents() {
        val etUser = findViewById<EditText>(R.id.etLoginUser)
        val etPass = findViewById<EditText>(R.id.etLoginPass)
        val btnLogin = findViewById<Button>(R.id.btnLoginSubmit)
        val btnOpenReg = findViewById<Button>(R.id.btnOpenRegisterContact)
        val regContactBox = findViewById<LinearLayout>(R.id.layoutRegisterContactBox)
        val btnCopyContact = findViewById<Button>(R.id.btnCopyAdminContact)
        val btnTelegram = findViewById<Button>(R.id.btnOpenAdminTelegram)

        btnLogin.setOnClickListener {
            val u = etUser.text.toString()
            val p = etPass.text.toString()
            if (AccountCoinManager.login(this, u, p)) {
                Toast.makeText(this, "🎉 Đăng nhập thành công!", Toast.LENGTH_SHORT).show()
                refreshScreenVisibility()
            } else {
                Toast.makeText(
                    this,
                    "❌ Sai tài khoản hoặc mật khẩu! Bấm Đăng ký để liên hệ Admin cấp tài khoản.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        btnOpenReg.setOnClickListener {
            regContactBox.visibility =
                if (regContactBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            updateContactDisplay()
        }

        btnCopyContact.setOnClickListener {
            val contact = AccountCoinManager.getAdminContact(this)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Admin Contact", contact))
            Toast.makeText(this, "📋 Đã sao chép liên hệ Admin: $contact", Toast.LENGTH_SHORT).show()
        }

        btnTelegram.setOnClickListener {
            val contact = AccountCoinManager.getAdminContact(this)
            AlertDialog.Builder(this)
                .setTitle("👑 Liên Hệ Admin Đăng Ký & Cấp Xu")
                .setMessage(
                    "Vui lòng nhắn trực tiếp cho Admin để tạo tài khoản và nạp Xu chơi (1 Xu = 1 Bàn):\n\n$contact"
                )
                .setPositiveButton("Sao chép liên hệ") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Admin Contact", contact))
                    Toast.makeText(this, "Đã sao chép!", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Đóng", null)
                .show()
        }
    }

    private fun bindDashboardEvents() {
        val btnLogout = findViewById<Button>(R.id.btnLogout)
        val btnLaunchRobot = findViewById<Button>(R.id.btnLaunchRobot)
        val etTopupCode = findViewById<EditText>(R.id.etTopupCode)
        val btnRedeemCode = findViewById<Button>(R.id.btnRedeemCode)
        val btnDashboardContact = findViewById<Button>(R.id.btnDashboardContactAdmin)

        btnLogout.setOnClickListener {
            AccountCoinManager.logout(this)
            refreshScreenVisibility()
        }

        btnLaunchRobot.setOnClickListener {
            val coins = AccountCoinManager.getCoins(this)
            if (coins < 1) {
                AlertDialog.Builder(this)
                    .setTitle("⚠️ Hết Xu Xem Cầu")
                    .setMessage(
                        "Tài khoản của bạn hiện có 0 Xu.\nMỗi bàn chơi tốn 1 Xu để mở phân tích Cầu Chính & Cầu Phụ.\n\nLiên hệ Admin: ${AccountCoinManager.getAdminContact(this)}"
                    )
                    .setPositiveButton("Copy liên hệ Admin") { _, _ ->
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("Admin", AccountCoinManager.getAdminContact(this))
                        )
                        Toast.makeText(this, "Đã sao chép liên hệ Admin!", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Đóng", null)
                    .show()
                return@setOnClickListener
            }
            startAutoRobotFlow()
        }

        btnRedeemCode.setOnClickListener {
            val code = etTopupCode.text.toString()
            val (ok, msg) = AccountCoinManager.redeemTopupCode(this, code)
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            if (ok) {
                etTopupCode.setText("")
                refreshDashboardState()
            }
        }

        btnDashboardContact.setOnClickListener {
            val contact = AccountCoinManager.getAdminContact(this)
            AlertDialog.Builder(this)
                .setTitle("💰 Nạp Thêm Xu Chơi")
                .setMessage(
                    "Liên hệ Admin để nạp thêm Xu vào tài khoản '${AccountCoinManager.getCurrentUser(this)}':\n\n$contact"
                )
                .setPositiveButton("Sao chép liên hệ") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Admin", contact))
                    Toast.makeText(this, "Đã sao chép!", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Đóng", null)
                .show()
        }
    }

    private fun bindAdminEvents() {
        val etTargetUser = findViewById<EditText>(R.id.etAdminTargetUser)
        val etTargetPass = findViewById<EditText>(R.id.etAdminTargetPass)
        val etTargetCoins = findViewById<EditText>(R.id.etAdminTargetCoins)
        val btnSaveUser = findViewById<Button>(R.id.btnAdminSaveUser)
        val etContactEdit = findViewById<EditText>(R.id.etAdminContactEdit)
        val btnSaveContact = findViewById<Button>(R.id.btnAdminSaveContact)

        btnSaveUser.setOnClickListener {
            val u = etTargetUser.text.toString()
            val p = etTargetPass.text.toString()
            val c = etTargetCoins.text.toString().toIntOrNull() ?: 0
            val res = AccountCoinManager.createOrAddCoinsToUser(this, u, p, c)
            Toast.makeText(this, res, Toast.LENGTH_LONG).show()
            etTargetUser.setText("")
            etTargetPass.setText("")
            etTargetCoins.setText("")
            refreshDashboardState()
        }

        btnSaveContact.setOnClickListener {
            val newContact = etContactEdit.text.toString()
            AccountCoinManager.setAdminContact(this, newContact)
            updateContactDisplay()
            Toast.makeText(this, "✅ Đã cập nhật thông tin liên hệ Admin!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshScreenVisibility() {
        val currentUser = AccountCoinManager.getCurrentUser(this)
        val authSection = findViewById<LinearLayout>(R.id.layoutAuthSection)
        val dashSection = findViewById<LinearLayout>(R.id.layoutDashboardSection)

        if (currentUser == null) {
            authSection.visibility = View.VISIBLE
            dashSection.visibility = View.GONE
            updateContactDisplay()
        } else {
            authSection.visibility = View.GONE
            dashSection.visibility = View.VISIBLE
            refreshDashboardState()
        }
    }

    private fun refreshDashboardState() {
        val currentUser = AccountCoinManager.getCurrentUser(this) ?: return
        val coins = AccountCoinManager.getCoins(this, currentUser)
        val isAdmin = AccountCoinManager.isCurrentUserAdmin(this)

        findViewById<TextView>(R.id.tvWelcomeUser).text =
            if (isAdmin) "👑 Xin chào, Admin ($currentUser)" else "👤 Xin chào, $currentUser"
        findViewById<TextView>(R.id.tvCoinBalance).text = "💰 Số dư hiện tại: $coins Xu"

        val adminPanel = findViewById<LinearLayout>(R.id.layoutAdminPanel)
        if (isAdmin) {
            adminPanel.visibility = View.VISIBLE
            findViewById<TextView>(R.id.tvAdminUserList).text =
                "Danh sách tài khoản & Xu:\n${AccountCoinManager.getAllUsersSummary(this)}"
            val etContactEdit = findViewById<EditText>(R.id.etAdminContactEdit)
            if (etContactEdit.text.isNullOrBlank()) {
                etContactEdit.setText(AccountCoinManager.getAdminContact(this))
            }
        } else {
            adminPanel.visibility = View.GONE
        }
    }

    private fun updateContactDisplay() {
        val contact = AccountCoinManager.getAdminContact(this)
        findViewById<TextView>(R.id.tvAdminContactInfo).text =
            "• Để tạo tài khoản mới và cấp Xu chơi (1 Xu = 1 Bàn), vui lòng liên hệ trực tiếp Admin:\n• $contact"
    }

    private fun requestNotificationPermissionAndroid14() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
    }

    private fun startAutoRobotFlow() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Bước 1: Bật quyền 'Hiển thị trên ứng dụng khác'",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpManager.createScreenCaptureIntent())
    }
}
