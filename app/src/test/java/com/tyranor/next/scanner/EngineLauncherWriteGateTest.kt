package com.tyranor.next.scanner

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * 写入型页面权限门禁回归（audit/13 §五）：存档管理 / 在线补丁落盘走 java.io，
 * 目标位于外置存储时必须先取得「管理所有文件」。
 *
 * 授权状态经 [TestableShadowEnvironment] 注入，使「已授权放行 / 未授权拦截」
 * 两分支均可确定性验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [TestableShadowEnvironment::class])
class EngineLauncherWriteGateTest {

    @Before
    fun resetGrantState() {
        TestableShadowEnvironment.setExternalStorageManager(false)
    }

    /** Android 10 及以下不弹系统页，直接放行（历史 SAF/直读模型）。 */
    @Test
    @Config(sdk = [29])
    fun belowRAlwaysAllows() {
        val context = RuntimeEnvironment.getApplication()
        assertNull(EngineLauncher.requestManageAllFilesForWrite(context, "/sdcard/game"))
        assertNull(
            EngineLauncher.requestManageAllFilesForWrite(context, "/storage/0000-0000/game"),
        )
    }

    @Test
    @Config(sdk = [34])
    fun grantedManagerAlwaysAllows() {
        TestableShadowEnvironment.setExternalStorageManager(true)
        val context = RuntimeEnvironment.getApplication()
        assertNull(EngineLauncher.requestManageAllFilesForWrite(context, "/sdcard/game"))
        assertEquals(null, Shadows.shadowOf(context as android.app.Application).nextStartedActivity)
    }

    @Test
    @Config(sdk = [34])
    fun gatedPathWithoutPermissionPromptsAndOpensSystemPage() {
        val context = RuntimeEnvironment.getApplication()
        for (path in listOf(
            "/storage/emulated/0/Android/data/x/save",
            "/sdcard/game",
            "/storage/0000-0000/game",
        )) {
            val message = EngineLauncher.requestManageAllFilesForWrite(context, path)
            assertNotNull("外置存储路径应触发门禁：$path", message)
        }
        // 首个提示动作必须尝试打开「本应用」的所有文件权限系统页
        val started = Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, started?.action)
        assertEquals("package:${context.packageName}", started?.data?.toString())
    }

    @Test
    @Config(sdk = [34])
    fun appPrivatePathNeverTriggersGate() {
        val context = RuntimeEnvironment.getApplication()
        assertNull(
            EngineLauncher.requestManageAllFilesForWrite(context, "/data/data/com.tyranor.next/files/saves"),
        )
        assertEquals(
            "非门禁路径不得启动系统设置页",
            null,
            Shadows.shadowOf(context as android.app.Application).nextStartedActivity,
        )
    }

    /** 门禁文案契约：SaveManagementActivity/KrkrOnlinePatchActivity 以返回值是否为 null 判定拦截。 */
    @Test
    @Config(sdk = [34])
    fun promptMessageIsUserActionable() {
        val context = RuntimeEnvironment.getApplication()
        val message = EngineLauncher.requestManageAllFilesForWrite(context, "/storage/emulated/0/game")
        assertNotNull(message)
        assertEquals(true, message!!.contains("管理所有文件"))
    }
}
