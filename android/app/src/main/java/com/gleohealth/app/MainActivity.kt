package com.gleohealth.app

import android.content.res.ColorStateList
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.gleohealth.app.core.HealthCore
import com.gleohealth.app.core.HealthService
import com.gleohealth.app.engine.Event
import com.gleohealth.app.engine.Gear
import com.gleohealth.app.engine.GleoHealthEngine
import com.gleohealth.app.engine.Posture
import com.gleohealth.app.engine.Rules
import com.gleohealth.app.engine.Seat
import com.gleohealth.app.engine.SeatState

/**
 * 글리오 헬스 화면 v0.3 (마스터 시안 2026-11-14). Pleos 다크 토큰(res/values/colors.xml)만 쓴다.
 * 홈 = 'GLIO Health' + 사람(좌석·공기 상태·마인드) / 차(노면 정보·차량 정보). 지금 열린 것은 좌석뿐 — 나머지는 이유와 다음 단계를 글로(R-5).
 * 좌석 = 안전벨트 · 휴식 · 이번 주행 점수 · 운전 자세 · 이번 주행 · 이번 달.
 * 기록은 HealthCore + HealthService 가 한다. 주행 중엔 요약만, 점수·동의 설정은 정차 중에만(D9).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var homeView: View
    private lateinit var seatView: View
    private lateinit var soonPanel: TextView
    private lateinit var tileSeatSub: TextView
    private lateinit var bubble: TextView
    private lateinit var bubbleBox: View
    private lateinit var driveState: TextView
    private lateinit var beltSummary: TextView
    private lateinit var restTime: TextView
    private lateinit var restBar: ProgressBar
    private lateinit var restText: TextView
    private lateinit var scoreText: TextView
    private lateinit var scoreDetail: TextView
    private lateinit var postureLabel: TextView
    private lateinit var postureSteps: TextView
    private lateinit var postureTip: TextView
    private lateinit var tripBox: LinearLayout
    private lateinit var monthText: TextView
    private lateinit var monthTip: TextView
    private lateinit var consent: Switch
    private lateinit var simPanel: View
    private val seatViews = mutableMapOf<Seat, TextView>()
    private var inSeat = false

    private fun c(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val wide get() = resources.configuration.screenWidthDp >= 1200

    private val onState: () -> Unit = { render() }
    private val onEvent: (Event) -> Unit = { e -> bubble.text = e.text; bubbleBox.visibility = View.VISIBLE }
    private val back = object : OnBackPressedCallback(false) { override fun handleOnBackPressed() = show(seat = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HealthCore.init(this)
        setContentView(buildUi())
        onBackPressedDispatcher.addCallback(this, back)
        show(seat = savedInstanceState?.getBoolean("seat") == true)
    }

    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("seat", inSeat); super.onSaveInstanceState(outState) }

    override fun onStart() {
        super.onStart()
        HealthCore.uiVisible = true
        HealthCore.addStateListener(onState)
        HealthCore.addEventListener(onEvent)
        HealthService.start(this)
        render()
    }

    override fun onStop() {
        HealthCore.uiVisible = false
        HealthCore.removeStateListener(onState)
        HealthCore.removeEventListener(onEvent)
        super.onStop()
    }

    private fun show(seat: Boolean) {
        inSeat = seat
        back.isEnabled = seat
        homeView.visibility = if (seat) View.GONE else View.VISIBLE
        seatView.visibility = if (seat) View.VISIBLE else View.GONE
        soonPanel.visibility = View.GONE
        render()
    }

    // ---------------- 공통 UI 조각 ----------------
    private fun text(size: Float, color: Int = c(R.color.text_primary), bold: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun round(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat(); setColor(color); stroke?.let { setStroke(dp(2), it) }
    }

    private fun card(title: String, vararg content: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(26), dp(22), dp(26), dp(22))
        background = round(c(R.color.surface_basic), 22)
        addView(text(18f, c(R.color.text_secondary)).apply { text = title; setPadding(0, 0, 0, dp(10)) })
        content.forEach { addView(it) }
    }

    private fun gap(v: Int = 18) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(v) }
    private fun weight(w: Float, right: Int = 0) = LinearLayout.LayoutParams(0, WRAP_CONTENT, w).apply { rightMargin = dp(right) }
    private fun bar(color: Int) = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progressTintList = ColorStateList.valueOf(color) }
    private fun tipBox() = text(16f).apply { setPadding(dp(14), dp(10), dp(14), dp(10)); background = round(c(R.color.surface_high), 12) }

    // ---------------- 화면 ----------------
    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(32), dp(22), dp(32), dp(22)); setBackgroundColor(c(R.color.background_secondary)) }
        driveState = text(17f).apply { setPadding(dp(14), dp(7), dp(14), dp(7)); background = round(c(R.color.surface_accent), 10) }
        // 안내 말풍선 — 토스 스타일(마스터 지시 2026-11-15): 단색 파랑, 장식 없음, Pretendard 굵게·좁은 자간
        val bold = ResourcesCompat.getFont(this, R.font.pretendard_bold)
        val regular = ResourcesCompat.getFont(this, R.font.pretendard_regular)
        bubble = text(25f, Color.WHITE).apply { typeface = bold; letterSpacing = -0.02f; setLineSpacing(0f, 1.2f) }
        bubbleBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            setPadding(dp(18), dp(18), dp(26), dp(18)); elevation = dp(8).toFloat()
            background = round(c(R.color.guide_blue), 24)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_tile_seat); imageTintList = ColorStateList.valueOf(Color.WHITE)
                setPadding(dp(10), dp(10), dp(10), dp(10)); background = round(Color.argb(46, 255, 255, 255), 14)
            }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(16) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(15f, Color.argb(199, 255, 255, 255)).apply { text = getString(R.string.guide_label); typeface = regular; letterSpacing = -0.02f })
                addView(bubble)
            })
        }
        homeView = buildHome()
        seatView = buildSeat()
        root.addView(homeView)
        root.addView(seatView)
        root.addView(bubbleBox, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(14) })
        simPanel = buildSimPanel().apply { visibility = View.GONE }
        root.addView(simPanel)
        return ScrollView(this).apply { addView(root) }
    }

    private fun buildHome(): View {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(72), dp(72)))
        head.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0)
            addView(text(38f, bold = true).apply { text = getString(R.string.home_title) })
            addView(text(17f, c(R.color.text_secondary)).apply { text = getString(R.string.home_sub) })
        })
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(driveState)
        v.addView(head)

        val people = group(getString(R.string.axis_people), c(R.color.informative_active), listOf(
            tile(R.drawable.ic_tile_seat, getString(R.string.tile_seat), "", live = true) { show(seat = true) }.also { tileSeatSub = it.getChildAt(2) as TextView },
            tile(R.drawable.ic_tile_air, getString(R.string.tile_air), getString(R.string.next_version)) { soon(R.string.soon_air) },
            tile(R.drawable.ic_tile_mind, getString(R.string.tile_mind), getString(R.string.next_version)) { soon(R.string.soon_mind) },
        ))
        val car = group(getString(R.string.axis_car), c(R.color.informative_positive), listOf(
            tile(R.drawable.ic_tile_road, getString(R.string.tile_road), getString(R.string.next_version)) { soon(R.string.soon_road) },
            tile(R.drawable.ic_tile_battery, getString(R.string.tile_battery), getString(R.string.tile_battery_sub)) { soon(R.string.soon_battery) },
        ))
        val groups = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        if (wide) { groups.addView(people, weight(3f, 22)); groups.addView(car, weight(2f)) }
        else { groups.addView(people); groups.addView(car, gap()) }
        v.addView(groups, gap(24))
        soonPanel = text(18f).apply { setPadding(dp(22), dp(18), dp(22), dp(18)); background = round(c(R.color.surface_basic), 18, c(R.color.surface_accent)); visibility = View.GONE }
        v.addView(soonPanel, gap())
        return v
    }

    private fun soon(textId: Int) { soonPanel.text = getString(textId); soonPanel.visibility = View.VISIBLE }

    private fun group(title: String, color: Int, tiles: List<View>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(20), dp(22), dp(24))
        background = round(c(R.color.surface_basic), 24)
        addView(text(18f, color, bold = true).apply { text = title; setPadding(0, 0, 0, dp(14)) })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        tiles.forEachIndexed { i, t -> row.addView(t, LinearLayout.LayoutParams(0, dp(190), 1f).apply { if (i < tiles.lastIndex) rightMargin = dp(14) }) }
        addView(row)
    }

    private fun tile(icon: Int, title: String, sub: String, live: Boolean = false, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        isClickable = true; isFocusable = true; contentDescription = title
        background = round(if (live) Color.argb(20, 0, 224, 116) else c(R.color.background_secondary), 20, if (live) c(R.color.informative_active) else c(R.color.surface_high))
        if (!live) alpha = 0.72f
        addView(ImageView(context).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(if (live) c(R.color.informative_active) else c(R.color.basic_300)) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(text(22f, bold = true).apply { text = title; setPadding(0, dp(10), 0, dp(4)) })
        addView(text(15f, c(R.color.text_secondary)).apply { text = sub; gravity = Gravity.CENTER })
        setOnClickListener { onClick() }
    }

    private fun buildSeat(): View {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title.addView(Button(this).apply {
            text = "‹"; textSize = 26f; setTextColor(c(R.color.text_primary)); contentDescription = getString(R.string.back_home)
            background = round(c(R.color.surface_high), 14); setOnClickListener { show(seat = false) }
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        title.addView(text(32f, bold = true).apply { text = getString(R.string.tile_seat); setPadding(dp(16), 0, dp(12), 0) })
        title.addView(text(17f, c(R.color.text_secondary)).apply { text = getString(R.string.seat_sub) })
        v.addView(title)

        // 안전벨트
        val grid = GridLayout(this).apply { columnCount = 2 }
        for (seat in Seat.entries) {
            val tv = text(18f).apply { this.text = seat.label; gravity = Gravity.CENTER }
            seatViews[seat] = tv
            grid.addView(tv, GridLayout.LayoutParams().apply { width = dp(170); height = dp(110); setMargins(dp(8), dp(8), dp(8), dp(8)) })
        }
        beltSummary = text(20f, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        val beltCard = card(getString(R.string.card_belt), grid, beltSummary).apply { gravity = Gravity.CENTER_HORIZONTAL }

        // 휴식 · 점수
        restTime = text(34f, bold = true)
        restBar = bar(c(R.color.informative_positive)).apply { max = Rules.REST_SUGGEST_AT_MIN }
        restText = text(17f, c(R.color.text_secondary)).apply { setPadding(0, dp(8), 0, 0) }
        val restCard = card(getString(R.string.card_rest), restTime, restBar, restText)
        scoreText = text(34f, bold = true)
        scoreDetail = text(16f, c(R.color.text_secondary)).apply { setPadding(0, dp(6), 0, 0) }
        val scoreCard = card(getString(R.string.card_score), scoreText, scoreDetail)

        // 운전 자세 · 이번 주행 · 이번 달
        postureLabel = text(34f, bold = true)
        postureSteps = text(15f, c(R.color.text_secondary)).apply { setPadding(0, dp(6), 0, dp(4)) }
        postureTip = tipBox()
        val postureCard = card(getString(R.string.card_posture), postureLabel, postureSteps, postureTip)
        tripBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tripCard = card(getString(R.string.card_trip), tripBox)
        monthText = text(17f, c(R.color.text_secondary))
        monthTip = tipBox()
        consent = Switch(this).apply { text = getString(R.string.consent); setTextColor(c(R.color.text_secondary)); textSize = 15f; setOnCheckedChangeListener { _, on -> if (on != HealthCore.ledger.consent) HealthCore.setConsent(on) } }
        val saved = text(13f, c(R.color.basic_300)).apply { text = getString(R.string.saved_note); setPadding(0, dp(10), 0, 0) }
        val monthCard = card(getString(R.string.card_month), monthText, monthTip, consent, saved)

        val col2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(restCard); addView(scoreCard, gap()) }
        if (wide) {
            val col3 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(postureCard); addView(tripCard, gap()); addView(monthCard, gap()) }
            v.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(beltCard, weight(1.05f, 20)); addView(col2, weight(1f, 20)); addView(col3, weight(1f))
            }, gap())
        } else {
            v.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(beltCard, weight(1.05f, 18)); addView(col2, weight(1f)) }, gap())
            v.addView(postureCard, gap()); v.addView(tripCard, gap()); v.addView(monthCard, gap())
        }
        return v
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f; setTextColor(c(R.color.background_secondary))
        background = round(c(R.color.button_filled_enabled), 12)
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
            button("새 주행") { HealthCore.newTrip(); bubbleBox.visibility = View.GONE },
        ))
        p.addView(row(
            button("등받이 세우기") { pose(-1, 0) }, button("등받이 눕히기") { pose(1, 0) },
            button("허리 받침 −") { pose(0, -1) }, button("허리 받침 +") { pose(0, 1) },
        ))
        return p
    }

    private fun pose(dBack: Int, dLumbar: Int) = HealthCore.manual.update { s ->
        val p = s.posture ?: return@update s
        s.copy(posture = p.copy(
            backrest = p.backrest?.let { (it + dBack).coerceIn(p.backrestMin, p.backrestMax) },
            lumbar = p.lumbar?.let { (it + dLumbar).coerceIn(p.lumbarMin, p.lumbarMax) },
        ))
    }

    private fun toggle(seat: Seat, occupied: Boolean) = HealthCore.manual.update { snap ->
        val cur = snap.seats[seat] ?: SeatState()
        val next = if (occupied) cur.copy(occupied = !cur.occupied, buckled = if (cur.occupied) false else cur.buckled)
        else cur.copy(buckled = !cur.buckled, occupied = cur.occupied || !cur.buckled)
        snap.copy(seats = snap.seats + (seat to next))
    }

    private fun hm(min: Int) = "%d:%02d".format(min / 60, min % 60)

    // ---------------- 그리기 ----------------
    private fun render() {
        val snap = HealthCore.snapshot
        val l = HealthCore.ledger
        val moving = HealthCore.signal(0).moving
        simPanel.visibility = if (HealthCore.usingManual) View.VISIBLE else View.GONE
        driveState.text = if (moving) "주행 중 · ${snap.gear} · ${snap.speedKmh}km/h" else "정차 · ${snap.gear}"

        // 안전벨트
        var occ = 0
        val open = mutableListOf<String>()
        for ((seat, v) in seatViews) {
            val st = snap.seats[seat] ?: SeatState()
            val (bg, stroke) = when {
                !st.occupied -> c(R.color.surface_high) to c(R.color.surface_high)
                st.buckled -> Color.argb(36, 0, 224, 116) to c(R.color.informative_active)
                else -> Color.argb(40, 255, 76, 40) to c(R.color.informative_negative)
            }
            v.background = round(bg, 20, stroke)
            v.setTextColor(if (st.occupied) Color.WHITE else c(R.color.basic_300))
            v.text = if (!st.occupied) seat.label else "${seat.label}\n${if (st.buckled) "맸어요" else "풀림"}"
            if (st.occupied) { occ++; if (!st.buckled) open += seat.label }
        }
        beltSummary.text = if (open.isNotEmpty()) "${open.joinToString()} 벨트가 풀려 있어요" else "${occ}명 모두 맸어요 ✓"
        beltSummary.setTextColor(if (open.isNotEmpty()) c(R.color.informative_negative) else c(R.color.informative_active))

        // 휴식
        val cont = l.current.continuousMin
        val left = Rules.REST_SUGGEST_AT_MIN - cont
        restTime.text = hm(cont)
        restBar.progress = minOf(cont, Rules.REST_SUGGEST_AT_MIN)
        restText.text = when { cont == 0 -> "2시간 달리면 15분 쉬어 가요"; left > 0 -> "쉬어 갈 때까지 ${hm(left)} 남았어요"; else -> "지금 쉬어 가면 좋아요 · 15분 쉬면 다시 셉니다" }
        tileSeatSub.text = if (open.isNotEmpty()) "${open.joinToString()} 벨트 풀림" else "${occ}명 착용 · 쉬기까지 ${if (left > 0) hm(left) else "지금"}"

        // 점수 (D9: 주행 중 잠금)
        val locked = GleoHealthEngine.canOpenDashboard(HealthCore.signal(0))
        val s = GleoHealthEngine.tripScore(l.current)
        if (locked != null) { scoreText.text = locked; scoreDetail.text = "" }
        else { scoreText.text = "${s.total}점"; scoreDetail.text = "벨트 ${s.belt} × 0.8 + 휴식 ${s.rest} × 0.2" }

        // 운전 자세 (웰니스, D25·D28)
        val p = snap.posture
        if (p?.backrestRel == null || p.backrestSpan <= 0) {
            postureLabel.text = "—"; postureSteps.text = ""
            postureTip.text = getString(R.string.posture_unavailable)
        } else {
            val adv = Posture.advice(p.backrestRel!!, p.backrestSpan, if (p.lumbarSpan > 0) p.lumbarRel else null, HealthCore.postureSameMin)
            postureLabel.text = "${adv.label}  ·  같은 자세 ${hm(HealthCore.postureSameMin)}"
            postureSteps.text = if (moving) "" else "등받이 ${p.backrestRel}/${p.backrestSpan}" + (if (p.lumbarSpan > 0) "   허리 받침 ${p.lumbarRel}/${p.lumbarSpan}" else "")
            postureTip.text = when { adv.tips.isEmpty() -> "지금 자세 좋아요"; moving -> adv.tips.last(); else -> adv.tips.joinToString("\n") }
        }

        // 이번 주행
        tripBox.removeAllViews()
        fun kv(k: String, v: String) = tripBox.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4))
            addView(text(17f, c(R.color.text_secondary)).apply { text = k }, weight(1f)); addView(text(17f, bold = true).apply { text = v })
        })
        kv("달린 시간", hm(l.current.minutes)); kv("탄 사람", "${occ}명"); kv("쉰 횟수", "${HealthCore.restCount}번")
        val rides = Seat.entries.filter { (l.current.seatMin[it]?.occupied ?: 0) > 0 }
        if (rides.isNotEmpty()) tripBox.addView(text(15f, c(R.color.text_secondary)).apply { text = "좌석별 벨트 맨 시간"; setPadding(0, dp(8), 0, dp(2)) })
        for (seat in rides) {
            val m = l.current.seatMin.getValue(seat)
            val pct = m.buckled * 100 / m.occupied
            tripBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(text(15f, c(R.color.text_secondary)).apply { text = seat.label }, LinearLayout.LayoutParams(dp(110), WRAP_CONTENT))
                addView(bar(if (pct < 100) c(R.color.informative_negative) else c(R.color.informative_active)).apply { progress = pct }, weight(1f, 10))
                addView(text(15f, c(R.color.text_secondary)).apply { text = "$pct%" })
            })
        }

        // 이번 달
        val month = GleoHealthEngine.monthlyReport(l.monthTrips(), l.consent)
        val trips = l.finished.count { it.month == l.month } + (if (l.current.minutes > 0) 1 else 0)
        monthText.text = "주행 ${trips}번 · ${month.monthScore}점"
        val gapScore = Math.max(0.0, Math.round((Rules.DISCOUNT_THRESHOLD - month.monthScore) * 10) / 10.0)
        monthTip.text = (if (month.eligible) "이번 달 보험 할인 받아요" else "할인까지 ${gapScore}점 남았어요") + (if (l.consent) " · 점수만 보험사에 보내요" else " · 보험사에는 안 보내요")
        consent.visibility = if (locked != null) View.GONE else View.VISIBLE
        if (consent.isChecked != l.consent) consent.isChecked = l.consent
    }
}
