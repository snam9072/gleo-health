package com.gleohealth.app.store

import com.gleohealth.app.engine.Seat
import com.gleohealth.app.engine.SeatMinutes
import com.gleohealth.app.engine.Trip
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 기기 안 기록 저장소 (P1·S4, 명세 D21).
 * - 저장하는 것은 '주행 요약'(Trip: 좌석별 착석·체결 분, 연속 운전 분 등)과 월 표시뿐. 초 단위 원자료·위치·시각은 없다.
 * - 파일 전체를 AES-GCM 으로 암호화한다. 키는 Android Keystore 안에 있어 앱 밖으로 꺼낼 수 없다(KeystoreSealer).
 * - 파일을 못 열면(깨짐·위변조·Keystore 오류) 한 번 더 시도하고, 그래도 안 되면 원본을 `trips.bin.bad-<시각>` 으로 옮겨 보존한 뒤
 *   빈 기록으로 시작한다 — 빈 기록이 원본을 덮어쓰지 않는다(감사 P1-1).
 * - 13개월 넘게 지난(14개월째) 주행은 불러올 때 지운다(명세 D21 예시). 시계가 뒤로 가거나 24개월 넘게 앞으로 튀면 아무것도 지우지 않는다(P1-2).
 */
data class StoredTrip(val month: String, val trip: Trip)

data class Ledger(
    val month: String,
    val current: Trip = Trip(),
    val finished: List<StoredTrip> = emptyList(),
    val consent: Boolean = false,
) {
    /** 이번 달 점수 계산에 쓰는 주행들 (끝난 것 + 지금 것). */
    fun monthTrips(): List<Trip> = finished.filter { it.month == month }.map { it.trip } + current
}

/** 암호화·복호화. 실제 앱은 KeystoreSealer, 단위 테스트는 소프트웨어 키. */
interface Sealer {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/** 형식: [버전 1바이트][IV 12바이트][암호문+인증 태그 16바이트]. IV 는 저장할 때마다 새로 만든다(GCM 규칙). */
open class AesGcmSealer(private val key: () -> SecretKey) : Sealer {
    override fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key()) // IV 는 제공자가 무작위로 만든다
        val iv = c.iv
        require(iv.size == IV_LEN) { "unexpected iv" }
        return byteArrayOf(VERSION) + iv + c.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > 1 + IV_LEN && sealed[0] == VERSION) { "bad format" }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 1, IV_LEN))
        return c.doFinal(sealed, 1 + IV_LEN, sealed.size - 1 - IV_LEN)
    }

    companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val TAG_BITS = 128
        const val VERSION: Byte = 1
    }
}

