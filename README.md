# GamePadKeyboard (Android / Kotlin)

시스템 키보드(IME)를 띄우고, 그 위에 오버레이 윈도우를 붙인 뒤 **게임패드로 커서를 움직여 키를 눌러 입력하는** 샘플.

오버레이가 키보드 위에 그려지는지, 터치가 통과하는지, 게임패드 커서 좌표로 실제 키 입력이 되는지를 Galaxy Note8 실기기에서 전부 실측한 결과가 함께 들어 있다. 게임패드 입력만 보려면 [게임패드로 키보드 입력하기](#게임패드로-키보드-입력하기-a안) 로 바로 가면 된다.

## 동작 원리

1. **키보드 띄우기** — `EditText` 에 포커스를 주고 `InputMethodManager.showSoftInput()` 호출.
2. **키보드 높이 감지** — `KeyboardInsetWatcher`
   - API 30+ : `WindowInsetsCompat.Type.ime()` 의 `bottom` 값을 사용 (정확). 인셋이 시스템에 소비되지 않도록 `WindowCompat.setDecorFitsSystemWindows(window, false)` 를 켜고 하단 패딩을 직접 적용.
   - API 29 이하 : `ime()` 인셋이 없으므로 `getWindowVisibleDisplayFrame()` 과 화면 높이의 차이로 추정 (`adjustResize` 전제).
3. **오버레이 띄우기** — `KeyboardOverlayController`
   - `WindowManager.addView()` 로 **`TYPE_APPLICATION_OVERLAY`** (API 26+, 미만은 `TYPE_PHONE`) 윈도우 추가.
   - 이 타입은 z-order 상 IME 윈도우(2011)보다 위 레이어(2038)라서 **별도 트릭 없이 키보드 위에 그려진다.**
   - 윈도우 크기 = `MATCH_PARENT × 키보드 높이`, 위치 = `gravity TOP` + `y = 화면 실제 높이 - 키보드 높이`.
4. **아이콘만 가리기** — `overlay_keyboard_icons.xml` 의 루트 배경이 투명이라 아이콘(원형 배경 + 벡터 아이콘)이 놓인 자리만 키보드가 가려진다.

## 핵심 플래그

| 플래그 | 이유 |
|---|---|
| `FLAG_NOT_FOCUSABLE` | 오버레이가 포커스를 뺏으면 키보드가 내려간다. 필수. |
| `FLAG_NOT_TOUCHABLE` | 터치가 오버레이를 통과해 키보드로 전달됨 → 타이핑 정상 동작. 앱 내 스위치로 끄면 터치까지 차단. |
| `FLAG_NOT_TOUCH_MODAL` | 윈도우 바깥 터치를 아래 윈도우로 전달. |
| `FLAG_LAYOUT_IN_SCREEN` / `FLAG_LAYOUT_NO_LIMITS` | 좌표를 화면 절대 기준으로 잡고 내비게이션 바 영역까지 덮기 위함. |
| `PixelFormat.TRANSLUCENT` | 투명 배경 렌더링. |
| `windowAnimations = 0` | 키보드와 같이 즉시 표시(깜빡임 제거). |

### `gravity = BOTTOM` 을 쓰면 안 되는 이유

`gravity = BOTTOM` + `y = 0` 으로 붙이면 윈도우가 **부모 프레임 기준**으로 정렬된다. 부모 프레임의 바닥은 화면 바닥이 아니라 **내비게이션 바 위쪽**이다 (`FLAG_LAYOUT_NO_LIMITS` 를 줘도 마찬가지). 반면 키보드 윈도우는 내비바 아래까지 그려지므로, 결과적으로 오버레이가 내비바 높이(이 기기는 126px)만큼 위로 밀려 키보드 밖으로 삐져나온다.

Galaxy Note8(API 28, 1080×2220, 내비바 126px) 실측:

```
gravity=BOTTOM, y=0   → mFrame=[0,1091][1080,2094]   ← 키보드보다 126px 위
gravity=TOP,    y=1217 → mFrame=[0,1217][1080,2220]   ← 키보드 영역과 정확히 일치
```

그래서 `realScreenHeight()` (`Display.getRealSize()` / R+ 는 `currentWindowMetrics`) 로 내비바를 포함한 실제 화면 높이를 구해 `y = 실제높이 - 키보드높이` 를 직접 지정한다.

## 권한

`AndroidManifest.xml` 의 `SYSTEM_ALERT_WINDOW` 선언만으로는 부족하다.

```kotlin
Settings.canDrawOverlays(context)  // 확인
Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))  // 요청
```

설정 화면에서 돌아올 때 `resultCode` 는 항상 `RESULT_CANCELED` 이므로 `canDrawOverlays()` 로 직접 재확인해야 한다.

## 대안: 권한 없이 Dialog 로 덮기 (`FLAG_ALT_FOCUSABLE_IM`)

`SYSTEM_ALERT_WINDOW` 없이도 **자기 앱 화면 위에서는** 키보드를 덮을 수 있다. `DialogOverlayController` 참고.

```kotlin
val dialog = Dialog(this)
dialog.setContentView(R.layout.dialog_overlay)
dialog.window?.apply {
    addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)   // ★ IME 위로 올리는 핵심
    addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)    // 창 밖 터치 통과
    addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)      // 창 안 터치도 통과
    clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    setDimAmount(0f)
    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    setLayout(MATCH_PARENT, 키보드높이 - 내비바높이)
    setGravity(Gravity.BOTTOM)
}
dialog.show()
```

### 왜 IME 위로 올라가는가

`FLAG_ALT_FOCUSABLE_IM` 은 `FLAG_NOT_FOCUSABLE` 의 "IME 상호작용 여부"를 반전시킨다.

| 조합 | 결과 |
|---|---|
| `NOT_FOCUSABLE` 없음 + `ALT_FOCUSABLE_IM` | IME 가 필요 없는 창으로 취급 → **IME 를 덮을 수 있음** |
| `NOT_FOCUSABLE` 있음 + `ALT_FOCUSABLE_IM` | 반대로 IME 뒤로 밀림 |

WMS 는 IME 윈도우를 "IME target 바로 위"에 배치하는데, 이 플래그가 붙은 창은 target 후보에서 제외되므로 target 이 아래 Activity 에 남고 z-order 가 `Activity < IME < Dialog` 가 된다.

**즉 focusable 을 유지해야만 IME 위로 간다.** 터치를 통과시키려고 `FLAG_NOT_FOCUSABLE` 을 붙이면 오히려 IME 뒤로 내려간다. 대신 `FLAG_NOT_TOUCHABLE` 을 쓰면 포커스는 유지한 채 터치만 통과시킬 수 있다 (`NOT_TOUCHABLE` 은 z-order 에 영향 없음).

### 실측 (같은 기기, `appops ... SYSTEM_ALERT_WINDOW deny` 상태)

```
z-order:  #6 Dialog(ALT_FOCUSABLE_IM) → #7 InputMethod → #8 Activity   ← Dialog 가 위
Dialog:   mFrame=[0,1217][1080,2094]   fl=NOT_TOUCHABLE NOT_TOUCH_MODAL ALT_FOCUSABLE_IM
IME:      mInputShown=true                                              ← 키보드 안 내려감
타이핑:    아이콘 바로 위를 탭해도 아래 키가 그대로 입력됨 ("asdg")
```

### 터치 통과 자동 검증 (`DialogOverlayTouchThroughTest`)

위 실측을 손으로 탭하지 않고 코드로 재현하는 계측 테스트. 오버레이가 뜬 상태에서 **오버레이 영역 안의 좌표로 터치를 주입**하고, `EditText` 에 입력이 들어오는지 확인한다.

```bash
adb shell settings put secure show_ime_with_hard_keyboard 1   # 에뮬레이터에서 소프트 키보드 강제 표시
./gradlew connectedDebugAndroidTest
```

| 테스트 | 조건 | 기대 |
|---|---|---|
| `tapOnIcon_typesOnKeyboard_whenTouchesPassThrough` | `NOT_TOUCHABLE` 有 | 아래 키가 눌려 입력이 들어온다 |
| `tapOnIcon_isSwallowed_whenBlockingTouches` | `NOT_TOUCHABLE` 無 (터치 차단) | 입력이 없다 |

두 번째(대조군)가 같이 통과해야 첫 번째의 "입력됨" 이 **오버레이를 통과한 결과**라는 게 증명된다. 좌표는 `DialogOverlayController.contentBoundsOnScreen()` 이 준 실제 오버레이 사각형(= 덮고 있는 키보드 영역)의 가운데 줄 1/4·2/4·3/4 지점이라 해상도/키보드 높이에 하드코딩이 없다.

**왜 앱 코드가 아니라 계측 테스트인가** — 앱 프로세스에서 다른 윈도우(IME)로 이벤트를 주입하려면 `INJECT_EVENTS` (signature 권한)가 필요하다. `Instrumentation.sendPointerSync()` 는 `SecurityException` 으로 막히고, `Runtime.exec("input tap")` 도 같은 이유로 실패한다. 계측 프로세스는 `UiAutomation` 주입이 허용되므로 `UiDevice.click()` 이 `adb shell input tap` 과 동일한 시스템 경로로 실제 터치스크린 이벤트를 내려준다.

Note8(API 28) 실행 결과:

```
탭 좌표=[Point(270, 1655), Point(540, 1655), Point(810, 1655)], 입력된 텍스트='ㄴ하'   ← 통과
터치 차단 모드 탭 좌표=[동일],                                 입력된 텍스트=''       ← 차단
```

입력된 문자는 그 좌표에 어떤 키가 있는지(=IME 의 현재 자판)에 따라 달라진다. 한글 모드였던 위 실행에서는 `ㄴ하`, 영문 모드에서는 같은 좌표에서 `dgj` 가 들어왔다. 검증 대상은 **문자 자체가 아니라 "입력이 들어왔는가"** 다.

### 두 방식 비교

| | WindowManager + `TYPE_APPLICATION_OVERLAY` | Dialog + `ALT_FOCUSABLE_IM` |
|---|---|---|
| 권한 | `SYSTEM_ALERT_WINDOW` 필요 (사용자 승인 + Play 심사 소명) | **불필요** |
| 다른 앱 위 | 가능 | **불가** (자기 Activity 위에서만) |
| 윈도우 포커스 | Activity 가 유지 | **Dialog 로 넘어감** (BACK 키를 Dialog 가 먼저 먹음) |
| 화면 바닥까지 확장 | 가능 (`y = 실제높이 - 키보드높이`) | 불가 — 부모 프레임(내비바 위)에 갇힘 |
| 키보드 표시 유지 | ✅ | ✅ |
| 터치 통과 | ✅ `NOT_TOUCHABLE` | ✅ `NOT_TOUCHABLE` + `NOT_TOUCH_MODAL` |

### 주의할 점

- `NOT_TOUCH_MODAL` 만으로는 부족하다. 이 플래그는 **창 바깥** 터치만 통과시키므로, 창이 키보드를 덮는 순간 키 입력이 전부 막힌다. 실측에서 `NOT_TOUCHABLE` 없이는 `asd` 조차 입력되지 않았다.
- Dialog 가 포커스를 가져가므로 **BACK 키를 Dialog 가 먼저 소비**한다 (한 번 눌러도 키보드는 안 내려감).
- Dialog 는 키보드 상태와 연동되지 않는다. 키보드가 내려가도 그대로 남으므로 `KeyboardInsetWatcher` 콜백에서 직접 `dismiss()` 해줘야 한다.
- `Dialog(this)` 기본값은 제목 영역 + `FLAG_DIM_BEHIND` + 불투명 배경이다. `FEATURE_NO_TITLE`, `clearFlags(DIM_BEHIND)`, `setDimAmount(0f)`, 투명 배경을 모두 지정해야 키보드가 비쳐 보인다.

**결론**: 자기 앱 안에서만 가리면 되는 요구라면 Dialog 방식이 낫다. 권한 요청 UX 와 Play 심사 부담이 사라진다. 다른 앱의 키보드까지 덮어야 하면 `TYPE_APPLICATION_OVERLAY` 외에 답이 없다.

## 게임패드로 키보드 입력하기 (A안)

이 프로젝트의 본 목적. **게임패드로 오버레이 위의 커서를 움직이고, 버튼을 눌러 그 좌표에 터치를 주입해서 아래 시스템 키보드의 키를 누른다.** 손가락 대신 게임패드로 남의 키보드를 타이핑하는 셈이다.

```
┌─────────────────────────────┐
│  EditText  "ㄴ하"           │   ← ④ 키가 눌려 입력됨
├─────────────────────────────┤
│  q w e r t y u i o p        │
│   a s d[◯]g h j k l         │   ← ② 커서(오버레이, NOT_TOUCHABLE)
│    z x c v b n m            │   ← ③ 커서 좌표에 제스처 주입
└─────────────────────────────┘
      ↑ ① DPAD 로 커서 이동
```

### 전체 흐름

| 단계 | 담당 | 무슨 일이 일어나는가 |
|---|---|---|
| ① DPAD 입력 | `DialogOverlayController.handleGamepadKey` | 오버레이 Dialog 의 `OnKeyListener` 가 받아 커서를 `cursor_step`(12dp)씩 이동 |
| ② 좌표 확정 | `cursorCenterOnScreen()` | 커서 중심의 **화면 절대 좌표**를 `getLocationOnScreen()` 으로 계산 |
| ③ 버튼 A | `MainActivity.injectTapAt` → `TapInjectionService.tapAt` | 접근성 서비스가 `dispatchGesture()` 로 그 좌표에 실제 탭을 주입 |
| ④ 키 눌림 | 시스템 → IME | 주입된 터치가 오버레이를 통과해 아래 키보드에 닿고 `EditText` 에 문자가 들어온다 |

### 왜 게임패드 키가 IME 를 거치지 않고 오버레이로 오는가

게임패드 키 이벤트는 **포커스를 가진 윈도우**로 간다. `FLAG_ALT_FOCUSABLE_IM` Dialog 는 포커스를 가지면서도 IME target 후보에서 빠지므로, 키가 IME 를 먼저 거치지 않고 곧바로 Dialog 로 온다. 그래서 커서 로직은 Activity 가 아니라 **오버레이 창 쪽**에 둔다 — Activity 에 두면 삼성 IME 가 DPAD 를 자체 키 탐색용으로 삼킬 수 있다.

```kotlin
// DialogOverlayController.show()
d.setOnKeyListener { _, keyCode, event -> handleGamepadKey(keyCode, event) }
```

커서는 `translationX/Y` 로 옮기고 오버레이 사각형 안으로 클램프한다. 오버레이가 키보드 영역과 1:1 대응하므로 이 클램프가 곧 "키보드 밖으로 안 나감" 이 된다.

### ③ 이 이 프로젝트에서 가장 어려운 부분이다 — 앱은 터치를 주입할 수 없다

윈도우 경계를 넘는 이벤트 주입은 전부 `INJECT_EVENTS`(signature 권한)에 걸린다. 앱 코드로 시도할 수 있는 방법은 모두 막힌다:

| 시도 | 결과 |
|---|---|
| `view.dispatchTouchEvent()` | 자기 뷰 계층 안에서만 전파. 윈도우를 넘지 못함 |
| `Instrumentation().sendPointerSync()` | `SecurityException` (앱 UID 엔 `INJECT_EVENTS` 없음) |
| `Runtime.exec("input tap x y")` | 같은 이유로 실패 (`input` 이 앱 UID 로 돌기 때문) |
| `UiAutomation.injectInputEvent()` | 계측 프로세스 전용. 앱에서 접근 불가 |

유일한 우회로가 **AccessibilityService + `dispatchGesture()`** 다 (API 24+). 접근성 서비스는 시그니처 권한 없이 — 사용자가 설정에서 켜주면 — 화면 절대 좌표에 실제 터치 제스처를 주입할 수 있고, 이 제스처는 IME 윈도우에도 도달한다. 스위치 액세스류 보조기기가 쓰는 것과 같은 경로다.

```kotlin
// TapInjectionService.tapAt()
val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
val gesture = GestureDescription.Builder()
    .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))   // 60ms: 너무 짧으면 IME 가 탭으로 안 봄
    .build()
dispatchGesture(gesture, callback, null)
```

필수 설정 두 가지:

```xml
<!-- res/xml/tap_injection_service.xml -->
<accessibility-service android:canPerformGestures="true" ... />   <!-- ★ dispatchGesture 전제조건 -->
```
```xml
<!-- AndroidManifest.xml — BIND_ACCESSIBILITY_SERVICE 로 보호해야 시스템만 바인드한다 -->
<service android:name=".TapInjectionService" android:exported="true"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"> … </service>
```

`canRetrieveWindowContent` 는 켜지 않았다. 좌표만 있으면 되고, 권한 범위를 최소로 두는 편이 Play 심사에도 유리하다.

접근성 서비스는 **앱과 같은 프로세스**에서 돌기 때문에 IPC 없이 싱글턴으로 바로 호출할 수 있다:

```kotlin
dialogOverlay.onCursorClick = { point -> injectTapAt(point) }   // MainActivity
TapInjectionService.instance?.tapAt(point.x, point.y)
```

### 접근성 서비스를 앱이 켜는 방법

**결론: 일반 배포 앱은 스스로 켤 수 없다.** `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 쓰기에 `WRITE_SECURE_SETTINGS` 가 필요하고, 이 권한은 `signature|privileged|development` 라서 Play 로 배포한 앱에는 절대 부여되지 않는다. 부여된다면 악성 앱이 사용자 모르게 화면을 읽고 터치를 주입할 수 있으니 막혀 있는 게 정상이다.

`AccessibilityPermission` 이 가능한 경로를 모두 담고 있고, 권한 유무에 따라 자동으로 갈라진다.

| 경로 | 대상 | 사용자 조작 | 실측 |
|---|---|---|---|
| `enableSelf()` — 설정에 직접 쓰기 | 개발/테스트/키오스크 | **없음** | ✅ 아래 참조 |
| `openServiceSettings()` — 설정 화면으로 안내 | 일반 배포 | 토글 1회 (필수) | ✅ 아래 참조 |
| Device Owner (MDM) | 사내 배포 | 없음 (프로비저닝 시 정책) | 미검증 |

#### 1. 코드로 직접 켜기 (개발/테스트)

`WRITE_SECURE_SETTINGS` 는 `development` 플래그가 있어 **adb 로 부여할 수 있다.** 시스템 서명 없이도 된다.

```bash
adb shell pm grant com.example.keyboardoverlay android.permission.WRITE_SECURE_SETTINGS
```

부여 후 앱의 `접근성 서비스 켜기` 버튼을 누르면 설정 화면을 거치지 않고 바로 켜진다. API 33 에뮬레이터 실측:

```
A11yPermission: 접근성 설정 직접 변경: enabled=true, value='...TapInjectionService'
TapInjection:   접근성 서비스 연결됨 (제스처 주입 가능)      ← 0.1초 뒤 연결
```

주의할 점:

- **다른 접근성 서비스를 덮어쓰지 말 것.** 이 설정은 `:` 로 구분된 목록이다. 통째로 덮으면 사용자의 TalkBack 이 꺼진다 — 시각장애 사용자에게는 심각한 사고다. `enableSelf()` 는 기존 항목을 파싱해 보존하고 자기 것만 추가한다.
- `accessibility_enabled` 는 접근성 기능 **전체** 스위치다. 목록이 비면 0, 하나라도 있으면 1로 맞춰야 한다.
- **재바인드에는 껐다 켜기가 필요하다.** 앱 프로세스가 죽으면 서비스도 죽는데, AMS 는 설정값이 *변할 때만* 재바인드한다. 값이 그대로면 다시 써도 no-op 이다. `rebind()` 가 이걸 처리한다.

#### 2. 설정 화면으로 안내하기 (일반 배포)

`openServiceSettings()` 가 후보 3개를 순서대로 시도한다.

| 후보 | 결과 |
|---|---|
| `android.settings.ACCESSIBILITY_DETAILS_SETTINGS` (서비스 상세 직행) | ❌ 권한 부족으로 실패 |
| 접근성 설정 + `:settings:fragment_args_key` (항목 강조) | ROM 에 따라 다름 |
| 접근성 설정 최상단 | ✅ 항상 동작 |

첫 후보는 매력적이지만 **일반 앱은 쓸 수 없다.** 공개 API 상수가 아예 없고(설치된 SDK 34/36/37.1 모두 `Settings` 에 없음), 호출에 별도 권한을 요구한다:

```
SecurityException: Permission Denial: starting Intent { act=...ACCESSIBILITY_DETAILS_SETTINGS }
  requires android.permission.OPEN_ACCESSIBILITY_DETAILS_SETTINGS
```

`resolveActivity()` 로는 걸러지지 않는다 — 컴포넌트는 존재하고 권한 단계에서 막히므로 **try/catch 가 필수**다. 시스템 앱으로 서명해 배포한다면 이 경로가 가장 깔끔하다.

#### 상태를 세 가지로 구분할 것

"설정에서 켜짐" 과 "실제로 연결됨" 은 다른 값이다. 사용자가 켰는데도 시스템이 바인드하지 못하는 경우가 실제로 있다.

```
enabled services: { ...TapInjectionService }   ← 설정 ON
binding services: { ...TapInjectionService }   ← 바인드 시도에서 멈춤
bound services:   { }                          ← 연결 안 됨
```

이 상태에서 `instance == null` 만 보고 "꺼져 있습니다" 라고 안내하면 사용자는 이미 켰으니 혼란스럽다. `TapInjectionService.isEnabledInSettings()` 로 설정값을 따로 읽어 구분한다.

| 상태 | 표시 | 안내 |
|---|---|---|
| `isConnected` | 연결됨 | — |
| 설정 ON + 미연결 | 설정 ON·미연결 | 껐다 켜기, 안 되면 재부팅 |
| 설정 OFF | 꺼짐 | 설정에서 켜기 |

실제로 이 프로젝트를 실측하다 `binding` 에 항목이 남아 껐다 켜도 절대 연결되지 않는 상태를 만났다(설정을 끈 뒤에도 `binding services` 가 비지 않았다). AMS 재시작(= 재부팅)으로만 풀렸다.

### 주입한 제스처도 오버레이가 먹을 수 있다

`dispatchGesture` 로 넣은 터치도 **일반 터치 디스패치를 탄다.** 따라서 오버레이가 그 좌표에서 터치를 받는 상태(`FLAG_NOT_TOUCHABLE` 없음)면 키보드가 아니라 오버레이가 먼저 먹는다. 커서는 반드시 `NOT_TOUCHABLE` 오버레이 위에 있어야 한다. 앱의 `터치까지 차단` 스위치를 켜면 이 현상을 직접 재현할 수 있고, 실측 스크립트의 대조군이 정확히 이것을 측정한다.

### 실행 방법

```bash
./gradlew installDebug
```

1. 앱 실행 → `접근성 서비스 설정 열기 (좌표 클릭 주입)` → **GamePadKeyboard** 켜기
2. 앱으로 돌아와 `키보드 띄우기`
3. `권한 없이 Dialog 로 덮기 (ALT_FOCUSABLE_IM)` → 초록 커서가 키보드 중앙에 나타난다
4. 게임패드 **DPAD** 로 커서를 원하는 키 위로 이동 → **A 버튼** (또는 DPAD 중앙/Enter) 으로 입력

adb 로 접근성 권한을 바로 주려면 (설정을 직접 쓰는 방법):

```bash
adb shell settings put secure enabled_accessibility_services \
    com.example.keyboardoverlay/com.example.keyboardoverlay.TapInjectionService
adb shell settings put secure accessibility_enabled 1
```

또는 앱이 스스로 켜게 만들려면 ([접근성 서비스를 앱이 켜는 방법](#접근성-서비스를-앱이-켜는-방법) 참조):

```bash
adb shell pm grant com.example.keyboardoverlay android.permission.WRITE_SECURE_SETTINGS
# 이후 앱의 "접근성 서비스 켜기" 버튼만 누르면 된다
```

> ⚠️ **앱이 실행된 뒤에** 켜야 한다. 앱 프로세스가 죽으면 접근성 서비스도 같이 죽는데(`Force stopping service`), AMS 는 `enabled_accessibility_services` **설정이 다시 쓰일 때만** 재바인드한다. 값이 이미 들어 있으면 쓰기가 no-op 이 되므로, 껐다 켜서 변화를 만들어야 한다.

키 매핑 (`DialogOverlayController.handleGamepadKey`):

| 버튼 | 동작 |
|---|---|
| `DPAD_LEFT/RIGHT/UP/DOWN` | 커서 이동 (`cursor_step` = 12dp. 키 하나 폭보다 작게 잡아 키 안에서 미세 조정이 되게) |
| `BUTTON_A` / `DPAD_CENTER` / `ENTER` | 커서 좌표에 탭 주입 |

아날로그 스틱은 미구현이다. 붙이려면 오버레이 창에서 `onGenericMotionEvent` 로 `SOURCE_JOYSTICK` 의 `AXIS_X/Y` 를 읽어 같은 `moveCursorBy()` 를 호출하면 된다.

### 실측 (`tools/verify_gamepad_injection.sh`)

```bash
tools/verify_gamepad_injection.sh <adb-serial>          # 기본: 입력이 들어와야 통과
tools/verify_gamepad_injection.sh <adb-serial> block    # 대조군: 입력이 없어야 통과
```

스크립트는 앱을 띄우고 → 키보드/오버레이를 올리고 → 접근성 서비스를 켜고 → `input keyevent` 로 DPAD·A 버튼을 주입한 뒤, 결과를 logcat 에서 읽는다. 끝나면 **접근성 설정을 원래 상태로 되돌린다** (사용자의 다른 접근성 서비스는 목록에 그대로 남긴다). 주입이 전부 시스템 레벨이라 실물 게임패드와 동일한 경로를 탄다.

Galaxy Note8 (SM-N950N, API 28, 삼성 키보드) 실행 결과:

```
== 2. 키보드 띄우기 ==            키보드 높이: 1003px
== 3. ALT_FOCUSABLE_IM 오버레이 == 커서 시작 위치: 539 1655
== 5. DPAD (→×4, ↓×2) ==
== 6. BUTTON_A ==
  KeyboardOverlay: 커서 클릭 → 주입 좌표=Point(667, 1719), 접수=true    ← ①② 게임패드 → 커서 좌표
  TapInjection:    제스처 완료 (667, 1719)                              ← ③ dispatchGesture 성공
  입력된 텍스트:   'h'                                                  ← ④ 키가 눌렸다

결과: 성공 — 게임패드 커서 좌표에 주입한 제스처가 키보드 키를 눌렀다 ('h')
```

커서 이동량도 계산과 일치한다: 시작 `(539,1655)` → `(667,1719)`, DPAD 오른쪽 4회 = 12dp×4 ≈ 128px, 아래 2회 ≈ 64px.

대조군 (`block`):

```
결과: 성공(대조군) — 오버레이가 터치를 받는 상태에서는 주입 제스처가 키보드에 닿지 않았다
  커서 클릭 → 주입 좌표=Point(667, 1719), 접수=true    ← 주입 자체는 성공했으나
  제스처 완료 (667, 1719)
  입력된 텍스트: ''                                     ← 오버레이가 먹어서 키에 닿지 않음
```

**대조군이 통과해야 위의 `'h'` 가 오버레이를 통과한 결과라는 게 증명된다.** 대조군 없이는 "오버레이가 애초에 그 자리에 없었다" 는 가능성과 구분되지 않는다.

### 계측 테스트(androidTest)로는 이 검증을 할 수 없다

`GamepadCursorTapTest` 도 함께 두었지만, 이 기기에서는 실행되지 않는다. 이유가 두 개 겹친다:

1. **`am instrument` 가 앱 패키지를 force-stop 한다** (`Force stopping ... : start instr`). 접근성 서비스가 같이 죽고 계측 프로세스로는 재바인드되지 않는다.
2. **접근성 서비스가 붙으면 `uiautomator dump` 가 죽는다** — `ERROR: null root node returned by UiTestAutomationBridge`. `canRetrieveWindowContent` 와 무관하게 재현된다. 그래서 실측 스크립트도 UI 탐색·탭을 서비스를 켜기 **전에** 모두 끝내고, 켠 뒤에는 키 주입과 logcat 만 쓴다.

`uiautomator dump` 관련해 한 가지 더: dump 가 실패해도 **직전 덤프 파일이 그대로 남는다.** 파일만 읽으면 낡은 좌표로 엉뚱한 곳을 탭하게 되므로(EditText 가 포커스되면 `adjustResize` 로 버튼 위치가 전부 바뀐다), 스크립트는 매번 파일을 지우고 dump 명령 자체의 성공 메시지를 확인한다.

### 대안: 직접 IME 만들기 (`InputMethodService`)

권한이 아예 필요 없는 다른 접근법. 시스템 키보드를 덮어 "키를 대신 누르는" 게 아니라, 내 IME 가 게임패드 입력을 받아 `InputConnection.commitText()` 로 문자를 직접 커밋한다. Android TV 의 게임패드용 키보드가 이 구조다.

| | 접근성 서비스 (이 프로젝트) | 직접 IME |
|---|---|---|
| 권한 | 접근성 권한 (사용자 승인 + Play 심사 소명) | 불필요 |
| 다른 앱 | 남의 키보드도 누를 수 있다 | 내 IME 를 선택한 앱에서만 |
| 키 배열 | 남의 자판을 좌표로 추측해야 한다 | 내가 소유하므로 정확 |
| 입력 정확도 | 좌표 → 키 매핑이 IME/언어에 따라 바뀐다 | 매핑이 고정 |

**정리**: "남의 키보드 위에 커서를 띄우고 대신 눌러준다" 는 요구라면 접근성 서비스 말고는 답이 없다. 입력 UI 자체를 소유해도 된다면 직접 IME 가 훨씬 견고하다.

## 파일 구조

```
app/src/main/
├── AndroidManifest.xml                        SYSTEM_ALERT_WINDOW, 접근성 서비스, adjustResize
├── java/com/example/keyboardoverlay/
│   ├── MainActivity.kt                        UI, 권한 요청, 키보드/오버레이/주입 연결
│   ├── KeyboardInsetWatcher.kt                키보드 높이 감지 (R+ / 레거시 분기)
│   ├── KeyboardOverlayController.kt           ① WindowManager 오버레이 (권한 필요)
│   ├── DialogOverlayController.kt             ② Dialog + ALT_FOCUSABLE_IM + 게임패드 커서
│   ├── TapInjectionService.kt                 ③ 접근성 서비스 — 좌표에 터치 제스처 주입
│   └── AccessibilityPermission.kt             ③ 접근성 권한 켜기 (직접 쓰기 / 설정 안내)
└── res/
    ├── layout/activity_main.xml
    ├── layout/overlay_keyboard_icons.xml      ① 오버레이 내용물 (파란 아이콘)
    ├── layout/dialog_overlay.xml              ② Dialog 내용물 (게임패드 커서)
    ├── xml/tap_injection_service.xml          ③ canPerformGestures="true"
    └── drawable/bg_cursor, ic_lock, ic_shield, ic_star, bg_overlay_icon(_alt)

app/src/androidTest/java/com/example/keyboardoverlay/
├── DialogOverlayTouchThroughTest.kt           ② 터치 주입으로 키 입력 통과 검증 (동작함)
└── GamepadCursorTapTest.kt                    ③ 게임패드 체인 (계측 환경에선 실행 불가 — 위 참조)

tools/
└── verify_gamepad_injection.sh                ③ 실기기 실측 스크립트 (계측 대신 사용)
```

## 빌드 / 실행

```bash
./gradlew assembleDebug
./gradlew installDebug
```

실행 후: `다른 앱 위에 그리기 권한 요청` → 승인 → 앱 복귀 → `키보드 띄우기`.

권한을 adb 로 바로 주려면:

```bash
adb shell appops set com.example.keyboardoverlay SYSTEM_ALERT_WINDOW allow
```

## 실기기 검증 결과

Galaxy Note8 (SM-N950N, Android 9 / API 28, 삼성 키보드) 에서 확인.

| 항목 | 결과 |
|---|---|
| 오버레이가 IME 위에 그려지는가 | ✅ 윈도우 순서 `#3 Sys2038(앱)` > `#7 InputMethod` |
| 오버레이 위치 | ✅ `mFrame=[0,1217][1080,2220]` = 키보드 영역과 일치 |
| 키보드 높이 감지 (레거시 경로) | ✅ 1003px |
| 포커스 유지 (키보드 안 내려감) | ✅ `mCurrentFocus` 는 계속 MainActivity |
| 터치 통과 (기본) | ✅ 오버레이 위를 탭 → 아래 키가 그대로 입력됨 |
| 터치 차단 모드 | ✅ 같은 위치 탭 → 입력 없음 (`NOT_TOUCHABLE` 플래그 제거 확인) |
| 키보드 내림 → 오버레이 제거 | ✅ |
| 홈 이동(`onStop`) → 오버레이 제거 | ✅ |
| 오버레이 ON/OFF 토글 | ✅ 키보드 표시 상태 유지한 채 add/remove |
| 게임패드 DPAD → 커서 이동 | ✅ `(539,1655)` → `(667,1719)` (12dp 스텝과 일치) |
| 게임패드 A → 좌표에 제스처 주입 | ✅ `dispatchGesture` 접수·완료 |
| 주입 제스처 → 키 눌림 | ✅ `'h'` 입력됨 |
| 터치 차단 시 주입 제스처 | ✅ 입력 없음 (오버레이가 먹음) |

접근성 권한 켜기는 API 33 에뮬레이터에서 확인 (`WRITE_SECURE_SETTINGS` 는 기기 종류와 무관한 AOSP 동작).

| 항목 | 결과 |
|---|---|
| `adb pm grant WRITE_SECURE_SETTINGS` | ✅ `development` 보호수준이라 부여됨 |
| 앱이 코드로 자체 활성화 | ✅ 설정 화면 없이 켜지고 0.14초 뒤 연결 |
| 권한 회수 후 폴백 | ✅ 상세 페이지 실패 → 접근성 설정 화면으로 이동, 설정값 변화 없음 |
| 연결 상태 표시 갱신 | ✅ 콜백으로 `연결됨` 으로 즉시 바뀜 |

> 터치 차단 모드는 커서 위치뿐 아니라 **오버레이 윈도우 전체(= 키보드 전 영역)** 의 터치를 먹는다. 특정 영역만 막고 싶다면 루트를 `NOT_TOUCHABLE` 로 두고 그 영역에만 별도의 작은 오버레이 윈도우를 붙여야 한다.

## 커스터마이징

- **가리는 범위 넓히기** — `overlay_keyboard_icons.xml` 루트 `FrameLayout` 에 `android:background="#CC000000"` 을 주면 키보드 전체가 덮인다.
- **커서 모양/크기** — `drawable/bg_cursor.xml` (속은 반투명, 테두리만 진하게 두면 아래 키가 보인다), `dimen/cursor_size`.
- **커서 이동 단위** — `dimen/cursor_step` (기본 12dp). 키 하나 폭(대략 36dp)보다 작게 두면 키 안에서 미세 조정이 된다. 키 단위로 껑충 넘어가게 하려면 키 폭에 맞춰 키운다.
- **오버레이 내용 위치** — 루트가 키보드 영역과 1:1 대응하므로 `layout_gravity` / `margin` 만으로 특정 키 위에 올릴 수 있다.
- **키 입력 차단** — 앱 내 `터치까지 차단` 스위치 (`KeyboardOverlayController.blockTouches`). 플래그는 윈도우 추가 시점에 확정되므로 변경 시 내렸다 다시 올린다. 이 스위치를 켜면 게임패드 주입도 함께 막힌다.
- **앱 밖에서도 유지** — 지금은 Activity 수명에 묶여 `onStop()` 에서 내려간다. 다른 앱의 키보드 위에도 띄우려면 이 컨트롤러를 포그라운드 Service 로 옮기고, 키보드 감지는 인셋 대신 `AccessibilityService` 또는 별도 `PopupWindow` 방식으로 바꿔야 한다.

## 주의사항

- **보안 키보드**: 금융앱 등에서 쓰는 IME 가 `FLAG_SECURE` 를 설정하면 화면 캡처는 막히지만 오버레이 자체는 여전히 그려진다. 반대로 일부 제조사 ROM 은 특정 시스템 UI 위 오버레이를 강제로 숨긴다.
- **Android 12+ 터치 가로채기 제한**: 다른 앱 위에서 터치를 소비하는 오버레이는 시스템이 차단할 수 있다. 이 샘플의 기본값(`FLAG_NOT_TOUCHABLE`)은 해당되지 않는다.
- **Play 정책**: `SYSTEM_ALERT_WINDOW` 와 접근성 API 는 모두 심사 시 사용 목적 소명이 필요하다. 접근성 서비스는 특히 엄격하다 — 실제로 접근성을 돕는 용도가 아니면 반려된다.
- 삼성 등 일부 IME 는 툴바/자동완성 영역 높이가 동적으로 변한다. `KeyboardInsetWatcher` 가 변화마다 콜백을 주므로 `show()` 에서 높이를 갱신한다.
- **좌표 → 키 매핑은 보장되지 않는다.** 커서 좌표에 어떤 키가 있는지는 IME 종류·언어·기기 해상도에 따라 전부 달라진다 (같은 좌표에서 영문 모드 `d`, 한글 모드 `ㄴ` 이 나왔다). 특정 키를 정확히 노려야 한다면 좌표 추측 대신 직접 IME 를 만드는 편이 맞다.
- **키보드가 내려가도 커서는 남는다.** Dialog 는 키보드 상태와 연동되지 않으므로 `KeyboardInsetWatcher` 콜백에서 `dismiss()` 를 호출해야 한다.
