# 글리오 헬스 (Gleo Health)

**한 줄 소개** — Gleo가 사람과 차의 건강을 직접 챙겨 주게 돕는 Pleos의 첫 차량 헬스 케어 앱, 글리오 헬스.

> 현대자동차그룹 차량용 앱 개발 공모전(Code your App into Pleos App Market) 출품작.

## 무엇을 하나
| 기능 | 동작 | 사용 SDK |
|---|---|---|
| 좌석별 벨트 케어 | 착석했는데 벨트를 안 맨 좌석을, **출발하는 순간 좌석 이름으로 한 번** 안내 (P단에선 알리지 않음, 반복 경고는 순정 경고음에 맡김) | Vehicle SDK `Seat` (registerSeatOccupancy · registerSeatBeltBuckledState, `SeatArea` ROW_1_LEFT …) |
| 휴식 페이스메이커 | 연속 운전 2시간 → 휴식 제안 1회, P단 15분 이상 정차 → 휴식 인정 | Vehicle SDK `Odometer`, AAOS `PERF_VEHICLE_SPEED` · `GEAR_SELECTION` |
| 휴식 뒤 자세 되돌리기 | 휴식 뒤 P단·벨트 체결일 때만 "저장된 자세로 되돌릴까요?" 제안 — 앱은 시트를 직접 움직이지 않음 | Vehicle SDK `Seat.selectSeatMemory` (권한 협의 후) |
| 글리오 헬스 점수 | 0.8 × 벨트 + 0.2 × 휴식, **정차 중에만** 표시. 보험 제휴 기준(시뮬레이션)은 **벨트 95점·휴식 90점을 둘 다** 넘을 때 (v0.3.4) | — |
| 동의 기반 보험 연계(기획) | 제휴되면 동의한 운전자의 월 점수와 기준 충족 여부만 제공(원자료 없음). 지금 앱은 인터넷 권한이 없어 아무것도 보내지 않음 | — |
| Gleo 앱 연동 | "자리·벨트 점검해 줘" · "내 글리오 헬스 점수" · "쉴 곳 찾아 줘" | Gleo Interface `assets/schema.json` (함수 3개), Callback `APP_ACTION_RESPONSE` |

디자인은 Pleos Connect UI Design Guide 다크 테마 토큰(`res/values/colors.xml`)과 Gleo 말풍선 색을 그대로 씁니다.

**화면 (v0.3)**: 첫 화면 'GLIO Health' 홈 = **human**(좌석 · 공기 상태 · 마인드) + **car**(노면 정보 · 차량 정보). 지금 열리는 것은 **좌석**이고, 나머지 칸은 누르면 무엇이 들어올지 안내합니다. 좌석 화면 = 안전벨트 · 휴식 · 이번 주행 점수 · **운전 자세**(시트 위치 단계와 시트가 그대로인 시간, 웰니스 안내 — 실제 자세를 재지는 않음) · 이번 주행 · 이번 달.

## 플랫폼 기반과 보안 (v0.2.0)
| 항목 | 내용 |
|---|---|
| 기록 코어 · 백그라운드 기록 | `core/HealthCore.kt` 하나를 화면·서비스·Gleo 수신기가 함께 본다. `core/HealthService.kt` 가 화면을 닫아도 1분마다 기록하고, 정차 30분 + 화면 꺼짐이면 스스로 멈춘다 |
| 암호화 저장 | `store/TripStore.kt` — 주행 요약만(초 단위 원자료·위치 없음) AES-GCM 으로 저장, 키는 Android Keystore. 깨지거나 바뀐 파일은 빈 기록으로 시작, 13개월 보관, 백업·기기 이전 제외 |
| Gleo 수신기 보호 | `gleo/GleoGuard.kt` — 보낸 앱이 신원을 공유하면 시스템 앱만 허용, 같은 함수 2초에 1번, 응답은 시스템 앱 수신기를 찾으면 그 앱에만, 응답 내용은 최소값. 신원을 공유하지 않는 요청까지 막는 엄격 모드(`GLEO_STRICT_SENDER`)는 에뮬레이터에서 Gleo 동작을 확인한 뒤 켠다 |
| 릴리스 빌드 | R8 축소·난독화·리소스 축소, 릴리스 진단 로그 제거, 디버깅 끔 |

## 저장소 구성
| 경로 | 내용 |
|---|---|
| `android/` | Android(Kotlin) 앱 — 엔진 `engine/GleoHealthEngine.kt`, 기록 코어 `core/`, 암호화 저장 `store/`, 차량 연동 `vehicle/PleosVehicleSource.kt`, Gleo 수신기 `gleo/` |
| `android/app/src/test/` | 단위 테스트 16개 — 엔진(명세 경계값: 2시간/15분, 80점, 점수 예시 100·80·93.3), 저장소(재시작 유지·평문 없음·위변조·13개월), Gleo 보호 규칙, 운전 자세 규칙 |
| `mvp/` | 같은 규칙의 JavaScript 엔진 + 브라우저 시뮬레이터(시연 영상 원본) |
| `apk/` | 실행 가능한 release APK |

## 실행 방법
1. Android Studio 에 Pleos Connect Emulator 를 설치합니다 (SDK Update Site: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`, API 34, AVD RAM 4GB, Cold boot).
2. APK 설치: `adb install apk/<APK 파일>`
3. 앱 목록에서 **글리오 헬스** 실행.
4. Pleos Connect Virtual Console 에서 좌석 착석·안전벨트·속도·기어를 바꾸면 화면과 음성 안내가 반응합니다. Vehicle SDK 를 쓸 수 없는 환경에서는 화면 아래 **시연용 신호** 패널이 자동으로 나타나 같은 흐름을 시험할 수 있습니다.

소스에서 빌드:
```bash
cd android
./gradlew testDebugUnitTest assembleRelease
```

## 테스트 환경 및 필요한 설정
| 항목 | 값 |
|---|---|
| Pleos Connect Emulator | **v2.1.0** (system image `system-images;android-34;pleos_car_emulator;x86_64`) |
| 화면 규격 | **에뮬레이터 기본 디스플레이 2560×1440, 대각 12.8인치, mdpi, 가로** (devices.xml 'Pleos Connect v2.1.0' 기준). 분할 화면·세로 레이아웃도 대응 |
| Android | minSdk 34 (Pleos Connect 전용), target/compile 34, Kotlin 1.9, AGP 8.5, Gradle 8.7, JDK 17 |
| Pleos SDK | Vehicle 2.3.2, TextToSpeech 3.3.1, NaviHelper 3.0.5 (`https://nexus-playground.pleos.ai/repository/maven-releases/`) |
| 권한 | `pleos.car.permission.CAR_SEATS` · `CAR_SAFETY` · `CAR_DRIVE` (조회만, 시트 제어 권한은 선언하지 않음), `android.car.permission.CAR_SPEED` · `CAR_POWERTRAIN`, `FOREGROUND_SERVICE` · `FOREGROUND_SERVICE_SPECIAL_USE` (주행 기록) |
| 외부 키·서버 | 없음 (점수는 기기 안에서만 계산) |
| 글꼴 | 안내 말풍선에 Pretendard(© Kil Hyung-jin, SIL Open Font License 1.1) 포함 — `android/app/src/main/assets/licenses/Pretendard-NOTICE.txt` |

## APK 위치
`apk/` 폴더 (파일명 규칙: `팀명_앱이름_release.apk`). 용량 초과 시 GitHub Releases 링크를 씁니다.
