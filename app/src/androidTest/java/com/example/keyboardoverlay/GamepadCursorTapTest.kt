package com.example.keyboardoverlay

import android.graphics.Point
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A안 실측: **게임패드로 오버레이 커서를 움직이고, 버튼으로 그 좌표에 접근성 제스처를 주입해서
 * 아래 키보드에 입력이 들어가는지** 확인한다.
 *
 * 체인을 세 토막으로 나눠 각각 따로 검증한다. 하나라도 끊기면 어디서 끊겼는지 바로 보이게.
 *  1. [dpadKeys_moveTheCursor]                — 게임패드 키 → 커서 이동
 *  2. [accessibilityGesture_typesUnderOverlay] — 좌표 → dispatchGesture → IME 입력 (A안의 핵심)
 *  3. [buttonA_atCursor_typesOnKeyboard]       — 1+2 를 이은 전 구간
 *  4. [accessibilityGesture_isSwallowed_whenOverlayTakesTouches] — 대조군
 *
 * 접근성 서비스는 테스트가 직접 켜고([enableInjectionService]) 끝나면
 * **원래 상태로 되돌린다**([restoreAccessibilitySettings]).
 * 계측 프로세스의 셸은 shell UID 로 돌아 `WRITE_SECURE_SETTINGS` 를 갖기 때문에 가능하다.
 *
 * 실제 게임패드는 붙어 있지 않아도 된다. 키 이벤트를 UiAutomation 으로 주입하면 실물 패드와
 * 동일한 디스패치 경로(포커스 윈도우 → Dialog.OnKeyListener)를 탄다. 시뮬레이션되는 부분은
 * "물리 입력장치" 뿐이고, 아날로그 스틱(SOURCE_JOYSTICK)은 이 테스트 범위 밖이다.
 */
