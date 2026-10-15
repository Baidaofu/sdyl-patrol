package com.sdyl.patrol.core.network.dto

import com.google.gson.annotations.SerializedName

/**
 * `GET /inter-api/organization/v1/persons/pages` 的 `data.list[]` 元素（只声明用得到的字段）。
 *
 * 实测（2026-10-14，ticket 有效；2026-10-15 复核）：
 *  - 服务端**忽略** `current` / `pageSize`，也忽略 `code` / `name` / `keyword` 等筛选参数，
 *    永远一次性返回全公司人员（`pagination.total = 9245`，响应 ≈ 6.8 MB，PC 直连 1.8s）。
 *  - 每项含 `id`（服务端内部人员 ID）、`code`（工号/人员编码）、`name`、`phone`、
 *    `status`、`valid`、`userName`、`avatarUrl`、`signPicUrl`、
 *    `department[]`（`name` / `fullPath` / `currentPosition`）、
 *    `position[]`（`name` / `fullPath` / `mainPosition`）、`gender`。
 *    **没有邮箱字段**（原版本地库 `CONTACT_ENTITY.EMAIL` 全公司只有 14 条非空，等于没有）。
 *  - 请求必须带 `User-Agent`（原版是 `.../mesjg supMobile/6.2.8.35`），否则网关直接 403。
 *
 * 为什么要这个接口：`巡检任务.xlsx` 里跟人走的标识只有「人员编码」（工号，如 `90000001`），
 * **导出里没有服务端内部 ID**；而写接口（`userId` / `staffId` / `completeUser.id`）认的是内部 ID，
 * 所以由「工号 → 内部 ID」在这里换算。
 */
data class PersonItem(
    val id: Long = 0L,
    val code: String? = null,
    val name: String? = null,
    val phone: String? = null,
    val valid: Boolean = true,
    /*
     * 用户 m17358：「可以查询到用户更多信息，我看好像还能显示电话？是不是还有邮箱和部门。」
     * 下面是解析出来的常用信息（部门/岗位由接口的嵌套数组拍平成两个字符串）。
     */
    val deptName: String? = null,
    val deptPath: String? = null,
    val positionName: String? = null,
    val gender: String? = null,
    val status: String? = null,
    @SerializedName("userName") val userName: String? = null,
    /**
     * 接口原始嵌套数组。6.8 MB 响应里体积主要在这里，落盘前会被清掉
     * （见 [com.sdyl.patrol.core.data.PersonDirectory.refresh]），只留上面拍平的字段。
     */
    @SerializedName("department") val departmentRaw: List<DepartmentRef>? = null,
    @SerializedName("position") val positionRaw: List<PositionRef>? = null,
) {
    /** 在职（服务端字典 `sys_person_status/onWork`）；字段缺失时按在职算 */
    val onWork: Boolean get() = status.isNullOrBlank() || status.endsWith("onWork")

    /**
     * 性别，服务端字典 `sys_gender/male|female`。
     * 取 `/` 后面那一段精确比，别用 endsWith —— `female` 也是以 `male` 结尾的。
     */
    val genderLabel: String?
        get() = when (gender?.substringAfterLast('/')?.lowercase()) {
            "male" -> "男"
            "female" -> "女"
            else -> null
        }

    /** 一行「部门 · 岗位」，都没有就 null */
    val deptPosition: String?
        get() {
            val dept = deptName?.trim().orEmpty()
            val position = positionName?.trim().orEmpty()
            return when {
                dept.isBlank() -> position.ifBlank { null }
                // 很多人的「岗位」名字和部门一模一样（服务端就这么填的），别显示两遍
                position.isBlank() || position == dept -> dept
                else -> "$dept · $position"
            }
        }
}

/** `department[]` 元素 */
data class DepartmentRef(
    val name: String? = null,
    @SerializedName("fullPath") val fullPath: String? = null,
    @SerializedName("currentPosition") val currentPosition: Boolean = false,
)

/** `position[]` 元素 */
data class PositionRef(
    val name: String? = null,
    @SerializedName("fullPath") val fullPath: String? = null,
    @SerializedName("mainPosition") val mainPosition: Boolean = false,
)

/** 上面接口的 `data` 段。 */
data class PersonPage(
    @SerializedName("list") val list: List<PersonItem> = emptyList(),
)
