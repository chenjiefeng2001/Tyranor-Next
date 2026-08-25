package com.tyranor.next.scanner

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class KrkrPatchEntry(
    val timestamp: Long,
    val brand: String,
    val path: String,
    val name: String,
    val patches: List<String>,
)

data class KrkrPatchInstallResult(
    val installed: List<String>,
    val target: String,
)

object KrkrOnlinePatchService {
    private const val INDEX_URL = "https://zeas2.github.io/Kirikiroid2_patch/patch/alldata.js"
    private const val PATCH_BASE_URL = "https://zeas2.github.io/Kirikiroid2_patch/patch/"
    private val indexRegex = Regex("""\[(\d+), "(.+?)", "(.+?)", "(.+?)", \[(.+)]],?""")

    suspend fun fetchPatchIndex(): List<KrkrPatchEntry> = withContext(Dispatchers.IO) {
        val text = httpGetText(INDEX_URL)
        text.lineSequence()
            .mapNotNull { line -> parseLine(line.trim()) }
            .sortedWith(compareBy<KrkrPatchEntry> { it.name }.thenBy { it.brand })
            .toList()
    }

    fun search(entries: List<KrkrPatchEntry>, keyword: String): List<KrkrPatchEntry> {
        val query = keyword.trim()
        if (query.isBlank()) return entries
        return entries.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.brand.contains(query, ignoreCase = true) ||
                it.path.contains(query, ignoreCase = true)
        }
    }

    suspend fun downloadAndInstall(
        context: Context,
        game: ScanGame,
        urls: List<String>,
        progress: (String) -> Unit = {},
    ): KrkrPatchInstallResult = withContext(Dispatchers.IO) {
        require(game.engine == EngineType.KIRIKIRI) { "仅 Kirikiri 游戏支持在线补丁" }
        require(urls.isNotEmpty()) { "请选择要下载的补丁" }

        val downloadDir = File(context.getExternalFilesDir(null), "Download").apply { mkdirs() }
        val installed = mutableListOf<String>()
        val targetDescription = resolveTargetDescription(game)

        urls.forEach { url ->
            val fileName = fileNameFromUrl(url)
            progress("正在下载 $fileName")
            val tempFile = File(downloadDir, fileName)
            try {
                downloadToFile(url, tempFile)
                progress("正在写入 $fileName")
                copyIntoGameDir(context, game, tempFile, fileName)
                installed += fileName
            } finally {
                tempFile.delete()
            }
        }

        KrkrPatchInstallResult(installed = installed, target = targetDescription)
    }

    private fun parseLine(line: String): KrkrPatchEntry? {
        val match = indexRegex.matchEntire(line) ?: return null
        val patches = match.groupValues[5]
            .split(", ")
            .map { it.trim().trim('"') }
            .filter { it.isNotBlank() }
            .map { PATCH_BASE_URL + it }

        return KrkrPatchEntry(
            timestamp = match.groupValues[1].toLongOrNull()?.times(1000L) ?: 0L,
            brand = match.groupValues[2],
            path = match.groupValues[3],
            name = match.groupValues[4],
            patches = patches,
        )
    }

    private fun httpGetText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            requestMethod = "GET"
        }
        return connection.use {
            if (responseCode !in 200..299) error("补丁索引获取失败：HTTP $responseCode")
            inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader -> reader.readText() }
        }
    }

    private fun downloadToFile(url: String, target: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 60_000
            requestMethod = "GET"
        }
        connection.use {
            if (responseCode !in 200..299) error("补丁下载失败：HTTP $responseCode")
            inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun copyIntoGameDir(context: Context, game: ScanGame, source: File, fileName: String) {
        resolveWritableGameFileDir(game)?.let { dir ->
            source.copyTo(File(dir, fileName), overwrite = true)
            return
        }

        val uri = runCatching { Uri.parse(game.uri) }.getOrNull()
        if (uri != null && uri.scheme.equals("content", ignoreCase = true)) {
            val docDir = DocumentFile.fromTreeUri(context, uri)
            if (docDir != null && docDir.isDirectory) {
                docDir.findFile(fileName)?.delete()
                val outFile = docDir.createFile("application/octet-stream", fileName)
                if (outFile != null) {
                    context.contentResolver.openOutputStream(outFile.uri, "w")?.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    } ?: error("无法打开补丁写入流")
                    return
                }
            }
        }

        error("无法写入游戏目录，请重新添加游戏目录并授予写入权限")
    }

    private fun resolveWritableGameFileDir(game: ScanGame): File? {
        val candidates = listOfNotNull(
            game.uri.takeIf { it.startsWith("/") },
            PathResolver.safUriToPath(game.uri),
        ).distinct()

        return candidates
            .map { File(it) }
            .firstOrNull { it.exists() && it.isDirectory && it.canWrite() }
    }

    private fun resolveTargetDescription(game: ScanGame): String {
        return resolveWritableGameFileDir(game)?.absolutePath ?: game.uri
    }

    private fun fileNameFromUrl(url: String): String {
        val rawName = URL(url).path.substringAfterLast('/').ifBlank { "patch.xp3" }
        return URLDecoder.decode(rawName, StandardCharsets.UTF_8.name())
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
    }

    private inline fun <T : HttpURLConnection, R> T.use(block: T.() -> R): R {
        return try {
            block()
        } finally {
            disconnect()
        }
    }
}
