package com.parmod.ema.training

import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import com.parmod.ema.model.MarketIndex
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream

/** Imports real Upstox contracts.json metadata from a prior archive or individual JSON file. */
class HistoricalContractCatalogImporter(private val store: HistoricalContractCatalogStore) {
    data class Result(
        val filesRead: Int = 0,
        val contractsRead: Int = 0,
        val contractsAdded: Int = 0,
        val niftyExpiries: Int = 0,
        val sensexExpiries: Int = 0,
        val rejectedRows: Int = 0,
        val errors: List<String> = emptyList(),
    )

    fun import(input: InputStream, nameHint: String = ""): Result {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        buffered.mark(8)
        val signature = ByteArray(4)
        val read = buffered.read(signature)
        buffered.reset()
        val zip = read == 4 && signature[0] == 0x50.toByte() && signature[1] == 0x4b.toByte()
        return if (zip) importZip(buffered) else importJson(buffered.readBytesLimited(MAX_SINGLE_JSON_BYTES), nameHint)
    }

    private fun importZip(input: InputStream): Result {
        var filesRead = 0
        var readContracts = 0
        var added = 0
        var rejected = 0
        val errors = mutableListOf<String>()
        val touched = linkedSetOf<Pair<MarketIndex, LocalDate>>()
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (++filesRead > MAX_ZIP_FILES) error("Historical catalogue ZIP contains too many files")
                val normalized = entry.name.replace('\\', '/').lowercase()
                if (!normalized.endsWith("contracts.json")) continue
                val bytes = try {
                    zip.readBytesLimited(MAX_SINGLE_JSON_BYTES)
                } catch (error: Throwable) {
                    errors += "${entry.name}: ${(error.message ?: error::class.java.simpleName).take(180)}"
                    continue
                }
                val parsed = parsePayload(bytes, entry.name)
                readContracts += parsed.read
                rejected += parsed.rejected
                errors += parsed.errors
                parsed.grouped.forEach { (key, contracts) ->
                    added += store.merge(key.first, key.second, contracts)
                    touched += key
                }
            }
        }
        return result(filesRead, readContracts, added, rejected, errors, touched)
    }

    private fun importJson(bytes: ByteArray, nameHint: String): Result {
        val parsed = parsePayload(bytes, nameHint)
        var added = 0
        val touched = linkedSetOf<Pair<MarketIndex, LocalDate>>()
        parsed.grouped.forEach { (key, contracts) ->
            added += store.merge(key.first, key.second, contracts)
            touched += key
        }
        return result(1, parsed.read, added, parsed.rejected, parsed.errors, touched)
    }

    private data class Parsed(
        val grouped: Map<Pair<MarketIndex, LocalDate>, List<UpstoxPlusHistoricalClient.ExpiredContract>>,
        val read: Int,
        val rejected: Int,
        val errors: List<String>,
    )

    private fun parsePayload(bytes: ByteArray, pathHint: String): Parsed {
        return runCatching {
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            val rows = when {
                root.optJSONArray("data") != null -> root.getJSONArray("data")
                root.optJSONArray("contracts") != null -> root.getJSONArray("contracts")
                else -> JSONArray()
            }
            require(rows.length() > 0) { "No option-contract rows found" }
            val grouped = linkedMapOf<Pair<MarketIndex, LocalDate>, MutableList<UpstoxPlusHistoricalClient.ExpiredContract>>()
            var rejected = 0
            for (i in 0 until rows.length()) {
                val item = rows.optJSONObject(i)
                if (item == null) {
                    rejected++
                    continue
                }
                val contract = HistoricalContractCatalogStore.parseContract(item)
                val market = HistoricalContractCatalogStore.inferMarket(item, pathHint)
                if (contract == null || market == null) {
                    rejected++
                    continue
                }
                grouped.getOrPut(market to contract.expiry) { mutableListOf() } += contract
            }
            Parsed(grouped, rows.length(), rejected, emptyList())
        }.getOrElse {
            Parsed(emptyMap(), 0, 0, listOf("${pathHint.ifBlank { "contracts.json" }}: ${(it.message ?: it::class.java.simpleName).take(180)}"))
        }
    }

    private fun result(
        filesRead: Int,
        read: Int,
        added: Int,
        rejected: Int,
        errors: List<String>,
        touched: Set<Pair<MarketIndex, LocalDate>>,
    ) = Result(
        filesRead = filesRead,
        contractsRead = read,
        contractsAdded = added,
        niftyExpiries = touched.count { it.first == MarketIndex.NIFTY },
        sensexExpiries = touched.count { it.first == MarketIndex.SENSEX },
        rejectedRows = rejected,
        errors = errors.distinct().takeLast(50),
    )

    private fun InputStream.readBytesLimited(maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val n = read(buffer)
            if (n <= 0) break
            total += n
            require(total <= maxBytes) { "contracts.json exceeds ${maxBytes / (1024 * 1024)} MB safety limit" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_SINGLE_JSON_BYTES = 16 * 1024 * 1024
        private const val MAX_ZIP_FILES = 25_000
    }
}
