package com.example.keyboardoverlay

import android.graphics.Point
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FLAG_ALT_FOCUSABLE_IM Dialog 오버레이가 키보드를 덮은 상태에서, **아이콘 위 좌표로 터치를
 * 주입했을 때 아래 키가 실제로 눌려 EditText 에 입력이 들어가는지** 를 코드로 검증한다.
 *
 * 왜 계측 테스트인가:
 * 앱 프로세스에서 다른 윈도우(IME)로 이벤트를 주입하려면 `INJECT_EVENTS` (signature 권한)가
 * 필요하다. `Instrumentation.sendPointerSync()` 는 SecurityException 으로 막힌다. 반면 계측은
 * `UiAutomation` 을 통한 주입이 허용되므로, [UiDevice.click] 이 `adb shell input tap` 과 동일한
 * 시스템 경로로 실제 터치스크린 이벤트를 내려준다.
 *
 * 두 케이스를 함께 돌려야 결과가 의미를 가진다.
 *  - [tapOnIcon_typesOnKeyboard_whenTouchesPassThrough] : NOT_TOUCHABLE → 입력이 들어와야 한다.
 *  - [tapOnIcon_isSwallowed_whenBlockingTouches]        : NOT_TOUCHABLE 제거 → 입력이 없어야 한다.
 *    (두 번째가 통과해야 첫 번째의 "입력됨" 이 오버레이를 통과한 결과라는 게 증명된다.)
 *
 * 실행:
 * ```
 * adb shell settings put secure show_ime_with_hard_keyboard 1   # 에뮬레이터에서 소프트 키보드 강제 표시
 * ./gradlew connectedDebugAndroidTest
 * ```
 * 기기에 실제 IME 가 활성화돼 있어야 한다(키보드가 안 뜨면 테스트는 실패로 보고된다).
 */
@RunWith(AndroidJUnit4::class)
class DialogOverlayTouchThroughTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice by lazy { UiDevice.getInstance(instrumentation) }

    @Before
    fun setUp() {
        device.wakeUp()
        // 검증 대상은 Dialog 방식이므로 WindowManager 오버레이는 끈다(권한 유무와 무관하게 격리).
        activityRule.scenario.onActivity { it.setOverlayEnabledForTest(false) }
    }

    @Test
    fun tapOnIcon_typesOnKeyboard_whenTouchesPassThrough() {
        val centers = showOverlayOverKeyboard(blockTouches = false)

        centers.forEach { device.click(it.x, it.y) }
        device.waitForIdle()

        val typed = awaitTypedText(waitForInput = true)
        Log.i(TAG, "탭 좌표=$centers, 입력된 텍스트='$typed'")
        assertTrue(
            "$centers 를 탭했지만 입력이 들어오지 않았다 (EditText='$typed'). " +
                "오버레이가 터치를 삼켰거나 탭이 키가 아닌 영역에 떨어졌다.",
            typed.isNotEmpty(),
        )
    }

    @Test
    fun tapOnIcon_isSwallowed_whenBlockingTouches() {
        val centers = showOverlayOverKeyboard(blockTouches = true)

        centers.forEach { device.click(it.x, it.y) }
        device.waitForIdle()

        val typed = awaitTypedText(waitForInput = false)
        Log.i(TAG, "터치 차단 모드 탭 좌표=$centers, 입력된 텍스트='$typed'")
        assertTrue(
            "터치 차단 모드(NOT_TOUCHABLE 제거)인데 입력이 들어갔다 (EditText='$typed').",
            typed.isEmpty(),
        )
    }

    /**
     * 키보드를 띄우고 그 위에 Dialog 오버레이를 올린 뒤, 탭 목표 좌표를 돌려준다.
     *
     * 좌표는 [DialogOverlayController.contentBoundsOnScreen] 이 준 실제 오버레이 사각형
     * (= 덮고 있는 키보드 영역)에서 뽑으므로 기기 해상도/키보드 높이에 하드코딩이 없다.
     * 가운데 줄의 1/4, 2/4, 3/4 지점 — 어느 기기에서든 문자 키가 놓이는 자리다.
     */
    private fun showOverlayOverKeyboard(blockTouches: Boolean): List<Point> {
        activityRule.scenario.onActivity {
            it.setBlockTouchesForTest(blockTouches)
            it.clearInputForTest()
            it.showKeyboardForTest()
        }
        await("키보드가 뜨지 않았다 (IME 활성화 / show_ime_with_hard_keyboard 확인)") {
            onActivity { it.keyboardHeightPx } > 0
        }

        activityRule.scenario.onActivity { it.showDialogOverlayForTest() }
        await("Dialog 오버레이가 레이아웃되지 않았다") {
            onActivity { it.dialogOverlayController.contentBoundsOnScreen() } != null
        }

        // 삼성 등 일부 IME 는 툴바 높이가 뒤늦게 바뀌면서 오버레이 높이도 갱신된다.
        // idle 을 한 번 기다린 뒤 최종 좌표를 읽는다.
        device.waitForIdle()
        val bounds = onActivity { it.dialogOverlayController.contentBoundsOnScreen() }
            ?: throw AssertionError("오버레이 영역을 읽을 수 없다")
        return listOf(1, 2, 3).map { quarter ->
            Point(bounds.left + bounds.width() * quarter / 4, bounds.centerY())
        }
    }

    /**
     * [waitForInput] 이 true 면 입력이 들어올 때까지(최대 [INPUT_TIMEOUT_MS]) 폴링하고,
     * false 면 같은 시간만큼 기다린 뒤 최종 상태를 읽는다(입력이 늦게 들어오는 경우도 잡기 위해).
     */
    private fun awaitTypedText(waitForInput: Boolean): String {
        val deadline = SystemClock.uptimeMillis() + INPUT_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val text = onActivity { it.inputFieldText }
            if (waitForInput && text.isNotEmpty()) return text
            SystemClock.sleep(POLL_MS)
        }
        return onActivity { it.inputFieldText }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + STATE_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(POLL_MS)
        }
        fail(message)
    }

    /** ActivityScenario.onActivity 는 메인 스레드에서 동기 실행되므로 값을 그대로 꺼낼 수 있다. */
    private fun <T> onActivity(block: (MainActivity) -> T): T {
        var result: T? = null
        activityRule.scenario.onActivity { result = block(it) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private companion object {
        const val TAG = "TouchThroughTest"
        const val STATE_TIMEOUT_MS = 5_000L
        const val INPUT_TIMEOUT_MS = 2_000L
        const val POLL_MS = 100L
    }
}
