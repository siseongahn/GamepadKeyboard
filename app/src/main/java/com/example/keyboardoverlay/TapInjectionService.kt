package com.example.keyboardoverlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi

/**
 * 화면 절대 좌표에 **실제 터치 제스처**를 주입하는 접근성 서비스.
 *
 * 왜 이게 필요한가:
 * 앱 프로세스에서 다른 윈도우(IME)로 이벤트를 주입하려면 `INJECT_EVENTS` (signature 권한)가
 * 필요하다. `Instrumentation.sendPointerSync()` 도 `Runtime.exec("input tap")` 도 앱 UID 로는
 * SecurityException 이다. 반면 접근성 서비스의 [dispatchGesture] 는 시그니처 권한 없이
 * (사용자가 설정에서 켜주면) 시스템이 대신 터치를 내려주므로 IME 윈도우까지 도달한다.
 *
 * 주의: 주입된 제스처도 일반 터치 디스패치를 타므로, 오버레이 윈도우가 그 좌표에서
 * 터치를 받는 상태(= `FLAG_NOT_TOUCHABLE` 없음)면 오버레이가 먼저 먹는다.
 * 반드시 오버레이를 NOT_TOUCHABLE 로 유지해야 키보드에 닿는다.
 *
 * 접근성 서비스는 앱과 **같은 프로세스**에서 돌기 때문에 [instance] 싱글턴으로 바로 호출할 수 있다
 * (별도 IPC 불필요).
 */
class TapInjectionService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "접근성 서비스 연결됨 (제스처 주입 가능)")
        onConnectionChanged?.invoke(true)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "접근성 서비스 해제됨")
        onConnectionChanged?.invoke(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        onConnectionChanged?.invoke(false)
        super.onDestroy()
    }

    // 제스처 주입만 쓰므로 이벤트 콜백은 비워둔다.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /**
     * [x], [y] (화면 절대 좌표)에 탭을 한 번 주입한다.
     *
     * @return 시스템이 제스처를 접수했는지. 실제 완료/취소는 [onResult] 로 온다.
     *   (접수 성공이 곧 "키가 눌렸다" 는 아니다. 그 좌표에 무엇이 있느냐는 별개다.)
     */
    fun tapAt(x: Int, y: Int, onResult: ((Boolean) -> Unit)? = null): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            // dispatchGesture 는 API 24+. 그 이하에서는 대안이 없다.
            Log.w(TAG, "dispatchGesture 는 API 24+ 에서만 동작한다 (현재 ${Build.VERSION.SDK_INT})")
            onResult?.invoke(false)
            return false
        }
        return dispatchTap(x, y, onResult)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun dispatchTap(x: Int, y: Int, onResult: ((Boolean) -> Unit)?): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
            .build()

        val callback = object : GestureResultCallback() {
            override fun onCompleted(description: GestureDescription?) {
                Log.i(TAG, "제스처 완료 ($x, $y)")
                onResult?.invoke(true)
            }

            override fun onCancelled(description: GestureDescription?) {
                Log.w(TAG, "제스처 취소됨 ($x, $y)")
                onResult?.invoke(false)
            }
        }

        val accepted = dispatchGesture(gesture, callback, null)
        Log.i(TAG, "dispatchGesture($x, $y) 접수=$accepted")
        return accepted
    }

    companion object {
        private const val TAG = "TapInjection"

        /** 너무 짧으면 IME 가 탭으로 인식하지 못할 수 있다. */
        const val TAP_DURATION_MS = 60L

        /** 서비스가 연결돼 있으면 non-null. 접근성 서비스는 앱과 같은 프로세스라 직접 참조해도 된다. */
        @Volatile
        var instance: TapInjectionService? = null
            private set

        /**
         * 연결/해제 시점을 UI 에 알린다. 설정을 켠 뒤 연결은 **비동기로** 일어나므로
         * (설정에서 돌아온 직후에는 아직 연결 전일 수 있다) 콜백 없이는 상태 표시가 낡는다.
         */
        @Volatile
        var onConnectionChanged: ((Boolean) -> Unit)? = null

        val isConnected: Boolean get() = instance != null

        /** 설정에 넣을 컴포넌트 문자열. `adb shell settings put secure ...` 에도 이 값을 쓴다. */
        const val COMPONENT = "com.example.keyboardoverlay/com.example.keyboardoverlay.TapInjectionService"

        /**
         * 설정에서 이 서비스가 **켜져 있는가**. [isConnected] 와 다른 값일 수 있다.
         *
         * 사용자가 설정에서 켰는데도 시스템이 바인드를 못 하는 경우가 있다
         * (`dumpsys accessibility` 에서 `binding services` 에 남고 `bound services` 는 빈 상태).
         * 그때 "꺼져 있습니다" 라고 안내하면 사용자는 이미 켰으므로 혼란스럽다.
         * 두 상태를 구분해서 안내하려고 따로 읽는다.
         */
        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val target = ComponentName(context, TapInjectionService::class.java)
            // unflattenFromString 은 "pkg/.Cls" 축약형도 풀어주므로 그대로 비교해도 된다.
            return enabled.split(':')
                .mapNotNull(ComponentName::unflattenFromString)
                .any { it == target }
        }
    }
}
