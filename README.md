# 오토터치 (AutoTouch)

안드로이드용 자동 클릭 / 드래그 앱. 접근성 서비스의 `dispatchGesture()` 로 동작하며 **루팅이 필요 없습니다.**

## 기능

- **다중 지점 시퀀스** — 여러 좌표를 순서대로 실행
- **동작별 개별 설정** — 누르는 시간, 다음 동작까지 대기(ms), 동작별 반복 횟수, 켜기/끄기
- **드래그(스와이프)** — 시작·끝 좌표와 이동 시간 지정, 화면에서 직접 끌어서 등록
- **길게 누르기** — 누름 시간을 600ms 이상으로 주면 롱프레스
- **전체 반복** — 횟수 지정 또는 무한 반복, 사이클 간 대기 시간
- **랜덤 오차** — 좌표(px) / 시간(ms) 에 무작위 편차를 줘서 기계적인 패턴 완화
- **플로팅 조작 패널** — 다른 앱 위에서 시작/정지, 좌표 추가, 마커 표시
- **좌표 마커** — 등록된 지점을 번호와 함께 화면에 표시

## 화면 위 버튼 설명

| 버튼 | 기능 |
|---|---|
| `⠿` | 길게 잡고 끌어서 패널 이동 |
| `▶` / `■` | 시퀀스 시작 / 정지 |
| `⊕` | 화면을 탭해서 클릭 지점 추가 (길게 누르면 롱프레스로 기록) |
| `↔` | 화면을 끌어서 드래그 동작 추가 (끄는 속도까지 그대로 기록) |
| `◉` | 등록된 좌표를 화면에 번호로 표시 / 숨기기 |
| `⚙` | 앱 본 화면 열기 |
| `✕` | 정지하고 패널 닫기 |

## 사용 순서

1. 앱 실행 → **① 접근성 켜기** → 설정에서 `오토터치` 활성화
2. 돌아오면 플로팅 패널이 화면에 뜹니다 (**② 플로팅 버튼** 으로 켜고 끌 수 있음)
3. 자동화할 앱으로 이동 → `⊕` 또는 `↔` 로 지점을 순서대로 추가
4. 앱 본 화면(`⚙`)에서 각 동작의 대기 시간·반복 횟수를 다듬고, 전체 반복 설정
5. `▶` 로 실행, `■` 로 정지

> 플로팅 패널 자체는 터치를 가로챕니다. 자동 클릭 지점과 겹치면 `⠿` 로 패널을 옆으로 옮겨 주세요.

## APK 만드는 법

### 방법 A — GitHub Actions (Android Studio 없이)

1. 이 폴더를 GitHub 저장소에 push
2. 저장소의 **Actions** 탭 → `Build APK` 워크플로가 자동 실행됨
3. 빌드가 끝나면 **Releases → latest** 에 `autotouch.apk` 가 올라갑니다
   - 휴대폰 브라우저에서 `https://github.com/<계정>/<저장소>/releases/latest` 를 열고 `autotouch.apk` 를 탭하면 바로 설치
   - Actions 탭 하단 **Artifacts → autotouch-debug-apk** 로도 받을 수 있습니다(zip 압축 해제 필요)

### 방법 B — Android Studio

1. Android Studio → **Open** → 이 폴더 선택
2. Gradle sync 가 끝나면 **Build → Build Bundle(s)/APK(s) → Build APK(s)**
3. `app/build/outputs/apk/debug/app-debug.apk`

### 방법 C — 커맨드라인

```bash
# Gradle 8.7+ 과 Android SDK(ANDROID_HOME) 가 설치돼 있어야 합니다.
gradle wrapper          # 최초 1회, gradlew 생성
./gradlew assembleDebug
```

## 서명된 릴리스 APK

`app/build.gradle.kts` 의 `release` 블록에 서명 설정을 추가하세요.

```kotlin
signingConfigs {
    create("release") {
        storeFile = file("../keystore.jks")
        storePassword = System.getenv("KS_PASS")
        keyAlias = "autotouch"
        keyPassword = System.getenv("KEY_PASS")
    }
}
buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
    }
}
```

키스토어 생성:

```bash
keytool -genkeypair -v -keystore keystore.jks -alias autotouch \
        -keyalg RSA -keysize 2048 -validity 10000
```

## 요구 사항

- 최소 Android 7.0 (API 24)
- 접근성 서비스 권한 (제스처 실행)
- `SYSTEM_ALERT_WINDOW` 는 폴백용으로만 선언 — 보통 접근성 오버레이로 동작해서 별도 허용이 필요 없습니다

## 주의

게임·앱의 이용약관에서 자동 입력을 금지하는 경우가 있습니다. 사용 책임은 사용자에게 있습니다.

## 프로젝트 구조

```
app/src/main/java/com/koosy/autotouch/
├── MainActivity.kt        설정 화면, 시퀀스 편집
├── ActionAdapter.kt       동작 목록 RecyclerView
├── ActionItem.kt          동작 모델 (TAP / SWIPE)
├── ScriptStore.kt         SharedPreferences JSON 영속화
├── AutoTouchService.kt    접근성 서비스 + 제스처 실행 엔진
├── OverlayController.kt   플로팅 패널 / 좌표 선택 / 마커 오버레이
├── PickerView.kt          좌표 선택 중 그리기
└── MarkerView.kt          등록 좌표 마커 그리기
```
