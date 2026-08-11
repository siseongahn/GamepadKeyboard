#!/usr/bin/env bash
#
# A안 실측: 게임패드 → 오버레이 커서 → 접근성 제스처 주입 → 키보드 입력.
#
# 계측 테스트(androidTest)로는 이 검증을 할 수 없다. `am instrument` 가 앱 패키지를
# force-stop 하면(`ActivityManager: Force stopping ... : start instr`) AMS 가 그 프로세스로
# 접근성 서비스를 다시 바인드하지 않기 때문이다. 그래서 계측 없이 실제 앱을 adb 로 조작한다.
# 주입은 전부 시스템 레벨(`input keyevent`, dispatchGesture)이라 실물 게임패드와 같은 경로다.
#
# 사용법: tools/verify_gamepad_injection.sh [adb-serial] [block]
#   block 을 주면 대조군: "터치까지 차단" 을 켜서 오버레이가 터치를 받게 만든다.
#   이때는 주입한 제스처를 오버레이가 먼저 먹으므로 **입력이 없어야** 정상이다.
#   (이 대조군이 통과해야 기본 모드의 "입력됨" 이 오버레이를 통과한 결과라는 게 증명된다.)
#
set -uo pipefail

SERIAL="${1:-}"
MODE="${2:-pass}"
if [ -n "$SERIAL" ]; then ADB=(adb -s "$SERIAL"); else ADB=(adb); fi

PKG=com.example.keyboardoverlay
SERVICE="$PKG/$PKG.TapInjectionService"
REMOTE_DUMP=/sdcard/kb_overlay_dump.xml

fail() { echo "실패: $*" >&2; exit 1; }   # 복구는 EXIT trap 이 담당

# ---- UI 덤프 헬퍼 ----

# `uiautomator dump` 는 화면이 애니메이션 중이면 조용히 실패한다("could not get idle state").
#
# 그때 **직전 덤프 파일이 그대로 남아 있다**는 게 함정이다. 파일만 읽으면 낡은 좌표를 읽고
# 엉뚱한 곳을 탭한다 (EditText 가 포커스되면 ScrollView 가 스크롤돼 버튼 위치가 전부 바뀐다).
# 그래서 매번 파일을 지우고 dump 명령 자체의 성공 메시지를 확인한다.
dump() {
    local attempt result out
    for attempt in 1 2 3 4 5; do
        "${ADB[@]}" shell rm -f "$REMOTE_DUMP" >/dev/null 2>&1
        result="$("${ADB[@]}" shell uiautomator dump "$REMOTE_DUMP" 2>&1)"
        case "$result" in
            *"dumped to"*)
                out="$("${ADB[@]}" shell cat "$REMOTE_DUMP" 2>/dev/null)"
                if [ "${out#*hierarchy}" != "$out" ]; then
                    echo "$out" | tr '<' '\n'
                    return 0
                fi
                ;;
        esac
        sleep 1
    done
    return 1
}

# 속성값으로 노드를 찾아 bounds 를 "x1 y1 x2 y2" 로 출력
bounds_of() {
    local attr="$1" value="$2"
    dump | grep -F "$attr=\"$value\"" | head -1 |
        sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/'
}

center_of() {
    local b; b="$(bounds_of "$1" "$2")"
    [ -n "$b" ] || return 1
    # shellcheck disable=SC2086
    set -- $b
    echo "$(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"
}

# 버튼 텍스트로 탭. 레이아웃이 adjustResize 로 움직이므로 매번 다시 덤프해서 좌표를 잡는다.
tap_text() {
    local c; c="$(center_of text "$1")" || fail "노드를 찾을 수 없다: $1"
    # shellcheck disable=SC2086
    set -- $c
    echo "  탭 ($1, $2)"
    "${ADB[@]}" shell input tap "$1" "$2"
}

# 스위치는 텍스트에 상태("사용 중지")가 붙으므로 부분 일치로 찾는다.
tap_contains() {
    local b; b="$(dump | grep -F "$1" | grep -F 'Switch' | head -1 |
        sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')"
    [ -n "$b" ] || fail "스위치를 찾을 수 없다: $1"
    # shellcheck disable=SC2086
    set -- $b
    echo "  탭 ($(( ($1 + $3) / 2 )), $(( ($2 + $4) / 2 )))"
    "${ADB[@]}" shell input tap "$(( ($1 + $3) / 2 ))" "$(( ($2 + $4) / 2 ))"
}

