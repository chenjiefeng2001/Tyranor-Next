package com.tyranor.next.scanner

import android.content.Context

/**
 * 游戏库持久化单点：主库 / 最近游玩 / 快捷启动 三张列表的序列化与进程内缓存。
 * 持久化格式：行分隔条目，字段以 \u0001 分隔（见 [serializeGame]）。
 * EngineScanner 保留同名门面委托；新代码应直接使用本对象。
 */
object GameStore {

    const val PREFS = "game_scanner"
    private const val KEY_GAMES = "scan_games"
    private const val KEY_RECENT_GAMES = "recent_games"
    private const val KEY_QUICK_LAUNCH = "quick_launch" // 首页快捷启动（最多 3 个）

    private val cacheLock = Any()
    @Volatile
    private var gamesCache: List<ScanGame>? = null
    @Volatile
    private var recentGamesCache: List<ScanGame>? = null
    @Volatile
    private var quickLaunchCache: List<ScanGame>? = null

    // ============ 主游戏库 ============

    fun saveGames(context: Context, games: List<ScanGame>) {
        val snapshot = games.toList()
        gamesCache = snapshot
        saveList(context, KEY_GAMES, snapshot)
    }

    fun loadGames(context: Context): List<ScanGame> =
        gamesCache ?: synchronized(cacheLock) {
            gamesCache ?: loadList(context, KEY_GAMES).also { gamesCache = it }
        }

    /** 从持久游戏库中移除指定游戏（在游戏页或首页删除游戏时调用，保证库与最近列表一致）。 */
    fun removeGame(context: Context, uri: String) {
        saveGames(context, loadGames(context).filterNot { it.uri == uri })
    }

    // ============ 最近游玩 ============

    /** 记录一次启动（置顶并打时间戳），列表上限 20。 */
    fun recordRecentGame(context: Context, game: ScanGame) {
        val touched = game.copy(openTime = System.currentTimeMillis())
        val next = (listOf(touched) + loadRecentGames(context).filterNot { it.uri == game.uri }).take(20)
        saveRecentGames(context, next)
    }

    fun loadRecentGames(context: Context): List<ScanGame> =
        recentGamesCache ?: synchronized(cacheLock) {
            recentGamesCache ?: loadList(context, KEY_RECENT_GAMES).also { recentGamesCache = it }
        }

    /** 删除游戏时从最近游戏列表中移除对应条目。 */
    fun removeRecentGame(context: Context, uri: String) {
        saveRecentGames(context, loadRecentGames(context).filterNot { it.uri == uri })
    }

    internal fun saveRecentGames(context: Context, games: List<ScanGame>) {
        val snapshot = games.toList()
        recentGamesCache = snapshot
        saveList(context, KEY_RECENT_GAMES, snapshot)
    }

    // ============ 首页快捷启动（最多 3 个） ============

    fun loadQuickLaunch(context: Context): List<ScanGame> =
        quickLaunchCache ?: synchronized(cacheLock) {
            quickLaunchCache ?: loadList(context, KEY_QUICK_LAUNCH).also { quickLaunchCache = it }
        }

    fun isQuickLaunched(context: Context, uri: String): Boolean =
        loadQuickLaunch(context).any { it.uri == uri }

    /** 加入快捷启动。已存在视为成功；槽位满 3 个返回 false。 */
    fun addQuickLaunch(context: Context, game: ScanGame): Boolean {
        val current = loadQuickLaunch(context)
        if (current.any { it.uri == game.uri }) return true
        if (current.size >= 3) return false
        saveQuickLaunch(context, current + game)
        return true
    }

    fun removeQuickLaunch(context: Context, uri: String) {
        saveQuickLaunch(context, loadQuickLaunch(context).filterNot { it.uri == uri })
    }

    /**
     * 用主游戏库最新数据刷新快捷启动快照（游戏页修改封面等后首页实时同步），并回写存储。
     * 已从库中删除的游戏保留原快照（不主动移除）。
     */
    fun refreshQuickLaunch(context: Context): List<ScanGame> {
        val library = loadGames(context).associateBy { it.uri }
        val current = loadQuickLaunch(context)
        val refreshed = current.mapNotNull { library[it.uri] ?: it }
        if (refreshed != current) saveQuickLaunch(context, refreshed)
        return refreshed
    }

    internal fun saveQuickLaunch(context: Context, games: List<ScanGame>) {
        val snapshot = games.toList()
        quickLaunchCache = snapshot
        saveList(context, KEY_QUICK_LAUNCH, snapshot)
    }

    // ---------- 通用存取助手 ----------

    private fun saveList(context: Context, key: String, games: List<ScanGame>) {
        val str = games.joinToString("\n") { serializeGame(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, str).apply()
    }

    private fun loadList(context: Context, key: String): List<ScanGame> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null) ?: return emptyList()
        return raw.split("\n").mapNotNull { parseGame(it) }
    }

    private fun serializeGame(g: ScanGame): String {
        // 标题/元数据可能来自 VNDB，含 \n 或 \u0001 会把整个持久化文件解析错乱，需清洗。
        fun clean(s: String): String = s.replace("\n", " ").replace("\u0001", " ")
        return listOf(
            clean(g.title),
            g.uri,
            g.engine.name,
            g.launchTarget,
            g.coverUri.orEmpty(),
            g.vndbId.orEmpty(),
            clean(g.metadataTitle.orEmpty()),
            g.launchFile.orEmpty(),
            g.openTime.toString(),
        ).joinToString("\u0001")
    }

    private fun parseGame(line: String): ScanGame? {
        val p = line.split("\u0001")
        if (p.size < 3) return null
        return ScanGame(
            title = p[0],
            uri = p[1],
            engine = runCatching { EngineType.valueOf(p[2]) }.getOrDefault(EngineType.UNKNOWN),
            launchTarget = p.getOrElse(3) { "" },
            coverUri = p.getOrElse(4) { "" }.takeIf { it.isNotBlank() },
            vndbId = p.getOrElse(5) { "" }.takeIf { it.isNotBlank() },
            metadataTitle = p.getOrElse(6) { "" }.takeIf { it.isNotBlank() },
            launchFile = p.getOrElse(7) { "" }.takeIf { it.isNotBlank() },
            openTime = p.getOrElse(8) { "" }.toLongOrNull() ?: 0,
        )
    }
}
