package com.sdyl.patrol.feature.patrol

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * 时间输入（用户 m06106：「把时间框变成分块填入，不需要输入中间的符号，然后也允许圆盘选择」）。
 *
 * **用户 m15940 改版**：原来的框是「一个 BasicTextField 只存 14 位数字 + 分隔符画出来」，
 * 每次输入都把光标钉到末尾（`TextRange(len)`），所以改前面的数字只能从后往前删；
 * 而且 M3 的 [androidx.compose.material3.TimePicker] 只有时/分、**没有秒**。
 * 现在改成：**一行只读的显示 + 点开「六列滚轮」（年/月/日/时/分/秒）**，秒也能选，不用键盘。
 *
 * 对外仍然只吐 `yyyy-MM-dd HH:mm:ss` 文本——调用方照旧用 [parsePatrolTimeOrNull] 解析。
 */
@Composable
internal fun DateTimeField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    onNow: (() -> Unit)? = null,
) {
    var showWheel by remember { mutableStateOf(false) }

    if (showWheel) {
        WheelDateTimeDialog(
            initial = value,
            onDismiss = { showWheel = false },
            onConfirm = { text ->
                showWheel = false
                onValueChange(text)
            },
        )
    }

    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 只做显示，点了才开滚轮：不再让操作员在框里和光标较劲
        Box(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .border(
                    1.dp,
                    if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(8.dp),
                )
                .clickable { showWheel = true }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value.ifBlank { "点这里选择日期时间" },
                    modifier = Modifier.weight(1f),
                    style = TextStyle(fontSize = 17.sp),
                    color = if (value.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Icon(
                    Icons.Filled.CalendarMonth,
                    contentDescription = "选择日期时间",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (onNow != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onNow, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("现在", fontSize = 13.sp)
                }
            }
        }
    }
}

/** 滚轮一行的高度（5 行一屏，中间那行是选中行）。 */
private val WHEEL_ROW_HEIGHT = 36.dp

/** 中间那一行对应的下标：谁的几何中心和视口中心最近就算谁。 */
private fun centeredIndex(info: LazyListLayoutInfo): Int {
    if (info.visibleItemsInfo.isEmpty()) return 0
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo
        .minByOrNull { abs(it.offset + it.size / 2 - center) }
        ?.index
        ?: 0
}

/**
 * 六列滚轮弹窗（年/月/日/时/分/秒）。
 *
 * 用 `LazyColumn` + 吸附（[rememberSnapFlingBehavior]）自己搭，是因为 M3 的 `TimePicker`
 * 只有时/分；上下留两行空位，让第一项/最后一项也能停在中间；选中值由滚动位置反推（[centeredIndex]）。
 */
@Composable
internal fun WheelDateTimeDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val now = remember { Calendar.getInstance() }
    val parts = remember(initial) { splitTimeParts(if (initial.any { it.isDigit() }) initial else "") }
    // 空白值（比如「实际结束」还没填）就落到当前时间上
    var year by remember {
        mutableIntStateOf(parts[0].toIntOrNull()?.takeIf { it >= 1900 } ?: now.get(Calendar.YEAR))
    }
    var month by remember {
        mutableIntStateOf(parts[1].toIntOrNull()?.coerceIn(1, 12) ?: (now.get(Calendar.MONTH) + 1))
    }
    var day by remember {
        mutableIntStateOf(parts[2].toIntOrNull()?.coerceIn(1, 31) ?: now.get(Calendar.DAY_OF_MONTH))
    }
    var hour by remember {
        mutableIntStateOf(parts[3].toIntOrNull()?.coerceIn(0, 23) ?: now.get(Calendar.HOUR_OF_DAY))
    }
    var minute by remember {
        mutableIntStateOf(parts[4].toIntOrNull()?.coerceIn(0, 59) ?: now.get(Calendar.MINUTE))
    }
    var second by remember {
        mutableIntStateOf(parts[5].toIntOrNull()?.coerceIn(0, 59) ?: now.get(Calendar.SECOND))
    }

    val maxDay = remember(year, month) { daysInMonth(year, month) }
    LaunchedEffect(maxDay) { if (day > maxDay) day = maxDay }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(
                    "选择日期时间",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                WheelDateTimePicker(
                    year = year,
                    month = month,
                    day = day,
                    hour = hour,
                    minute = minute,
                    second = second,
                    onYear = { year = it },
                    onMonth = { month = it },
                    onDay = { day = it },
                    onHour = { hour = it },
                    onMinute = { minute = it },
                    onSecond = { second = it },
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(
                        onClick = {
                            onConfirm("%04d-%02d-%02d %02d:%02d:%02d".format(year, month, day, hour, minute, second))
                        },
                    ) { Text("确定") }
                }
            }
        }
    }
}

