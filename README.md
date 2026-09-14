# FloodDetector

CCTV 영상과 딥러닝을 활용해 도로 침수 상황을 실시간으로 감지하고, 침수 구간을 회피하는 경로를 안내하는 안드로이드 내비게이션 앱입니다.

**Version:** 3.0.0

---

## 주요 기능

- **실시간 CCTV 스트리밍**: ITS(국가교통정보센터) CCTV 영상을 HLS 방식으로 재생
- **AI 기반 침수 감지**: TensorFlow Lite 모델로 CCTV 영상 프레임을 분석해 침수 여부 판별
- **지도 기반 UI**: 네이버 지도에 침수 지점 및 CCTV 위치 표시
- **경로 안내 연동**: 카카오 내비(KNSDK)를 이용한 목적지 길찾기, 침수 구간 회피 경로 제공
- **장소 검색**: 카카오/네이버 API 기반 목적지 검색
- **백그라운드 감지**: WorkManager를 이용해 주기적으로 침수 상태 갱신

---

## 기술 스택

| 구분 | 사용 기술 |
|---|---|
| 언어 | Kotlin, Java |
| 최소 SDK | 24 (Android 7.0) |
| 타겟 SDK | 33 |
| 컴파일 SDK | 35 |
| 아키텍처 | MVVM + Hilt (DI) |
| 지도 / 내비 | Naver Map SDK, Kakao KNSDK, Kakao Navi SDK |
| 네트워킹 | Retrofit2, OkHttp, Moshi/Gson |
| 비동기 | Kotlin Coroutines, WorkManager |
| 미디어 | ExoPlayer (HLS) |
| ML | TensorFlow Lite (mlModelBinding) |
| UI | DataBinding, Material Components |

---

## 프로젝트 구조

```
app/src/main/java/com/navirotation/
├── base/           # BaseActivity 등 공통 컴포넌트
├── data/           # 데이터 모델, DTO
├── navi/           # 내비게이션 관련 로직
├── repository/     # 리포지토리 계층
├── service/        # 백그라운드 서비스
├── ui/
│   └── map/        # MapActivity, SearchActivity, NavigationActivity, CctvPlayerActivity
├── worker/         # WorkManager Worker
├── CctvFeed.kt / CctvService.kt   # CCTV 데이터 소스
├── TFLiteHelper.java              # TFLite 추론 헬퍼
└── ServiceApplication.kt          # Hilt Application
```

---

## 빌드 및 실행

### 1. 요구 사항

- Android Studio Ladybug 이상
- JDK 17
- Gradle 8.x (프로젝트에 gradle wrapper 포함)

### 2. 프로젝트 클론

```bash
git clone https://github.com/ysj4384/FloodDetector.git
cd FloodDetector
```

### 3. `local.properties` 설정

프로젝트 루트에 `local.properties` 파일을 만들고 아래 키를 채워 넣으세요. **이 파일은 절대 커밋하지 마세요.**

```properties
sdk.dir=<Android SDK 경로>

# Kakao
KAKAO_NATIVE_APP_KEY=your_kakao_native_app_key
KAKAO_REST_API_KEY=your_kakao_rest_api_key
KAKAO_BASE_URL=https://dapi.kakao.com/

# Naver Map
NAVER_CLIENT_ID=your_naver_client_id

# Naver Directions
NAVER_DIRECTION_CLIENT_ID=your_naver_direction_client_id
NAVER_DIRECTION_CLIENT_SECRET=your_naver_direction_client_secret
NAVER_DIRECTION_BASE_URL=https://naveropenapi.apigw.ntruss.com/

# SK OpenAPI
SK_APP_KEY=your_sk_app_key
SK_BASE_URL=https://apis.openapi.sk.com/

# 사용자 식별 키
USER_KEY=your_user_key

# ITS CCTV API
CCTV_BASE_URL=https://openapi.its.go.kr:9443/
CCTV_API_KEY=your_cctv_api_key
```

### 4. 빌드

```bash
./gradlew assembleDebug
```

또는 Android Studio에서 `Run 'app'` 실행.

---

## 필요한 권한

- `INTERNET`
- `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION`
- `POST_NOTIFICATIONS`

---

## 빌드 타입

| Build Type | USE_KAKAO_NAV | 설명 |
|---|---|---|
| debug | false | 카카오 내비를 사용하지 않는 우회 테스트 모드 |
| release | true | 실제 카카오 내비 연동 |

---

## 참고 API

- [Kakao KNSDK](https://apis.kakaomobility.com/)
- [Naver Maps Android SDK](https://navermaps.github.io/android-map-sdk/)
- [ITS 국가교통정보센터 CCTV OpenAPI](https://www.its.go.kr/opendata/)

---

## 라이선스

본 프로젝트는 학습 및 연구 목적으로 작성되었습니다.