class TripStore(
    private val file: File,
    private val sealer: Sealer,
    private val nowMonth: () -> String,
) {
    /** 불러오기. 없거나 깨졌으면 빈 기록. 달이 바뀌었으면 지난 주행을 끝난 주행으로 넘긴다. */
    fun load(): Ledger {
        val month = nowMonth()
        if (!file.exists()) return Ledger(month)
        val read = { LedgerCodec.decode(String(sealer.open(file.readBytes()), Charsets.UTF_8)) }
        val raw = runCatching(read).recoverCatching { read() }.getOrNull()
        if (raw == null) { quarantine(); return Ledger(month) }
        return rollOver(raw, month)
    }

    /** 못 연 파일을 지우지 않고 옆으로 옮겨 둔다(나중에 키가 돌아오면 복구할 수 있게). */
    private fun quarantine() {
        runCatching { file.renameTo(File(file.parentFile, "${file.name}.bad-${System.currentTimeMillis()}-${System.nanoTime()}")) }
    }

    /** 저장. 암호문만 임시 파일에 쓰고 이름을 바꿔 교체한다(쓰는 중 꺼져도 이전 파일이 남는다). */
    fun save(ledger: Ledger) {
        val bytes = sealer.seal(LedgerCodec.encode(ledger).toByteArray(Charsets.UTF_8))
        val tmp = File(file.parentFile, file.name + ".new")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() } // 디스크에 쓴 뒤에 바꾼다(P2-2)
        runCatching { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            .getOrElse { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    companion object {
        const val KEEP_MONTHS = 13
        const val MAX_CLOCK_JUMP_MONTHS = 24

        fun rollOver(l: Ledger, month: String): Ledger {
            // 차량 시계가 부팅 직후 2000-01 처럼 되돌아가거나 터무니없이 앞으로 가면 기록을 건드리지 않는다(P1-2)
            val gap = monthsBetween(l.month, month)
            if (gap < 0 || gap > MAX_CLOCK_JUMP_MONTHS) return l
            var out = l
            if (l.month != month) {
                val done = if (l.current.minutes > 0) l.finished + StoredTrip(l.month, l.current) else l.finished
                out = l.copy(month = month, current = Trip(), finished = done)
            }
            return out.copy(finished = out.finished.filter { monthsBetween(it.month, month) in 0..KEEP_MONTHS })
        }

        /** "2025-10" → "2026-11" = 13 */
        fun monthsBetween(from: String, to: String): Int {
            fun idx(m: String): Int { val (y, mo) = m.split("-").map(String::toInt); return y * 12 + (mo - 1) }
            return runCatching { idx(to) - idx(from) }.getOrDefault(Int.MAX_VALUE)
        }
    }
}

/**
 * 사람이 읽지 않는 짧은 줄 형식(외부 라이브러리 없이 JVM 단위 테스트 가능).
 * v1 / m|<달>|<동의 0·1> / c|<주행> / f|<달>|<주행>
 * 주행 = minutes,continuousMin,parkMin,overLimitMin,(좌석별 occupied,buckled ×4),announced 비트,플래그 비트
 */
object LedgerCodec {
    fun encode(l: Ledger): String = buildString {
        append("v1\n")
        append("m|").append(l.month).append('|').append(if (l.consent) 1 else 0).append('\n')
        append("c|").append(trip(l.current)).append('\n')
        for (f in l.finished) append("f|").append(f.month).append('|').append(trip(f.trip)).append('\n')
    }

    fun decode(s: String): Ledger {
        val lines = s.split('\n').filter { it.isNotEmpty() }
        require(lines.firstOrNull() == "v1") { "version" }
        var month = ""; var consent = false; var current = Trip(); val finished = mutableListOf<StoredTrip>()
        for (line in lines.drop(1)) {
            val p = line.split('|')
            when (p[0]) {
                "m" -> { month = p[1]; consent = p[2] == "1" }
                "c" -> current = parseTrip(p[1])
                "f" -> finished += StoredTrip(p[1], parseTrip(p[2]))
                else -> error("line")
            }
        }
        require(Regex("""\d{4}-\d{2}""").matches(month)) { "month" }
        return Ledger(month, current, finished, consent)
    }

    private fun trip(t: Trip): String {
        val seats = Seat.entries.flatMap { listOf(t.seatMin.getValue(it).occupied, t.seatMin.getValue(it).buckled) }
        val announced = Seat.entries.foldIndexed(0) { i, acc, s -> if (s in t.announced) acc or (1 shl i) else acc }
        val flags = listOf(t.restSuggested, t.restTaken, t.postureOffered, t.ergoTipGiven)
            .foldIndexed(0) { i, acc, b -> if (b) acc or (1 shl i) else acc }
        return (listOf(t.minutes, t.continuousMin, t.parkMin, t.overLimitMin) + seats + listOf(announced, flags)).joinToString(",")
    }

    private fun parseTrip(s: String): Trip {
        val n = s.split(',').map(String::toInt)
        require(n.size == 4 + Seat.entries.size * 2 + 2 && n.all { it >= 0 }) { "trip" }
        val seatMin = Seat.entries.withIndex().associate { (i, seat) -> seat to SeatMinutes(n[4 + i * 2], n[5 + i * 2]) }
        val a = n[4 + Seat.entries.size * 2]; val f = n[5 + Seat.entries.size * 2]
        fun bit(v: Int, i: Int) = v and (1 shl i) != 0
        return Trip(
            minutes = n[0], continuousMin = n[1], parkMin = n[2], overLimitMin = n[3], seatMin = seatMin,
            announced = Seat.entries.filterIndexed { i, _ -> bit(a, i) }.toSet(),
            restSuggested = bit(f, 0), restTaken = bit(f, 1), postureOffered = bit(f, 2), ergoTipGiven = bit(f, 3),
        )
    }
}
