package com.example.domain.model

data class ParsedSignal(
    val category: String = "",
    val symbol: String? = null,
    val direction: String? = null,
    val entryPrice: Double? = null,
    val additionalEntries: List<Double> = emptyList(),
    val stopLoss: Double? = null,
    val targets: List<Double> = emptyList(),
    val optionType: String? = null,
    val strikePrice: Double? = null,
    val expiryDate: String? = null,
    val entryMode: String = "limit",
    val status: String = "needs_review",
    val confidence: Double = 0.0,
    val review: String = "required",
    val explanation: String = "",
    val defaultSlApplied: Boolean = false,
    val defaultSlNote: String? = null,
    val defaultTpApplied: Boolean = false
)

data class DefaultSlSymbolRule(
    val sl: Double? = null,
    val tp1: Double? = null,
    val tp2: Double? = null,
    val tp3: Double? = null,
    val lots: Double? = null,
    val lotSize: Double? = null,
    val usdAmount: Double? = null
)

data class DefaultSlCategoryRule(
    val categoryDefault: Double? = null,
    val tp1: Double? = null,
    val tp2: Double? = null,
    val tp3: Double? = null,
    val lots: Double? = 1.0,
    val lotSize: Double? = null,
    val usdAmount: Double? = null,
    val symbols: MutableMap<String, DefaultSlSymbolRule> = linkedMapOf()
)

data class InstrumentProfile(
    val category: String,
    val unitType: String,
    val sourceLabel: String,
    val sl: Double?,
    val tp1: Double?,
    val tp2: Double?,
    val tp3: Double?,
    val lots: Double,
    val lotSize: Double?,
    val usdAmount: Double
)

data class Candle(
    val provider: String,
    val symbol: String,
    val exchange: String = "",
    val interval: String = "1m",
    val candleTimestamp: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0
)

data class TradeEvent(
    val type: String,
    val label: String,
    val price: Double,
    val candleTime: String,
    val leg: Int
)

data class TargetLevelPnl(
    val index: Int,
    val targetPrice: Double,
    val pointsPnl: Double = 0.0,
    val moneyPnl: Double = 0.0,
    val hit: Boolean = false,
    val hitAt: String? = null
) {
    val level: Int get() = index
    val label: String get() = "TP$index"
}

data class LegResult(
    val leg: Int,
    val entryPrice: Double,
    val entered: Boolean,
    val entryHitAt: String?,
    val outcome: String,
    val exitPrice: Double?,
    val exitTime: String?,
    val pointsPnl: Double,
    val targetsHit: List<Int>,
    val moneyPnl: Double = 0.0,
    val currency: String = "INR",
    val positionLabel: String? = null,
    val closedBySlAfterTp: Boolean = false,
    val slHitAfterTpAt: String? = null,
    val tp1Pnl: Double? = null,
    val tp2Pnl: Double? = null,
    val tp3Pnl: Double? = null,
    val highestTpPnl: Double = 0.0,
    val slPnl: Double = 0.0
)

data class VerificationTimeline(
    val entryHitAt: String?,
    val firstTargetHitAt: String?,
    val stopLossHitAt: String?,
    val allTargetsHit: Boolean,
    val events: List<TradeEvent>,
    val legs: List<LegResult>,
    val closedBySlAfterTp: Boolean = false,
    val maxTpHit: Int = 0
)

data class VerificationResult(
    val status: String,
    val detail: String,
    val decisionTime: String?,
    val timeline: VerificationTimeline
)

data class FilteredPnlSummary(
    val basis: String = "tp1",
    val totalSignals: Int = 0,
    val tp1Hits: Int = 0,
    val tp2Hits: Int = 0,
    val tp3Hits: Int = 0,
    val allTpHits: Int = 0,
    val tpThenSlHits: Int = 0,
    val slOnlyHits: Int = 0,
    val activeMonitoring: Int = 0,
    val winRate: Double = 0.0,
    val pnlInr: Double = 0.0,
    val pnlUsd: Double = 0.0
)

