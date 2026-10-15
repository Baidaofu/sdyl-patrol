package com.sdyl.patrol.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「登录过的账号」列表的合并规则（用户 m16146：切换账号 / 登录页选账号）。
 *
 * 这里只测纯函数：同「服务器 + 账号」要覆盖并挪到最前、不同服务器要各留一条、
 * 超过上限丢最久没用的那个。存盘那部分在 [SessionStore] 里，单测碰不到 Context。
 */
class SavedAccountTest {

    private fun acc(user: String, host: String = "supos.example.com:8443", pwd: String = "p") =
        SavedAccount(user = user, password = pwd, staffName = "张三", host = host)

    @Test
    fun `same account on same host is replaced and moved to front`() {
        val old = listOf(acc("90000001"), acc("90000007"))
        val merged = mergeSavedAccount(old, acc("90000007", pwd = "new"), max = 8)
        assertEquals(listOf("90000007", "90000001"), merged.map { it.user })
        assertEquals("new", merged.first().password)
    }

    @Test
    fun `same account on another host is a different entry`() {
        val old = listOf(acc("90000001", host = "10.0.0.1:8445"))
        val merged = mergeSavedAccount(old, acc("90000001", host = "supos.example.com:8443"), max = 8)
        assertEquals(2, merged.size)
    }

    @Test
    fun `oldest entries fall off the end at the limit`() {
        val old = listOf(acc("a"), acc("b"), acc("c"))
        val merged = mergeSavedAccount(old, acc("d"), max = 3)
        assertEquals(listOf("d", "a", "b"), merged.map { it.user })
    }

    @Test
    fun `blank user is ignored`() {
        val old = listOf(acc("a"))
        assertEquals(old, mergeSavedAccount(old, acc(""), max = 8))
    }

    @Test
    fun `title prefers staff name and password gets masked`() {
        val a = SavedAccount(user = "90000001", password = "1234", staffName = "张三")
        assertEquals("张三", a.title)
        assertEquals("••••", a.maskedPassword())
        assertEquals("（没记密码）", SavedAccount(user = "x").maskedPassword())
        // 没有姓名时退回账号本身，不然列表里会出现空白
        assertEquals("x", SavedAccount(user = "x").title)
    }

    @Test
    fun `label never repeats the account in parentheses`() {
        // 用户 m16405：「登录界面的登陆账号会显示工号（工号），有问题」
        assertEquals("张三（90000001）", SavedAccount(user = "90000001", staffName = "张三").label)
        assertEquals("90000001", SavedAccount(user = "90000001", staffName = "90000001").label)
        assertEquals("90000001", SavedAccount(user = "90000001").label)
    }

    @Test
    fun `legacy account row becomes a saved account`() {
        val a = LegacyAccountImporter.accountFromRow(
            "90000001",
            "张三",
            "Demo-Passw0rd",
            "https://supos.example.com:8443",
        )
        assertEquals("90000001", a?.user)
        assertEquals("张三", a?.staffName)
        assertEquals("Demo-Passw0rd", a?.password)
        assertEquals("supos.example.com:8443", a?.host)
        assertEquals(true, a?.useHttps)
        // 原版库里的 IP 列可能带 http://，那就记成不用 https
        assertEquals(false, LegacyAccountImporter.accountFromRow("x", "y", "p", "http://10.0.0.1:8080")?.useHttps)
        // 没有账号的行丢掉（原版库里有空行）
        assertNull(LegacyAccountImporter.accountFromRow("  ", "y", "p", "https://h"))
        // 姓名缺失时用账号兜底，列表里不会出现空白
        assertEquals("90000001", LegacyAccountImporter.accountFromRow("90000001", "", "p", "https://h")?.staffName)
    }

    /**
     * 用户 m17221：登录时服务端没下发姓名，会把工号当成姓名存下来，
     * 于是列表里出现「90000002」这种行。规则：占位名（空 / 等于工号）不许覆盖已查到的真名。
     */
    @Test
    fun `placeholder name never overwrites a real name`() {
        val old = listOf(
            SavedAccount(user = "90000002", password = "p", staffName = "赵六", host = "h"),
        )
        // 新条目只带工号（= 没查到名字）
        val merged = mergeSavedAccount(
            old,
            SavedAccount(user = "90000002", password = "p2", staffName = "90000002", host = "h"),
        )
        assertEquals("赵六", merged.first().staffName)
        assertEquals("p2", merged.first().password)

        // 新条目带真名时照旧更新
        val renamed = mergeSavedAccount(
            old,
            SavedAccount(user = "90000002", password = "p2", staffName = "赵六（改）", host = "h"),
        )
        assertEquals("赵六（改）", renamed.first().staffName)

        // 老记录本身就是工号，新条目也是工号：随便存哪个都一样
        val both = mergeSavedAccount(
            listOf(SavedAccount(user = "90000008", password = "p", staffName = "90000008", host = "h")),
            SavedAccount(user = "90000008", password = "p", staffName = "90000008", host = "h"),
        )
        assertEquals("90000008", both.first().staffName)

        assertEquals(true, isPlaceholderName("", "90000008"))
        assertEquals(true, isPlaceholderName(null, "90000008"))
        assertEquals(true, isPlaceholderName("90000008", "90000008"))
        assertEquals(false, isPlaceholderName("陈十二", "90000008"))
    }
}
