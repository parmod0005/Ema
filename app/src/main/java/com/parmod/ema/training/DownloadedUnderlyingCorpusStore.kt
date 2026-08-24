package com.parmod.ema.training

import android.content.Context
import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import com.parmod.ema.model.MarketIndex
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Verified day-sharded 1-minute NIFTY/SENSEX history for causal historical signal reconstruction. */
class DownloadedUnderlyingCorpusStore(context: Context) {
    data class SaveResult(val addedRows: Int, val totalRows: Int, val days: Int)

    data class Coverage(
        val rows: Long = 0L,
        val fromDate: LocalDate? = null,
        val toDate: LocalDate? = null,
    )

    private data class DayData(
        val index: MarketIndex,
        val date: LocalDate,
        val candles: List<UpstoxPlusHistoricalClient.Candle>,
    )

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "vardhani_downloaded_historical/v1/underlying").apply { mkdirs() }
    private val temp = File(appContext.filesDir, "vardhani_downloaded_historical/v1/tmp_underlying").apply { mkdirs() }

    @Synchronized
    fun save(index: MarketIndex, candles: List<UpstoxPlusHistoricalClient.Candle>): SaveResult {
        val clean = candles.asSequence()
            .filter { minOf(it.open, it.high, it.low, it.close) > 0.0 }
            .sortedBy { it.time.toInstant().toEpochMilli() }
            .distinctBy { it.time.toInstant().toEpochMilli() }
            .toList()
        if (clean.isEmpty()) return SaveResult(0, 0, 0)
        var added = 0
        var total = 0
        var days = 0
        clean.groupBy { it.time.toLocalDate() }.toSortedMap().forEach { (date, incoming) ->
            val target = file(index, date)
            val old = if (target.isFile) runCatching { readDay(target) }.getOrNull()?.candles.orEmpty() else emptyList()
            val merged = (old + incoming).sortedBy { it.time.toInstant().toEpochMilli() }
                .distinctBy { it.time.toInstant().toEpochMilli() }
            added += (merged.size - old.size).coerceAtLeast(0)
            total += merged.size
            writeDay(index, date, merged, target)
            days++
        }
        return SaveResult(added, total, days)
    }

    fun load(index: MarketIndex, fromDate: LocalDate, toDate: LocalDate): List<UpstoxPlusHistoricalClient.Candle> {
        require(!fromDate.isAfter(toDate))
        return files(index)
            .mapNotNull { file -> runCatching { readDay(file) }.getOrNull() }
            .filter { !it.date.isBefore(fromDate) && !it.date.isAfter(toDate) }
            .flatMap { it.candles }
            .sortedBy { it.time.toInstant().toEpochMilli() }
            .distinctBy { it.time.toInstant().toEpochMilli() }
    }

    fun hasUsableRange(index: MarketIndex, fromDate: LocalDate, toDate: LocalDate): Boolean {
        val bars = load(index, fromDate, toDate)
        if (bars.size < MIN_USABLE_BARS) return false
        val first = bars.first().time.toLocalDate()
        val last = bars.last().time.toLocalDate()
        return !first.isAfter(fromDate.plusDays(3)) && !last.isBefore(toDate.minusDays(3))
    }

    fun rows(index: MarketIndex? = null): Long =
        (index?.let(::files) ?: MarketIndex.entries.flatMap(::files)).sumOf { file ->
            runCatching { readHeaderCount(file).toLong() }.getOrDefault(0L)
        }

    fun coverage(index: MarketIndex): Coverage {
        val days = files(index).mapNotNull { file ->
            runCatching { readDay(file) }.getOrNull()
        }
        if (days.isEmpty()) return Coverage()
        return Coverage(
            rows = days.sumOf { it.candles.size.toLong() },
            fromDate = days.minOf { it.date },
            toDate = days.maxOf { it.date },
        )
    }

    @Synchronized
    fun clear() {
        root.deleteRecursively()
        temp.deleteRecursively()
        root.mkdirs()
        temp.mkdirs()
    }

    private fun writeDay(
        index: MarketIndex,
        date: LocalDate,
        candles: List<UpstoxPlusHistoricalClient.Candle>,
        target: File,
    ) {
        require(candles.isNotEmpty())
        val tmp = File(temp, target.name + ".${System.nanoTime()}.tmp")
        DataOutputStream(FileOutputStream(tmp).buffered(BUFFER)).use { out ->
            out.writeUTF(MAGIC)
            out.writeUTF(index.name)
            out.writeUTF(date.toString())
            out.writeInt(candles.size)
            candles.forEach { c ->
                out.writeLong(c.time.toInstant().toEpochMilli())
                out.writeInt(c.time.offset.totalSeconds)
                out.writeDouble(c.open); out.writeDouble(c.high); out.writeDouble(c.low); out.writeDouble(c.close)
                out.writeLong(c.volume); out.writeLong(c.openInterest)
            }
        }
        val verified = runCatching { readDay(tmp) }.getOrNull()
        require(verified != null && verified.index == index && verified.date == date && verified.candles.size == candles.size) {
            "Underlying historical day verification failed"
        }
        replace(tmp, target)
    }

    private fun readDay(file: File): DayData = DataInputStream(FileInputStream(file).buffered(BUFFER)).use { input ->
        require(input.readUTF() == MAGIC)
        val index = MarketIndex.valueOf(input.readUTF())
        val date = LocalDate.parse(input.readUTF())
        val count = input.readInt()
        require(count in 1..MAX_ROWS_PER_DAY)
        val rows = ArrayList<UpstoxPlusHistoricalClient.Candle>(count)
        var previous = Long.MIN_VALUE
        repeat(count) {
            val epoch = input.readLong()
            require(epoch >= previous)
            previous = epoch
            val offset = input.readInt().coerceIn(-64_800, 64_800)
            val o = input.readDouble(); val h = input.readDouble(); val l = input.readDouble(); val c = input.readDouble()
            require(minOf(o, h, l, c) > 0.0)
            val volume = input.readLong().coerceAtLeast(0L); val oi = input.readLong().coerceAtLeast(0L)
            val candle = UpstoxPlusHistoricalClient.Candle(
                Instant.ofEpochMilli(epoch).atOffset(ZoneOffset.ofTotalSeconds(offset)), o, h, l, c, volume, oi,
            )
            require(candle.time.toLocalDate() == date)
            rows += candle
        }
        require(input.read() == -1)
        DayData(index, date, rows)
    }

    private fun readHeaderCount(file: File): Int = DataInputStream(FileInputStream(file).buffered(BUFFER)).use { input ->
        require(input.readUTF() == MAGIC)
        input.readUTF(); input.readUTF()
        input.readInt().also { require(it in 1..MAX_ROWS_PER_DAY) }
    }

    private fun replace(source: File, target: File) {
        val atomic = runCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.isSuccess
        if (!atomic) Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun files(index: MarketIndex): List<File> =
        root.listFiles { f -> f.isFile && f.name.startsWith("${index.name}_") && f.extension == EXT }?.sortedBy { it.name }.orEmpty()

    private fun file(index: MarketIndex, date: LocalDate): File =
        File(root, "${index.name}_${date}_${sha("${index.name}|$date").take(12)}.$EXT")

    private fun sha(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAGIC = "VARDHANI-UNDERLYING-1M-V1"
        private const val EXT = "vhu"
        private const val BUFFER = 64 * 1024
        private const val MAX_ROWS_PER_DAY = 2_000
        private const val MIN_USABLE_BARS = 60
    }
}
