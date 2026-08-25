package com.tyranor.next.ui.components

import com.tyranor.next.scanner.ScanGame
import com.tyranor.next.settings.AppSettingsStore
import java.util.Locale

/** 游戏库排序：括号标签分组优先模式与纯标题字母序模式。 */
object GameSorter {

    fun sort(games: List<ScanGame>, sortMode: String): List<ScanGame> =
        when (sortMode) {
            AppSettingsStore.GAME_SORT_BRACKET_TAG -> games.sortedWith(
                compareBy<ScanGame> { bracketTag(it.title).isBlank() }
                    .thenBy { bracketTag(it.title).lowercase(Locale.ROOT) }
                    .thenBy { titleSortKey(it.title) },
            )
            else -> games.sortedBy { titleSortKey(it.title) }
        }

    /** 提取标题首组【…】或 […] 标签，无标签返回空串。 */
    internal fun bracketTag(title: String): String {
        val match = Regex("""【([^】]+)】|\[([^\]]+)]""").find(title) ?: return ""
        return (match.groups[1]?.value ?: match.groups[2]?.value).orEmpty().trim()
    }

    internal fun titleSortKey(title: String): String =
        title.lowercase(Locale.ROOT).trim()
}
