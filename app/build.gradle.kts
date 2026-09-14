import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("dagger.hilt.android.plugin")
    id("kotlin-kapt")
    id ("kotlin-parcelize")
}

android {
    namespace = "com.navirotation"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.navirotation"
        minSdk = 24
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // buildConfigField 용(문자열 따옴표 포함)
        fun key(pKey: String): String {
            val value = gradleLocalProperties(rootDir, providers).getProperty(pKey) ?: ""
            return "\"$value\""
        }
        // manifestPlaceholders 용(따옴표 없이 원문 그대로)
        fun prop(pKey: String): String {
            return gradleLocalProperties(rootDir, providers).getProperty(pKey) ?: ""
        }

        // BuildConfig.* 값
        buildConfigField("String", "KAKAO_NATIVE_APP_KEY", key("KAKAO_NATIVE_APP_KEY"))
        buildConfigField("String", "KAKAO_REST_API_KEY", key("KAKAO_REST_API_KEY"))
        buildConfigField("String", "SK_APP_KEY", key("SK_APP_KEY"))
        buildConfigField("String", "USER_KEY", key("USER_KEY"))
        buildConfigField("String", "SK_BASE_URL", key("SK_BASE_URL"))
        buildConfigField("String", "KAKAO_BASE_URL", key("KAKAO_BASE_URL"))
        buildConfigField("String", "NAVER_CLIENT_ID", key("NAVER_CLIENT_ID"))
        buildConfigField("String", "CCTV_BASE_URL", key("CCTV_BASE_URL"))
        buildConfigField("String", "CCTV_API_KEY", key("CCTV_API_KEY"))
        buildConfigField("String", "NAVER_DIRECTION_CLIENT_ID", key("NAVER_DIRECTION_CLIENT_ID"))
        buildConfigField("String", "NAVER_DIRECTION_CLIENT_SECRET", key("NAVER_DIRECTION_CLIENT_SECRET"))
        buildConfigField("String", "NAVER_DIRECTION_BASE_URL", key("NAVER_DIRECTION_BASE_URL"))

        // Manifest placeholder는 따옴표 없이 값 넣기
        manifestPlaceholders["NAVER_CLIENT_ID"] = prop("NAVER_CLIENT_ID")
    }

    buildTypes {
        debug {
            // 디버그: 카카오내비 미사용(앱 내 우회 테스트)
            buildConfigField("boolean", "USE_KAKAO_NAV", "false")
        }
        release {
            // 릴리즈: 카카오내비 사용
            buildConfigField("boolean", "USE_KAKAO_NAV", "true")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // ✅ buildFeatures는 한 번만 선언 (중복 제거)
    buildFeatures {
        dataBinding = true
        buildConfig = true
        mlModelBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

kapt {
    correctErrorTypes = true
}

val hiltVersion = 2.44



dependencies {
    // Retrofit
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.retrofit2:converter-scalars:2.9.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.9.0")

    implementation("androidx.activity:activity-ktx:1.7.2")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    // Kakao KNSDK
    implementation("com.kakaomobility.knsdk:knsdk_ui:1.9.4")

    // Kakao SDK v2 (버전 고정)
    implementation("com.kakao.sdk:v2-navi:2.11.0")
    implementation("com.kakao.sdk:v2-common:2.11.0")
    implementation("com.kakao.sdk:v2-network:2.11.0")

    // Naver Map
    implementation("com.naver.maps:map-sdk:3.18.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-android-compiler:2.51.1")

    // ✅ 잘못 들어가 있던 runner 의존성 제거(implementation → androidTestImplementation로 이동)
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    // TFLite
    implementation("org.tensorflow:tensorflow-lite-support:0.1.0")
    implementation("org.tensorflow:tensorflow-lite-metadata:0.1.0")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    // ExoPlayer
    implementation("com.google.android.exoplayer:exoplayer:2.18.5")
    implementation("com.google.android.exoplayer:exoplayer-hls:2.18.5")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")

    // WorkManager (백그라운드 작업용)
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}
