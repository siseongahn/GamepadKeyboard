package com.example.keyboardoverlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager

/**
 * 시스템 키보드 위에 아이콘을 그리는 오버레이 윈도우를 관리한다.
 *
 * 동작 원리
 * - TYPE_APPLICATION_OVERLAY(API 26+) 윈도우는 z-order 상 IME 윈도우보다 위 레이어에 놓인다.
 *   따라서 별도의 트릭 없이 키보드 위에 그려진다. (API 25 이하는 TYPE_PHONE 사용)
 * - 윈도우 크기를 "키보드 높이"로, gravity 를 BOTTOM 으로 잡아 키보드 영역과 정확히 겹치게 한다.
 * - 루트 배경은 투명이고 아이콘만 그리므로 키보드는 아이콘이 놓인 자리만 가려진다.
 * - FLAG_NOT_FOCUSABLE 로 포커스를 뺏지 않아 EditText 의 입력 커서와 키보드가 유지된다.
 * - [blockTouches] 가 false 면 FLAG_NOT_TOUCHABLE 이 붙어 터치가 오버레이를 통과해
 *   그대로 키보드에 전달된다. 즉 화면상으로만 가리고 타이핑은 정상 동작한다.
 */
class KeyboardOverlayController(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    /** true 로 두면 오버레이가 터치를 가로채 실제 키 입력까지 막는다. show() 이전에 설정할 것. */
    var blockTouches: Boolean = false

    val isShowing: Boolean get() = overlayView != null

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /**
     * 키보드 높이만큼의 오버레이를 띄우거나, 이미 떠 있으면 높이만 갱신한다.
     * [keyboardHeightPx] 가 0 이하이면 키보드가 닫힌 것으로 보고 오버레이를 내린다.
     */
    fun show(keyboardHeightPx: Int) {
        if (keyboardHeightPx <= 0) {
            hide()
            return
        }
        if (!hasPermission()) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW 권한이 없어 오버레이를 띄울 수 없다")
            return
        }

        val existing = overlayView
        if (existing != null) {
            val params = layoutParams ?: return
            val top = realScreenHeight() - keyboardHeightPx
            if (params.height != keyboardHeightPx || params.y != top) {
                params.height = keyboardHeightPx
                params.y = top
                windowManager.updateViewLayout(existing, params)
            }
            return
        }

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_keyboard_icons, null)
        val params = buildLayoutParams(keyboardHeightPx)

        try {
            windowManager.addView(view, params)
            overlayView = view
            layoutParams = params
        } catch (e: WindowManager.BadTokenException) {
            // 권한이 런타임에 회수된 경우 등
            Log.e(TAG, "오버레이 addView 실패", e)
        }
    }

    fun hide() {
        val view = overlayView ?: return
        overlayView = null
        layoutParams = null
        runCatching { windowManager.removeViewImmediate(view) }
            .onFailure { Log.w(TAG, "오버레이 removeView 실패", it) }
    }

    private fun buildLayoutParams(heightPx: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or       // 키보드 포커스 유지
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or             // 바깥 터치는 아래로
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or            // 좌표를 화면 절대 기준으로
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS               // 시스템 바 영역까지 확장

        if (!blockTouches) {
            // 아이콘만 시각적으로 덮고 터치는 전부 키보드로 흘려보낸다.
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            heightPx,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // gravity = BOTTOM 으로 붙이면 윈도우가 "부모 프레임"(= 내비게이션 바 위쪽) 기준으로
            // 정렬돼서 내비바 높이만큼 위로 밀린다. 반면 키보드는 내비바 아래까지 그려지므로
            // 둘이 어긋난다. 그래서 TOP 기준 + 화면 실제 높이로 계산한 절대 y 좌표를 쓴다.
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = realScreenHeight() - heightPx
            windowAnimations = 0 // 키보드와 함께 즉시 나타나도록 애니메이션 제거
        }
    }

    /** 내비게이션 바를 포함한 디스플레이 실제 높이(px). */
    private fun realScreenHeight(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            val size = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(size)
            size.y
        }

    private companion object {
        const val TAG = "KeyboardOverlay"
    }
}
