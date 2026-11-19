package com.stats.baccaratoverlay

import java.util.Locale
import kotlin.math.sqrt

data class DerivedRoadSummary(
    val redCount: Int,
    val blueCount: Int,
    val trendLabel: String, // "THUAN" (Red dominant), "DAO" (Blue dominant), "NEUTRAL"
    val displayText: String
)

data class V5HybridReport(
    val lastCapturedLabel: String,
    val robotBadgeText: String,
    val mainRecommendation: String,
    val recommendedSide: String, // "P", "B", or "WAIT"
    val mainRoadStatusText: String,
    val derivedRoadStatusText: String,
    val realDistributionText: String,
    val markovProbabilityText: String,
    val recentSequenceText: String
)

object BaccaratV5HybridEngine {

    private const val TRUE_BANKER_PROB = 0.5068
    private const val TRUE_PLAYER_PROB = 0.4932
    private const val MIN_CONFIDENCE_THRESHOLD = 57.5

    fun evaluate(
        rawHistory: List<String>,
        derivedRoad: DerivedRoadSummary,
        sourceTag: String
    ): V5HybridReport {
        val total = rawHistory.size
        if (total == 0) {
            return V5HybridReport(
                lastCapturedLabel = "Vừa bắt được: Đang tự soi màn hình...",
                robotBadgeText = "AUTO: SOI CẦU",
                mainRecommendation = "🤖 ĐANG QUÉT CẦU CHÍNH & CẦU PHỤ...",
                recommendedSide = "WAIT",
                mainRoadStatusText = "📊 Cầu Chính (Cầu 9): Chờ nhận diện chấm cầu...",
                derivedRoadStatusText = derivedRoad.displayText,
                realDistributionText = "Tỷ lệ thực bàn (0 ván): P 0.0% | B 0.0% | T 0",
                markovProbabilityText = "Xác suất nhịp thực: Chờ dữ liệu bàn",
                recentSequenceText = "Chuỗi bắt được: Trống"
            )
        }

        val lastRaw = rawHistory.last()
        val lastDesc = when (lastRaw) {
            "P" -> "🔵 CON (PLAYER)"
            "B" -> "🔴 CÁI (BANKER)"
            else -> "🟢 HÒA (TIE)"
        }

        val pCount = rawHistory.count { it == "P" }
        val bCount = rawHistory.count { it == "B" }
        val tCount = rawHistory.count { it == "T" }

        val pTotalRate = (pCount.toDouble() / total) * 100.0
        val bTotalRate = (bCount.toDouble() / total) * 100.0

        val distText = String.format(
            Locale.US,
            "Tỷ lệ thực (%d ván): P %.1f%% (%d) | B %.1f%% (%d) | T %d",
            total, pTotalRate, pCount, bTotalRate, bCount, tCount
        )

        val pbHistory = rawHistory.filter { it == "P" || it == "B" }
        val pbTotal = pbHistory.size

        if (pbTotal == 0) {
            return V5HybridReport(
                lastCapturedLabel = "Vừa bắt được: $lastDesc [$sourceTag]",
                robotBadgeText = "CHỜ P/B",
                mainRecommendation = "⏸ CHỜ KẾT QUẢ CON HOẶC CÁI",
                recommendedSide = "WAIT",
                mainRoadStatusText = "📊 Cầu Chính (Cầu 9): Mới có ván Hòa",
                derivedRoadStatusText = derivedRoad.displayText,
                realDistributionText = distText,
                markovProbabilityText = "Xác suất nhịp thực: Chưa có P/B",
                recentSequenceText = "Chuỗi bắt được: ${rawHistory.takeLast(12).joinToString("-")}"
            )
        }

        val lastSide = pbHistory.last()
        var streak = 0
        for (i in pbHistory.indices.reversed()) {
            if (pbHistory[i] == lastSide) streak++ else break
        }

        val mainRoadPattern = detectMainRoadPattern(pbHistory, lastSide, streak)
        val mainRoadLine = "📊 Cầu Chính (Cầu 9): $mainRoadPattern"
        val recentSeqText = "Chuỗi (${pbHistory.size} tay): ${pbHistory.takeLast(12).joinToString("-")} (Dây $streak)"

        if (pbTotal < 3) {
            return V5HybridReport(
                lastCapturedLabel = "Vừa bắt được: $lastDesc [$sourceTag]",
                robotBadgeText = "SOI ($pbTotal/3)",
                mainRecommendation = "⏳ ĐANG THU THẬP DATA ($pbTotal/3 TAY)",
                recommendedSide = "WAIT",
                mainRoadStatusText = mainRoadLine,
                derivedRoadStatusText = derivedRoad.displayText,
                realDistributionText = distText,
                markovProbabilityText = "Cần đủ 3 tay P/B thực tế để tính tỷ lệ",
                recentSequenceText = recentSeqText
            )
        }

        // --- LÕI TÍNH TOÁN CHUẨN BẢN V2 (MARKOV 2 BẬC + MARKOV 1 BẬC + Z-SCORE) ---
        var followP1 = 0
        var followB1 = 0
        for (i in 0 until pbHistory.size - 1) {
            if (pbHistory[i] == lastSide) {
                if (pbHistory[i + 1] == "P") followP1++
                else if (pbHistory[i + 1] == "B") followB1++
            }
        }
        val sample1 = followP1 + followB1

        var followP2 = 0
        var followB2 = 0
        val s1 = pbHistory[pbHistory.size - 2]
        val s2 = pbHistory[pbHistory.size - 1]
        for (i in 0 until pbHistory.size - 2) {
            if (pbHistory[i] == s1 && pbHistory[i + 1] == s2) {
                if (pbHistory[i + 2] == "P") followP2++
                else if (pbHistory[i + 2] == "B") followB2++
            }
        }
        val sample2 = followP2 + followB2

        val expectedB = pbTotal * TRUE_BANKER_PROB
        val stdDev = sqrt(pbTotal * TRUE_BANKER_PROB * TRUE_PLAYER_PROB)
        val zScore = if (stdDev > 0) (bCount - expectedB) / stdDev else 0.0

        var probP: Double
        var probB: Double
        val markovDetail: String

        if (sample2 >= 2) {
            probP = (followP2.toDouble() / sample2) * 100.0
            probB = (followB2.toDouble() / sample2) * 100.0
            markovDetail = String.format(
                Locale.US,
                "Nhịp [%s-%s] thực tế: P %.1f%% (%d/%d) | B %.1f%% (%d/%d)",
                s1, s2, probP, followP2, sample2, probB, followB2, sample2
            )
        } else if (sample1 >= 2) {
            probP = (followP1.toDouble() / sample1) * 100.0
            probB = (followB1.toDouble() / sample1) * 100.0
            markovDetail = String.format(
                Locale.US,
                "Sau [%s] thực tế: P %.1f%% (%d/%d) | B %.1f%% (%d/%d)",
                lastSide, probP, followP1, sample1, probB, followB1, sample1
            )
        } else {
            probP = (pCount.toDouble() / pbTotal) * 100.0
            probB = (bCount.toDouble() / pbTotal) * 100.0
            markovDetail = String.format(
                Locale.US,
                "Tỷ lệ nghiêng bàn thực: P %.1f%% | B %.1f%% (Z=%.2f)",
                probP, probB, zScore
            )
        }

        // --- KẾT HỢP TÍN HIỆU CẦU PHỤ (ĐỎ = THUẬN CẦU, XANH = ĐẢO NHỊP) ---
        var derivedBonusNote = ""
        if (derivedRoad.trendLabel == "THUAN") {
            // Cầu phụ Đỏ chiếm ưu thế -> Ưu tiên đi theo nhịp đang chạy của Cầu Chính
            if (lastSide == "P") {
                probP = (probP + 3.5).coerceAtMost(88.0)
                probB = (100.0 - probP).coerceAtLeast(12.0)
            } else {
                probB = (probB + 3.5).coerceAtMost(88.0)
                probP = (100.0 - probB).coerceAtLeast(12.0)
            }
            derivedBonusNote = " + Cầu Phụ Đỏ thuận nhịp"
        } else if (derivedRoad.trendLabel == "DAO") {
            // Cầu phụ Xanh chiếm ưu thế -> Ưu tiên bẻ cầu ngược tay cuối
            if (lastSide == "P") {
                probB = (probB + 3.5).coerceAtMost(88.0)
                probP = (100.0 - probB).coerceAtLeast(12.0)
            } else {
                probP = (probP + 3.5).coerceAtMost(88.0)
                probB = (100.0 - probP).coerceAtLeast(12.0)
            }
            derivedBonusNote = " + Cầu Phụ Xanh đảo nhịp"
        }

        return when {
            probP >= MIN_CONFIDENCE_THRESHOLD && probP > probB -> {
                val pct = String.format(Locale.US, "%.1f%%", probP)
                V5HybridReport(
                    lastCapturedLabel = "Vừa bắt được: $lastDesc [$sourceTag]",
                    robotBadgeText = "CON $pct",
                    mainRecommendation = "🔵 CHỐT: CON (PLAYER) — $pct",
                    recommendedSide = "P",
                    mainRoadStatusText = mainRoadLine,
                    derivedRoadStatusText = "${derivedRoad.displayText}$derivedBonusNote",
                    realDistributionText = distText,
                    markovProbabilityText = markovDetail,
                    recentSequenceText = recentSeqText
                )
            }
            probB >= MIN_CONFIDENCE_THRESHOLD && probB > probP -> {
                val pct = String.format(Locale.US, "%.1f%%", probB)
                V5HybridReport(
                    lastCapturedLabel = "Vừa bắt được: $lastDesc [$sourceTag]",
                    robotBadgeText = "CÁI $pct",
                    mainRecommendation = "🔴 CHỐT: CÁI (BANKER) — $pct",
                    recommendedSide = "B",
                    mainRoadStatusText = mainRoadLine,
                    derivedRoadStatusText = "${derivedRoad.displayText}$derivedBonusNote",
                    realDistributionText = distText,
                    markovProbabilityText = markovDetail,
                    recentSequenceText = recentSeqText
                )
            }
            else -> {
                V5HybridReport(
                    lastCapturedLabel = "Vừa bắt được: $lastDesc [$sourceTag]",
                    robotBadgeText = "BỎ QUA",
                    mainRecommendation = "⏸ BỎ QUA (Tỷ lệ 50/50 chưa đạt ngưỡng)",
                    recommendedSide = "WAIT",
                    mainRoadStatusText = mainRoadLine,
                    derivedRoadStatusText = derivedRoad.displayText,
                    realDistributionText = distText,
                    markovProbabilityText = markovDetail,
                    recentSequenceText = recentSeqText
                )
            }
        }
    }

    private fun detectMainRoadPattern(pb: List<String>, lastSide: String, streak: Int): String {
        val sideName = if (lastSide == "P") "CON" else "CÁI"
        if (streak >= 4) return "Cầu Bệt $sideName dây $streak"
        if (streak == 3) return "Đang lên Bệt $sideName (3 tay)"

        if (pb.size >= 4) {
            val n = pb.size
            if (pb[n - 1] != pb[n - 2] && pb[n - 2] != pb[n - 3] && pb[n - 3] != pb[n - 4]) {
                return "Cầu 1-1 nhảy đều"
            }
        }
        if (pb.size >= 4) {
            val n = pb.size
            if (pb[n - 1] == pb[n - 2] && pb[n - 3] == pb[n - 4] && pb[n - 2] != pb[n - 3]) {
                return "Cầu 2-2 theo cặp"
            }
        }
        return "Nhịp $sideName (Dây $streak)"
    }
}