press() { "${ADB[@]}" shell input keyevent "$1"; }

# ---- 접근성 서비스 ----

ORIG_SERVICES="$("${ADB[@]}" shell settings get secure enabled_accessibility_services | tr -d '\r')"
ORIG_ENABLED="$("${ADB[@]}" shell settings get secure accessibility_enabled | tr -d '\r')"

# 목록에 우리 서비스가 이미 있으면 직전 실행이 남긴 잔여물이다. "원래 값" 으로 취급해서
# 복구 때 되살리면 사용자 기기에 켜진 채 남는다. 그래서 미리 걷어낸다.
if [ "$ORIG_SERVICES" != "null" ] && [ -n "$ORIG_SERVICES" ]; then
    ORIG_SERVICES="$(echo "$ORIG_SERVICES" | tr ':' '\n' | grep -vF "$SERVICE" | paste -sd: -)"
    [ -z "$ORIG_SERVICES" ] && ORIG_SERVICES="null"
fi

restore_settings() {
    if [ -z "$ORIG_SERVICES" ] || [ "$ORIG_SERVICES" = "null" ]; then
        "${ADB[@]}" shell settings delete secure enabled_accessibility_services >/dev/null
    else
        "${ADB[@]}" shell settings put secure enabled_accessibility_services "$ORIG_SERVICES"
    fi
    "${ADB[@]}" shell settings put secure accessibility_enabled "${ORIG_ENABLED:-0}"
    echo "접근성 설정 복구: services=${ORIG_SERVICES}, enabled=${ORIG_ENABLED}"
}
trap restore_settings EXIT

# 반드시 **앱이 뜬 뒤에** 호출해야 한다.
#
# 앱 프로세스가 죽으면(force-stop, `am instrument` 등) 접근성 서비스도 같이 죽고
# (`ActivityManager: Scheduling restart of crashed service` → `Force stopping service`),
# AMS 는 **enabled_accessibility_services 설정이 다시 쓰일 때만** 재바인드한다.
# 값이 이미 들어 있으면 쓰기가 no-op 이 되므로 껐다 켜서 변화를 만들어야 한다.
enable_service() {
    local others=""
    if [ -n "$ORIG_SERVICES" ] && [ "$ORIG_SERVICES" != "null" ]; then
        # 사용자의 다른 접근성 서비스(TalkBack 등)는 목록에 그대로 남긴다.
        others="$(echo "$ORIG_SERVICES" | tr ':' '\n' | grep -vF "$SERVICE" | paste -sd: -)"
    fi

    if [ -z "$others" ]; then
        "${ADB[@]}" shell settings delete secure enabled_accessibility_services >/dev/null
    else
        "${ADB[@]}" shell settings put secure enabled_accessibility_services "$others"
    fi
    sleep 1

    local target="$SERVICE"
    [ -n "$others" ] && target="$others:$SERVICE"
    "${ADB[@]}" shell settings put secure enabled_accessibility_services "$target"
    "${ADB[@]}" shell settings put secure accessibility_enabled 1
}

# 서비스 객체가 실제로 살아 있는지는 앱이 찍는 로그로 확인한다.
# dumpsys 의 "bound services" 는 프로세스가 죽은 뒤에도 잠시 남아 있어 믿을 수 없다.
service_connected() {
    "${ADB[@]}" logcat -d -s TapInjection:I 2>/dev/null | grep -q "접근성 서비스 연결됨"
}

# 앱의 상태 표시(txtStatus)에서 감지된 키보드 높이를 읽는다. sleep 추측보다 정확하다.
keyboard_height() {
    dump | grep -F 'resource-id="com.example.keyboardoverlay:id/txtStatus"' | head -1 |
        sed -nE 's/.*키보드 높이: ([0-9]+)px.*/\1/p'
}

# 이 앱의 접근성 서비스가 붙어 있으면 `uiautomator dump` 가
# "ERROR: null root node returned by UiTestAutomationBridge" 로 실패한다
# (canRetrieveWindowContent 와 무관하게 재현됨). 그래서 **UI 탐색·탭은 서비스를 켜기 전에**
# 모두 끝내고, 켠 뒤에는 키 이벤트 주입과 logcat 읽기만 한다.
echo "== 1. 앱 실행 (접근성 서비스 OFF 상태) =="
"${ADB[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
"${ADB[@]}" shell wm dismiss-keyguard >/dev/null 2>&1
"${ADB[@]}" shell am force-stop "$PKG"
"${ADB[@]}" shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 3

