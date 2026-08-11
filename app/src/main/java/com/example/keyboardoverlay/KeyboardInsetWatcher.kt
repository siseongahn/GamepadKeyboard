package com.example.keyboardoverlay

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 시스템 키보드(IME)의 높이 변화를 관찰해서 [onChanged] 로 알려준다.
 *
 * - API 30(R) 이상: WindowInsets 의 ime() 인셋을 그대로 사용한다. 가장 정확하다.
 *   단, 인셋을 직접 다루기 위해 decorFitsSystemWindows 를 끄고 하단 패딩을 손수 넣는다.
 * - API 29 이하: ime() 인셋이 제공되지 않으므로 화면 높이와 가시 영역의 차이로 추정한다.
 *   (매니페스트의 windowSoftInputMode="adjustResize" 가 전제)
 *
 * 전달되는 값은 "화면 하단부터 키보드 상단까지의 높이(px)"이며 키보드가 닫히면 0 이다.
 * 이 값이 곧 오버레이 윈도우가 덮어야 할 영역의 높이가 된다.
 */
class KeyboardInsetWatcher(
    private val activity: Activity,
    private val onChanged: (keyboardHeightPx: Int) -> Unit,
) {

    private val content: View = activity.findViewById(android.R.id.content)
    private var lastHeight = -1
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startWithInsets()
        } else {
            startWithGlobalLayout()
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ViewCompat.setOnApplyWindowInsetsListener(content, null)
        } else {
            globalLayoutListener?.let { content.viewTreeObserver.removeOnGlobalLayoutListener(it) }
            globalLayoutListener = null
        }
        lastHeight = -1
    }

    private fun startWithInsets() {
        // 인셋을 시스템이 알아서 소비하지 않게 해야 ime() 값을 온전히 받을 수 있다.
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)

        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val ime: Insets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // decorFitsSystemWindows 를 껐으므로 컨텐츠가 상태바/키보드에 가리지 않도록 직접 패딩.
            view.setPadding(bars.left, bars.top, bars.right, maxOf(ime.bottom, bars.bottom))

            emit(ime.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(content)
    }

    private fun startWithGlobalLayout() {
        val listener = ViewTreeObserver.OnGlobalLayoutListener { emit(estimateLegacyKeyboardHeight()) }
        globalLayoutListener = listener
        content.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    private fun estimateLegacyKeyboardHeight(): Int {
        val visible = Rect()
        val rootView = content.rootView
        rootView.getWindowVisibleDisplayFrame(visible)

        val screenHeight = rootView.height
        val hidden = screenHeight - visible.bottom

        // 내비게이션 바 정도의 작은 차이는 키보드로 보지 않는다.
        return if (hidden > screenHeight * 0.15f) hidden else 0
    }

    private fun emit(height: Int) {
        if (height == lastHeight) return
        lastHeight = height
        onChanged(height)
    }
}
