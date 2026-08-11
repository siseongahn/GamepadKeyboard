package com.example.keyboardoverlay

import android.content.Intent
import android.graphics.Point
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.widget.addTextChangedListener
import com.example.keyboardoverlay.databinding.ActivityMainBinding

/**
 * 1. EditText 에 포커스를 줘서 시스템 키보드를 띄운다.
 * 2. KeyboardInsetWatcher 로 키보드 높이를 감지한다.
 * 3. 그 높이만큼 TYPE_APPLICATION_OVERLAY 윈도우를 띄워 키보드 위에 아이콘을 그린다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var overlay: KeyboardOverlayController
    private lateinit var watcher: KeyboardInsetWatcher

    /** 권한 없이 키보드를 덮는 대안(FLAG_ALT_FOCUSABLE_IM Dialog) 비교용. */
    private lateinit var dialogOverlay: DialogOverlayController

    /** 사용자가 오버레이 기능 자체를 켜뒀는지. 꺼두면 키보드가 떠도 아이콘을 그리지 않는다. */
    private var overlayEnabled = true

    /** 가장 최근에 감지한 키보드 높이(px). 0 이면 키보드가 닫힌 상태. */
    private var keyboardHeight = 0

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // 설정 화면에서 돌아온 시점에는 결과 코드가 항상 RESULT_CANCELED 라 직접 확인해야 한다.
            updateStatus()
            syncOverlay()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        overlay = KeyboardOverlayController(this)
        dialogOverlay = DialogOverlayController(this)

        // 게임패드 A 버튼 → 커서 좌표 → 접근성 서비스가 그 좌표에 실제 터치를 주입.
        // 앱이 직접 주입할 수 없으므로(INJECT_EVENTS) 이 우회로가 유일한 방법이다.
        dialogOverlay.onCursorClick = { point -> injectTapAt(point) }

        // 서비스 연결은 설정을 켠 뒤 비동기로 일어난다. 콜백으로 상태 표시를 즉시 갱신한다.
        TapInjectionService.onConnectionChanged = { runOnUiThread { updateStatus() } }
        watcher = KeyboardInsetWatcher(this) { height ->
            keyboardHeight = height
            syncOverlay()
            updateStatus()
        }

        binding.btnPermission.setOnClickListener { requestOverlayPermission() }

        binding.btnToggleOverlay.setOnClickListener {
            overlayEnabled = !overlayEnabled
            syncOverlay()
            updateStatus()
        }

        binding.switchBlockTouch.setOnCheckedChangeListener { _, checked ->
            // 플래그는 윈도우 추가 시점에 결정되므로 한 번 내렸다가 다시 올린다.
            overlay.hide()
            overlay.blockTouches = checked
            dialogOverlay.blockTouches = checked
            syncOverlay()
        }

        binding.btnAccessibility.setOnClickListener { grantAccessibility() }

        // 주입 결과를 외부에서 읽기 위한 로그.
        // 이 앱의 접근성 서비스가 켜지면 `uiautomator dump` 가 루트 노드를 못 가져오므로
        // (ERROR: null root node returned by UiTestAutomationBridge) 실측 스크립트는 logcat 을 쓴다.
        binding.editInput.addTextChangedListener(
            onTextChanged = { text, _, _, _ -> Log.i(TAG, "입력 변화: '$text'") },
        )

        binding.btnShowKeyboard.setOnClickListener { showKeyboard() }
        binding.btnHideKeyboard.setOnClickListener { hideKeyboard() }

        binding.btnDialogOverlay.setOnClickListener {
            if (dialogOverlay.isShowing) {
                dialogOverlay.dismiss()
            } else {
                dialogOverlay.show(keyboardHeight)
            }
        }

        updateStatus()
    }

    override fun onStart() {
        super.onStart()
        watcher.start()
    }

    override fun onResume() {
        super.onResume()
        // 접근성 설정 화면에서 돌아온 시점에 상태를 갱신한다.
        // 서비스 연결은 설정 변경 뒤 비동기로 일어나므로 여기서 다시 읽어야 최신값이 보인다.
        updateStatus()
    }

    override fun onStop() {
        // 앱이 백그라운드로 가면 오버레이도 반드시 내린다. 안 그러면 다른 앱 위에 남는다.
        watcher.stop()
        overlay.hide()
        dialogOverlay.dismiss()
        keyboardHeight = 0
        super.onStop()
    }

    override fun onDestroy() {
        // 정적 콜백이 Activity 를 붙잡고 있으면 누수가 된다 (서비스는 Activity 보다 오래 산다).
        TapInjectionService.onConnectionChanged = null
        super.onDestroy()
    }

    private fun syncOverlay() {
        if (overlayEnabled && overlay.hasPermission()) {
            overlay.show(keyboardHeight)
        } else {
            overlay.hide()
        }
    }

    private fun requestOverlayPermission() {
        if (overlay.hasPermission()) {
            updateStatus()
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun showKeyboard() {
        binding.editInput.requestFocus()
        getSystemService<InputMethodManager>()
            ?.showSoftInput(binding.editInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(binding.editInput.windowToken, 0)
    }

    /**
     * 접근성 서비스를 켠다. 가능한 최선의 경로를 고른다.
     *
     * `WRITE_SECURE_SETTINGS` 가 있으면(= adb 로 부여했거나 시스템 서명) 코드로 바로 켜고,
     * 없으면 설정의 **이 서비스 상세 페이지**로 보낸다. 일반 배포에서는 후자만 가능하다 —
     * 앱이 스스로 접근성 권한을 얻는 방법은 존재하지 않는다.
     */
    private fun grantAccessibility() {
        if (TapInjectionService.isConnected) {
            // 이미 연결됨. 껐다 켜는 재바인드 용도로도 쓰이므로 상태만 알린다.
            Toast.makeText(this, R.string.accessibility_already_on, Toast.LENGTH_SHORT).show()
            return
        }

        if (AccessibilityPermission.canWriteSecureSettings(this)) {
            // 설정에 남아 있는데 연결이 안 된 상태면 껐다 켜야 재바인드된다 (값이 안 변하면 no-op).
            val ok = if (TapInjectionService.isEnabledInSettings(this)) {
                AccessibilityPermission.rebind(this)
            } else {
                AccessibilityPermission.enableSelf(this)
            }
            val msg = if (ok) R.string.accessibility_self_enabled else R.string.accessibility_self_failed
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            updateStatus()
            return
        }

        Toast.makeText(this, R.string.accessibility_open_settings_hint, Toast.LENGTH_LONG).show()
        AccessibilityPermission.openServiceSettings(this)
    }

    /**
     * 커서 좌표에 터치를 주입한다. 성공하면 그 좌표의 키가 눌린다.
     *
     * 오버레이가 `blockTouches` 상태면 주입된 제스처도 오버레이가 먼저 먹으므로 키에 닿지 않는다.
     * (주입 제스처 역시 일반 터치 디스패치를 탄다.)
     */
    private fun injectTapAt(point: Point) {
        val service = TapInjectionService.instance
        if (service == null) {
            // 설정에서 켰는데도 시스템이 바인드를 못 한 경우와, 아예 안 켠 경우를 구분해서 안내한다.
            // (전자는 `dumpsys accessibility` 에서 binding 에만 남고 bound 는 빈 상태로 관측된다.)
            val enabledInSettings = TapInjectionService.isEnabledInSettings(this)
            val message = if (enabledInSettings) {
                R.string.accessibility_not_connected
            } else {
                R.string.accessibility_needed
            }
            Log.w(TAG, "주입 불가 — 설정 ON=$enabledInSettings, 서비스 연결=false")
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        val accepted = service.tapAt(point.x, point.y)
        Log.i(TAG, "커서 클릭 → 주입 좌표=$point, 접수=$accepted")
    }

    private fun updateStatus() {
        val permission = if (overlay.hasPermission()) "허용됨" else "필요함"
        val keyboard = if (keyboardHeight > 0) "${keyboardHeight}px" else "닫힘"
        val enabled = if (overlayEnabled) "ON" else "OFF"
        val injection = when {
            TapInjectionService.isConnected -> "연결됨"
            TapInjectionService.isEnabledInSettings(this) -> "설정 ON·미연결"
            else -> "꺼짐"
        }
        binding.txtStatus.text = getString(
            R.string.status_format,
            permission,
            keyboard,
            enabled,
            overlay.isShowing,
            injection,
        )
        binding.btnToggleOverlay.text =
            getString(if (overlayEnabled) R.string.overlay_off else R.string.overlay_on)
    }

    // ---- 계측 테스트용 훅 (DialogOverlayTouchThroughTest) ----
    // 앱 프로세스는 IME 윈도우로 터치를 주입할 수 없어(INJECT_EVENTS 는 signature 권한)
    // 실제 검증은 androidTest 에서 UiAutomation 으로 한다. 여기서는 상태 조작/조회만 열어둔다.

    @VisibleForTesting
    internal val dialogOverlayController: DialogOverlayController get() = dialogOverlay

    @VisibleForTesting
    internal val keyboardHeightPx: Int get() = keyboardHeight

    @VisibleForTesting
    internal val inputFieldText: String get() = binding.editInput.text.toString()

    @VisibleForTesting
    internal fun clearInputForTest() {
        binding.editInput.setText("")
    }

    @VisibleForTesting
    internal fun showKeyboardForTest() = showKeyboard()

    @VisibleForTesting
    internal fun showDialogOverlayForTest() = dialogOverlay.show(keyboardHeight)

    @VisibleForTesting
    internal fun setBlockTouchesForTest(block: Boolean) {
        overlay.hide()
        overlay.blockTouches = block
        dialogOverlay.blockTouches = block
        syncOverlay()
    }

    /** WindowManager 오버레이를 끄고 Dialog 방식만 남기기 위한 훅. */
    @VisibleForTesting
    internal fun setOverlayEnabledForTest(enabled: Boolean) {
        overlayEnabled = enabled
        syncOverlay()
        updateStatus()
    }

    /** 게임패드 없이 커서를 목표 좌표로 보내기 위한 훅 (좌표 정확도를 위해 DPAD 반복 대신 사용). */
    @VisibleForTesting
    internal fun moveCursorToForTest(x: Int, y: Int) = dialogOverlay.moveCursorToScreen(x, y)

    @VisibleForTesting
    internal fun cursorCenterForTest(): Point? = dialogOverlay.cursorCenterOnScreen()

    private companion object {
        const val TAG = "KeyboardOverlay"
    }
}
