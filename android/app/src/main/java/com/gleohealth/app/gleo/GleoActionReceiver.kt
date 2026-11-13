package com.gleohealth.app.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.helper.NaviHelper
import com.gleohealth.app.AppState
import com.gleohealth.app.engine.GleoHealthEngine
import com.gleohealth.app.engine.Seat
import com.gleohealth.app.engine.SeatState
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gleo Interface 수신기 — assets/schema.json 의 actionCalls 3개 (component = "service" → sendBroadcast).
 * 응답은 Callback Schema 형식으로 ai.pleos.playground.intent.action.APP_ACTION_RESPONSE 에 보낸다 (7초 안).
 * message 는 영어 문장(문서 규칙), 좌석 이름 등 데이터는 한국어 허용.
 */
class GleoActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val params = runCatching { JSONObject(intent.getStringExtra("ACTION_PARAM")?.trim('"') ?: "{}") }.getOrDefault(JSONObject())
        if (action == REST) { openRestAreaSearch(context); return }
        val reply = when (action) {
            CHECK -> checkSeats()
            SCORE -> score(params.optString("period", "trip"))
            else -> Reply("fail", "Unknown action.", null)
        }
        send(context, action, reply)
    }

    /**
     * NaviHelper 에는 휴게소 검색 API 가 없어, 내비 장소 검색 화면을 '휴게소' 키워드로 연다.
     * 운전자가 결과에서 골라 경유지로 넣는다. (자동 검색·경유지 추가는 휴게소 공공데이터 연동 후 — 2차 전 과제)
     */
    private fun openRestAreaSearch(context: Context) {
        val pending = goAsync()
        val navi = NaviHelper(context.applicationContext)
        fun finish(r: Reply) { send(context, REST, r); runCatching { navi.release() }; pending.finish() }
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

    private fun checkSeats(): Reply {
        val seats = AppState.snapshot.seats
        val open = Seat.entries.filter { (seats[it] ?: SeatState()).let { s -> s.occupied && !s.buckled } }
        val occupied = Seat.entries.count { seats[it]?.occupied == true }
        val data = JSONArray(Seat.entries.filter { seats[it]?.occupied == true }.map {
            JSONObject().put("seat", it.label).put("buckled", seats[it]?.buckled == true)
        })
        return if (open.isEmpty()) Reply("success", "All $occupied occupied seats have their seat belts buckled.", data)
        else Reply("success", "Seat belt not buckled: ${open.joinToString { it.label }}.", data)
    }

    private fun score(period: String): Reply {
        val s = GleoHealthEngine.tripScore(AppState.trip)
        return if (period == "month") {
            val m = GleoHealthEngine.monthlyReport(AppState.monthTrips + AppState.trip, consent = false)
            Reply("success", "This month's Gleo Health score is ${m.monthScore}. " + if (m.eligible) "It qualifies for the insurance discount." else "A score of 80 or more qualifies for the discount.",
                JSONArray().put(JSONObject().put("monthScore", m.monthScore).put("eligible", m.eligible)))
        } else {
            Reply("success", "This trip's Gleo Health score is ${s.total}: seat belts ${s.belt}, rest ${s.rest}.",
                JSONArray().put(JSONObject().put("total", s.total).put("belt", s.belt).put("rest", s.rest)))
        }
    }

    private fun send(context: Context, functionName: String, r: Reply) {
        runCatching {
            val body = JSONObject().put("functionName", functionName).put("status", r.status).put("message", r.message)
            r.data?.let { body.put("data", it.toString()) }
            context.sendBroadcast(Intent(RESPONSE).putExtra("packageName", context.packageName).putExtra("response", body.toString()))
        }.onFailure { Log.e("GleoHealth", "Gleo callback failed: $it") }
    }

    companion object {
        const val CHECK = "CHECK_SEAT_AND_BELTS"
        const val SCORE = "MY_SEAT_HEALTH_SCORE"
        const val REST = "FIND_REST_STOP"
        const val RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    }
}
