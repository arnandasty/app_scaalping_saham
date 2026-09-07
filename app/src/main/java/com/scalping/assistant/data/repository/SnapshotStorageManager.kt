package com.scalping.assistant.data.repository

import android.content.Context
import android.util.Log
import com.scalping.assistant.data.models.BandarDetectorStat
import com.scalping.assistant.data.models.OrderBookSnapshot
import com.scalping.assistant.data.models.PriceLevel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LoadedSnapshotData(
    val historyMap: MutableMap<String, MutableList<OrderBookSnapshot>>,
    val bandarMap: MutableMap<String, BandarDetectorStat>,
    val totalLoadedCount: Int,
    val tickerCount: Int,
    val savedAt: Long
)

object SnapshotStorageManager {

    private const val TAG = "SNAPSHOT_CACHE"
    private const val FILE_NAME = "orderbook_snapshots_cache.json"
    private const val BACKUP_FILE_NAME = "orderbook_snapshots_backup.json"
    
    // Default masa retensi: 48 jam (2 hari) sesuai permintaan pengguna
    const val DEFAULT_MAX_AGE_HOURS = 48L

    /**
     * Menyimpan snapshots dan bandar detector ke internal file storage (dan secondary backup).
     * Thread-safe & non-blocking jika dijalankan di Dispatchers.IO.
     */
    fun saveSnapshots(
        context: Context,
        historyMap: Map<String, List<OrderBookSnapshot>>,
        bandarMap: Map<String, BandarDetectorStat>
    ) {
        try {
            val root = JSONObject()
            root.put("version", 1)
            root.put("savedAt", System.currentTimeMillis())

            // 1. Serialize Snapshots
            val historyObj = JSONObject()
            var totalCount = 0
            for ((key, list) in historyMap) {
                if (list.isEmpty()) continue
                val arr = JSONArray()
                // Simpan maksimal 40 snapshot per ticker
                val toSave = list.takeLast(40)
                for (snap in toSave) {
                    val sObj = JSONObject()
                    sObj.put("t", snap.ticker)
                    sObj.put("p", snap.lastPrice)
                    sObj.put("c", snap.changePercent)
                    sObj.put("ts", snap.timestamp)
                    sObj.put("tbl", snap.totalBidLot)
                    sObj.put("tol", snap.totalOfferLot)
                    if (snap.araPrice > 0) sObj.put("ara", snap.araPrice)
                    if (snap.arbPrice > 0) sObj.put("arb", snap.arbPrice)

                    // Compact serialization untuk level orderbook: [price, lot, freq]
                    if (snap.bidLevels.isNotEmpty()) {
                        val bidsArr = JSONArray()
                        for (b in snap.bidLevels.take(10)) {
                            val bItem = JSONArray()
                            bItem.put(b.price)
                            bItem.put(b.lot)
                            bItem.put(b.frequency)
                            bidsArr.put(bItem)
                        }
                        sObj.put("bids", bidsArr)
                    }

                    if (snap.offerLevels.isNotEmpty()) {
                        val offersArr = JSONArray()
                        for (o in snap.offerLevels.take(10)) {
                            val oItem = JSONArray()
                            oItem.put(o.price)
                            oItem.put(o.lot)
                            oItem.put(o.frequency)
                            offersArr.put(oItem)
                        }
                        sObj.put("offers", offersArr)
                    }

                    arr.put(sObj)
                    totalCount++
                }
                historyObj.put(key, arr)
            }
            root.put("history", historyObj)

            // 2. Serialize Bandar Detector
            val bandarObj = JSONObject()
            for ((ticker, stat) in bandarMap) {
                val b = JSONObject()
                b.put("ticker", stat.ticker)
                b.put("accdist", stat.accdistStatus)
                b.put("avg", stat.averagePrice)
                b.put("amount", stat.amountRupiah)
                b.put("vol", stat.volumeLot)
                b.put("topBrokers", stat.topBrokers)
                b.put("topConc", stat.topConcentration)
                b.put("ff", stat.foreignFlow)
                b.put("ffMulti", stat.foreignFlowMultiDay)
                b.put("sm", stat.smartMoneySummary)
                b.put("ts", stat.lastUpdated)
                bandarObj.put(ticker, b)
            }
            root.put("bandar", bandarObj)

            val jsonString = root.toString()

            // Tulis secara atomic via temp file ke internal storage
            val primaryFile = File(context.filesDir, FILE_NAME)
            val tempFile = File(context.filesDir, "$FILE_NAME.tmp")
            tempFile.writeText(jsonString)
            if (tempFile.renameTo(primaryFile) || (primaryFile.delete() && tempFile.renameTo(primaryFile))) {
                // Success
            } else {
                primaryFile.writeText(jsonString)
            }

            // Tulis secondary backup ke external files dir jika tersedia
            try {
                val extDir = context.getExternalFilesDir(null)
                if (extDir != null && extDir.exists()) {
                    File(extDir, BACKUP_FILE_NAME).writeText(jsonString)
                }
            } catch (_: Exception) {}

            Log.d(TAG, "💾 Tersimpan $totalCount snapshot (${historyMap.size} ticker) & ${bandarMap.size} bandar data (${jsonString.length / 1024} KB)")

        } catch (e: Exception) {
            Log.e(TAG, "Gagal menyimpan snapshot: ${e.message}")
        }
    }