@RunWith(AndroidJUnit4::class)
class GamepadCursorTapTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice by lazy { UiDevice.getInstance(instrumentation) }

    @Before
    fun setUp() {
        device.wakeUp()
        assertTrue(
            "접근성 서비스가 연결되지 않았다 (제조사 ROM 이 셸 활성화를 막았을 수 있다)",
            TapInjectionService.isConnected,
        )
        activityRule.scenario.onActivity { it.setOverlayEnabledForTest(false) }
    }

    @After
    fun tearDown() {
        activityRule.scenario.onActivity { it.clearInputForTest() }
    }

    @Test
    fun dpadKeys_moveTheCursor() {
        showOverlayOverKeyboard(blockTouches = false)
        val start = requireCursor()

        repeat(PRESSES) { device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT) }
        repeat(PRESSES) { device.pressKeyCode(KeyEvent.KEYCODE_DPAD_DOWN) }
        device.waitForIdle()

        val moved = requireCursor()
        Log.i(TAG, "커서 이동: $start → $moved (DPAD ${PRESSES}회씩)")
        assertTrue("DPAD_RIGHT 를 눌렀는데 커서가 오른쪽으로 가지 않았다 ($start → $moved)", moved.x > start.x)
        assertTrue("DPAD_DOWN 을 눌렀는데 커서가 아래로 가지 않았다 ($start → $moved)", moved.y > start.y)
    }

    @Test
    fun accessibilityGesture_typesUnderOverlay() {
        val targets = showOverlayOverKeyboard(blockTouches = false)

        targets.forEach { tapViaAccessibility(it) }

        val typed = awaitTypedText(waitForInput = true)
        Log.i(TAG, "dispatchGesture 좌표=$targets, 입력된 텍스트='$typed'")
        assertTrue(
            "접근성 제스처를 $targets 에 주입했는데 입력이 없다 (EditText='$typed').",
            typed.isNotEmpty(),
        )
    }

    @Test
    fun buttonA_atCursor_typesOnKeyboard() {
        val targets = showOverlayOverKeyboard(blockTouches = false)

        targets.forEach { target ->
            // DPAD 반복으로 정확히 맞추려면 수십 번 눌러야 하므로, 좌표 이동은 훅으로 하고
            // "버튼 → 클릭" 구간만 실제 키 이벤트로 검증한다.
            onActivity { it.moveCursorToForTest(target.x, target.y) }
            val placed = requireCursor()
            assertEquals("커서 X 가 목표에 놓이지 않았다", target.x, placed.x)
            assertEquals("커서 Y 가 목표에 놓이지 않았다", target.y, placed.y)

            device.pressKeyCode(KeyEvent.KEYCODE_BUTTON_A)
            SystemClock.sleep(GESTURE_SETTLE_MS)
        }

        val typed = awaitTypedText(waitForInput = true)
        Log.i(TAG, "게임패드 A 버튼, 커서 좌표=$targets, 입력된 텍스트='$typed'")
        assertTrue(
            "커서를 $targets 에 두고 BUTTON_A 를 눌렀는데 입력이 없다 (EditText='$typed').",
            typed.isNotEmpty(),
        )
    }

    @Test
    fun accessibilityGesture_isSwallowed_whenOverlayTakesTouches() {
        // NOT_TOUCHABLE 을 떼면 주입된 제스처도 오버레이가 먼저 먹는다.
        // 이게 통과해야 위 테스트들의 "입력됨" 이 오버레이를 통과한 결과라는 게 증명된다.
        val targets = showOverlayOverKeyboard(blockTouches = true)

        targets.forEach { tapViaAccessibility(it) }

        val typed = awaitTypedText(waitForInput = false)
        Log.i(TAG, "터치 차단 모드 dispatchGesture 좌표=$targets, 입력된 텍스트='$typed'")
        assertTrue(
            "오버레이가 터치를 받는 상태인데 주입된 제스처가 키보드까지 갔다 (EditText='$typed').",
            typed.isEmpty(),
        )
    }

    // ---- 헬퍼 ----

    /** 접근성 서비스로 [point] 에 탭을 주입하고 제스처가 끝날 시간을 준다. */
    private fun tapViaAccessibility(point: Point) {
        var accepted = false
        instrumentation.runOnMainSync {
            accepted = TapInjectionService.instance?.tapAt(point.x, point.y) == true
        }
        assertTrue("dispatchGesture 가 $point 를 접수하지 못했다", accepted)
        SystemClock.sleep(GESTURE_SETTLE_MS)
    }

    /**
     * 키보드를 띄우고 그 위에 오버레이를 올린 뒤, 탭 목표 좌표를 돌려준다.
     * 오버레이 사각형(= 덮고 있는 키보드 영역)의 가운데 줄 1/4, 2/4, 3/4 지점.
     */
    private fun showOverlayOverKeyboard(blockTouches: Boolean): List<Point> {
        activityRule.scenario.onActivity {
            it.setBlockTouchesForTest(blockTouches)
            it.clearInputForTest()
            it.showKeyboardForTest()
        }
        await("키보드가 뜨지 않았다 (IME 활성화 확인)") { onActivity { it.keyboardHeightPx } > 0 }

        activityRule.scenario.onActivity { it.showDialogOverlayForTest() }
        await("오버레이가 레이아웃되지 않았다") {
            onActivity { it.dialogOverlayController.contentBoundsOnScreen() } != null
        }
        await("커서가 레이아웃되지 않았다") { onActivity { it.cursorCenterForTest() } != null }

        device.waitForIdle()
        val bounds = onActivity { it.dialogOverlayController.contentBoundsOnScreen() }
            ?: throw AssertionError("오버레이 영역을 읽을 수 없다")
        return listOf(1, 2, 3).map { quarter ->
            Point(bounds.left + bounds.width() * quarter / 4, bounds.centerY())
        }
    }

    private fun requireCursor(): Point =
        onActivity { it.cursorCenterForTest() } ?: throw AssertionError("커서 좌표를 읽을 수 없다")

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

    private fun <T> onActivity(block: (MainActivity) -> T): T {
        var result: T? = null
        activityRule.scenario.onActivity { result = block(it) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    companion object {
        private const val TAG = "GamepadCursorTest"
        private const val PRESSES = 4
        private const val STATE_TIMEOUT_MS = 10_000L
        private const val INPUT_TIMEOUT_MS = 3_000L
        private const val GESTURE_SETTLE_MS = 400L
        private const val POLL_MS = 100L

        private const val BIND_ATTEMPTS = 3
        private const val BIND_TIMEOUT_MS = 15_000L

        private var originalServices: String = "null"
        private var originalEnabled: String = "0"

        /**
         * 접근성 서비스를 켠다. **클래스 단위로 한 번만** 하는 게 중요하다.
         *
         * `am instrument` 가 앱 프로세스를 재시작하면 접근성 서비스도 같이 죽고
         * (`ActivityManager: Force stopping service ...`), AccessibilityManagerService 는
         * **enabled_accessibility_services 설정이 다시 쓰일 때** 재바인드한다.
         * 이미 값이 들어 있다고 쓰기를 건너뛰면 계측 프로세스로는 영원히 붙지 않는다.
         * 그래서 값을 껐다 켜서 재바인드를 강제한다.
         *
         * 사용자의 다른 접근성 서비스(TalkBack 등)는 목록에 그대로 남겨 건드리지 않는다.
         */
        @JvmStatic
        @BeforeClass
        fun enableInjectionService() {
            originalServices = shell("settings get secure enabled_accessibility_services")
            originalEnabled = shell("settings get secure accessibility_enabled")
            Log.i(TAG, "원래 접근성 설정: services=$originalServices, enabled=$originalEnabled")

            val others = originalServices
                .takeUnless { it.isEmpty() || it == "null" }
                ?.split(':')
                ?.filter { it.isNotBlank() && it != TapInjectionService.COMPONENT }
                ?: emptyList()
            val withoutOurs = others.joinToString(":")
            val withOurs = (others + TapInjectionService.COMPONENT).joinToString(":")

            repeat(BIND_ATTEMPTS) { attempt ->
                writeEnabledServices(withoutOurs)
                SystemClock.sleep(500)
                writeEnabledServices(withOurs)
                shell("settings put secure accessibility_enabled 1")

                val deadline = SystemClock.uptimeMillis() + BIND_TIMEOUT_MS
                while (SystemClock.uptimeMillis() < deadline) {
                    if (TapInjectionService.isConnected) {
                        Log.i(TAG, "접근성 서비스 연결 확인 (시도 ${attempt + 1})")
                        return
                    }
                    SystemClock.sleep(POLL_MS)
                }
                Log.w(TAG, "바인드 실패, 재시도 ${attempt + 1}/$BIND_ATTEMPTS")
            }
        }

        /** 개인 기기 설정을 남기지 않는다. */
        @JvmStatic
        @AfterClass
        fun restoreAccessibilitySettings() {
            writeEnabledServices(originalServices.takeUnless { it == "null" }.orEmpty())
            shell("settings put secure accessibility_enabled ${originalEnabled.ifEmpty { "0" }}")
            Log.i(TAG, "접근성 설정 복구 완료")
        }

        /**
         * `executeShellCommand` 는 셸을 거치지 않고 명령을 직접 exec 한다. 따라서
         * 따옴표를 붙이면 **따옴표까지 값으로 저장**되고, 시스템은 그 컴포넌트를
         * "not installed" 로 보고 언바인드한다. 빈 값은 put 대신 delete 로 지운다.
         */
        private fun writeEnabledServices(value: String) {
            if (value.isEmpty()) {
                shell("settings delete secure enabled_accessibility_services")
            } else {
                shell("settings put secure enabled_accessibility_services $value")
            }
        }

        /** 계측 셸은 shell UID 로 돌기 때문에 WRITE_SECURE_SETTINGS 가 필요한 명령도 통한다. */
        private fun shell(command: String): String =
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .executeShellCommand(command)
                .trim()
    }
}
