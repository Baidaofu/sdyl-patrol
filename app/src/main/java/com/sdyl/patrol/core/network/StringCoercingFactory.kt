package com.sdyl.patrol.core.network

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * 原平台 的字段类型不稳定：同一个字段有时返回字符串，有时返回 `{id, code, value, name, ...}` 对象。
 *
 * 真机实测（2026-10-12，任务 patrolTask_20261012_2544 / 区域 1000000000000248）：
 * ```
 * "inputStandardCode": {
 *   "candidateValue": "是,否", "code": "M-RJY-1-002",
 *   "editType": {"id":"PATROL_editType/whether","code":"whether","value":"是否","uniqueCode":"..."},
 *   "valType":  {"id":"PATROL_valueType/char","code":"char","value":"字符","uniqueCode":"..."}
 * }
 * ```
 * 模型把 `editType` 当字符串用，Gson 就会抛：
 * `IllegalStateException: Expected a string but was BEGIN_OBJECT at line 1 column 600 path $.data.result[0].inputStandardCode.editType`
 * 一个字段就能让整页数据同步失败（真机上就发生过）。
 *
 * 这里注册一个全局适配器：任何 String 字段收到对象时，按
 * id → uniqueCode → code → value → name 的顺序压平成字符串；收不到就返回 null。
 * 反向（声明为对象、服务端给字符串）不在这里处理。
 */
internal class StringCoercingFactory : TypeAdapterFactory {

    override fun <T : Any?> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        if (type.rawType != String::class.java) return null
        val delegate = gson.getDelegateAdapter(this, type)
        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T) = delegate.write(out, value)

            @Suppress("UNCHECKED_CAST")
            override fun read(input: JsonReader): T = when (input.peek()) {
                JsonToken.BEGIN_OBJECT -> flatten(input) as T
                JsonToken.NULL -> {
                    input.nextNull()
                    null as T
                }
                // Gson 的 String 适配器不认 BOOLEAN，这里自己兜住
                JsonToken.BOOLEAN -> input.nextBoolean().toString() as T
                else -> delegate.read(input)
            }
        }
    }

    /** 读取整个对象，只留标量字段，然后挑一个最有代表性的键返回。 */
    private fun flatten(input: JsonReader): String? {
        val scalars = LinkedHashMap<String, String>()
        input.beginObject()
        while (input.hasNext()) {
            val name = input.nextName()
            when (input.peek()) {
                JsonToken.NULL -> input.nextNull()
                JsonToken.BEGIN_OBJECT, JsonToken.BEGIN_ARRAY -> input.skipValue()
                JsonToken.BOOLEAN -> scalars[name] = input.nextBoolean().toString()
                else -> scalars[name] = input.nextString()
            }
        }
        input.endObject()
        for (key in KEYS) {
            val v = scalars[key]
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    private companion object {
        val KEYS = listOf("id", "uniqueCode", "code", "value", "name", "fullPathName")
    }
}