# 화면이 꺼져 있거나 잠금화면(Bouncer)이면 앱 창이 안 뜬다. 원인을 바로 알려준다.
FOCUS="$("${ADB[@]}" shell dumpsys window | grep -m1 mCurrentFocus)"
case "$FOCUS" in
    *"$PKG"*) ;;
    *) fail "앱 창이 포커스를 갖지 못했다 (화면 꺼짐/잠금 해제 필요). 현재: ${FOCUS# *}" ;;
esac

echo "== 2. 키보드 띄우기 =="
tap_text "키보드 띄우기"
for _ in $(seq 15); do
    KB_HEIGHT="$(keyboard_height)"
    [ -n "$KB_HEIGHT" ] && break
    sleep 1
done
[ -n "${KB_HEIGHT:-}" ] || fail "키보드가 올라오지 않았다"
echo "  키보드 높이: ${KB_HEIGHT}px"

if [ "$MODE" = "block" ]; then
    echo "== 3a. 대조군: 터치까지 차단 켜기 (플래그는 show() 시점에 확정되므로 오버레이 전에) =="
    tap_contains "터치까지 차단"
    sleep 1
fi

echo "== 3. ALT_FOCUSABLE_IM 오버레이 띄우기 =="
tap_text "권한 없이 Dialog 로 덮기 (ALT_FOCUSABLE_IM)"
sleep 2

CURSOR_START="$(center_of content-desc "게임패드 커서")" || fail "커서를 찾을 수 없다 (오버레이가 안 떴다)"
echo "  커서 시작 위치: $CURSOR_START"

echo "== 4. 접근성 서비스 켜기 (앱이 살아있는 상태에서) =="
"${ADB[@]}" logcat -c
enable_service
for _ in $(seq 20); do service_connected && break; sleep 1; done
service_connected || fail "접근성 서비스가 앱 프로세스에 바인드되지 않았다"
echo "  연결 확인"

echo "== 5. 게임패드 DPAD 로 커서 이동 =="
for _ in 1 2 3 4; do press KEYCODE_DPAD_RIGHT; done
for _ in 1 2; do press KEYCODE_DPAD_DOWN; done
sleep 1

echo "== 6. BUTTON_A 로 커서 좌표에 제스처 주입 =="
press KEYCODE_BUTTON_A
sleep 3

# 결과는 앱/서비스가 남긴 로그로 읽는다 (dump 를 쓸 수 없으므로).
LOG="$("${ADB[@]}" logcat -d -s KeyboardOverlay:I TapInjection:I 2>/dev/null)"
CLICK_LINE="$(echo "$LOG" | grep -F '커서 클릭 → 주입 좌표' | tail -1)"
GESTURE_LINE="$(echo "$LOG" | grep -F '제스처 완료' | tail -1)"
TYPED="$(echo "$LOG" | sed -nE "s/.*입력 변화: '(.*)'.*/\1/p" | tail -1)"

echo
echo "  DPAD 이동 전 커서:  $CURSOR_START"
echo "  BUTTON_A 시점 커서: ${CLICK_LINE:-(클릭 로그 없음)}"
echo "  제스처:             ${GESTURE_LINE:-(완료 로그 없음)}"
echo "  입력된 텍스트:      '${TYPED:-}'"
echo

[ -n "$CLICK_LINE" ] || fail "BUTTON_A 가 오버레이에 도달하지 않았다 (Dialog OnKeyListener 미호출)"
[ -n "$GESTURE_LINE" ] || fail "dispatchGesture 가 완료되지 않았다"

if [ "$MODE" = "block" ]; then
    if [ -z "${TYPED:-}" ]; then
        echo "결과: 성공(대조군) — 오버레이가 터치를 받는 상태에서는 주입 제스처가 키보드에 닿지 않았다"
    else
        echo "결과: 실패(대조군) — 터치를 차단했는데도 입력이 들어갔다 ('$TYPED')"
        exit 2
    fi
elif [ -n "${TYPED:-}" ]; then
    echo "결과: 성공 — 게임패드 커서 좌표에 주입한 제스처가 키보드 키를 눌렀다 ('$TYPED')"
else
    echo "결과: 입력 없음 — 커서 좌표에 키가 없었거나 제스처가 키보드에 닿지 않았다"
    exit 2
fi
