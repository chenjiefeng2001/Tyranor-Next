package com.tyranor.next.ui.pages

import android.app.Activity
import android.app.ActivityOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.scanner.EngineLauncher
import com.tyranor.next.scanner.EngineScanner
import com.tyranor.next.scanner.GameStore
import com.tyranor.next.scanner.GameSaveManager
import com.tyranor.next.scanner.ScanGame
import com.tyranor.next.scanner.VndbCoverService
import com.tyranor.next.settings.AppSettingsStore
import com.tyranor.next.settings.PerGameSettingsStore
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.common.TopBarIcon
import com.tyranor.next.ui.components.GameGrid
import com.tyranor.next.ui.components.GameSorter
import com.tyranor.next.ui.dialogs.GameActionsSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun GameScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var games by remember { mutableStateOf(GameStore.loadGames(context)) }
    var scanning by remember { mutableStateOf(false) }
    var selectedGame by remember { mutableStateOf<ScanGame?>(null) }
    var launchError by remember { mutableStateOf<String?>(null) }
    var patchLaunchTarget by remember { mutableStateOf<ScanGame?>(null) }

    val gridState = rememberLazyGridState()

    fun replaceGame(updated: ScanGame) {
        val nextGames = games.map { if (it.uri == updated.uri) updated else it }
        games = nextGames
        selectedGame = selectedGame?.let { if (it.uri == updated.uri) updated else it }
        GameStore.saveGames(context, nextGames)
    }

    fun deleteGame(target: ScanGame) {
        val nextGames = games.filterNot { it.uri == target.uri }
        games = nextGames
        selectedGame = null
        GameStore.saveGames(context, nextGames)
        // 最近记录/快捷启动同步持久化移除，避免切页取消 IO 清理协程后残留脏数据
        GameStore.removeRecentGame(context, target.uri)
        GameStore.removeQuickLaunch(context, target.uri)
        // 仅清理应用内数据（每游戏设置、最近记录、封面缓存、应用内存档镜像）；不触碰游戏文件
        scope.launch(Dispatchers.IO) {
            cleanupDeletedGame(context, target)
        }
    }

    fun syncMissingCovers() {
        if (scanning) return
        scope.launch {
            scanning = true
            val current = games
            val updated = withContext(Dispatchers.IO) {
                current.map { game ->
                    val local = runCatching { EngineScanner.applyLocalCover(context, game) }.getOrDefault(game)
                    val next = runCatching { VndbCoverService.fetchBestCover(context, local) }.getOrNull()
                    if (next != null && next.coverUri != game.coverUri) {
                        next
                    } else if (local.coverUri != game.coverUri) {
                        local
                    } else {
                        game
                    }
                }
            }
            games = updated
            GameStore.saveGames(context, updated)
            scanning = false
        }
    }

    // 扫描游戏库：每次按扫描目录全量重建，删除/改名/移动后的旧缓存条目会被清理。
    fun scanLibrary() {
        if (scanning) return
        scope.launch {
            scanning = true
            val roots = EngineScanner.loadRoots(context)
            if (roots.isNotEmpty()) {
                val updated = EngineScanner.rescanLibrary(context)
                games = updated
                selectedGame = selectedGame?.let { selected ->
                    updated.firstOrNull { it.uri == selected.uri }
                }
            }
            scanning = false
        }
    }

    val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { u ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    u,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            // 保存根目录后立即全量扫描
            EngineScanner.saveRoot(context, u)
            scanLibrary()
        }
    }

    GameLibraryContent(
        modifier = modifier,
        games = games,
        scanning = scanning,
        gridState = gridState,
        dirPickerLaunch = { dirPicker.launch(null) },
        syncMissingCovers = { syncMissingCovers() },
        refreshGames = { scanLibrary() },
        onGameClick = { selectedGame = it },
        onGameLongClick = { game ->
            if (EngineLauncher.needsArtemisPatchConfirm(context, game)) {
                patchLaunchTarget = game
            } else {
                launchError = EngineLauncher.launch(context, game)
            }
        },
    )

    // ===== 点击游戏卡片的底部抽屉栏 =====
    selectedGame?.let { game ->
        GameActionsSheet(
            game = game,
            onDismiss = { selectedGame = null },
            onGameUpdated = { replaceGame(it) },
            onDeleteGame = { deleteGame(game) },
            onEngineSettings = {
                startActivityWithPageTransition(context, PerGameSettingsActivity.createIntent(context, game))
                selectedGame = null
            },
        )
    }

    // ===== 长按游戏卡片：启动游戏；Artemis 按既有策略弹出补丁确认 =====
    patchLaunchTarget?.let { game ->
        AppAlertDialog(
            onDismissRequest = { patchLaunchTarget = null },
            title = {
                Text(
                    "应用自动补丁",
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Text(
                    "「${game.title}」的启动文件打包在 .pfs 归档内，首次启动需要解出少量基础文件" +
                        "（system.ini、窗口配置与视频）并适配 Android 平台。是否应用补丁？",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        patchLaunchTarget = null
                        launchError = EngineLauncher.launch(context, game, EngineLauncher.ArtemisPatchChoice.ALWAYS)
                    },
                ) { Text("总是") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            patchLaunchTarget = null
                            launchError = EngineLauncher.launch(context, game, EngineLauncher.ArtemisPatchChoice.NEVER)
                        },
                    ) { Text("不再") }
                    TextButton(
                        onClick = {
                            patchLaunchTarget = null
                            launchError = EngineLauncher.launch(context, game, EngineLauncher.ArtemisPatchChoice.ONCE)
                        },
                    ) { Text("本次") }
                }
            },
        )
    }

    launchError?.let { message ->
        AppAlertDialog(
            onDismissRequest = { launchError = null },
            title = { Text("启动失败", style = MaterialTheme.typography.titleMedium) },
            text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { launchError = null }) { Text("确定") }
            },
        )
    }
}