@Composable
private fun WheelDateTimePicker(
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
    second: Int,
    onYear: (Int) -> Unit,
    onMonth: (Int) -> Unit,
    onDay: (Int) -> Unit,
    onHour: (Int) -> Unit,
    onMinute: (Int) -> Unit,
    onSecond: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nowYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val years = remember(nowYear, year) {
        (minOf(nowYear, year) - 5..maxOf(nowYear, year) + 5).toList()
    }
    val days = remember(year, month) { (1..daysInMonth(year, month)).toList() }

    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            listOf("年", "月", "日", "时", "分", "秒").forEach { title ->
                Text(
                    title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth()) {
            // 中间选中行的底衬
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(WHEEL_ROW_HEIGHT)
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                        RoundedCornerShape(10.dp),
                    ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                WheelColumn(years, year, 4, onYear, Modifier.weight(1f))
                WheelColumn((1..12).toList(), month, 2, onMonth, Modifier.weight(1f))
                // 换年/换月后天数会变（31 → 28），用 resetKey 让这一列重新定位到夹过的天数
                WheelColumn(days, day, 2, onDay, Modifier.weight(1f), resetKey = year * 100 + month)
                WheelColumn((0..23).toList(), hour, 2, onHour, Modifier.weight(1f))
                WheelColumn((0..59).toList(), minute, 2, onMinute, Modifier.weight(1f))
                WheelColumn((0..59).toList(), second, 2, onSecond, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun WheelColumn(
    items: List<Int>,
    value: Int,
    digits: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    resetKey: Any? = null,
) {
    // 注意参数名：构造函数叫 firstVisibleItemIndex（rememberLazyListState 才叫 initialFirstVisibleItemIndex）
    val state = remember(resetKey) {
        LazyListState(firstVisibleItemIndex = items.indexOf(value).coerceAtLeast(0))
    }
    val centered by remember { derivedStateOf { centeredIndex(state.layoutInfo) } }
    LaunchedEffect(centered, items) {
        items.getOrNull(centered)?.let { if (it != value) onValueChange(it) }
    }
    LazyColumn(
        state = state,
        modifier = modifier.height(WHEEL_ROW_HEIGHT * 5),
        contentPadding = PaddingValues(vertical = WHEEL_ROW_HEIGHT * 2),
        flingBehavior = rememberSnapFlingBehavior(lazyListState = state),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(count = items.size) { i ->
            val v = items[i]
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(WHEEL_ROW_HEIGHT),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = v.toString().padStart(digits, '0'),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (v == value) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (v == value) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

/** 某年某月有多少天（自己算，免得为了 `YearMonth` 打开 desugaring）。 */
internal fun daysInMonth(year: Int, month: Int): Int =
    Calendar.getInstance().apply {
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month - 1)
        set(Calendar.DAY_OF_MONTH, 1)
    }.getActualMaximum(Calendar.DAY_OF_MONTH)

/**
 * `20261014060030` → 该日期在 **UTC** 的零点毫秒。
 *
 * M3 `DatePickerState.selectedDateMillis` 就是这个口径（"UTC 当天零点"），
 * 读回来也必须按 UTC 解日期，否则东八区会差一天。
 */
internal fun dateMillisUtc(digits: String): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    if (digits.length >= 8) {
        runCatching {
            cal.set(
                digits.substring(0, 4).toInt(),
                digits.substring(4, 6).toInt() - 1,
                digits.substring(6, 8).toInt(),
                0,
                0,
                0,
            )
            cal.set(Calendar.MILLISECOND, 0)
        }
    }
    return cal.timeInMillis
}

/** 14 位数字 → `yyyy-MM-dd HH:mm:ss`；不足 14 位就按已有位数显示，分隔符仍然自动补。 */
internal fun formatTimeDigits(digits: String): String {
    val d = digits.filter { it.isDigit() }.take(14)
    val sb = StringBuilder()
    for (i in d.indices) {
        when (i) {
            4, 6 -> sb.append('-')
            8 -> sb.append(' ')
            10, 12 -> sb.append(':')
        }
        sb.append(d[i])
    }
    return sb.toString()
}

/**
 * 只负责显示：把纯数字画成 `2026-10-14 06:00:30`（用户不用自己打分隔符）。
 *
 * 光标位置也要换算，否则点击/退格会跳位——[OffsetMapping] 里按"这个位置之前插了几个分隔符"算。
 * 用户 m15940 之后主界面不再用这个输入框（改用滚轮），这里保留给可能的键盘输入场景和单测。
 */
internal object TimeSeparatorTransformation : VisualTransformation {
    private val separatorsBefore = listOf(4, 6, 8, 10, 12)

    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val out = formatTimeDigits(digits)
        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val o = offset.coerceIn(0, digits.length)
                // 两个条件都要：分隔符只插在"已经打出来的那一位数字"之前。
                // 只写 `o >= it` 时，数字刚好 6 位会多算一个分隔符（第 7 位还没打），
                // 映射越界 → Compose 的 ValidatingOffsetMapping 抛 IllegalStateException 闪退（真机实测抓到）。
                return o + separatorsBefore.count { it < o && it < digits.length }
            }

            override fun transformedToOriginal(offset: Int): Int {
                val o = offset.coerceIn(0, out.length)
                val seps = out.take(o).count { !it.isDigit() }
                return (o - seps).coerceIn(0, digits.length)
            }
        }
        return TransformedText(AnnotatedString(out), offsetMapping)
    }
}

/** `2026-10-14 06:00:30` → `["2026","10","14","06","00","30"]`；缺的部分补 0。 */
internal fun splitTimeParts(text: String): List<String> {
    val nums = Regex("""\d+""").findAll(text).map { it.value }.toList()
    val y = nums.getOrElse(0) { "0" }.take(4).padStart(4, '0')
    val mo = nums.getOrElse(1) { "0" }.take(2).padStart(2, '0')
    val d = nums.getOrElse(2) { "0" }.take(2).padStart(2, '0')
    val h = nums.getOrElse(3) { "0" }.take(2).padStart(2, '0')
    val mi = nums.getOrElse(4) { "0" }.take(2).padStart(2, '0')
    val s = nums.getOrElse(5) { "0" }.take(2).padStart(2, '0')
    return listOf(y, mo, d, h, mi, s)
}

internal fun rebuildTimeText(parts: List<String>): String = "%s-%s-%s %s:%s:%s".format(
    parts[0].ifBlank { "0000" },
    parts[1].ifBlank { "00" },
    parts[2].ifBlank { "00" },
    parts[3].ifBlank { "00" },
    parts[4].ifBlank { "00" },
    parts[5].ifBlank { "00" },
)
