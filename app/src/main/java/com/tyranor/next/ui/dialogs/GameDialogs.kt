package com.tyranor.next.ui.dialogs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.scanner.EngineLauncher
import com.tyranor.next.scanner.EngineType
import com.tyranor.next.scanner.GameStore
import com.tyranor.next.scanner.ScanGame
import com.tyranor.next.scanner.ScanGameIntents
import com.tyranor.next.scanner.VndbCoverService
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.pages.AppAlertDialog
import com.tyranor.next.ui.pages.KrkrOnlinePatchActivity
import com.tyranor.next.ui.pages.SaveManagementActivity
import com.tyranor.next.ui.pages.startActivityWithPageTransition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val GameActionsSheetMaxHeight: Dp = 560.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GameActionsSheet(
    game: ScanGame,
    onDismiss: () -> Unit,
    onGameUpdated: (ScanGame) -> Unit,
    onDeleteGame: () -> Unit,
    onEngineSettings: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var launchError by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showLaunchFilePicker by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showPatchConfirm by remember { mutableStateOf(false) }

    // 发起启动；Artemis 需要 PFS 基础补丁且策略为“启动时询问”时，先弹窗确认再带选择启动
    fun startLaunch(patchChoice: EngineLauncher.ArtemisPatchChoice? = null) {
        launchError = EngineLauncher.launch(context, game, patchChoice)
        if (launchError == null) onDismiss()
    }

    // 打开相册选择自定义封面
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            launchError = "正在设置封面…"
            val updated = withContext(Dispatchers.IO) {
                runCatching { VndbCoverService.saveCustomCover(context, game, uri) }.getOrNull()
            }
            if (updated != null) {
                onGameUpdated(updated)
                launchError = null
                onDismiss()
            } else {
                launchError = "封面设置失败"
            }
        }
    }

    // VNDB 封面搜索独立页（upstream#5）：绑定成功经 setResult 回传更新后的条目
    val vndbSearchLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val updated = result.data?.let { ScanGameIntents.getGame(it) }
        if (updated != null) {
            onGameUpdated(updated)
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = GameActionsSheetMaxHeight),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    game.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }

            item {
                GameActionRow(R.drawable.ic_sheet_launch, "启动游戏") {
                    if (EngineLauncher.needsArtemisPatchConfirm(context, game)) {
                        showPatchConfirm = true
                    } else {
                        startLaunch()
                    }
                }
            }
            if (game.engine == EngineType.KIRIKIRI) {
                item {
                    GameActionRow(
                        iconRes = R.drawable.ic_sheet_launch_file,
                        label = "启动文件",
                        subtitle = game.launchFile ?: "自动",
                    ) { showLaunchFilePicker = true }
                }
            }
            item {
                val quickLaunched = GameStore.isQuickLaunched(context, game.uri)
                GameActionRow(
                    iconRes = R.drawable.ic_home,
                    label = if (quickLaunched) "移除快捷启动" else "添加快捷启动",
                ) {
                    if (quickLaunched) {
                        GameStore.removeQuickLaunch(context, game.uri)
                        onDismiss()
                    } else if (GameStore.addQuickLaunch(context, game)) {
                        onDismiss()
                    } else {
                        android.widget.Toast.makeText(context, "首页快捷启动已满（最多 3 个）", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            item { GameActionRow(R.drawable.ic_sheet_search_cover, "搜索封面") { vndbSearchLauncher.launch(com.tyranor.next.ui.pages.VndbCoverActivity.createIntent(context, game)) } }
            item { GameActionRow(R.drawable.ic_sheet_edit_cover, "修改封面") { imagePicker.launch("image/*") } }
            item { GameActionRow(R.drawable.ic_sheet_rename, "名称修改") { showRenameDialog = true } }
            item {
                GameActionRow(R.drawable.ic_sheet_saves, "存档管理") {
                    startActivityWithPageTransition(context, SaveManagementActivity.createIntent(context, game))
                    onDismiss()
                }
            }
            if (game.engine == EngineType.KIRIKIRI) {
                item {
                    GameActionRow(R.drawable.ic_sheet_patch, "在线补丁") {
                        startActivityWithPageTransition(context, KrkrOnlinePatchActivity.createIntent(context, game))
                        onDismiss()
                    }
                }
            }
            item { GameActionRow(R.drawable.ic_sheet_settings, "引擎设置", onClick = onEngineSettings) }
            item { GameActionRow(R.drawable.ic_sheet_delete, "删除游戏", danger = true) { showDeleteConfirm = true } }

            launchError?.let {
                item {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
            }

            // 底部安全区留白
            item { Box(Modifier.fillMaxWidth().navigationBarsPadding().height(16.dp)) }
        }
    }

    // ===== Artemis 自动补丁确认：总是（记住 auto）/ 本次 / 不再（记住 off）；点遮罩取消 = 不启动 =====
    if (showPatchConfirm) {
        AppAlertDialog(
            onDismissRequest = { showPatchConfirm = false },
            title = { Text("应用自动补丁", style = MaterialTheme.typography.titleMedium) },
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
                        showPatchConfirm = false
                        startLaunch(EngineLauncher.ArtemisPatchChoice.ALWAYS)
                    },
                ) { Text("总是") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            showPatchConfirm = false
                            startLaunch(EngineLauncher.ArtemisPatchChoice.NEVER)
                        },
                    ) { Text("不再") }
                    TextButton(
                        onClick = {
                            showPatchConfirm = false
                            startLaunch(EngineLauncher.ArtemisPatchChoice.ONCE)
                        },
                    ) { Text("本次") }
                }
            },
        )
    }

    if (showRenameDialog) {
        RenameGameDialog(
            game = game,
            onDismiss = { showRenameDialog = false },
            onConfirm = { title ->
                showRenameDialog = false
                onGameUpdated(game.copy(title = title))
            },
        )
    }

    if (showLaunchFilePicker) {
        LaunchFileDialog(
            game = game,
            onDismiss = { showLaunchFilePicker = false },
            onConfirm = { name ->
                showLaunchFilePicker = false
                onGameUpdated(game.copy(launchFile = name))
            },
        )
    }

    if (showDeleteConfirm) {
        AppAlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除游戏", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "将移除「${game.title}」的应用内记录、设置与缓存，不会删除游戏文件。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDeleteGame()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun RenameGameDialog(
    game: ScanGame,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by remember(game.uri, game.title) { mutableStateOf(game.title) }
    val normalizedTitle = title.trim()
    val canConfirm = normalizedTitle.isNotEmpty() && normalizedTitle != game.title

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("名称修改", style = MaterialTheme.typography.titleMedium) },
        text = {
            // 统一 Miuix 风格输入框（AppSearchField）；键盘“搜索/完成”动作直接保存（内容有效时）
            AppSearchField(
                query = title,
                onQueryChange = { title = it },
                onSearch = { if (canConfirm) onConfirm(normalizedTitle) },
                leadingIcon = painterResource(R.drawable.ic_sheet_rename),
                iconContentDescription = "Rename",
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(normalizedTitle) },
                enabled = canConfirm,
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}


/** KRKR 专属：选择游戏启动入口文件（目录内 xp3 / exe）。 */
@Composable
private fun LaunchFileDialog(
    game: ScanGame,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(game.uri) {
        val (names, current) = withContext(Dispatchers.IO) {
            val names = EngineLauncher.listKrLaunchFiles(context, game)
            val current = EngineLauncher.currentKrLaunchFileName(context, game)
            names to current
        }
        files = names
        selected = current?.takeIf { names.contains(it) }
        loading = false
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("启动文件", style = MaterialTheme.typography.titleMedium) },
        text = {
            when {
                loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                files.isEmpty() -> Text(
                    "目录中未找到 xp3 或 exe 文件",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    lazyItems(files) { name ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(NavWhite)
                                .clickable { selected = name }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected == name,
                                onClick = { selected = name },
                            )
                            Text(
                                name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let(onConfirm) },
                enabled = selected != null,
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun GameActionRow(
    iconRes: Int,
    label: String,
    subtitle: String? = null,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(NavWhite)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.padding(start = 20.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
