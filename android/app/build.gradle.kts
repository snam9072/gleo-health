plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gleohealth.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gleohealth.app"
        minSdk = 34 // 실행 대상은 Pleos Connect(AAOS API 34)뿐. Pleos 최소 요건(28, 사이닝 키 로테이션)을 넘고, Gleo 발신자 신원 확인 API(34)를 모든 지원 기기에서 쓸 수 있다 (review r4 R-1)
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"
        // S1 엄격 모드: 신원을 모르는 Gleo 요청까지 거부. 에뮬레이터에서 Gleo 의 신원 공유를 확인한 뒤 true (마스터 결정 2026-11-14)
        buildConfigField("boolean", "GLEO_STRICT_SENDER", "false")
    }
    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            // P7·S5: R8 축소·난독화 + 안 쓰는 리소스 제거, 릴리스 로그 제거(proguard-rules.pro)
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false // S9
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 공모전 심사용 실행 가능한 release APK: 로컬 디버그 키로 서명 (App Market 출시 땐 별도 키)
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    useLibrary("android.car")
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    testImplementation("junit:junit:4.13.2")
}
