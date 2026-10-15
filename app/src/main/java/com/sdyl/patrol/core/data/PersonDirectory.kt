package com.sdyl.patrol.core.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sdyl.patrol.core.network.ApiClient
import com.sdyl.patrol.core.network.BapResponse
import com.sdyl.patrol.core.network.dto.PersonItem
import com.sdyl.patrol.core.network.dto.PersonPage
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.File

/**
 * 人员名录：**工号（人员编码）↔ 服务端内部人员 ID ↔ 姓名**。
 *
 * 为什么需要它（用户 m08022）：`巡检任务.xlsx` 里跟人走的标识只有「人员编码」
 * （如 `90000001`）和「员工姓名」，导出里根本没有内部 ID（`1000000000000952` 那串，
 * 那是登录接口 `getCurrentLoginInfo` 下发的）。可写接口认的偏偏是内部 ID，
 * 所以设置界面让用户填**工号**，由这里换算成内部 ID。
 *
 * 数据源与形态都照抄原版「通讯录」（`module_contact`）：同一个
 * `GET /inter-api/organization/v1/persons/pages`，服务端不给分页也不给筛选，
 * 一次返回全公司人员，原版也是整包拉下来缓存到本地再搜。
 * 这里落盘成只含 `id/code/name/phone` 的小 JSON，之后离线可查。
 */
class PersonDirectory(private val api: ApiClient, private val dir: File) {

    interface PersonApi {
        /** 服务端忽略这两个参数（见 [com.sdyl.patrol.core.network.dto.PersonItem] 的说明）。 */
        @GET("/inter-api/organization/v1/persons/pages")
        suspend fun persons(
            @Query("current") current: Int,
            @Query("pageSize") pageSize: Int,
        ): BapResponse<PersonPage>
    }

    private val gson = Gson()
    private val file = File(dir, "persons.json")

    @Volatile
    private var cache: List<PersonItem>? = null

    /** 本地是否已有名单（有就不用联网） */
    val isCached: Boolean get() = file.exists() || cache?.isNotEmpty() == true

    /** 本地名单里的人数 */
    val count: Int get() = load().size

    /** 名录最后更新时间（落盘文件的修改时间）；没有名录时是 0 */
    val lastUpdatedAt: Long get() = if (file.exists()) file.lastModified() else 0L

    /**
     * 名录是不是太旧了（用户 m17358 第 3 条：超过 7 天打开「我的」时后台自动刷一次）。
     * 没有名录也算旧。
     */
    fun isStale(maxAgeMillis: Long = DEFAULT_MAX_AGE_MS): Boolean {
        val t = lastUpdatedAt
        return t == 0L || System.currentTimeMillis() - t > maxAgeMillis
    }

    private fun load(): List<PersonItem> {
        cache?.let { return it }
        val loaded = runCatching {
            if (!file.exists()) {
                emptyList()
            } else {
                val type = object : TypeToken<List<PersonItem>>() {}.type
                gson.fromJson<List<PersonItem>>(file.readText(), type) ?: emptyList()
            }
        }.getOrDefault(emptyList())
        cache = loaded
        return loaded
    }

    /** 拉全量人员名单并落盘，返回人数。 */
    suspend fun refresh(): Result<Int> = runCatching {
        // 7.6 MB / 9228 人的大响应，用长超时的 client（原版也把超时调到 90s）
        val res = api.createLong(PersonApi::class.java).persons(1, 10000)
        if (!res.isOk) throw IllegalStateException(res.errorText)
        val people = (res.payload()?.list.orEmpty())
            .filter { it.id > 0L && !it.code.isNullOrBlank() }
            .distinctBy { it.code }
            .map { it.slimmed() }
        if (people.isEmpty()) throw IllegalStateException("服务端没有返回人员名单")
        file.parentFile?.mkdirs()
        // 文件写成功，修改时间就是「最后更新」时间（[lastUpdatedAt] 就取它）
        file.writeText(gson.toJson(people))
        cache = people
        people.size
    }

    fun byCode(code: String): PersonItem? {
        val key = code.trim()
        if (key.isEmpty()) return null
        return load().firstOrNull { it.code?.equals(key, ignoreCase = true) == true }
    }

    fun byName(name: String): PersonItem? {
        val key = name.trim()
        if (key.isEmpty()) return null
        return load().firstOrNull { it.name == key }
    }

