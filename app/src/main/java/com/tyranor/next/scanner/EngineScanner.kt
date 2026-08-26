package com.tyranor.next.scanner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.tyranor.next.settings.AppSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 精简版游戏扫描器，识别逻辑移植自 RinneMobile 的 EngineDetector/GameScanner。
 * 支持引擎：Kirikiri、ONS、Tyrano、RPG Maker MV/MZ、VN、WebOther、Artemis。
 *
 * 职责边界（R-06）：本类只负责扫描遍历、引擎识别、扫描根目录与本地封面发现；
 * 游戏库/最近/快捷启动持久化见 [GameStore]；路径解析见 [PathResolver]。
 */
object EngineScanner {

    private const val PREFS = "game_scanner"
    private const val KEY_ROOTS = "scan_roots"      // uri 按换行分隔

    // ============ 扫描根目录持久化 ============

    fun saveRoot(context: Context, uri: Uri): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = loadRoots(context).toMutableList()
        val key = uri.toString()
        if (!existing.contains(key)) existing.add(key)
        prefs.edit().putString(KEY_ROOTS, existing.joinToString("\n")).apply()
        return existing
    }

    fun removeRoot(context: Context, uri: Uri) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = loadRoots(context).filterNot { it == uri.toString() }
        prefs.edit().putString(KEY_ROOTS, existing.joinToString("\n")).apply()
    }

    fun removeRootAndGames(context: Context, uri: Uri) {
        removeRoot(context, uri)
        val root = uri.toString()
        val removedUris = GameStore.loadGames(context)
            .filter { isGameUnderRoot(root, it.uri) }
            .mapTo(HashSet()) { it.uri }
        if (removedUris.isEmpty()) return
        GameStore.saveGames(context, GameStore.loadGames(context).filterNot { it.uri in removedUris })
        GameStore.saveRecentGames(context, GameStore.loadRecentGames(context).filterNot { it.uri in removedUris })
        GameStore.saveQuickLaunch(context, GameStore.loadQuickLaunch(context).filterNot { it.uri in removedUris })
    }

    fun loadRoots(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ROOTS, null)
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    /**
     * 扫描根目录 → 本地文件路径：优先 SAF 映射；非 content 的纯路径（含反斜杠/盘符形态）
     * 归一化后直接使用，保证损坏或手工写入的根目录条目走 File 回退而非静默丢弃。
     */
    private fun resolveRootFilePath(root: String): String? {
        PathResolver.safUriToPath(root)?.let { return it }
        if (root.startsWith("content://", ignoreCase = true)) return null
        val normalized = root.replace('\\', '/')
        return normalized.takeIf { it.isNotBlank() }
    }

    private fun isGameUnderRoot(rootUriText: String, gameUriText: String): Boolean {
        val rootPath = normalizePath(PathResolver.safUriToPath(rootUriText))
        val gamePath = normalizePath(PathResolver.safUriToPath(gameUriText) ?: uriFilePath(gameUriText))
        if (rootPath != null && gamePath != null && isSameOrChildPath(rootPath, gamePath)) return true

        val rootDocId = documentId(rootUriText) ?: return false
        val gameDocId = documentId(gameUriText) ?: return false
        return gameDocId == rootDocId || gameDocId.startsWith("${rootDocId.trimEnd('/')}/")
    }

    private fun documentId(uriText: String): String? = runCatching {
        val uri = Uri.parse(uriText)
        DocumentsContract.getDocumentId(uri)
    }.getOrNull() ?: runCatching {
        DocumentsContract.getTreeDocumentId(Uri.parse(uriText))
    }.getOrNull()

    private fun uriFilePath(uriText: String): String? = runCatching {
        val uri = Uri.parse(uriText)
        if (uri.scheme.equals("file", ignoreCase = true)) uri.path else null
    }.getOrNull() ?: uriText.takeIf { it.startsWith("/") }

    private fun normalizePath(path: String?): String? =
        path?.replace('\\', '/')?.trimEnd('/')?.takeIf { it.isNotBlank() }

    private fun isSameOrChildPath(rootPath: String, gamePath: String): Boolean =
        gamePath == rootPath || gamePath.startsWith("$rootPath/")

    // ============ 扫描游戏 ============

    /** 全量扫描所有根目录（结果以本次扫描为准，用于首次/无数据场景）。 */
    suspend fun scanAll(context: Context): List<ScanGame> = withContext(Dispatchers.IO) {
        val all = mutableListOf<ScanGame>()
        val maxDepth = AppSettingsStore.getScanDepth(context)
        loadRoots(context).forEach { root ->
            all += scanRoot(context, root, maxDepth)
        }
        val seen = mutableSetOf<String>()
        all.filter { seen.add(it.uri) }
    }

    /** 全量刷新游戏库：以当前扫描结果为准，移除已删除/改名路径的旧缓存条目。 */
    suspend fun rescanLibrary(context: Context): List<ScanGame> = withContext(Dispatchers.IO) {
        val existingByUri = GameStore.loadGames(context).associateBy { it.uri }
        val scanned = scanAll(context)
        val refreshed = scanned.map { current ->
            existingByUri[current.uri]?.let { previous ->
                current.copy(
                    coverUri = previous.coverUri ?: current.coverUri,
                    vndbId = previous.vndbId,
                    metadataTitle = previous.metadataTitle,
                    launchFile = previous.launchFile,
                    openTime = previous.openTime,
                )
            } ?: current
        }
        GameStore.saveGames(context, refreshed)
        val validUris = refreshed.mapTo(HashSet()) { it.uri }
        GameStore.saveRecentGames(context, GameStore.loadRecentGames(context).filter { it.uri in validUris })
        GameStore.saveQuickLaunch(context, GameStore.loadQuickLaunch(context).filter { it.uri in validUris })
        refreshed
    }

    /**
     * 增量扫描（游戏库已有数据时调用）：遍历根目录时对已识别游戏目录剪枝跳过，
     * 只发现新游戏；返回 现有游戏 + 新发现游戏（已删除游戏保留，不主动移除）。
     */
    suspend fun incrementalScan(context: Context): List<ScanGame> = withContext(Dispatchers.IO) {
        val existing = GameStore.loadGames(context)
        val known = existing.mapTo(HashSet()) { it.uri }
        val seen = HashSet<String>()
        val found = mutableListOf<ScanGame>()
        val maxDepth = AppSettingsStore.getScanDepth(context)
        loadRoots(context).forEach { root ->
            val beforeCount = found.size
            val rootUri = Uri.parse(root)
            val rootDir = runCatching { DocumentFile.fromTreeUri(context.applicationContext, rootUri) }.getOrNull()
            if (rootDir != null) {
                scanRootIncremental(context.applicationContext, rootDir, 0, maxDepth, known, found)
            }
            if (found.size == beforeCount) resolveRootFilePath(root)?.let { path ->
                scanRootIncrementalFile(File(path), 0, maxDepth, known, found)
            }
        }
        existing + found.filter { seen.add(it.uri) }
    }

    /** 增量遍历：目录已在库中（已知游戏）→ 剪枝；识别为新游戏 → 记录并停止下钻。 */
    private fun scanRootIncremental(
        context: Context,
        dir: DocumentFile,
        level: Int,
        maxDepth: Int,
        known: HashSet<String>,
        out: MutableList<ScanGame>,
    ) {
        if (level > maxDepth) return
        if (dir.uri.toString() in known) return
        val children = dir.listFiles()

        val detected = detectEngine(dir)
        if (detected.engine != EngineType.UNKNOWN) {
            val coverUri = findLocalCoverUri(children)
            out.add(
                ScanGame(
                    title = dir.name?.takeIf { it.isNotBlank() } ?: "未命名游戏",
                    uri = dir.uri.toString(),
                    engine = detected.engine,
                    launchTarget = detected.launchTarget,
                    coverUri = coverUri,
                )
            )
            return
        }
        for (child in children) {
            if (child.isDirectory) {
                scanRootIncremental(context, child, level + 1, maxDepth, known, out)
            }
        }
    }

    suspend fun scanRoot(context: Context, rootUriStr: String, maxDepth: Int = 3): List<ScanGame> = withContext(Dispatchers.IO) {
        val rootUri = Uri.parse(rootUriStr)
        val root = runCatching { DocumentFile.fromTreeUri(context.applicationContext, rootUri) }.getOrNull()
        val results = mutableListOf<ScanGame>()
        if (root != null && root.isDirectory) {
            // 深度优先遍历子目录，识别每个候选游戏目录（深度由应用设置「扫描深度」控制）
            traverseDirectories(context.applicationContext, root, 0, maxDepth, results)
        }
        if (results.isEmpty()) resolveRootFilePath(rootUriStr)?.let { path ->
            traverseFileDirectories(File(path), 0, maxDepth, results)
        }
        val seen = HashSet<String>()
        results.filter { seen.add(it.uri) }
    }

    private fun traverseDirectories(
        context: Context,
        dir: DocumentFile,
        level: Int,
        maxDepth: Int,
        out: MutableList<ScanGame>,
    ) {
        if (level > maxDepth) return
        val children = dir.listFiles()

        // 1) 本级目录本身可能是游戏（含引擎特征文件）
        val detected = detectEngine(dir)
        if (detected.engine != EngineType.UNKNOWN) {
            val coverUri = findLocalCoverUri(children)
            out.add(
                ScanGame(
                    title = dir.name?.takeIf { it.isNotBlank() } ?: "未命名游戏",
                    uri = dir.uri.toString(),
                    engine = detected.engine,
                    launchTarget = detected.launchTarget,
                    coverUri = coverUri,
                )
            )
            // 已识别为游戏，其子目录多为引擎内部资源，仅扫描直接文件层，不再深挖
            return
        }

        // 2) 否则递归子目录
        for (child in children) {
            if (child.isDirectory) {
                traverseDirectories(context, child, level + 1, maxDepth, out)
            }
        }
    }

    fun applyLocalCover(context: Context, game: ScanGame): ScanGame {
        if (!game.coverUri.isNullOrBlank()) return game
        val dir = runCatching { DocumentFile.fromTreeUri(context.applicationContext, Uri.parse(game.uri)) }.getOrNull() ?: return game
        val coverUri = findLocalCoverUri(dir.listFiles())
        return if (coverUri.isNullOrBlank()) game else game.copy(coverUri = coverUri)
    }

    private fun findLocalCoverUri(children: Array<DocumentFile>): String? {
        return LOCAL_COVER_NAMES.firstNotNullOfOrNull { expected ->
            children.firstOrNull { child ->
                !child.isDirectory && child.name.equals(expected, ignoreCase = true)
            }?.uri?.toString()
        }
    }

    private fun scanRootIncrementalFile(
        dir: File,
        level: Int,
        maxDepth: Int,
        known: HashSet<String>,
        out: MutableList<ScanGame>,
    ) {
        if (level > maxDepth || !dir.isDirectory) return
        if (dir.absolutePath in known) return
        val children = dir.listFiles() ?: return

        val detected = detectEngine(dir)
        if (detected.engine != EngineType.UNKNOWN) {
            out.add(
                ScanGame(
                    title = dir.name.takeIf { it.isNotBlank() } ?: "未命名游戏",
                    uri = dir.absolutePath,
                    engine = detected.engine,
                    launchTarget = detected.launchTarget,
                    coverUri = findLocalCoverUri(children),
                )
            )
            return
        }
        children.filter { it.isDirectory }.forEach { child ->
            scanRootIncrementalFile(child, level + 1, maxDepth, known, out)
        }
    }

    private fun traverseFileDirectories(
        dir: File,
        level: Int,
        maxDepth: Int,
        out: MutableList<ScanGame>,
    ) {
        if (level > maxDepth || !dir.isDirectory) return
        val children = dir.listFiles() ?: return

        val detected = detectEngine(dir)
        if (detected.engine != EngineType.UNKNOWN) {
            out.add(
                ScanGame(
                    title = dir.name.takeIf { it.isNotBlank() } ?: "未命名游戏",
                    uri = dir.absolutePath,
                    engine = detected.engine,
                    launchTarget = detected.launchTarget,
                    coverUri = findLocalCoverUri(children),
                )
            )
            return
        }
        children.filter { it.isDirectory }.forEach { child ->
            traverseFileDirectories(child, level + 1, maxDepth, out)
        }
    }

    private fun findLocalCoverUri(children: Array<File>): String? {
        return LOCAL_COVER_NAMES.firstNotNullOfOrNull { expected ->
            children.firstOrNull { child ->
                child.isFile && child.name.equals(expected, ignoreCase = true)
            }?.let { Uri.fromFile(it).toString() }
        }
    }

    private val LOCAL_COVER_NAMES = listOf(
        "cover.jpg",
        "cover.png",
        "cover.webp",
        "cover.jpeg",
        "cover.bmp",
        "icon.png",
    )

    // ============ 引擎识别（移植自 EngineDetector） ============

    data class Detection(val engine: EngineType, val confidence: Int, val launchTarget: String)

    fun detectEngine(dir: DocumentFile): Detection {
        val r = Detection(EngineType.UNKNOWN, 0, "")
        if (!dir.isDirectory) return r
        val children = dir.listFiles()

        val names = HashSet<String>()            // 小写名
        val xp3Files = mutableListOf<String>()
        var hasStartupTjs = false
        var hasConfigTjs = false
        var hasIndex = false
        var hasAppAsar = false
        var hasTyranoDir = false
        var hasRpgMvCore = false
        var hasRpgMzCore = false
        var hasVnData = false
        var hasSystemIni = false
        var hasFirstIet = false
        var hasRootPfs = false
        var hasAnyPfs = false
        var hasOnsScript = false
        var hasOnsArchive = false

        fun collect(f: DocumentFile, rel: String) {
            val raw = f.name ?: ""
            val lower = raw.lowercase(Locale.ROOT)
            if (lower.isEmpty()) return
            // 保留真实大小写的相对路径（上游 issue #34：小写化会导致大小写敏感设备无法回配启动文件）
            val childRel = if (rel.isEmpty()) raw else "$rel/$raw"
            val childRelLower = childRel.lowercase(Locale.ROOT)
            names.add(lower)
            if (f.isDirectory) {
                if (lower == "tyrano") hasTyranoDir = true
                if (lower == "app.asar" || childRelLower.endsWith("/app.asar")) hasAppAsar = true
                // resources/app.asar 可能是文件，也可能是已解包目录，需继续下钻识别父级游戏目录。
                if (lower == "data" || lower == "tyrano" || lower == "scenario" ||
                    lower == "system" || lower == "app" || lower == "game" ||
                    lower == "resources" || lower == "app.asar" || lower == "www" || lower == "js"
                ) {
                    val sub = f.listFiles()
                    sub.forEach { collect(it, childRel) }
                }
                return
            }
            when {
                lower == "index.html" || lower == "index.htm" -> hasIndex = true
                childRelLower == "js/rpg_core.js" || childRelLower.endsWith("/js/rpg_core.js") -> hasRpgMvCore = true
                childRelLower == "js/rmmz_core.js" || childRelLower.endsWith("/js/rmmz_core.js") -> hasRpgMzCore = true
                lower == "globaldata.vndata" -> hasVnData = true
                lower == "app.asar" || childRelLower.endsWith("/app.asar") -> hasAppAsar = true
                lower == "startup.tjs" -> hasStartupTjs = true
                lower == "config.tjs" -> hasConfigTjs = true
                lower == "system.ini" -> hasSystemIni = true
                childRelLower == "system/first.iet" || childRelLower.endsWith("/system/first.iet") -> hasFirstIet = true
                lower == "root.pfs" -> hasRootPfs = true
                lower.endsWith(".pfs") -> hasAnyPfs = true
                lower == "0.txt" || lower == "00.txt" || lower == "nscript.dat" ||
                    lower == "onscript.nt2" || lower == "onscript.nt3" -> hasOnsScript = true
                lower.endsWith(".nsa") || lower.endsWith(".sar") -> hasOnsArchive = true
                lower.endsWith(".xp3") -> xp3Files.add(childRel)
            }
        }
        children.forEach { collect(it, "") }

        // 优先 Artemis（Ar）
        if ((hasSystemIni && hasFirstIet) || hasRootPfs || hasAnyPfs) {
            return Detection(EngineType.ARTEMIS, if ((hasSystemIni && hasFirstIet) || hasRootPfs) 95 else 90, "[游戏目录]")
        }
        if (hasIndex && hasTyranoDir) {
            return Detection(EngineType.TYRANO, 95, "[游戏目录]")
        }
        // RPG Maker 的 Windows/NW.js 发布目录通常是“游戏主目录/www/...”。
        // 在主目录识别可避免继续下钻后把所有游戏都命名为 www。
        if (hasIndex && hasRpgMvCore) {
            return Detection(EngineType.RPG_MV, 95, "[游戏目录]")
        }
        if (hasIndex && hasRpgMzCore) {
            return Detection(EngineType.RPG_MZ, 95, "[游戏目录]")
        }
        if (hasIndex && hasVnData) {
            return Detection(EngineType.VN, 90, "[游戏目录]")
        }
        // 打包 ASAR 无法在 SAF 扫描阶段读取内部目录，启动后由 Web 宿主再次精确识别。
        if (hasAppAsar) {
            return Detection(EngineType.TYRANO, 80, "[游戏目录]")
        }
        if (hasIndex) {
            return Detection(EngineType.WEB_OTHER, 70, "[游戏目录]")
        }
        // Kirikiri（kr）
        if (xp3Files.isNotEmpty() || hasStartupTjs || hasConfigTjs) {
            return Detection(EngineType.KIRIKIRI, if (xp3Files.isNotEmpty()) 95 else 80, xp3Files.firstOrNull() ?: "[游戏目录]")
        }
        // ONS
        if (hasOnsScript || hasOnsArchive) {
            return Detection(EngineType.ONS, if (hasOnsScript) 90 else 70, "[游戏目录]")
        }
        return r
    }

    fun detectEngine(dir: File): Detection {
        val r = Detection(EngineType.UNKNOWN, 0, "")
        if (!dir.isDirectory) return r
        val children = dir.listFiles() ?: return r

        val xp3Files = mutableListOf<String>()
        var hasStartupTjs = false
        var hasConfigTjs = false
        var hasIndex = false
        var hasAppAsar = false
        var hasTyranoDir = false
        var hasRpgMvCore = false
        var hasRpgMzCore = false
        var hasVnData = false
        var hasSystemIni = false
        var hasFirstIet = false
        var hasRootPfs = false
        var hasAnyPfs = false
        var hasOnsScript = false
        var hasOnsArchive = false

        fun collect(f: File, rel: String) {
            val raw = f.name
            val lower = raw.lowercase(Locale.ROOT)
            if (lower.isEmpty()) return
            // 保留真实大小写（上游 issue #34）
            val childRel = if (rel.isEmpty()) raw else "$rel/$raw"
            val childRelLower = childRel.lowercase(Locale.ROOT)
            if (f.isDirectory) {
                if (lower == "tyrano") hasTyranoDir = true
                if (lower == "app.asar" || childRelLower.endsWith("/app.asar")) hasAppAsar = true
                if (lower == "data" || lower == "tyrano" || lower == "scenario" ||
                    lower == "system" || lower == "app" || lower == "game" ||
                    lower == "resources" || lower == "app.asar" || lower == "www" || lower == "js"
                ) {
                    f.listFiles()?.forEach { collect(it, childRel) }
                }
                return
            }
            when {
                lower == "index.html" || lower == "index.htm" -> hasIndex = true
                childRelLower == "js/rpg_core.js" || childRelLower.endsWith("/js/rpg_core.js") -> hasRpgMvCore = true
                childRelLower == "js/rmmz_core.js" || childRelLower.endsWith("/js/rmmz_core.js") -> hasRpgMzCore = true
                lower == "globaldata.vndata" -> hasVnData = true
                lower == "app.asar" || childRelLower.endsWith("/app.asar") -> hasAppAsar = true
                lower == "startup.tjs" -> hasStartupTjs = true
                lower == "config.tjs" -> hasConfigTjs = true
                lower == "system.ini" -> hasSystemIni = true
                childRelLower == "system/first.iet" || childRelLower.endsWith("/system/first.iet") -> hasFirstIet = true
                lower == "root.pfs" -> hasRootPfs = true
                lower.endsWith(".pfs") -> hasAnyPfs = true
                lower == "0.txt" || lower == "00.txt" || lower == "nscript.dat" ||
                    lower == "onscript.nt2" || lower == "onscript.nt3" -> hasOnsScript = true
                lower.endsWith(".nsa") || lower.endsWith(".sar") -> hasOnsArchive = true
                lower.endsWith(".xp3") -> xp3Files.add(childRel)
            }
        }
        children.forEach { collect(it, "") }

        if ((hasSystemIni && hasFirstIet) || hasRootPfs || hasAnyPfs) {
            return Detection(EngineType.ARTEMIS, if ((hasSystemIni && hasFirstIet) || hasRootPfs) 95 else 90, "[游戏目录]")
        }
        if (hasIndex && hasTyranoDir) {
            return Detection(EngineType.TYRANO, 95, "[游戏目录]")
        }
        if (hasIndex && hasRpgMvCore) {
            return Detection(EngineType.RPG_MV, 95, "[游戏目录]")
        }
        if (hasIndex && hasRpgMzCore) {
            return Detection(EngineType.RPG_MZ, 95, "[游戏目录]")
        }
        if (hasIndex && hasVnData) {
            return Detection(EngineType.VN, 90, "[游戏目录]")
        }
        if (hasAppAsar) {
            return Detection(EngineType.TYRANO, 80, "[游戏目录]")
        }
        if (hasIndex) {
            return Detection(EngineType.WEB_OTHER, 70, "[游戏目录]")
        }
        if (xp3Files.isNotEmpty() || hasStartupTjs || hasConfigTjs) {
            return Detection(EngineType.KIRIKIRI, if (xp3Files.isNotEmpty()) 95 else 80, xp3Files.firstOrNull() ?: "[游戏目录]")
        }
        if (hasOnsScript || hasOnsArchive) {
            return Detection(EngineType.ONS, if (hasOnsScript) 90 else 70, "[游戏目录]")
        }
        return r
    }
}