/** 删除游戏后清理应用内关联数据（设置/最近记录/快捷启动/封面/存档镜像），绝不触碰游戏文件。 */
internal fun cleanupDeletedGame(context: android.content.Context, target: ScanGame) {
    PerGameSettingsStore.clear(context, target.uri)
    GameStore.removeRecentGame(context, target.uri)
    GameStore.removeQuickLaunch(context, target.uri)
    deleteCoverFile(context, target.coverUri)
    GameSaveManager(context).cleanupAppData(target)
}

private fun deleteCoverFile(context: android.content.Context, coverUri: String?) {
    if (coverUri.isNullOrBlank()) return
    val file = runCatching { File(android.net.Uri.parse(coverUri).path ?: return) }.getOrNull() ?: return
    val coverDir = File(context.filesDir, "covers_remote").canonicalPath
    if (runCatching { file.canonicalPath }.getOrNull()?.startsWith(coverDir) == true) {
        file.delete()
    }
}

internal fun startActivityWithPageTransition(context: android.content.Context, intent: android.content.Intent) {
    if (context is Activity) {
        val options = ActivityOptions.makeCustomAnimation(
            context,
            R.anim.page_slide_in_from_bottom,
            R.anim.page_slide_out_to_top,
        )
        context.startActivity(intent, options.toBundle())
    } else {
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

@Composable
private fun GameLibraryContent(
    modifier: Modifier,
    games: List<ScanGame>,
    scanning: Boolean,
    gridState: LazyGridState,
    dirPickerLaunch: () -> Unit,
    syncMissingCovers: () -> Unit,
    refreshGames: () -> Unit,
    onGameClick: (ScanGame) -> Unit,
    onGameLongClick: (ScanGame) -> Unit,
) {
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val gameSort = AppSettingsStore.gameSortState.value
    val sortedGames = remember(games, gameSort) { GameSorter.sort(games, gameSort) }
    val filteredGames = remember(sortedGames, query) {
        val q = query.trim()
        if (q.isEmpty()) sortedGames else sortedGames.filter { it.title.contains(q, ignoreCase = true) }
    }

    Column(modifier.fillMaxSize()) {
        // ===== 顶部栏：页面背景色，标题居左 + 右侧四个图标按钮 =====
        Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "游戏",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    TopBarIcon(painterResource(R.drawable.ic_game_search), "搜索游戏", MaterialTheme.colorScheme.primary) {
                        showSearch = !showSearch
                        if (!showSearch) query = ""
                    }
                    TopBarIcon(painterResource(R.drawable.ic_game_cover), "自动获取封面", MaterialTheme.colorScheme.primary) {
                        syncMissingCovers()
                    }
                    TopBarIcon(painterResource(R.drawable.ic_game_scan), "扫描游戏", MaterialTheme.colorScheme.primary) {
                        refreshGames()
                    }
                }
                // 搜索框：点击搜索按钮后出现在顶部栏下方
                if (showSearch) {
                    AppSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
                    )
                }
            }
        }

        // ===== 内容区 =====
        Box(Modifier.fillMaxSize()) {
            when {
                scanning -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                games.isEmpty() -> {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("暂无游戏", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "点击添加文件夹并扫描",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Button(
                            onClick = { dirPickerLaunch() },
                            modifier = Modifier.padding(top = 16.dp),
                        ) { Text("添加文件夹") }
                    }
                }
                else -> {
                    if (filteredGames.isEmpty()) {
                        Text(
                            "未找到匹配的游戏",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        GameGrid(
                            games = filteredGames,
                            gridState = gridState,
                            onGameClick = onGameClick,
                            onGameLongClick = onGameLongClick,
                        )
                    }
                }
            }
        }
    }
}