    /**
     * Memuat snapshots dari file storage.
     * Secara otomatis membuang snapshot yang lebih tua dari maxAgeHours (default 48 jam / 2 hari).
     */
    fun loadSnapshots(context: Context, maxAgeHours: Long = DEFAULT_MAX_AGE_HOURS): LoadedSnapshotData {
        val resultMap = mutableMapOf<String, MutableList<OrderBookSnapshot>>()
        val resultBandar = mutableMapOf<String, BandarDetectorStat>()
        var totalLoaded = 0
        var savedAtTime = 0L

        try {
            val primaryFile = File(context.filesDir, FILE_NAME)
            val extDir = context.getExternalFilesDir(null)
            val backupFile = if (extDir != null) File(extDir, BACKUP_FILE_NAME) else null

            val targetFile = when {
                primaryFile.exists() && primaryFile.length() > 0 -> primaryFile
                backupFile != null && backupFile.exists() && backupFile.length() > 0 -> backupFile
                else -> null
            }

            if (targetFile == null) {
                Log.d(TAG, "File cache snapshot belum ada.")
                return LoadedSnapshotData(resultMap, resultBandar, 0, 0, 0L)
            }

            val jsonString = targetFile.readText()
            if (jsonString.isBlank()) {
                return LoadedSnapshotData(resultMap, resultBandar, 0, 0, 0L)
            }

            val root = JSONObject(jsonString)
            savedAtTime = root.optLong("savedAt", 0L)
            val cutoffTime = System.currentTimeMillis() - (maxAgeHours * 60 * 60 * 1000L)

            // 1. Deserialize Snapshots
            val historyObj = root.optJSONObject("history")
            if (historyObj != null) {
                val keys = historyObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val arr = historyObj.optJSONArray(key) ?: continue
                    val list = mutableListOf<OrderBookSnapshot>()

                    for (i in 0 until arr.length()) {
                        val sObj = arr.optJSONObject(i) ?: continue
                        val ts = sObj.optLong("ts", 0L)

                        // FILTER AUTO-RESET: Jangan gunakan data yang lebih lama dari 48 jam (2 hari)
                        if (ts > 0 && ts < cutoffTime) {
                            continue
                        }

                        val ticker = sObj.optString("t", "")
                        if (ticker.isEmpty()) continue
                        val lastPrice = sObj.optInt("p", 0)
                        val changePercent = sObj.optDouble("c", 0.0)
                        val totalBidLot = sObj.optLong("tbl", 0L)
                        val totalOfferLot = sObj.optLong("tol", 0L)
                        val araPrice = sObj.optInt("ara", 0)
                        val arbPrice = sObj.optInt("arb", 0)

                        val bids = mutableListOf<PriceLevel>()
                        val bidsArr = sObj.optJSONArray("bids")
                        if (bidsArr != null) {
                            for (bIdx in 0 until bidsArr.length()) {
                                val item = bidsArr.optJSONArray(bIdx) ?: continue
                                if (item.length() >= 2) {
                                    bids.add(PriceLevel(
                                        price = item.getInt(0),
                                        lot = item.getLong(1),
                                        frequency = if (item.length() > 2) item.getInt(2) else 0
                                    ))
                                }
                            }
                        }

                        val offers = mutableListOf<PriceLevel>()
                        val offersArr = sObj.optJSONArray("offers")
                        if (offersArr != null) {
                            for (oIdx in 0 until offersArr.length()) {
                                val item = offersArr.optJSONArray(oIdx) ?: continue
                                if (item.length() >= 2) {
                                    offers.add(PriceLevel(
                                        price = item.getInt(0),
                                        lot = item.getLong(1),
                                        frequency = if (item.length() > 2) item.getInt(2) else 0
                                    ))
                                }
                            }
                        }

                        list.add(OrderBookSnapshot(
                            ticker = ticker,
                            lastPrice = lastPrice,
                            changePercent = changePercent,
                            timestamp = if (ts > 0) ts else System.currentTimeMillis(),
                            bidLevels = bids,
                            offerLevels = offers,
                            totalBidLot = totalBidLot,
                            totalOfferLot = totalOfferLot,
                            araPrice = araPrice,
                            arbPrice = arbPrice
                        ))
                    }

                    if (list.isNotEmpty()) {
                        resultMap[key] = list
                        totalLoaded += list.size
                    }
                }
            }

            // 2. Deserialize Bandar Detector
            val bandarObj = root.optJSONObject("bandar")
            if (bandarObj != null) {
                val bKeys = bandarObj.keys()
                while (bKeys.hasNext()) {
                    val ticker = bKeys.next()
                    val b = bandarObj.optJSONObject(ticker) ?: continue
                    val ts = b.optLong("ts", 0L)
                    // Pertahankan data bandar jika masih dalam batas 48 jam
                    if (ts > 0 && ts < cutoffTime) continue

                    resultBandar[ticker] = BandarDetectorStat(
                        ticker = b.optString("ticker", ticker),
                        accdistStatus = b.optString("accdist", "Neutral"),
                        averagePrice = b.optDouble("avg", 0.0),
                        amountRupiah = b.optLong("amount", 0L),
                        volumeLot = b.optLong("vol", 0L),
                        topBrokers = b.optString("topBrokers", ""),
                        topConcentration = b.optString("topConc", ""),
                        foreignFlow = b.optString("ff", ""),
                        foreignFlowMultiDay = b.optString("ffMulti", ""),
                        smartMoneySummary = b.optString("sm", ""),
                        lastUpdated = ts
                    )
                }
            }

            Log.d(TAG, "📂 Berhasil memuat $totalLoaded snapshot dari ${resultMap.size} emiten & ${resultBandar.size} data bandar (disaring batas ${maxAgeHours} jam)")

        } catch (e: Exception) {
            Log.e(TAG, "Error saat memuat snapshot dari disk: ${e.message}")
        }

        return LoadedSnapshotData(
            historyMap = resultMap,
            bandarMap = resultBandar,
            totalLoadedCount = totalLoaded,
            tickerCount = resultMap.size,
            savedAt = savedAtTime
        )
    }

    /**
     * Menghapus seluruh snapshot cache dari disk (Reset Manual).
     */
    fun clearSnapshots(context: Context): Boolean {
        return try {
            val primaryFile = File(context.filesDir, FILE_NAME)
            if (primaryFile.exists()) primaryFile.delete()
            val extDir = context.getExternalFilesDir(null)
            if (extDir != null) {
                val backupFile = File(extDir, BACKUP_FILE_NAME)
                if (backupFile.exists()) backupFile.delete()
            }
            Log.d(TAG, "🗑️ Cache snapshot dibersihkan.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menghapus cache snapshot: ${e.message}")
            false
        }
    }
}
