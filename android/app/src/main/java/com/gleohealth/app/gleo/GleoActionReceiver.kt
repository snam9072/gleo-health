package com.gleohealth.app.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.helper.NaviHelper
import com.gleohealth.app.BuildConfig
import com.gleohealth.app.core.HealthCore
import com.gleohealth.app.engine.GleoHealthEngine
import com.gleohealth.app.engine.Seat
import com.gleohealth.app.engine.SeatState
import com.gleohealth.app.vehicle.VehicleSnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gleo Interface 수신기 — assets/schema.json 의 actionCalls 3개 (component = "service" → Gleo 가 우리 패키지를 지정해 sendBroadcast).
 * 응답은 Callback Schema 형식으로 ai.pleos.playground.intent.action.APP_ACTION_RESPONSE (7초 안, message 는 영어).
 *
 * P3: 화면 상태를 빌리지 않고 HealthCore(암호화 저장소)를 직접 읽는다 → 앱이 안 떠 있어도 점수를 답한다.
 * S1·S2: GleoGuard 규칙 — 보낸 앱 확인(가능할 때), 2초 빈도 제한, 응답은 시스템 앱 수신기에만, data 는 최소값.
 */
class GleoActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACTIONS) return
        if (!GleoGuard.acceptSender(senderOf(context), BuildConfig.GLEO_STRICT_SENDER)) return
        val senderKey = sentFromUid.takeIf { it >= 0 }?.toString() ?: "unknown"
        if (!GleoGuard.allow(action, senderKey, SystemClock.elapsedRealtime())) {
            send(context, action, Reply("fail", "Please try again in a moment.", null)) // 말없이 버리지 않는다(감사 P1-7)
            return
        }
        HealthCore.init(context)
        val params = GleoGuard.parseParams(intent.getStringExtra("ACTION_PARAM")) ?: JSONObject()
        when (action) {
            REST -> openRestAreaSearch(context)
            SCORE -> send(context, SCORE, score(params.optString("period", "trip")))
            CHECK -> {
                val pending = goAsync()
                HealthCore.readSnapshotOnce(context, READ_TIMEOUT_MS) { snap ->
                    send(context, CHECK, if (snap == null) Reply("fail", "Seat sensors could not be read right now.", null) else checkSeats(snap))
                    pending.finish()
                }
            }
        }
    }

    /** 보낸 앱이 신원을 공유했으면 그 앱이 시스템 앱인지 본다(minSdk 34 라 모든 지원 기기에서 확인). 공유 안 했으면 알 수 없음. */
    private fun senderOf(context: Context): GleoGuard.Sender {
        val pkg = sentFromPackage ?: return GleoGuard.Sender.Unknown
        // 패키지 정보를 못 읽으면(가시성 제한 등) '모름'으로 둔다 — Gleo 를 잘못 막지 않기 위해
        val system = isSystemPackage(context.packageManager, pkg) ?: return GleoGuard.Sender.Unknown
        return GleoGuard.Sender.Known(system)
    }

    /**
     * NaviHelper 에는 휴게소 검색 API 가 없어, 내비 장소 검색 화면을 '휴게소' 키워드로 연다.
     * 운전자가 결과에서 골라 경유지로 넣는다.
     */
    private fun openRestAreaSearch(context: Context) {
        val pending = goAsync()
        val navi = NaviHelper(context.applicationContext)
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish(r: Reply) {
            if (!done.compareAndSet(false, true)) return
            send(context, REST, r); runCatching { navi.release() }; pending.finish()
        }
        // NaviHelper 가 답하지 않아도 5초 안에 끝낸다 — Gleo 7초 제한·브로드캐스트 ANR 방지(감사 P1-8)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finish(Reply("fail", "Navigation did not respond in time.", null)) }, REST_TIMEOUT_MS)
        runCatching {
            navi.initialize(
                {
                    runCatching { navi.requestNavigationScreen(NavigationScreenRequest.SearchPlace("휴게소")) }
                        .onSuccess { finish(Reply("success", "Opened the navigation search for rest areas so the driver can pick one as a stop.", null)) }
                        .onFailure { finish(Reply("fail", "Could not open the navigation search.", null)) }
                },
                { finish(Reply("fail", "Navigation is not available right now.", null)) },
            )
        }.onFailure { finish(Reply("fail", "Navigation is not available right now.", null)) }
    }

    data class Reply(val status: String, val message: String, val data: JSONArray?)

    private fun checkSeats(snap: VehicleSnapshot): Reply = checkSeatsRaw(snap).let { r ->
        // 시연 신호로 답할 때는 실제 차 값이 아님을 밝힌다(감사 P2-6)
        if (HealthCore.usingManual) r.copy(message = "Demo signals: " + r.message) else r
    }

    private fun checkSeatsRaw(snap: VehicleSnapshot): Reply {
        val seats = snap.seats
        val open = Seat.entries.filter { (seats[it] ?: SeatState()).let { s -> s.occupied && !s.buckled } }
        val occupied = Seat.entries.count { seats[it]?.occupied == true }
        // 최소값: 착석한 좌석의 이름과 체결 여부만
        val data = JSONArray(Seat.entries.filter { seats[it]?.occupied == true }.map {
            JSONObject().put("seat", it.label).put("buckled", seats[it]?.buckled == true)
        })
        return if (open.isEmpty()) Reply("success", "All $occupied occupied seats have their seat belts buckled.", data)
        else Reply("success", "Seat belt not buckled: ${open.joinToString { it.label }}.", data)
    }

    private fun score(period: String): Reply {
        val l = HealthCore.currentLedger()
        return if (period == "month") {
            val m = GleoHealthEngine.monthlyReport(l.monthTrips(), consent = false)
            Reply("success", "This month's Gleo Health score is ${m.monthScore}. " + if (m.eligible) "It qualifies for the insurance discount." else "A score of 80 or more qualifies for the discount.",
                JSONArray().put(JSONObject().put("monthScore", m.monthScore).put("eligible", m.eligible)))
        } else {
            val s = GleoHealthEngine.tripScore(l.current)
            Reply("success", "This trip's Gleo Health score is ${s.total}: seat belts ${s.belt}, rest ${s.rest}.",
                JSONArray().put(JSONObject().put("total", s.total).put("belt", s.belt).put("rest", s.rest)))
        }
    }

    private fun send(context: Context, functionName: String, r: Reply) {
        runCatching {
            val body = JSONObject().put("functionName", functionName).put("status", r.status).put("message", r.message)
            r.data?.let { body.put("data", it.toString()) }
            val base = Intent(RESPONSE).putExtra("packageName", context.packageName).putExtra("response", body.toString())
            val targets = GleoGuard.responseTargets(systemResponseReceivers(context), context.packageName)
            if (targets == null) {
                // 받는 앱을 못 찾으면 공식 예제대로 보내되, 다른 앱이 엿들어도 쓸 수 있는 data(좌석·점수 값)는 빼고 문장만(감사 P1-6)
                val minimal = JSONObject().put("functionName", functionName).put("status", r.status).put("message", r.message)
                context.sendBroadcast(Intent(RESPONSE).putExtra("packageName", context.packageName).putExtra("response", minimal.toString()))
            } else targets.forEach { context.sendBroadcast(Intent(base).setPackage(it)) }
        }
    }

    private fun systemResponseReceivers(context: Context): List<String> {
        val pm = context.packageManager
        val found = runCatching { pm.queryBroadcastReceivers(Intent(RESPONSE), PackageManager.ResolveInfoFlags.of(0)) }.getOrDefault(emptyList())
        return found.mapNotNull { it.activityInfo?.packageName }.filter { isSystemPackage(pm, it) == true }
    }

    /** 시스템 앱이면 true, 아니면 false, 정보를 못 읽으면 null. */
    private fun isSystemPackage(pm: PackageManager, pkg: String): Boolean? = runCatching {
        val ai = pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }.getOrNull()

    companion object {
        const val CHECK = "CHECK_SEAT_AND_BELTS"
        const val SCORE = "MY_SEAT_HEALTH_SCORE"
        const val REST = "FIND_REST_STOP"
        val ACTIONS = setOf(CHECK, SCORE, REST)
        const val RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
        private const val READ_TIMEOUT_MS = 3_000L
        private const val REST_TIMEOUT_MS = 5_000L
    }
}