    /** 同名的人可能不止一个（用户 m14256：重名要弹出来让用户自己挑） */
    fun byNameAll(name: String): List<PersonItem> {
        val key = name.trim()
        if (key.isEmpty()) return emptyList()
        return load().filter { it.name == key }
    }

    /**
     * 用户 m14256：查询要**双向**——工号查名字、名字查工号。
     * 先按工号精确命中，再按姓名精确命中，最后才退化成模糊搜（子串）。
     */
    fun candidates(keyword: String, limit: Int = 20): List<PersonItem> =
        candidatesIn(load(), keyword, limit)

    /**
     * 把用户填的「工号」换算成服务端内部人员 ID。
     * 填的本来就是内部 ID（旧数据 / 手工填的）时原样返回，找不到也不编一个。
     */
    fun toInternalId(input: String?): String? = internalIdIn(load(), input)

    /**
     * 按服务端内部人员 ID 反查 —— 任务投影里的 `executeUser`（执行人）只给这个 ID
     * （`executeUser: {id: 1000000000000776}`），要显示成人名就得靠本地名录。
     */
    fun byId(id: String?): PersonItem? {
        val key = id?.trim()?.toLongOrNull() ?: return null
        return load().firstOrNull { it.id == key }
    }

    /** 按关键字（工号 / 姓名）模糊找，供设置界面提示用 */
    fun search(keyword: String, limit: Int = 10): List<PersonItem> {
        val key = keyword.trim()
        if (key.isEmpty()) return emptyList()
        return load().filter {
            it.code?.contains(key, ignoreCase = true) == true || it.name?.contains(key) == true
        }.take(limit)
    }

    /**
     * 按工号换姓名，查不到返回 null。
     * 登录响应没带姓名、本地账号表存成工号时用它补名字（用户 m17221）。
     */
    fun nameByCode(code: String?): String? {
        val key = code?.trim().orEmpty()
        if (key.isEmpty()) return null
        return load().firstOrNull { it.code?.equals(key, ignoreCase = true) == true }
            ?.name?.takeIf { it.isNotBlank() }
    }

    /**
     * 纯函数版查询（不碰文件/网络，供单测覆盖重名、双向查这些规则）。
     * 顺序：工号精确 → 姓名精确（可能多条 = 重名）→ 模糊子串。
     */
    internal companion object {
        /** 名录默认多久算过时（用户 m17358 第 3 条：7 天） */
        const val DEFAULT_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

        fun candidatesIn(
            people: List<PersonItem>,
            keyword: String,
            limit: Int = 20,
        ): List<PersonItem> {
            val key = keyword.trim()
            if (key.isEmpty()) return emptyList()
            people.firstOrNull { it.code?.equals(key, ignoreCase = true) == true }?.let {
                return listOf(it)
            }
            val exact = people.filter { it.name == key }
            if (exact.isNotEmpty()) return exact.take(limit)
            return people.filter {
                it.code?.contains(key, ignoreCase = true) == true || it.name?.contains(key) == true
            }.take(limit)
        }

        fun internalIdIn(people: List<PersonItem>, input: String?): String? {
            val key = input?.trim().orEmpty()
            if (key.isEmpty()) return null
            people.firstOrNull { it.code?.equals(key, ignoreCase = true) == true }?.let {
                return it.id.toString()
            }
            return key
        }
    }
}

/**
 * 把接口原始项压成落盘形态（用户 m17358：查询结果要能显示电话 / 部门 / 岗位）：
 * `department[]` / `position[]` 拍平成两个字符串，丢掉嵌套数组——
 * 6.8 MB 响应里体积主要在这，全量 9245 人存下来会白占好几 MB。
 * 服务端每项可以带多个部门/岗位，取 `currentPosition` / `mainPosition` 那个，没有就取第一个。
 */
internal fun PersonItem.slimmed(): PersonItem {
    val dept = departmentRaw?.firstOrNull { it.currentPosition } ?: departmentRaw?.firstOrNull()
    val pos = positionRaw?.firstOrNull { it.mainPosition } ?: positionRaw?.firstOrNull()
    return copy(
        deptName = dept?.name?.takeIf { it.isNotBlank() },
        deptPath = dept?.fullPath?.takeIf { it.isNotBlank() },
        positionName = pos?.name?.takeIf { it.isNotBlank() },
        departmentRaw = null,
        positionRaw = null,
    )
}
