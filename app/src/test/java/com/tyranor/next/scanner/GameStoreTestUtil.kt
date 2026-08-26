package com.tyranor.next.scanner

/** 测试专用：重置 GameStore 进程内缓存（object 单例跨 Robolectric 用例存活）。 */
object GameStoreTestUtil {
    fun resetCaches() {
        val instance = GameStore::class.java.getDeclaredField("INSTANCE").get(null)
        for (name in listOf("gamesCache", "recentGamesCache", "quickLaunchCache")) {
            GameStore::class.java.getDeclaredField(name).apply { isAccessible = true }
                .set(instance, null)
        }
    }
}
