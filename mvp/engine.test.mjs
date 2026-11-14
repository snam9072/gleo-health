// 명세 D1~D11 의 예시를 그대로 테스트로 (docs/thinking/01-hmg-app-challenge/index.md '6. 명세')
import { test } from "node:test";
import assert from "node:assert/strict";
import { newTrip, tick, tripScore, monthlyReport, canOpenDashboard, RULES } from "./engine.js";

const seats = (o = {}) => ({
  DRIVER: { occupied: true, buckled: true },
  PASSENGER: { occupied: false, buckled: false },
  REAR_LEFT: { occupied: false, buckled: false },
  REAR_RIGHT: { occupied: false, buckled: false },
  ...o,
});
const drive = (s, min = 1) => ({ minutes: min, speedKmh: 60, gear: "D", seats: s });
const park = (s, min = 1) => ({ minutes: min, speedKmh: 0, gear: "P", seats: s });
function run(trip, signals) {
  const events = [];
  for (const sig of signals) {
    const r = tick(trip, sig);
    trip = r.trip;
    events.push(...r.events);
  }
  return { trip, events };
}
const repeat = (sig, n) => Array.from({ length: n }, () => sig);

test("D1·D3: 점수는 벨트·휴식 두 축뿐, 조작 항목 없음", () => {
  const s = tripScore(run(newTrip(), repeat(drive(seats()), 10)).trip);
  assert.deepEqual(Object.keys(s).sort(), ["belt", "rest", "total"]);
  assert.equal(RULES.beltWeight + RULES.restWeight, 1);
});

test("D4: P단에선 알림 없음, 출발하면 좌석 이름 1회, 반복 없음, 빈 좌석은 기록 안 함", () => {
  const s = seats({ PASSENGER: { occupied: true, buckled: false }, REAR_LEFT: { occupied: false, buckled: false } });
  let r = run(newTrip(), [park(s)]);
  assert.equal(r.events.filter((e) => e.type === "BELT").length, 0, "P단 알림 없음");
  r = run(r.trip, repeat(drive(s), 5));
  const belts = r.events.filter((e) => e.type === "BELT");
  assert.equal(belts.length, 1, "반복 음성 없음");
  assert.equal(belts[0].text, "조수석, 벨트 매 주세요");
  assert.equal(r.trip.seatMin.REAR_LEFT.occupied, 0, "빈 좌석 기록 안 함");
  // 체결했다가 다시 풀면 한 번 더 안내
  r = run(r.trip, [drive(seats({ PASSENGER: { occupied: true, buckled: true } })), drive(s)]);
  assert.equal(r.events.filter((e) => e.type === "BELT").length, 1);
});

test("D5: 1:59 → 없음, 2:00 → 휴식 제안 1회, P단 14분 → 이어짐, 15분 → 초기화", () => {
  let r = run(newTrip(), repeat(drive(seats()), 119));
  assert.equal(r.events.filter((e) => e.type === "REST").length, 0);
  r = run(r.trip, [drive(seats())]);
  assert.equal(r.events.filter((e) => e.type === "REST").length, 1);
  assert.match(r.events.find((e) => e.type === "REST").text, /2시간 달렸어요/);
  r = run(r.trip, repeat(drive(seats()), 30));
  assert.equal(r.events.filter((e) => e.type === "REST").length, 0, "같은 구간 반복 제안 없음");
  r = run(r.trip, repeat(park(seats()), 14));
  assert.equal(r.trip.continuousMin, 150, "14분 정차는 휴식 아님");
  r = run(r.trip, [park(seats())]);
  assert.equal(r.trip.continuousMin, 0, "15분 정차 = 휴식");
  assert.ok(r.events.some((e) => e.type === "REST_OK"));
});

test("D2·D6: 휴식 뒤 P단·운전석 착석·체결에서만 자세 되돌리기 제안, 주행 중엔 없음, 시트를 움직이지 않음", () => {
  let r = run(newTrip(), repeat(drive(seats()), 30));
  r = run(r.trip, repeat(park(seats({ DRIVER: { occupied: true, buckled: false } })), 15));
  assert.equal(r.events.filter((e) => e.type === "POSTURE").length, 0, "벨트 안 맸으면 제안 없음");
  r = run(r.trip, [park(seats())]);
  const p = r.events.filter((e) => e.type === "POSTURE");
  assert.equal(p.length, 1);
  assert.match(p[0].text, /원래 운전 자세로 돌려 드릴까요/);
  assert.ok(!r.events.some((e) => /MOVE|SEAT_SET/.test(e.type)), "엔진은 시트를 직접 움직이지 않음");
  // 휴식 뒤 바로 출발하면 그 뒤엔 제안 없음
  let q = run(newTrip(), repeat(drive(seats()), 10));
  q = run(q.trip, repeat(park(seats({ DRIVER: { occupied: true, buckled: false } })), 15));
  q = run(q.trip, [drive(seats()), drive(seats())]);
  assert.equal(q.events.filter((e) => e.type === "POSTURE").length, 0);
});

test("D6: 주행 1시간 뒤 에르고 모션 안내 1회", () => {
  const r = run(newTrip(), repeat(drive(seats()), 90));
  assert.equal(r.events.filter((e) => e.type === "ERGO_TIP").length, 1);
});

