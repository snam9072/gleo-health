package com.gleohealth.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.gleohealth.app.engine.Event
import com.gleohealth.app.engine.GleoHealthEngine
import com.gleohealth.app.engine.Gear
import com.gleohealth.app.engine.Rules
import com.gleohealth.app.engine.Seat
import com.gleohealth.app.engine.SeatState
import com.gleohealth.app.engine.Signal
import com.gleohealth.app.engine.Trip
import com.gleohealth.app.vehicle.ManualVehicleSource
import com.gleohealth.app.vehicle.VehicleSnapshot
import com.gleohealth.app.vehicle.VehicleSource
import com.gleohealth.app.voice.Voice

/**
 * 글리오 헬스 메인 화면. Pleos 다크 토큰(res/values/colors.xml)만 쓴다.
 * 주행 중엔 음성 1회 + 상태 배지만, 점수는 정차 중에만 (명세 D9).
 */
class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var source: VehicleSource
    private val manual = ManualVehicleSource()
    private lateinit var voice: Voice

    private var snapshot = VehicleSnapshot()
    private var trip = Trip()
    private val monthTrips = mutableListOf<Trip>()
    private var consent = false

    // views
    private lateinit var bubble: TextView
    private lateinit var driveState: TextView
    private lateinit var restText: TextView
    private lateinit var restBar: ProgressBar
    private lateinit var scoreText: TextView
    private lateinit var beltSummary: TextView
    private val seatViews = mutableMapOf<Seat, TextView>()

    private fun c(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voice = Voice(this)
        source = PleosSourceFactory.createOrNull(this) ?: manual
        setContentView(buildUi())
        source.start { snap -> snapshot = snap; AppState.snapshot = snap; runOnUiThread { render() } }
        handler.postDelayed(minuteTicker, 60_000)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        source.stop()
        voice.release()
        super.onDestroy()
    }

    private val minuteTicker = object : Runnable {
        override fun run() { advance(1); handler.postDelayed(this, 60_000) }
    }

    private fun signal(min: Int) = Signal(min, snapshot.speedKmh, snapshot.gear, snapshot.seats)

    private fun advance(minutes: Int) {
        repeat(minutes) {
            val r = GleoHealthEngine.tick(trip, signal(1))
            trip = r.trip
            r.events.forEach(::announce)
        }
        AppState.trip = trip
        render()
    }

    private fun announce(e: Event) {
        bubble.text = e.text
        bubble.visibility = View.VISIBLE
        voice.speakOnce(e.text)
    }

    // ---------------- UI ----------------
    private fun card(title: String, content: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(18))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(c(R.color.surface_basic)); setStroke(dp(1), c(R.color.surface_high)) }
        addView(TextView(context).apply { text = title; setTextColor(c(R.color.text_secondary)); textSize = 16f })
        addView(content)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(16)); setBackgroundColor(c(R.color.background_secondary)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply { text = getString(R.string.app_name); textSize = 28f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        header.addView(TextView(this).apply { text = "  " + getString(R.string.app_sub); setTextColor(c(R.color.text_secondary)); textSize = 16f })
        driveState = TextView(this).apply { textSize = 15f; setPadding(dp(12), dp(6), dp(12), dp(6)); background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(c(R.color.surface_accent)) } }
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(driveState)
        root.addView(header)

        bubble = TextView(this).apply {
            textSize = 20f; setTextColor(Color.WHITE); setPadding(dp(22), dp(14), dp(22), dp(14)); visibility = View.GONE
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c(R.color.gleo_bubble_primary), c(R.color.gleo_bubble_secondary))).apply { cornerRadius = dp(22).toFloat() }
        }
        root.addView(bubble, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // 좌석 카드
        val grid = GridLayout(this).apply { columnCount = 2; useDefaultMargins = true }
        for (seat in Seat.entries) {
            val v = TextView(this).apply { text = seat.label; gravity = Gravity.CENTER; textSize = 16f; minWidth = dp(130); minHeight = dp(90) }
            seatViews[seat] = v
            grid.addView(v)
        }
        beltSummary = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        val seatBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; addView(grid); addView(beltSummary) }
        body.addView(card(getString(R.string.seats_title), seatBox), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(16) })

        // 휴식 + 점수
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        restText = TextView(this).apply { textSize = 22f }
        restBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = Rules.REST_SUGGEST_AT_MIN }
        right.addView(card(getString(R.string.rest_title), LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(restText); addView(restBar) }))
        scoreText = TextView(this).apply { textSize = 20f; setPadding(0, dp(6), 0, 0) }
        val consentBox = CheckBox(this).apply { text = getString(R.string.consent); setTextColor(c(R.color.text_secondary)); setOnCheckedChangeListener { _, on -> consent = on; render() } }
        right.addView(card(getString(R.string.score_title), LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(scoreText); addView(consentBox) }),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        body.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        if (source === manual) root.addView(buildSimPanel())
        return ScrollView(this).apply { addView(root) }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(c(R.color.background_secondary))
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(c(R.color.button_filled_enabled)) }
        setOnClickListener { onClick() }
    }

    /** 시연용 신호 패널 — Pleos Vehicle SDK 를 못 쓰는 환경에서만 보인다. */
    private fun buildSimPanel(): View {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(16), 0, 0) }
        p.addView(TextView(this).apply { text = getString(R.string.sim_title); setTextColor(c(R.color.text_secondary)) })
        val rowSeats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (seat in Seat.entries) {
            rowSeats.addView(button("${seat.label} 착석") { toggle(seat, occupied = true) })
            rowSeats.addView(button("벨트") { toggle(seat, occupied = false) })
        }
        p.addView(HorizontalWrap(rowSeats))
        val rowDrive = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowDrive.addView(button("P 정차") { manual.update { it.copy(gear = Gear.P, speedKmh = 0) } })
        rowDrive.addView(button("D 60km/h") { manual.update { it.copy(gear = Gear.D, speedKmh = 60) } })
        for (m in listOf(1, 10, 15, 60)) rowDrive.addView(button("+${m}분") { advance(m) })
        rowDrive.addView(button("새 주행") { monthTrips += trip; AppState.monthTrips += trip; trip = Trip(); AppState.trip = trip; bubble.visibility = View.GONE; render() })
        p.addView(HorizontalWrap(rowDrive))
        return p
    }

    private fun HorizontalWrap(v: View) = android.widget.HorizontalScrollView(this).apply { addView(v) }

    private fun toggle(seat: Seat, occupied: Boolean) = manual.update { snap ->
        val cur = snap.seats[seat] ?: SeatState()
        val next = if (occupied) cur.copy(occupied = !cur.occupied, buckled = if (cur.occupied) false else cur.buckled)
        else cur.copy(buckled = !cur.buckled, occupied = cur.occupied || !cur.buckled)
        snap.copy(seats = snap.seats + (seat to next))
    }

    private fun render() {
        val moving = snapshot.speedKmh > 0 && snapshot.gear != Gear.P
        driveState.text = if (moving) "주행 중 · ${snapshot.gear} · ${snapshot.speedKmh}km/h" else "정차 · ${snapshot.gear}"
        var need = 0; var occ = 0
        for ((seat, v) in seatViews) {
            val st = snapshot.seats[seat] ?: SeatState()
            val (bg, stroke) = when {
                !st.occupied -> c(R.color.surface_high) to c(R.color.surface_high)
                st.buckled -> Color.argb(36, 0, 224, 116) to c(R.color.informative_active)
                else -> Color.argb(40, 255, 76, 40) to c(R.color.informative_negative)
            }
            v.background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(bg); setStroke(dp(2), stroke) }
            v.setTextColor(if (st.occupied) Color.WHITE else c(R.color.basic_300))
            if (st.occupied) { occ++; if (!st.buckled) need++ }
        }
        beltSummary.text = if (need > 0) "벨트를 매지 않은 좌석 ${need}곳" else "탑승 ${occ}석 모두 착용"
        beltSummary.setTextColor(if (need > 0) c(R.color.informative_negative) else c(R.color.informative_active))

        val cont = trip.continuousMin
        restText.text = "%d:%02d / 2:00 연속 운전".format(cont / 60, cont % 60)
        restBar.progress = minOf(cont, Rules.REST_SUGGEST_AT_MIN)

        // D9: 주행 중엔 점수를 열지 않는다
        val locked = GleoHealthEngine.canOpenDashboard(signal(0))
        scoreText.text = if (locked != null) "🔒 $locked" else {
            val s = GleoHealthEngine.tripScore(trip)
            val m = GleoHealthEngine.monthlyReport(monthTrips + trip, consent)
            "${s.total}점  (벨트 ${s.belt} × 0.8 + 휴식 ${s.rest} × 0.2)\n" +
                (if (m.eligible) "이번 달 ${m.monthScore}점 · 할인 대상" else "이번 달 ${m.monthScore}점 · 80점부터 할인 대상") +
                (if (consent) "\n점수만 공유 중 · 할인에만 사용" else "\n보험사 공유 꺼짐")
        }
    }
}
