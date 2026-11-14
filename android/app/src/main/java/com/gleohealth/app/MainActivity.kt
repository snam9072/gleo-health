package com.gleohealth.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.gleohealth.app.core.HealthCore
import com.gleohealth.app.core.HealthService
import com.gleohealth.app.engine.Event
import com.gleohealth.app.engine.Gear
import com.gleohealth.app.engine.GleoHealthEngine
import com.gleohealth.app.engine.Rules
import com.gleohealth.app.engine.Seat
import com.gleohealth.app.engine.SeatState

/**
 * 글리오 헬스 메인 화면 (P4, 명세 D9·D20). Pleos 다크 토큰(res/values/colors.xml)만 쓴다.
 * 왼쪽 = 사람 축(좌석·벨트, 휴식, 점수), 오른쪽 = 차 축 자리(2차 버전에 들어올 카드와 이유 — R-5).
 * 기록은 화면이 아니라 HealthCore + HealthService 가 한다 → 화면을 닫아도 계속된다.
 * 주행 중엔 큰 글자 요약만, 점수·동의 설정은 정차 중에만.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var bubble: TextView
    private lateinit var driveState: TextView
    private lateinit var beltSummary: TextView
    private lateinit var restText: TextView
    private lateinit var restBar: ProgressBar
    private lateinit var scoreText: TextView
    private lateinit var monthText: TextView
    private lateinit var consent: Switch
    private lateinit var simPanel: View
    private val seatViews = mutableMapOf<Seat, TextView>()

    private fun c(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val onState: () -> Unit = { render() }
    private val onEvent: (Event) -> Unit = { e -> bubble.text = e.text; bubble.visibility = View.VISIBLE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HealthCore.init(this)
        setContentView(buildUi())
    }

    override fun onStart() {
        super.onStart()
        HealthCore.uiVisible = true
        HealthCore.addStateListener(onState)
        HealthCore.addEventListener(onEvent)
        HealthService.start(this) // 이미 돌고 있으면 그대로
        render()
    }

    override fun onStop() {
        HealthCore.uiVisible = false
        HealthCore.removeStateListener(onState)
        HealthCore.removeEventListener(onEvent)
        super.onStop()
    }

    // ---------------- UI ----------------
    private fun text(size: Float, color: Int = c(R.color.text_primary), bold: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun card(title: String, vararg content: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(28), dp(24), dp(28), dp(24))
        background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(c(R.color.surface_basic)) }
        addView(text(18f, c(R.color.text_secondary)).apply { this.text = title; setPadding(0, 0, 0, dp(12)) })
        content.forEach { addView(it) }
    }

    private fun gap(v: Int = 20) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(v) }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(32), dp(24), dp(32), dp(24)); setBackgroundColor(c(R.color.background_secondary)) }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(text(32f, bold = true).apply { text = getString(R.string.app_name) })
        header.addView(text(18f, c(R.color.text_secondary)).apply { text = "   " + getString(R.string.app_sub) })
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        driveState = text(18f).apply { setPadding(dp(16), dp(8), dp(16), dp(8)); background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(c(R.color.surface_accent)) } }
        header.addView(driveState)
        root.addView(header)

        bubble = text(22f, Color.WHITE).apply {
            setPadding(dp(26), dp(16), dp(26), dp(16)); visibility = View.GONE
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c(R.color.gleo_bubble_primary), c(R.color.gleo_bubble_secondary))).apply { cornerRadius = dp(24).toFloat() }
        }
        root.addView(bubble, gap(16))

        // 사람 축
        val grid = GridLayout(this).apply { columnCount = 2 }
        for (seat in Seat.entries) {
            val v = text(18f).apply { this.text = seat.label; gravity = Gravity.CENTER }
            seatViews[seat] = v
            grid.addView(v, GridLayout.LayoutParams().apply { width = dp(170); height = dp(110); setMargins(dp(8), dp(8), dp(8), dp(8)) })
        }
        beltSummary = text(22f, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        val seatCard = card(getString(R.string.seats_title), grid, beltSummary).apply { gravity = Gravity.CENTER_HORIZONTAL }

        restText = text(30f, bold = true)
        restBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = Rules.REST_SUGGEST_AT_MIN; progressTintList = android.content.res.ColorStateList.valueOf(c(R.color.informative_positive)) }
        val restCard = card(getString(R.string.rest_title), restText, restBar)

        scoreText = text(30f, bold = true)
        monthText = text(18f, c(R.color.text_secondary)).apply { setPadding(0, dp(6), 0, 0) }
        consent = Switch(this).apply { text = getString(R.string.consent); setTextColor(c(R.color.text_secondary)); textSize = 16f; setOnCheckedChangeListener { _, on -> if (on != HealthCore.ledger.consent) HealthCore.setConsent(on) } }
        val saved = text(14f, c(R.color.basic_300)).apply { text = getString(R.string.saved_note); setPadding(0, dp(8), 0, 0) }
        val scoreCard = card(getString(R.string.score_title), scoreText, monthText, consent, saved)

        val restScore = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(restCard); addView(scoreCard, gap()) }
        val people = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        people.addView(seatCard, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = dp(20) })
        people.addView(restScore, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val peopleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(text(20f, c(R.color.informative_active), bold = true).apply { text = getString(R.string.people_title); setPadding(0, 0, 0, dp(12)) }); addView(people) }

        // 차 축 (2차 버전 자리 — 비어 있는 이유와 다음 단계를 글로)
        val carRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (name in listOf("배터리", "타이어 공기압", "경고등 · 소모품")) {
            carRows.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10))
                addView(text(20f).apply { text = name }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(text(14f, c(R.color.gleo_bubble_speaker)).apply { text = "2차"; setPadding(dp(10), dp(4), dp(10), dp(4)); background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(c(R.color.surface_high)) } })
            })
        }
        val carCard = card(getString(R.string.car_title), carRows, text(16f, c(R.color.text_secondary)).apply { text = getString(R.string.car_soon); setPadding(0, dp(12), 0, 0) })

        // 넓은 가로 화면은 좌우, 좁거나 분할 화면은 위아래
        val wide = resources.configuration.screenWidthDp >= 1200
        val body = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        if (wide) {
            body.addView(peopleCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 2f).apply { rightMargin = dp(20) })
            body.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(text(20f, c(R.color.informative_positive), bold = true).apply { text = "차"; setPadding(0, 0, 0, dp(12)) }); addView(carCard) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        } else {
            body.addView(peopleCol)
            body.addView(carCard, gap())
        }
        root.addView(body, gap())

        simPanel = buildSimPanel().apply { visibility = View.GONE }
        root.addView(simPanel)
        return ScrollView(this).apply { addView(root) }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 16f; setTextColor(c(R.color.background_secondary))
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(c(R.color.button_filled_enabled)) }
        setOnClickListener { onClick() }
    }

    private fun row(vararg b: View) = HorizontalScrollView(this).apply {
        addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; b.forEach { addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) }) } })
    }

    /** 시연용 신호 패널 — Pleos Vehicle SDK 를 못 쓰는 환경에서만 보인다. */
    private fun buildSimPanel(): View {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(24), 0, 0) }
        p.addView(text(16f, c(R.color.text_secondary)).apply { text = getString(R.string.sim_title) })
        p.addView(row(*Seat.entries.flatMap { seat -> listOf(button("${seat.label} 착석") { toggle(seat, true) }, button("벨트") { toggle(seat, false) }) }.toTypedArray()))
        p.addView(row(
            button("P 정차") { HealthCore.manual.update { it.copy(gear = Gear.P, speedKmh = 0) } },
            button("D 60km/h") { HealthCore.manual.update { it.copy(gear = Gear.D, speedKmh = 60) } },
            *listOf(1, 10, 15, 60).map { m -> button("+${m}분") { HealthCore.tick(m) } }.toTypedArray(),
            button("새 주행") { HealthCore.newTrip(); bubble.visibility = View.GONE },
        ))
        return p
    }

    private fun toggle(seat: Seat, occupied: Boolean) = HealthCore.manual.update { snap ->
        val cur = snap.seats[seat] ?: SeatState()
        val next = if (occupied) cur.copy(occupied = !cur.occupied, buckled = if (cur.occupied) false else cur.buckled)
        else cur.copy(buckled = !cur.buckled, occupied = cur.occupied || !cur.buckled)
        snap.copy(seats = snap.seats + (seat to next))
    }

    private fun render() {
        val snap = HealthCore.snapshot
        val l = HealthCore.ledger
        val moving = HealthCore.signal(0).moving
        simPanel.visibility = if (HealthCore.usingManual) View.VISIBLE else View.GONE
        driveState.text = if (moving) "주행 중 · ${snap.gear} · ${snap.speedKmh}km/h" else "정차 · ${snap.gear}"

        var need = 0; var occ = 0
        for ((seat, v) in seatViews) {
            val st = snap.seats[seat] ?: SeatState()
            val (bg, stroke) = when {
                !st.occupied -> c(R.color.surface_high) to c(R.color.surface_high)
                st.buckled -> Color.argb(36, 0, 224, 116) to c(R.color.informative_active)
                else -> Color.argb(40, 255, 76, 40) to c(R.color.informative_negative)
            }
            v.background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(bg); setStroke(dp(2), stroke) }
            v.setTextColor(if (st.occupied) Color.WHITE else c(R.color.basic_300))
            v.text = if (!st.occupied) seat.label else "${seat.label}\n${if (st.buckled) "착용" else "미착용"}"
            if (st.occupied) { occ++; if (!st.buckled) need++ }
        }
        beltSummary.text = if (need > 0) "벨트를 매지 않은 좌석 ${need}곳" else "탑승 ${occ}석 모두 착용"
        beltSummary.setTextColor(if (need > 0) c(R.color.informative_negative) else c(R.color.informative_active))

        val cont = l.current.continuousMin
        restText.text = "%d:%02d / 2:00".format(cont / 60, cont % 60)
        restBar.progress = minOf(cont, Rules.REST_SUGGEST_AT_MIN)

        // D9: 주행 중엔 점수·동의 설정을 열지 않는다
        val locked = GleoHealthEngine.canOpenDashboard(HealthCore.signal(0))
        consent.visibility = if (locked != null) View.GONE else View.VISIBLE
        if (consent.isChecked != l.consent) consent.isChecked = l.consent
        if (locked != null) {
            scoreText.text = "🔒 $locked"; monthText.text = ""
        } else {
            val s = GleoHealthEngine.tripScore(l.current)
            val m = GleoHealthEngine.monthlyReport(l.monthTrips(), l.consent)
            scoreText.text = "${s.total}점"
            monthText.text = "벨트 ${s.belt} × 0.8 + 휴식 ${s.rest} × 0.2\n" +
                (if (m.eligible) "이번 달 ${m.monthScore}점 · 할인 대상" else "이번 달 ${m.monthScore}점 · 80점부터 할인 대상") +
                (if (l.consent) " · 점수만 공유 중(할인에만 사용)" else " · 보험사 공유 꺼짐")
        }
    }
}
