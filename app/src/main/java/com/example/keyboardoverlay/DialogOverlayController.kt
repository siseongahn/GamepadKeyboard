package com.example.keyboardoverlay

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 권한(SYSTEM_ALERT_WINDOW) 없이 키보드를 덮는 대안 검증용.
 *
 * 핵심은 [WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM].
 * 이 플래그는 FLAG_NOT_FOCUSABLE 의 "IME 상호작용 여부"를 반전시킨다.
 *   - NOT_FOCUSABLE 없음 + ALT_FOCUSABLE_IM  → IME 가 필요 없는 창으로 취급 → IME 를 덮을 수 있음
 *   - NOT_FOCUSABLE 있음 + ALT_FOCUSABLE_IM  → 반대로 IME 뒤로 밀림
 *
 * WMS 는 IME 윈도우를 "IME target 바로 위"에 놓는데, 이 플래그가 붙은 창은 target 후보에서
 * 빠지므로 target 이 아래 Activity 에 남고 z-order 가 Activity < IME < Dialog 가 된다.
 * 앱 자신의 윈도우이므로 별도 권한이 필요 없다.
 *
 * 대신 "덮으려면 focusable 이어야" 하고, focusable 인 순간 Activity 는 윈도우 포커스를 잃는다.
 * 키보드가 그대로 떠 있는지 / 타이핑이 계속되는지는 기기·IME 구현에 달렸으므로 실측이 필요하다.
 */
class DialogOverlayController(private val activity: Activity) {

    private var dialog: Dialog? = null

    /** true 면 오버레이가 터치를 가로채 키 입력까지 막는다. show() 이전에 설정할 것. */
    var blockTouches: Boolean = false

    val isShowing: Boolean get() = dialog?.isShowing == true

    /**
     * [keyboardHeightPx] 를 주면 키보드 영역을 꽉 채우고, 0 이면 WRAP_CONTENT.
     *
     * Dialog 윈도우는 부모 프레임(= 내비게이션 바 위쪽)에 갇히므로 화면 바닥까지 내려갈 수 없다.
     * 키보드 높이에서 내비바 높이를 빼야 gravity=BOTTOM 기준으로 키보드와 정확히 겹친다.
     * (내비바 영역은 어차피 키가 아니라 내비게이션 버튼이 차지한다.)
     */
    fun show(keyboardHeightPx: Int = 0) {
        val navBarPx = ViewCompat.getRootWindowInsets(activity.window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val heightPx = if (keyboardHeightPx > 0) {
            (keyboardHeightPx - navBarPx).coerceAtLeast(1)
        } else {
            0
        }

        dismiss()

        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(R.layout.dialog_overlay)
        d.setCanceledOnTouchOutside(false)

        d.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)

            // Dialog 는 기본이 touch-modal 이라 창 밖 터치까지 전부 삼킨다.
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)

            // NOT_TOUCH_MODAL 은 "창 바깥" 터치만 통과시킨다. 창이 키보드를 덮는 순간
            // 키보드 터치가 전부 막히므로, 통과시키려면 NOT_TOUCHABLE 이 추가로 필요하다.
            // (NOT_FOCUSABLE 과 달리 NOT_TOUCHABLE 은 IME 와의 z-order 에 영향을 주지 않는다.)
            if (!blockTouches) {
                addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            }