data class FilteredChannelAnalyticsRow(
    val channel: String,
    val total: Int,
    val todayTotal: Int = 0,
    val todayTp: Int = 0,
    val todaySl: Int = 0,
    val parsed: Int = 0,
    val tradesTriggered: Int = 0,
    val targets: Int = 0,
    val tp1Hits: Int = 0,
    val tp2Hits: Int = 0,
    val tp3Hits: Int = 0,
    val allTpHits: Int = 0,
    val tpThenSlHits: Int = 0,
    val stops: Int = 0,
    val pending: Int = 0,
    val winRate: Double = 0.0,
    val pnlInr: Double = 0.0,
    val pnlUsd: Double = 0.0,
    val todayPnlInr: Double = 0.0,
    val todayPnlUsd: Double = 0.0,
    val pnlBasis: String = "tp1"
)

data class ApiProviderHealth(
    val provider: String,
    val displayName: String,
    val status: String, // "healthy", "degraded", "rate_limited", "error", "unconfigured"
    val calls24h: Int = 0,
    val errors24h: Int = 0,
    val rateLimitHits24h: Int = 0,
    val lastSuccessAt: String? = null,
    val lastErrorAt: String? = null,
    val lastErrorCategory: String? = null,
    val lastHttpCode: Int? = null,
    val lastErrorMessage: String? = null
)

data class ApiHealthAlert(
    val provider: String,
    val severity: String = "warning", // "warning", "error"
    val category: String,
    val httpCode: Int?,
    val message: String,
    val occurredAt: String
)

data class ApiHealthSummary(
    val overallStatus: String = "healthy", // "healthy", "degraded", "error"
    val activeAlerts: List<ApiHealthAlert> = emptyList(),
    val providers: List<ApiProviderHealth> = emptyList(),
    val twelveDataUsage: Map<String, Double> = emptyMap(),
    val recentIncidents: List<com.example.data.local.ApiRequestLogEntity> = emptyList()
)

data class SourceNotificationRow(
    val receivedAt: String = "",
    val notificationTime: String = "",
    val sourceApp: String = "",
    val packageName: String = "",
    val channel: String = "",
    val category: String = "",
    val title: String = "",
    val message: String = "",
    val notificationId: String = "",
    val deliveryId: String = ""
) {
    fun toColumnMap(): Map<String, String> = mapOf(
        "Received At" to receivedAt,
        "Notification Time" to notificationTime,
        "Source App" to sourceApp,
        "Package" to packageName,
        "Channel" to channel,
        "Category" to category,
        "Title" to title,
        "Message" to message,
        "Notification ID" to notificationId,
        "Delivery ID" to deliveryId
    )

    companion object {
        val SOURCE_COLUMNS = listOf(
            "Received At", "Notification Time", "Source App", "Package",
            "Channel", "Category", "Title", "Message", "Notification ID", "Delivery ID"
        )

        val DEFAULT_CATEGORIES = listOf(
            "Index Options", "Stock Options", "MCX", "Forex", "Crypto"
        )

        fun fromMap(map: Map<String, String?>): SourceNotificationRow = SourceNotificationRow(
            receivedAt = map["Received At"].orEmpty(),
            notificationTime = map["Notification Time"].orEmpty(),
            sourceApp = map["Source App"].orEmpty(),
            packageName = map["Package"].orEmpty(),
            channel = map["Channel"].orEmpty(),
            category = map["Category"].orEmpty(),
            title = map["Title"].orEmpty(),
            message = map["Message"].orEmpty(),
            notificationId = map["Notification ID"].orEmpty(),
            deliveryId = map["Delivery ID"].orEmpty()
        )
    }
}

data class AppEvent(
    val type: String,
    val entityId: Long? = null,
    val status: String? = null,
    val detail: String? = null,
    val timestamp: String
)
