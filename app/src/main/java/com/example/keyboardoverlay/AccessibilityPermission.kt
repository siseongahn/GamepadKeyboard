package com.example.keyboardoverlay

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log

/**
 * 접근성 서비스를 "켜는" 세 가지 경로.
 *
 * **앱이 스스로 켤 수는 없다.** `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 쓰기에는
 * `WRITE_SECURE_SETTINGS` 가 필요하고, 이 권한은 `signature|privileged|development` 라서
 * 일반 배포 앱에는 부여되지 않는다. (부여된다면 악성 앱이 사용자 모르게 접근성 권한을 얻어
 * 화면을 읽고 터치를 주입할 수 있으니, 막혀 있는 게 정상이다.)
 *
 * 그래서 현실적인 선택지는:
 *
 * | 경로 | 대상 | 사용자 조작 |
 * |---|---|---|
 * | [openServiceSettings] | 일반 배포 | 설정 화면에서 토글 1회 (필수) |
 * | [enableSelf] (`WRITE_SECURE_SETTINGS`) | 개발/테스트/키오스크 | 없음 — 대신 adb 나 시스템 서명 필요 |
 * | Device Owner (MDM) | 사내 배포 | 없음 — 프로비저닝 단계에서 정책으로 지정 |
 *
 * `development` 플래그 덕분에 두 번째는 adb 로 부여할 수 있다:
 * ```
 * adb shell pm grant com.example.keyboardoverlay android.permission.WRITE_SECURE_SETTINGS
 * ```
 */
object AccessibilityPermission {

    private const val TAG = "A11yPermission"

    /**
     * 특정 접근성 서비스의 상세 페이지를 여는 액션.
     *
     * AOSP 에 존재하지만 **공개 API 가 아니고**(`Settings` 에 상수가 없어 문자열로 써야 한다),
     * 게다가 호출에 `OPEN_ACCESSIBILITY_DETAILS_SETTINGS` 권한이 필요하다. 이 권한은 일반 앱이
     * 받을 수 없으므로 **실제로는 거의 항상 실패한다.** API 33 에뮬레이터 실측:
     *
     * ```
     * SecurityException: Permission Denial: starting Intent { act=...ACCESSIBILITY_DETAILS_SETTINGS }
     *   requires android.permission.OPEN_ACCESSIBILITY_DETAILS_SETTINGS
     * ```
     *
     * 그래도 후보로 남겨둔다 — 시스템 앱으로 서명해 배포하는 경우엔 이 경로가 가장 깔끔하고,
     * 실패해도 아래 폴백이 받아주기 때문에 비용이 없다. `resolveActivity` 로는 걸러지지 않으므로
     * (컴포넌트는 존재하고 권한에서 막힌다) try/catch 가 반드시 필요하다.
     */
    private const val ACTION_ACCESSIBILITY_DETAILS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    /** 설정 화면에서 특정 항목을 지정/강조하는 비공개 extra. 무시돼도 해는 없다. */
    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

    fun component(context: Context): ComponentName =
        ComponentName(context, TapInjectionService::class.java)

    // ---- 경로 1: 설정 화면으로 보내기 (일반 배포) ----

