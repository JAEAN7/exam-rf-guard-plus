plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "kr.reformparty.koriyoscan"
    // Android 10(API 29) ~ Android 16(API 36)까지 같은 코드로 동작한다.
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.reformparty.koriyoscan"
        minSdk = 29
        targetSdk = 36
        versionCode = 22
        // [FORK] 외부 포크 배포본. 원본과 구분되도록 -fork를 붙인다.
        versionName = "1.1.2-fork"
        // [FORK 1.1.2] 와이파이 탐색 제한 안내 UI 표시 여부.
        // 원본이 플레이 정책만 따르려면 이 값을 false로 두면 안내가 사라지고, 동작(10초 주기)만 남는다.
        buildConfigField("boolean", "THROTTLE_GUIDE", "true")
    }

    // 정식 서명: 키 파일과 비밀번호는 저장소에 두지 않고 환경 변수로 받는다.
    // 환경 변수가 없으면 release 는 서명 없이 빌드된다. 절차는 docs/RELEASE.ko.md.
    val releaseStoreFile = System.getenv("RELEASE_STORE_FILE")
    if (releaseStoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_STORE_PASSWORD")
            }
        }
        buildTypes {
            getByName("release") { signingConfig = signingConfigs.getByName("release") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
}
