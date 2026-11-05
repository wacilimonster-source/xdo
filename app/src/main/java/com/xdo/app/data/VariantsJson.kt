package com.xdo.app.data

import org.json.JSONArray
import org.json.JSONObject

/** QualityOption ↔ JSON 序列化（存入 records.variantsJson，跨重启保留清晰度与音频轨信息） */
object VariantsJson {

    fun encode(qualities: List<QualityOption>): String {
        val arr = JSONArray()
        qualities.forEach { q ->
            arr.put(JSONObject().apply {
                put("label", q.label)
                put("url", q.url)
                put("bitrate", q.bitrateKbps)
                put("isHls", q.isHls)
                q.audioUrl?.let { put("audioUrl", it) }
            })
        }
        return arr.toString()
    }

    fun decode(json: String): List<QualityOption> {
        val list = ArrayList<QualityOption>()
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    QualityOption(
                        label = o.getString("label"),
                        width = 0,
                        height = 0,
                        bitrateKbps = o.optInt("bitrate", 0),
                        url = o.getString("url"),
                        isHls = o.optBoolean("isHls", false),
                        audioUrl = o.optString("audioUrl", "").takeIf { it.isNotBlank() },
                    )
                )
            }
        }
        return list
    }
}
