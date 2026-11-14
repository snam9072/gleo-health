package com.gleohealth.app.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.gleohealth.app.PleosSourceFactory
import com.gleohealth.app.engine.Event
import com.gleohealth.app.engine.GleoHealthEngine
import com.gleohealth.app.engine.Signal
import com.gleohealth.app.engine.Trip
import com.gleohealth.app.store.KeystoreSealer
import com.gleohealth.app.store.Ledger
import com.gleohealth.app.store.TripStore
import com.gleohealth.app.vehicle.ManualVehicleSource
import com.gleohealth.app.vehicle.VehicleSnapshot
import com.gleohealth.app.vehicle.VehicleSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 앱 프로세스 안의 단 하나의 기록 코어 (P2·P3).
 * 화면·백그라운드 서비스·Gleo 수신기가 모두 이것을 본다 — 화면이 닫혀도 서비스가 1분마다 tick 을 부르고,
 * 프로세스가 새로 떠도 저장소에서 이번 달 기록을 다시 읽는다. 모든 호출은 메인 스레드에서 한다.
 */
object HealthCore {
    private var store: TripStore? = null
    var ledger: Ledger = Ledger(month())
        private set
    var snapshot: VehicleSnapshot = VehicleSnapshot()
        private set

    /** 차량 SDK 를 못 쓰는 환경(시연)에서만 쓰는 수동 신호. */
    val manual = ManualVehicleSource()
    private var source: VehicleSource? = null
    val usingManual get() = source === manual
    val sourceRunning get() = source != null

    @Volatile var uiVisible = false

    private val stateListeners = mutableSetOf<() -> Unit>()
    private val eventListeners = mutableSetOf<(Event) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun month(now: Date = Date()): String = SimpleDateFormat("yyyy-MM", Locale.US).format(now)

    /** 여러 번 불러도 한 번만 저장소를 연다. */
    @Synchronized
    fun init(context: Context) {
        if (store != null) return
        val s = TripStore(File(context.applicationContext.filesDir, "trips.bin"), KeystoreSealer()) { month() }
        store = s
        ledger = s.load()
    }

    /** 차량 신호 구독 시작(서비스가 부른다). Pleos SDK 가 없으면 수동 신호. */
    fun startSource(context: Context) {
        if (source != null) return
        val src = PleosSourceFactory.createOrNull(context.applicationContext) ?: manual
        source = src
        src.start { snap -> main.post { snapshot = snap; notifyState() } }
    }

    fun stopSource() {
        source?.stop()
        source = null
    }

    fun signal(minutes: Int) = Signal(minutes, snapshot.speedKmh, snapshot.gear, snapshot.seats)

    /** 1분(또는 시연 빨리 감기 n분) 기록. 달이 바뀌면 저장소 규칙대로 넘긴다. */
    fun tick(minutes: Int) {
        var l = TripStore.rollOver(ledger, month())
        val events = mutableListOf<Event>()
        var trip = l.current
        repeat(minutes) {
            val r = GleoHealthEngine.tick(trip, signal(1))
            trip = r.trip
            events += r.events
        }
        l = l.copy(current = trip)
        commit(l)
        events.forEach { e -> eventListeners.forEach { it(e) } }
    }

    /** 주행을 끝내고 새 주행 시작(시연 버튼). */
    fun newTrip() {
        val l = ledger
        if (l.current.minutes == 0) return
        commit(l.copy(current = Trip(), finished = l.finished + com.gleohealth.app.store.StoredTrip(l.month, l.current)))
    }

    fun setConsent(on: Boolean) = commit(ledger.copy(consent = on))

    private fun commit(l: Ledger) {
        ledger = l
        runCatching { store?.save(l) } // 저장 실패해도 기록은 메모리에 남고 다음 tick 에 다시 저장한다
        notifyState()
    }

    private fun notifyState() = stateListeners.toList().forEach { it() }

    fun addStateListener(l: () -> Unit) { stateListeners += l }
    fun removeStateListener(l: () -> Unit) { stateListeners -= l }
    fun addEventListener(l: (Event) -> Unit) { eventListeners += l }
    fun removeEventListener(l: (Event) -> Unit) { eventListeners -= l }

    /**
     * Gleo 수신기용: 신호 구독이 없으면 차량 SDK 로 한 번만 읽고 바로 끊는다(3초 안). 못 읽으면 null.
     * 시연용 수동 신호가 돌고 있으면 그 값.
     */
    fun readSnapshotOnce(context: Context, timeoutMs: Long, done: (VehicleSnapshot?) -> Unit) {
        if (source != null) { done(snapshot); return }
        val src = PleosSourceFactory.createOrNull(context.applicationContext)
        if (src == null) { done(null); return }
        var finished = false
        fun finish(v: VehicleSnapshot?) {
            if (finished) return
            finished = true
            runCatching { src.stop() }
            done(v)
        }
        // 좌석 구독 콜백이 차례로 오므로 잠깐 모아서 답한다. 첫 값은 '아직 모름' 초기값이라 실제 콜백이 1번 이상 와야 답한다.
        var latest: VehicleSnapshot? = null
        var updates = 0
        runCatching { src.start { snap -> main.post { latest = snap; updates++ } } }.onFailure { finish(null); return }
        main.postDelayed({ if (updates > 1) finish(latest) }, minOf(timeoutMs, SEAT_SETTLE_MS))
        main.postDelayed({ finish(if (updates > 1) latest else null) }, timeoutMs)
    }

    private const val SEAT_SETTLE_MS = 1_500L
}