test("D7: 예시 100 / 80.0 / 93.3", () => {
  assert.equal(tripScore(run(newTrip(), repeat(drive(seats()), 60)).trip).total, 100);
  const half = [
    ...repeat(drive(seats({ PASSENGER: { occupied: true, buckled: false } })), 30),
    ...repeat(drive(seats({ PASSENGER: { occupied: true, buckled: true } })), 30),
  ];
  const s2 = tripScore(run(newTrip(), half).trip);
  assert.equal(s2.belt, 75);
  assert.equal(s2.total, 80);
  const s3 = tripScore(run(newTrip(), repeat(drive(seats()), 180)).trip);
  assert.equal(s3.rest, 66.7);
  assert.equal(s3.total, 93.3);
});

test("D8·D11·D-10: 동의 없으면 전송 없음, 80점이어도 벨트 75면 기준 미달, 보내는 건 점수와 대상 여부뿐", () => {
  const t80 = run(newTrip(), [
    ...repeat(drive(seats({ PASSENGER: { occupied: true, buckled: false } })), 30),
    ...repeat(drive(seats({ PASSENGER: { occupied: true, buckled: true } })), 30),
  ]).trip;
  assert.equal(monthlyReport([t80], { consent: false }).payload, null);
  const m = monthlyReport([t80], { consent: true });
  assert.equal(m.monthScore, 80);
  assert.equal(m.monthBelt, 75);
  assert.equal(m.eligible, false);
  assert.deepEqual(Object.keys(m.payload).sort(), ["eligible", "monthScore"]);
});

test("D-10 경계: 벨트 95.0 충족·94.2 미달, 휴식 90.2 충족·89.6 미달, 3시간 무휴식 93.3점은 미달", () => {
  const open = drive(seats({ PASSENGER: { occupied: true, buckled: false } }));
  const ok = drive(seats({ PASSENGER: { occupied: true, buckled: true } }));
  const a = monthlyReport([run(newTrip(), [...repeat(open, 6), ...repeat(ok, 54)]).trip], { consent: false });
  assert.equal(a.monthBelt, 95); assert.equal(a.eligible, true);
  const b = monthlyReport([run(newTrip(), [...repeat(open, 7), ...repeat(ok, 53)]).trip], { consent: false });
  assert.equal(b.monthBelt, 94.2); assert.equal(b.eligible, false);
  assert.equal(monthlyReport([run(newTrip(), repeat(drive(seats()), 133)).trip], { consent: false }).eligible, true);
  const c = monthlyReport([run(newTrip(), repeat(drive(seats()), 134)).trip], { consent: false });
  assert.equal(c.monthRest, 89.6); assert.equal(c.eligible, false);
  const d = monthlyReport([run(newTrip(), repeat(drive(seats()), 180)).trip], { consent: false });
  assert.equal(d.monthScore, 93.3); assert.equal(d.eligible, false);
});

test("D9: 주행 중 대시보드는 열지 않음", () => {
  assert.deepEqual(canOpenDashboard({ speedKmh: 40, gear: "D" }), { ok: false, text: "멈추면 보여 드릴게요" });
  assert.deepEqual(canOpenDashboard({ speedKmh: 0, gear: "P" }), { ok: true });
});

import { postureAdvice } from "./engine.js";
test("D28: 등받이 단계 비율 경계 — 1/3 미만 세움, 2/3 초과 눕힘, 경계는 보통", () => {
  assert.equal(postureAdvice({ backrest: 2, backrestMax: 10 }).label, "세움");
  assert.equal(postureAdvice({ backrest: 3, backrestMax: 9 }).label, "보통"); // 정확히 1/3
  assert.equal(postureAdvice({ backrest: 6, backrestMax: 9 }).label, "보통"); // 정확히 2/3
  assert.equal(postureAdvice({ backrest: 7, backrestMax: 9 }).label, "눕힘");
  assert.equal(postureAdvice({ backrest: 5, backrestMax: 9 }).tips.length, 0);
});
test("D28: 허리 받침 0단계·같은 자세 60분에서만 안내, 받침 없는 차(null)는 안내 없음", () => {
  assert.equal(postureAdvice({ backrest: 5, backrestMax: 9, lumbar: 0 }).tips.length, 1);
  assert.equal(postureAdvice({ backrest: 5, backrestMax: 9, lumbar: null }).tips.length, 0);
  assert.equal(postureAdvice({ backrest: 5, backrestMax: 9, sameMin: 59 }).tips.length, 0);
  assert.match(postureAdvice({ backrest: 5, backrestMax: 9, sameMin: 60 }).tips[0], /한 시간째/);
});

import { guideMs } from "./engine.js";
test("안내 말풍선은 항상 3.5~7초 안에 사라진다 (계속 떠 있던 버그 재발 방지)", () => {
  assert.equal(guideMs(""), 3500);
  assert.equal(guideMs("조수석, 벨트 매 주세요"), 3500); // 13자 → 3.28초 → 최소 3.5초
  assert.equal(guideMs("2시간 달렸어요. 다음 휴게소에서 쉬어 갈까요?"), 4060); // 26자
  assert.equal(guideMs("가".repeat(200)), 7000);
});

test("저장소에서 불러온 트립(announced 가 객체·배열)이어도 tick 이 멈추지 않는다 (2026-11-15 버그)", () => {
  const restored = JSON.parse(JSON.stringify(newTrip())); // Set → {}
  const r = tick(restored, drive(seats({ PASSENGER: { occupied: true, buckled: false } })));
  assert.equal(r.events[0].text, "조수석, 벨트 매 주세요");
  const fromArray = { ...newTrip(), announced: ["PASSENGER"] };
  assert.equal(tick(fromArray, drive(seats({ PASSENGER: { occupied: true, buckled: false } }))).events.length, 0);
});
