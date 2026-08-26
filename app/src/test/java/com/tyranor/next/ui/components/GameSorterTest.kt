package com.tyranor.next.ui.components

import com.tyranor.next.scanner.EngineType
import com.tyranor.next.scanner.ScanGame
import com.tyranor.next.settings.AppSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Test

class GameSorterTest {

    private fun game(title: String) = ScanGame(
        title = title,
        uri = "/$title",
        engine = EngineType.VN,
        launchTarget = "",
    )

    @Test
    fun alphaModeSortsCaseInsensitivelyAndTrims() {
        val sorted = GameSorter.sort(listOf(game("b"), game("A"), game(" c ")), AppSettingsStore.GAME_SORT_ALPHA)
        assertEquals(listOf("A", "b", " c "), sorted.map { it.title })
    }

    @Test
    fun bracketModeGroupsTaggedTitlesBeforeUntagged() {
        val titles = listOf("未标记", "【乙】two", "[甲]one", "【甲】three")
        val sorted = GameSorter.sort(titles.map(::game), AppSettingsStore.GAME_SORT_BRACKET_TAG)
        // 标签组内按码点序（乙 U+4E59 < 甲 U+7532），非拼音序；同标签按完整标题键排序
        assertEquals(
            listOf("【乙】two", "[甲]one", "【甲】three", "未标记").map(::game),
            sorted,
        )
    }

    @Test
    fun bracketTagSupportsBothBracketStyles() {
        assertEquals("甲", GameSorter.bracketTag("【甲】title"))
        assertEquals("乙", GameSorter.bracketTag("[乙]title"))
        assertEquals("", GameSorter.bracketTag("no tag"))
        assertEquals("", GameSorter.bracketTag("【unclosed"))
    }

    @Test
    fun untaggedEntriesKeepRelativeAlphabeticOrder() {
        val titles = listOf("zeta", "alpha")
        val sorted = GameSorter.sort(titles.map(::game), AppSettingsStore.GAME_SORT_BRACKET_TAG)
        assertEquals(listOf("alpha", "zeta"), sorted.map { it.title })
    }

    @Test
    fun unknownSortModeFallsBackToAlpha() {
        val sorted = GameSorter.sort(listOf(game("b"), game("a")), "whatever")
        assertEquals(listOf("a", "b"), sorted.map { it.title })
    }

    @Test
    fun largeLibraryScaleSmoke() {
        // 复杂度回归哨兵：万级条目两种模式都应在宽松时限内完成（CI 友好阈值，非精确基准）
        val titles = (0 until 10_000).map { idx ->
            if (idx % 3 == 0) "【组${idx % 97}】title-$idx" else "game-$idx"
        }
        val games = titles.map(::game)

        val startAlpha = System.nanoTime()
        val alpha = GameSorter.sort(games, AppSettingsStore.GAME_SORT_ALPHA)
        val alphaMs = (System.nanoTime() - startAlpha) / 1_000_000

        val startBracket = System.nanoTime()
        val bracket = GameSorter.sort(games, AppSettingsStore.GAME_SORT_BRACKET_TAG)
        val bracketMs = (System.nanoTime() - startBracket) / 1_000_000

        assertEquals(10_000, alpha.size)
        assertEquals(titles.sortedBy { it.lowercase() }, alpha.map { it.title })
        assertEquals(10_000, bracket.size)
        // 括号分组模式必须保持稳定序：同输入两次排序结果一致
        assertEquals(bracket, GameSorter.sort(games, AppSettingsStore.GAME_SORT_BRACKET_TAG))

        org.junit.Assert.assertTrue("alpha sort took ${alphaMs}ms", alphaMs < 2_000)
        org.junit.Assert.assertTrue("bracket sort took ${bracketMs}ms", bracketMs < 2_000)
    }
}
