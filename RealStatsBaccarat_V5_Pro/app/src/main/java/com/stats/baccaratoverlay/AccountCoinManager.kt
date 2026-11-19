package com.stats.baccaratoverlay

import android.content.Context
import org.json.JSONObject

object AccountCoinManager {
    private const val PREF_NAME = "autorobot_v5_accounts"
    private const val KEY_USERS_JSON = "users_json"
    private const val KEY_CURRENT_USER = "current_user"
    private const val KEY_ADMIN_CONTACT = "admin_contact"
    private const val KEY_REDEEMED_CODES = "redeemed_codes"

    const val ADMIN_USER = "admin"
    const val ADMIN_PASS = "admin123"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun ensureDefaultAccounts(context: Context) {
        val p = prefs(context)
        val raw = p.getString(KEY_USERS_JSON, null)
        val obj = if (raw != null) JSONObject(raw) else JSONObject()
        var changed = false

        if (!obj.has(ADMIN_USER)) {
            val adminObj = JSONObject().apply {
                put("pass", ADMIN_PASS)
                put("coins", 999)
                put("isAdmin", true)
            }
            obj.put(ADMIN_USER, adminObj)
            changed = true
        }
        if (!obj.has("vip01")) {
            val demoObj = JSONObject().apply {
                put("pass", "123456")
                put("coins", 3)
                put("isAdmin", false)
            }
            obj.put("vip01", demoObj)
            changed = true
        }
        if (changed) {
            p.edit().putString(KEY_USERS_JSON, obj.toString()).apply()
        }
    }

    fun login(context: Context, usernameInput: String, passwordInput: String): Boolean {
        ensureDefaultAccounts(context)
        val user = usernameInput.trim().lowercase()
        val pass = passwordInput.trim()
        val obj = JSONObject(prefs(context).getString(KEY_USERS_JSON, "{}") ?: "{}")
        if (!obj.has(user)) return false
        val uObj = obj.getJSONObject(user)
        if (uObj.getString("pass") != pass) return false
        prefs(context).edit().putString(KEY_CURRENT_USER, user).apply()
        return true
    }

    fun logout(context: Context) {
        prefs(context).edit().remove(KEY_CURRENT_USER).apply()
    }

    fun getCurrentUser(context: Context): String? {
        return prefs(context).getString(KEY_CURRENT_USER, null)
    }

    fun isCurrentUserAdmin(context: Context): Boolean {
        val user = getCurrentUser(context) ?: return false
        if (user == ADMIN_USER) return true
        val obj = JSONObject(prefs(context).getString(KEY_USERS_JSON, "{}") ?: "{}")
        return obj.optJSONObject(user)?.optBoolean("isAdmin", false) ?: false
    }

    fun getCoins(context: Context, username: String? = getCurrentUser(context)): Int {
        if (username == null) return 0
        ensureDefaultAccounts(context)
        val obj = JSONObject(prefs(context).getString(KEY_USERS_JSON, "{}") ?: "{}")
        return obj.optJSONObject(username)?.optInt("coins", 0) ?: 0
    }

    fun consumeOneCoinForTable(context: Context): Boolean {
        val user = getCurrentUser(context) ?: return false
        val obj = JSONObject(prefs(context).getString(KEY_USERS_JSON, "{}") ?: "{}")
        val uObj = obj.optJSONObject(user) ?: return false
        val current = uObj.optInt("coins", 0)
        if (current < 1) return false
        uObj.put("coins", current - 1)
        obj.put(user, uObj)
        prefs(context).edit().putString(KEY_USERS_JSON, obj.toString()).apply()
        return true
    }

    fun createOrAddCoinsToUser(
        context: Context,
        targetUserRaw: String,
        targetPassRaw: String,
        addCoins: Int
    ): String {
        ensureDefaultAccounts(context)
        val user = targetUserRaw.trim().lowercase()
        val pass = targetPassRaw.trim()
        if (user.isEmpty()) return "Vui lòng nhập tên tài khoản!"

        val obj = JSONObject(prefs(context).getString(KEY_USERS_JSON, "{}") ?: "{}")
        if (obj.has(user)) {
            val uObj = obj.getJSONObject(user)
            val oldCoins = uObj.optInt("coins", 0)
            uObj.put("coins", (oldCoins + addCoins).coerceAtLeast(0))
            if (pass.isNotEmpty()) uObj.put("pass", pass)
            obj.put(user, uObj)
            prefs(context).edit().putString(KEY_USERS_JSON, obj.toString()).apply()
            return "✅ Đã cộng $addCoins Xu cho '$user' (Tổng: ${oldCoins + addCoins} Xu)"
        } else {
            val finalPass = pass.ifEmpty { "123456" }
            val uObj = JSONObject().apply {
                put("pass", finalPass)
                put("coins", addCoins.coerceAtLeast(0))
                put("isAdmin", false)
            }
            obj.put(user, uObj)
            prefs(context).edit().putString(KEY_USERS_JSON, obj.toString()).apply()
            return "✅ Đã tạo tài khoản '$user' (MK: $finalPass) với $addCoins Xu"
        }
    }

    fun redeemTopupCode(context: Context, codeRaw: String): Pair<Boolean, String> {
        val user = getCurrentUser(context) ?: return false to "Chưa đăng nhập!"
        val code = codeRaw.trim().uppercase()
        val reward = when (code) {
            "XU5", "TANTHU5" -> 5
            "XU10", "VIP10" -> 10
            "XU30", "PRO30" -> 30
            "XU100", "MASTER100" -> 100
            else -> 0
        }
        if (reward <= 0) {
            return false to "❌ Mã Xu không hợp lệ! Liên hệ Admin để nhận mã mới."
        }
        val redeemedKey = "$user:$code"
        val usedSet = prefs(context).getStringSet(KEY_REDEEMED_CODES, emptySet())?.toMutableSet()
            ?: mutableSetOf()
        if (usedSet.contains(redeemedKey)) {
            return false to "⚠️ Tài khoản của bạn đã sử dụng mã '$code' rồi!"
        }
        usedSet.add(redeemedKey)
        prefs(context).edit().putStringSet(KEY_REDEEMED_CODES, usedSet).apply()
        val msg = createOrAddCoinsToUser(context, user, "", reward)
        return true to "🎉 Nạp thành công +$reward Xu! ($msg)"
    }

    fun getAllUsersSummary(context: Context): String {
        ensureDefaultAccounts(context)
        val obj = JSONObject(prefs(context).getString(KEY_USERS_JSON, "{}") ?: "{}")
        val sb = StringBuilder()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val u = obj.getJSONObject(k)
            sb.append("• $k (MK: ${u.optString("pass")}) -> ${u.optInt("coins")} Xu\n")
        }
        return sb.toString().trim()
    }

    fun getAdminContact(context: Context): String {
        return prefs(context).getString(
            KEY_ADMIN_CONTACT,
            "Zalo/SĐT: 0900.000.000 | Telegram: @AdminBaccaratV5"
        ) ?: "Zalo/SĐT: 0900.000.000 | Telegram: @AdminBaccaratV5"
    }

    fun setAdminContact(context: Context, contact: String) {
        if (contact.isNotBlank()) {
            prefs(context).edit().putString(KEY_ADMIN_CONTACT, contact.trim()).apply()
        }
    }
}
