# 글리오 헬스 release 규칙 (P7·S5)

# Pleos SDK: AIDL·리플렉션으로 불리는 클래스가 있어 통째로 유지한다
-keep class ai.pleos.** { *; }
-keep interface ai.pleos.** { *; }
-dontwarn ai.pleos.**
# AAOS Car API 는 기기에 있는 라이브러리(useLibrary)
-dontwarn android.car.**

# S5: 릴리스에서 진단 로그 제거 (경고·오류만 남긴다 — 좌석 상태 등 값은 넣지 않는다)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