    /**
     * **이 서비스의 상세 페이지**로 바로 보낸다. 접근성 목록 최상단으로 보내는 것보다
     * 사용자가 항목을 찾아 헤맬 일이 없다.
     *
     * 후보를 순서대로 시도하고, 열리는 첫 번째를 쓴다.
     *  1. [ACTION_ACCESSIBILITY_DETAILS] — 상세 페이지 직행. 권한 때문에 보통 실패한다(시스템 앱만).
     *  2. 접근성 설정 + 비공개 `fragment_args_key` extra — 목록에서 해당 항목을 강조.
     *  3. 접근성 설정 최상단 — 어디서나 동작하는 최종 폴백. 실측에서 여기까지 왔다.
     *
     * 어느 경로든 **마지막 토글은 사용자가 직접 해야 한다.** 우회 방법은 없다.
     */
    fun openServiceSettings(context: Context) {
        val key = component(context).flattenToString()
        val candidates = listOf(
            Intent(ACTION_ACCESSIBILITY_DETAILS).apply {
                putExtra(Intent.EXTRA_COMPONENT_NAME, key)
                putExtra(EXTRA_FRAGMENT_ARG_KEY, key)
            },
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                putExtra(EXTRA_FRAGMENT_ARG_KEY, key)
                putExtra(EXTRA_SHOW_FRAGMENT_ARGS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, key) })
            },
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )

        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) == null) continue
            runCatching { context.startActivity(intent) }
                .onSuccess {
                    Log.i(TAG, "설정 화면 열기 성공: ${intent.action}")
                    return
                }
                .onFailure { Log.w(TAG, "설정 화면 열기 실패(${intent.action}), 다음 후보 시도", it) }
        }
        Log.e(TAG, "접근성 설정 화면을 열 수 없다")
    }

    // ---- 경로 2: 코드로 직접 켜기 (WRITE_SECURE_SETTINGS 필요) ----

    /**
     * `WRITE_SECURE_SETTINGS` 가 부여돼 있는가. 부여 방법:
     * ```
     * adb shell pm grant com.example.keyboardoverlay android.permission.WRITE_SECURE_SETTINGS
     * ```
     * (`development` 보호수준이라 adb 로 부여 가능. 시스템 서명 앱이면 처음부터 갖는다.)
     */
    fun canWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 이 서비스를 설정에 추가해 켠다. [canWriteSecureSettings] 가 true 일 때만 성공한다.
     *
     * 사용자의 다른 접근성 서비스(TalkBack 등)는 목록에 그대로 남긴다 — 통째로 덮어쓰면
     * 시각장애 사용자의 TalkBack 이 꺼지는 심각한 결과가 된다.
     *
     * @return 쓰기 성공 여부. 성공해도 연결은 비동기이므로 [TapInjectionService.isConnected] 를
     *   따로 기다려야 한다.
     */
    fun enableSelf(context: Context): Boolean = setSelfEnabled(context, true)

    /** 이 서비스만 목록에서 제거한다. 다른 서비스는 건드리지 않는다. */
    fun disableSelf(context: Context): Boolean = setSelfEnabled(context, false)

    private fun setSelfEnabled(context: Context, enabled: Boolean): Boolean {
        if (!canWriteSecureSettings(context)) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS 없음 — 코드로 켤 수 없다 (설정 화면으로 안내할 것)")
            return false
        }
        val target = component(context)
        val current = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()

        val others = current.split(':')
            .filter { it.isNotBlank() }
            .mapNotNull(ComponentName::unflattenFromString)
            .filter { it != target }

        val next = if (enabled) others + target else others
        val value = next.joinToString(":") { it.flattenToString() }

        return runCatching {
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                value,
            )
            // accessibility_enabled 는 "접근성 기능 전체" 스위치다. 서비스가 하나라도 있으면 1.
            Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                if (next.isEmpty()) 0 else 1,
            )
            Log.i(TAG, "접근성 설정 직접 변경: enabled=$enabled, value='$value'")
        }.onFailure { Log.e(TAG, "접근성 설정 쓰기 실패", it) }.isSuccess
    }

    /**
     * 껐다 다시 켜서 **재바인드를 강제**한다.
     *
     * 앱 프로세스가 죽으면 접근성 서비스도 같이 죽는데, AccessibilityManagerService 는
     * 설정값이 *변할 때만* 재바인드한다. 값이 이미 들어 있으면 다시 쓰는 것만으로는
     * 아무 일도 일어나지 않으므로, 뺀 뒤 다시 넣어야 한다.
     */
    fun rebind(context: Context): Boolean =
        disableSelf(context) && enableSelf(context)
}
