package com.xdo.app.net

import com.xdo.app.data.RecordDao
import com.xdo.app.data.RecordStatus
import com.xdo.app.data.VariantsJson

/**
 * 统一的解析入口：解析结果安全写回数据库。
 *
 * 修复的问题：
 * - B02：resolve 不再用旧快照整行 REPLACE（否则重复分享会把 DONE 打回 READY）；
 * - B03：resolveAgain 之前只改状态不解析，现统一走真实解析；
 * - B12：删除记录后在途解析会把记录「复活」——写回用条件 UPDATE，
 *         记录已被删除时 UPDATE 影响行数为 0，自然放弃；
 * - 写回只更新元数据字段，绝不触碰 chosenUrl/fileUri/progress/fileSize/positionMs。
 */
object RecordResolver {

    /** 允许发起解析/被解析结果覆盖的状态 */
    val RESOLVABLE = listOf(RecordStatus.PARSING, RecordStatus.READY, RecordStatus.FAILED)

    /**
     * 对指定记录执行解析并写回。记录不存在或状态不允许（DONE/DOWNLOADING/PAUSED）时直接返回。
     */
    suspend fun resolveAndApply(dao: RecordDao, recordId: Long): ResolveResult {
        val rec = dao.getById(recordId) ?: return ResolveResult.Failure("记录不存在")
        if (rec.status !in RESOLVABLE) {
            return ResolveResult.Failure("当前状态无需解析")
        }
        dao.updateStatusIn(recordId, RecordStatus.PARSING, null, RESOLVABLE)
        val result = XResolver.resolve(rec.tweetId)
        when (result) {
            is ResolveResult.Success -> {
                dao.applyResolveResult(
                    id = recordId,
                    authorName = result.authorName,
                    handle = result.handle,
                    text = result.text,
                    posterUrl = result.posterUrl,
                    durationMs = result.durationMs,
                    variantsJson = VariantsJson.encode(result.qualities),
                    status = RecordStatus.READY,
                    errorMsg = null,
                    allowed = RESOLVABLE,
                )
            }
            is ResolveResult.Failure -> {
                dao.updateStatusIn(recordId, RecordStatus.FAILED, result.message, RESOLVABLE)
            }
        }
        return result
    }
}
