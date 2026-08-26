package com.tyranor.next.scanner

import android.content.Context
import com.core.engine.EnginePrefs
import com.core.nativeplugin.NativePluginConstants
import com.core.nativeplugin.NativePluginInstallState
import com.core.nativeplugin.NativePluginManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * C-2 回归护栏（audit/13 §二）：引擎插件解压必须「单飞锁 + 双检」。
 *
 * 锁定的不变量：
 * 1. 已安装（DISABLED）时快路径**不得重新解压**——状态短路被回退会每次启动重写插件目录；
 * 2. 启用（ensureForLaunch）在已安装前提下原地进行，只翻开关不落盘；
 * 3. 并发触发后目录要么完整有效、要么完全为空，**绝不允许半套 .so 的撕裂状态**；
 * 4. 安装资源缺失时优雅失败（返回错误文案），不得向调用方抛异常。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnginePluginBootstrapConcurrencyTest {

    private lateinit var context: Context

    private val sentinel = ByteArray(4096) { (it % 97).toByte() }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(EnginePrefs.APP_PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
        NativePluginManager.onsCurrentDir(context).deleteRecursively()
    }

    /** 构造能通过 validateOnsDirectory 的最小插件树；libSDL2.so 写入哨兵内容用于检测被覆写。 */
    private fun buildValidOnsTree(enabled: Boolean) {
        val current = NativePluginManager.onsCurrentDir(context)
        val abiDir = File(current, NativePluginConstants.ABI_ARM64)
        assertTrue(abiDir.mkdirs())
        for (lib in NativePluginConstants.ONS_REQUIRED_LIBS) {
            File(abiDir, lib).writeBytes(if (lib == "libSDL2.so") sentinel else ByteArray(16))
        }
        File(current, "manifest.json").writeText(
            """{"engineId":"ons","abi":"arm64-v8a","bridgeAbi":${NativePluginConstants.ONS_BRIDGE_ABI}}""",
        )
        context.getSharedPreferences(EnginePrefs.APP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(EnginePrefs.KEY_NATIVE_PLUGIN_ONS_INSTALLED, true)
            .putBoolean(EnginePrefs.KEY_NATIVE_PLUGIN_ONS_ENABLED, enabled)
            .commit()
    }

    private fun sdl2SentinelIntact(): Boolean {
        val f = File(
            File(NativePluginManager.onsCurrentDir(context), NativePluginConstants.ABI_ARM64),
            "libSDL2.so",
        )
        return f.isFile && f.readBytes().contentEquals(sentinel)
    }

    /** 测试环境是否带构建期生成的内置插件 zip（CI/本机 assembleDebug 后通常存在）。 */
    private fun assetZipPresent(): Boolean =
        runCatching {
            context.assets.open("nativeplugins/${NativePluginConstants.ENGINE_ONS}.zip").use { true }
        }.getOrDefault(false)

    @Test(timeout = 30_000L)
    fun installedDisabledFastPathMustNotReextract() {
        buildValidOnsTree(enabled = false)
        EnginePluginBootstrap.provisionIfNeeded(context)
        assertEquals(
            NativePluginInstallState.INSTALLED_DISABLED,
            NativePluginManager.onsInstallState(context),
        )
        assertTrue("快路径不得重写已安装的插件文件", sdl2SentinelIntact())
    }

    @Test(timeout = 30_000L)
    fun ensureForLaunchEnablesInstalledPluginInPlace() {
        buildValidOnsTree(enabled = false)
        val error = EnginePluginBootstrap.ensureForLaunch(context, EngineType.ONS)
        assertNull("已安装插件启用应成功，实际错误：$error", error)
        assertEquals(
            NativePluginInstallState.INSTALLED_ENABLED,
            NativePluginManager.onsInstallState(context),
        )
        assertTrue("启用只翻开关，不得重写插件文件", sdl2SentinelIntact())
    }

    /**
     * 并发压测：8 线程混合双入口同时触发。核心断言是**静止态完整性**——
     * abi 目录要么没有任何 .so（全部失败），要么状态必须完整有效（单飞下只会有一线程解压）。
     * 移除 provisionLock/双检后，交错删除+解压大概率留下撕裂目录使本例失败。
     */
    @Test(timeout = 90_000L)
    fun concurrentProvisionNeverLeavesTornDirectory() {
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val futures = (0 until threads).map { t ->
            pool.submit(Callable {
                ready.countDown()
                ready.await(10, TimeUnit.SECONDS)
                if (t % 2 == 0) {
                    EnginePluginBootstrap.provisionIfNeeded(context)
                } else {
                    // 失败会被封装成错误文案返回，不允许异常逃逸
                    EnginePluginBootstrap.ensureForLaunch(context, EngineType.ONS)
                }
                true
            })
        }
        pool.shutdown()
        assertTrue("并发调用应在时限内全部完成（无死锁）", pool.awaitTermination(80, TimeUnit.SECONDS))
        for (f in futures) f.get(5, TimeUnit.SECONDS)

        val abiDir = File(
            NativePluginManager.onsCurrentDir(context),
            NativePluginConstants.ABI_ARM64,
        )
        val soCount = abiDir.listFiles { file -> file.name.endsWith(".so") }?.size ?: 0
        val state = NativePluginManager.onsInstallState(context)
        if (soCount > 0) {
            assertEquals(
                "存在已解压 so 时目录必须完整有效（撕裂=单飞锁被移除），实际 so=$soCount state=$state",
                NativePluginInstallState.INSTALLED_ENABLED,
                state,
            )
        } else {
            assertTrue(
                "无 so 时不得报告完整安装，实际 $state",
                state == NativePluginInstallState.NOT_INSTALLED || state == NativePluginInstallState.INVALID,
            )
        }
    }

    @Test(timeout = 30_000L)
    fun missingAssetFailsGracefullyWithMessage() {
        assumeFalse("仅当测试环境无内置插件 zip 时执行", assetZipPresent())
        val error = EnginePluginBootstrap.ensureForLaunch(context, EngineType.ONS)
        assertTrue("缺失资源应返回错误文案而非抛异常", error != null && error.contains("插件"))
    }
}
