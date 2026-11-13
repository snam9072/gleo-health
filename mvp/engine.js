// 글리오 헬스 — 규칙·점수 엔진 (MVP)
// 명세: docs/thinking/01-hmg-app-challenge/index.md '6. 명세' D4~D9, D7(0.8×벨트 + 0.2×휴식), D8(80점·점수만), D11(할인에만)
// 차량 신호를 '틱'(1분 단위 등) 으로 받아 상태를 갱신하는 순수 함수 모음. 2차 때 Kotlin 으로 옮긴다.

export const SEATS = ["DRIVER", "PASSENGER", "REAR_LEFT", "REAR_RIGHT"];
export const SEAT_NAMES = { DRIVER: "운전석", PASSENGER: "조수석", REAR_LEFT: "뒷좌석 왼쪽", REAR_RIGHT: "뒷좌석 오른쪽" };

export const RULES = {
  beltWeight: 0.8, // D7
  restWeight: 0.2, // D7
  restSuggestAtMin: 120, // D5: 연속 운전 2시간
  restResetParkMin: 15, // D5: P단 15분 이상 정차 = 휴식
  discountThreshold: 80, // D8: 월 점수 80 이상 할인 대상
  ergoTipAtMin: 60, // D6: 주행 1시간 뒤 에르고 모션 안내 1회
};

/** 새 주행(트립) 상태 */
export function newTrip() {
  return {
    minutes: 0, // 총 주행(속도>0) 분
    continuousMin: 0, // 쉬지 않고 이어진 운전 분 (휴식 인정 때 0)
    parkMin: 0, // 지금 P단으로 서 있는 연속 분
    overLimitMin: 0, // 2시간을 넘겨 쉬지 않고 달린 분 (휴식 점수 감점)
    seatMin: Object.fromEntries(SEATS.map((s) => [s, { occupied: 0, buckled: 0 }])),
    announced: new Set(), // 이번 '미체결 구간'에 이미 음성 안내한 좌석 (D4: 반복 음성 없음)
    restSuggested: false, // 이번 연속 운전 구간에 휴식 제안을 했나
    restTaken: false, // 휴식 인정됨 → 다시 출발 전 자세 제안 대상
    postureOffered: false,
    ergoTipGiven: false,
    moving: false,
  };
}

/**
 * 한 틱 진행. signal = { minutes, speedKmh, gear('P'|'D'), seats: {SEAT: {occupied, buckled}} }
 * 반환: { trip(새 상태), events: [{type, seat?, text}] } — 이벤트는 음성 1회 안내·화면 배지용
 */
export function tick(prev, signal) {
  const t = structuredClone({ ...prev, announced: [...prev.announced] });
  t.announced = new Set(t.announced);
  const events = [];
  const dt = signal.minutes ?? 1;
  const moving = signal.speedKmh > 0 && signal.gear !== "P";
  const parked = signal.gear === "P";

  // D4 벨트: 착석 + 미체결 + 출발(속도>0) → 좌석 이름 음성 1회. 빈 좌석은 기록·알림 없음.
  for (const s of SEATS) {
    const st = signal.seats?.[s] ?? { occupied: false, buckled: false };
    if (!st.occupied || st.buckled) t.announced.delete(s); // 체결하거나 내리면 다음 미체결 때 다시 1회
    if (moving && st.occupied) {
      t.seatMin[s].occupied += dt;
      if (st.buckled) t.seatMin[s].buckled += dt;
      else if (!t.announced.has(s)) {
        t.announced.add(s);
        events.push({ type: "BELT", seat: s, text: `${SEAT_NAMES[s]} 안전벨트를 매 주세요` });
      }
    }
  }

  // D5 피로·휴식
  if (moving) {
    t.minutes += dt;
    t.continuousMin += dt;
    t.parkMin = 0;
    if (t.continuousMin > RULES.restSuggestAtMin) t.overLimitMin += Math.min(dt, t.continuousMin - RULES.restSuggestAtMin);
    if (t.continuousMin >= RULES.restSuggestAtMin && !t.restSuggested) {
      t.restSuggested = true;
      events.push({ type: "REST", text: `${Math.floor(t.continuousMin / 60)}시간째 운전 중이에요. 다음 휴게소에 들를까요?` });
    }
    // D6 에르고 모션 안내 1회
    if (t.minutes >= RULES.ergoTipAtMin && !t.ergoTipGiven) {
      t.ergoTipGiven = true;
      events.push({ type: "ERGO_TIP", text: "주행 1시간이 지났어요. 에르고 모션 시트를 켜 보세요" });
    }
  } else if (parked) {
    t.parkMin += dt;
    if (t.parkMin >= RULES.restResetParkMin && t.continuousMin > 0) {
      t.continuousMin = 0; // 휴식 인정
      t.restSuggested = false;
      t.restTaken = true;
      t.postureOffered = false;
      events.push({ type: "REST_OK", text: "휴식이 기록됐어요. 연속 운전 시간을 다시 셉니다" });
    }
  }

  // D6 휴식 뒤 자세 되돌리기: 휴식 인정 뒤, P단 + 운전석 착석·체결일 때만 1회. 주행 중엔 제안 안 함.
  const driver = signal.seats?.DRIVER ?? {};
  if (t.restTaken && !t.postureOffered && parked && driver.occupied && driver.buckled) {
    t.postureOffered = true;
    events.push({ type: "POSTURE", text: "다시 출발하기 전에, 저장된 운전 자세로 되돌릴까요?" });
  }
  if (moving) t.restTaken = false; // 다시 출발하면 제안 기회는 끝 (D6: 주행 중 제안 없음)

  t.moving = moving;
  return { trip: t, events };
}

/** D7 트립 점수 */
export function tripScore(trip) {
  let occ = 0;
  let buck = 0;
  for (const s of SEATS) {
    occ += trip.seatMin[s].occupied;
    buck += trip.seatMin[s].buckled;
  }
  const belt = occ ? (buck / occ) * 100 : 100;
  const rest = trip.minutes ? (1 - trip.overLimitMin / trip.minutes) * 100 : 100;
  const total = RULES.beltWeight * belt + RULES.restWeight * rest;
  return { belt: round1(belt), rest: round1(rest), total: round1(total) };
}

/** D8 월 집계: 트립 점수를 주행 시간으로 가중 평균 → 할인 대상 여부. 동의 안 하면 전송 내용 없음, 동의해도 점수만. */
export function monthlyReport(trips, { consent }) {
  const minutes = trips.reduce((a, t) => a + t.minutes, 0);
  const score = minutes ? trips.reduce((a, t) => a + tripScore(t).total * t.minutes, 0) / minutes : 100;
  const monthScore = round1(score);
  const eligible = monthScore >= RULES.discountThreshold;
  // D11: 기록은 할인에만 — 보험사에는 월 점수와 대상 여부만, 원자료(좌석별 분·시각)는 보내지 않는다.
  const payload = consent ? { monthScore, eligible } : null;
  return { monthScore, eligible, payload };
}

/** D9 주행 중 화면 규칙 */
export function canOpenDashboard(signal) {
  const moving = signal.speedKmh > 0 && signal.gear !== "P";
  return moving ? { ok: false, text: "정차 후 볼 수 있어요" } : { ok: true };
}

const round1 = (x) => Math.round(x * 10) / 10;