            // Dialog 기본값인 뒷배경 어둡게(DIM)와 불투명 배경을 제거해야 키보드가 비쳐 보인다.
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                if (heightPx > 0) heightPx else WindowManager.LayoutParams.WRAP_CONTENT,
            )
            setGravity(Gravity.BOTTOM)
        }

        // 게임패드 키는 포커스를 가진 윈도우로 간다. ALT_FOCUSABLE_IM Dialog 는 포커스를 갖고
        // 있으면서도 IME target 이 아니므로, 키 이벤트가 IME 를 먼저 거치지 않고 여기로 온다.
        // (Activity 쪽에 커서 로직을 두면 IME 가 DPAD 를 자체 키 탐색용으로 삼킬 수 있다.)
        d.setOnKeyListener { _, keyCode, event -> handleGamepadKey(keyCode, event) }

        d.show()
        dialog = d

        cursor = d.findViewById(R.id.overlay_cursor)
        cursor?.post { centerCursor() }
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
        cursor = null
    }

    // ---- 게임패드 커서 ----

    private var cursor: View? = null

    private val stepPx: Int
        get() = activity.resources.getDimensionPixelSize(R.dimen.cursor_step)

    /**
     * 게임패드 버튼(A / DPAD_CENTER / ENTER)으로 커서 위치를 확정했을 때 화면 절대 좌표로 호출된다.
     * 여기서 무엇을 할지는 [MainActivity] 가 정한다 (접근성 서비스로 제스처 주입).
     */
    var onCursorClick: ((Point) -> Unit)? = null

    /** DPAD 로 커서를 옮기고 A 버튼으로 클릭. 소비한 키만 true 를 돌려준다. */
    private fun handleGamepadKey(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> moveCursorBy(-stepPx, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveCursorBy(stepPx, 0)
            KeyEvent.KEYCODE_DPAD_UP -> moveCursorBy(0, -stepPx)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveCursorBy(0, stepPx)
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> clickAtCursor()
            else -> false
        }
    }

    /** 커서를 오버레이(=키보드 영역) 안으로 클램프하며 이동. */
    fun moveCursorBy(dx: Int, dy: Int): Boolean {
        val c = cursor ?: return false
        val parent = c.parent as? View ?: return false
        if (parent.width == 0) return false
        c.translationX = (c.translationX + dx).coerceIn(0f, (parent.width - c.width).toFloat())
        c.translationY = (c.translationY + dy).coerceIn(0f, (parent.height - c.height).toFloat())
        return true
    }

    /** 커서 중심을 화면 절대 좌표 ([x], [y]) 로 옮긴다. 오버레이 밖이면 경계에서 멈춘다. */
    fun moveCursorToScreen(x: Int, y: Int): Boolean {
        val center = cursorCenterOnScreen() ?: return false
        return moveCursorBy(x - center.x, y - center.y)
    }

    private fun centerCursor() {
        val c = cursor ?: return
        val parent = c.parent as? View ?: return
        c.translationX = ((parent.width - c.width) / 2).toFloat()
        c.translationY = ((parent.height - c.height) / 2).toFloat()
    }

    /** 커서 중심의 화면 절대 좌표. 레이아웃 전이면 null. */
    fun cursorCenterOnScreen(): Point? {
        val c = cursor ?: return null
        if (c.width == 0 || c.height == 0) return null
        val origin = IntArray(2)
        // getLocationOnScreen 은 translation 을 반영한다.
        c.getLocationOnScreen(origin)
        return Point(origin[0] + c.width / 2, origin[1] + c.height / 2)
    }

    /** 현재 커서 위치로 [onCursorClick] 을 호출한다. */
    fun clickAtCursor(): Boolean {
        val center = cursorCenterOnScreen() ?: return false
        onCursorClick?.invoke(center) ?: return false
        return true
    }

    /**
     * 오버레이 내용물이 화면에서 차지하는 사각형(= 덮고 있는 키보드 영역).
     *
     * Dialog 윈도우 기준이 아니라 [android.view.View.getLocationOnScreen] 기준이므로
     * 이벤트 주입 좌표와 그대로 호환된다. 여기서 탭 목표 좌표를 만든다.
     * 아직 레이아웃 전(폭/높이 0)이면 null.
     */
    fun contentBoundsOnScreen(): Rect? {
        val root = dialog?.findViewById<ViewGroup>(R.id.overlay_root) ?: return null
        if (root.width == 0 || root.height == 0) return null
        val origin = IntArray(2)
        root.getLocationOnScreen(origin)
        return Rect(origin[0], origin[1], origin[0] + root.width, origin[1] + root.height)
    }
}
