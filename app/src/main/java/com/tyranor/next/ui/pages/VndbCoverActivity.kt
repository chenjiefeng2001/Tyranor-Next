package com.tyranor.next.ui.pages

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.scanner.ScanGame
import com.tyranor.next.scanner.ScanGameIntents
import com.tyranor.next.scanner.VndbCandidate
import com.tyranor.next.scanner.VndbCoverService
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.ui.common.AppSearchField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * VNDB 封面搜索独立页（upstream#5）：从底部抽屉栏的弹窗形式升级为整页，
 * 绑定成功后经 setResult 把更新后的 [ScanGame] 回传给调用方刷新列表。
 */
class VndbCoverActivity : ComponentActivity() {

    private lateinit var game: ScanGame

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        game = ScanGameIntents.getGame(intent) ?: run {
            finish()
            return
        }

        setContent {
            TyranorNextTheme {
                VndbCoverScreen(
                    initialKeyword = game.title,
                    onDismiss = { finish() },
                    onBind = { candidate ->
                        val updated = withContext(Dispatchers.IO) {
                            runCatching { VndbCoverService.bindCandidate(applicationContext, game, candidate) }.getOrNull()
                        }
                        if (updated != null) {
                            setResult(RESULT_OK, Intent().apply { ScanGameIntents.putGame(this, updated) })
                            finish()
                            null
                        } else {
                            "封面下载失败"
                        }
                    },
                )
            }
        }
    }

    @Composable
    private fun VndbCoverScreen(
        initialKeyword: String,
        onDismiss: () -> Unit,
        onBind: suspend (VndbCandidate) -> String?,
    ) {
        var keyword by remember { mutableStateOf(initialKeyword) }
        var searching by remember { mutableStateOf(false) }
        var bindingCandidateId by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var candidates by remember { mutableStateOf<List<VndbCandidate>>(emptyList()) }
        val scope = rememberCoroutineScope()

        fun search() {
            val query = keyword.trim()
            if (query.isEmpty() || searching) return
            scope.launch {
                searching = true
                error = null
                val result = withContext(Dispatchers.IO) {
                    runCatching { VndbCoverService.searchCandidates(query, 8) }
                }
                candidates = result.getOrDefault(emptyList())
                result.exceptionOrNull()?.let { error = it.message ?: "VNDB 搜索失败" }
                if (candidates.isEmpty() && error == null) error = "未找到匹配结果"
                searching = false
            }
        }

        fun bind(candidate: VndbCandidate) {
            if (bindingCandidateId != null) return
            scope.launch {
                bindingCandidateId = candidate.id
                error = onBind(candidate)
                bindingCandidateId = null
            }
        }

        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // ===== 顶部栏（AGENT.md 规范：背景色延伸状态栏 + 64dp 标题区，无返回按钮）=====
            Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "搜索封面",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismiss) { Text("关闭") }
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AppSearchField(
                    query = keyword,
                    onQueryChange = { keyword = it },
                    onSearch = { search() },
                    modifier = Modifier.padding(top = 6.dp),
                )
                Button(
                    onClick = { search() },
                    enabled = !searching,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (searching) "搜索中…" else "搜索", style = MaterialTheme.typography.bodyMedium)
                }

                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                when {
                    searching -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    candidates.isEmpty() -> {}
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        lazyItems(candidates, key = { it.id }) { candidate ->
                            val binding = bindingCandidateId == candidate.id
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(NavWhite)
                                    .clickable(enabled = bindingCandidateId == null) { bind(candidate) }
                                    .padding(10.dp),
                            ) {
                                Text(
                                    candidate.title.ifBlank { candidate.originalTitle },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (candidate.originalTitle.isNotBlank()) {
                                    Text(
                                        candidate.originalTitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    listOf(candidate.id, candidate.released, candidate.developer)
                                        .filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (binding) {
                                    Text(
                                        "正在绑定…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        fun createIntent(context: Context, game: ScanGame): Intent =
            Intent(context, VndbCoverActivity::class.java).apply { ScanGameIntents.putGame(this, game) }
    }
}
