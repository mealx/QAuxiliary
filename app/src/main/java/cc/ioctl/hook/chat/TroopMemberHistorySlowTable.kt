/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2026 QAuxiliary developers
 * https://github.com/cinit/QAuxiliary
 *
 * This software is an opensource software: you can redistribute it
 * and/or modify it under the terms of the General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version
 * as published by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the General Public License for more details.
 *
 * You should have received a copy of the General Public License
 * along with this software.
 * If not, see
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */

package cc.ioctl.hook.chat

import cc.ioctl.util.HookUtils
import cc.ioctl.util.HostInfo
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.bridge.ManagerHelper
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import io.github.qauxv.util.SyncUtils
import io.github.qauxv.util.xpcompat.XC_MethodHook
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 群聊「查找聊天记录 - 按成员查看」默认只查询主库 `mr_troop_xxx_New`，
 * 被裁剪/迁移到 slowtable 的历史消息不会出现在列表里。
 *
 * 这里拦截 EntityManager 里真正干活的 `queryInner`（公共 `query` 只是薄封装、
 * 容易被 ART 内联，所以不挂它），把 slowtable 中满足同样条件的历史消息合并进返回结果。
 * 后续的分页游标 shmsgseq 与「是否还有更多」判断仍由 QQ 自己的代码完成，因此不需要
 * 复刻它那一大堆消息摘要逻辑。
 */
@FunctionHookEntry
@UiItemAgentEntry
object TroopMemberHistorySlowTable : CommonSwitchFunctionHook(
    hookKey = "TroopMemberHistorySlowTable",
    defaultEnabled = false,
    targetProc = SyncUtils.PROC_MAIN,
) {

    override val name = "按成员查看包含慢表"
    override val description = "群聊「查找聊天记录 - 按成员查看」同时查询 slowtable，显示已迁移到慢表的历史消息"
    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.CHAT_CATEGORY
    override val isAvailable: Boolean
        get() = HostInfo.isQQ()

    private const val TAG = "TroopMemberHistorySlowTable"
    private const val PAGE_SIZE = 50
    private const val TROOP_TABLE_PREFIX = "mr_troop_"
    private const val TROOP_TABLE_SUFFIX = "_New"
    private const val HEX_DIGITS = "0123456789ABCDEF"

    private val slowTableManagerId by lazy { resolveSlowTableManagerId() }

    /** (String) -> List<MessageRecord> 的慢表查询方法，按 QQ 版本混淆名不同。 */
    private val slowQueryMethodCache = ConcurrentHashMap<Class<*>, Method>()

    /** MessageRecord.shmsgseq 字段。 */
    private val shmsgseqField: Field by lazy {
        Initiator._MessageRecord().getField("shmsgseq").apply { isAccessible = true }
    }

    override fun initOnce(): Boolean {
        val entityManagerClass = try {
            Initiator.loadClass("com.tencent.mobileqq.persistence.EntityManager")
        } catch (e: ClassNotFoundException) {
            Log.e("$TAG: EntityManager not found", e)
            return false
        }
        // 优先挂真正干活的 queryInner；它不太可能被内联，公共 query 只是薄封装。
        val queryMethod = findQueryInner(entityManagerClass) ?: try {
            entityManagerClass.getDeclaredMethod(
                "query",
                Class::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
                String::class.java,
                Array<String>::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
            )
        } catch (e: NoSuchMethodException) {
            Log.e("$TAG: queryInner/query not found", e)
            return false
        }
        HookUtils.hookAfterIfEnabled(this, queryMethod) { param ->
            try {
                handleQuery(param)
            } catch (t: Throwable) {
                Log.e("$TAG: merge failed", t)
            }
        }
        return true
    }

    private fun findQueryInner(entityManagerClass: Class<*>): Method? {
        return entityManagerClass.declaredMethods.firstOrNull { m ->
            m.parameterTypes.size == 11 &&
                m.parameterTypes[0] == Class::class.java &&
                m.parameterTypes[2] == Boolean::class.javaPrimitiveType &&
                List::class.java.isAssignableFrom(m.returnType)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleQuery(param: XC_MethodHook.MethodHookParam) {
        val entityClass = param.args[0] as? Class<*> ?: return
        val tableName = param.args[1] as? String ?: return
        val selection = param.args[3] as? String ?: return
        val orderBy = param.args[7] as? String ?: return
        val limitString = param.args[8] as? String ?: return
        if (!isTargetQuery(entityClass, tableName, selection, orderBy, limitString)) return

        val slowList = querySlowTable(
            tableName = tableName,
            selection = selection,
            selectionArgs = param.args[4] as? Array<*>,
            orderBy = orderBy,
            limit = PAGE_SIZE,
        ) ?: return
        if (slowList.isEmpty()) return

        val mainList = param.result as? List<Any>
        param.result = merge(mainList, slowList, PAGE_SIZE)
    }

    private fun isTargetQuery(
        entityClass: Class<*>,
        tableName: String,
        selection: String,
        orderBy: String,
        limit: String,
    ): Boolean {
        if (!Initiator._MessageRecord().isAssignableFrom(entityClass)) return false
        if (!tableName.startsWith(TROOP_TABLE_PREFIX) || !tableName.endsWith(TROOP_TABLE_SUFFIX)) return false
        if (orderBy != "shmsgseq DESC") return false
        if (limit != PAGE_SIZE.toString()) return false
        // 按成员查看的查询一定带 senderuin 占位符和有效性过滤。
        if (!selection.contains("senderuin = ?")) return false
        if (!selection.contains("isValid=1")) return false
        return true
    }

    private fun resolveSlowTableManagerId(): Int {
        return try {
            val clazz = Initiator.loadClass("com.tencent.mobileqq.app.QQManagerFactory")
            clazz.getField("SLOW_TABLE_MANAGER").getInt(null)
        } catch (t: Throwable) {
            Log.e("$TAG: SLOW_TABLE_MANAGER not found", t)
            -1
        }
    }

    private fun getSlowTableManager(): Any? {
        val id = slowTableManagerId
        if (id < 0) return null
        return try {
            ManagerHelper.getManager(id)
        } catch (t: Throwable) {
            Log.e("$TAG: getManager failed", t)
            null
        }
    }

    private fun findSlowQueryMethod(manager: Any): Method? {
        val clazz = manager.javaClass
        slowQueryMethodCache[clazz]?.let { return it }
        val found = clazz.methods.firstOrNull { m ->
            m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == String::class.java &&
                List::class.java.isAssignableFrom(m.returnType)
        } ?: return null
        found.isAccessible = true
        slowQueryMethodCache[clazz] = found
        return found
    }

    /**
     * 把 selection 中的 `?` 按顺序替换成 SQL 字面量，方便慢表 rawQuery 使用。
     *
     * 注意：这里不能用 `'value'` 直接拼。QQ 8.8.90 的 senderuin 是带控制字符（甚至
     * 含 0x00）的二进制 UID，直接放进单引号会触发 sqlite parse error，导致 rawQuery
     * 返回 null。统一按字节 hex 编码成 `CAST(X'..' AS TEXT)`。
     */
    private fun inlineSelectionArgs(selection: String, args: Array<*>?): String {
        if (!selection.contains('?')) return selection
        val sb = StringBuilder(selection.length + 64)
        var argIndex = 0
        for (c in selection) {
            if (c == '?' && args != null && argIndex < args.size) {
                sb.append(sqlTextLiteral(args[argIndex]?.toString() ?: ""))
                argIndex++
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun sqlTextLiteral(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val hex = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            hex.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0f])
        }
        return "CAST(X'$hex' AS TEXT)"
    }

    private fun shmsgseq(record: Any): Long = shmsgseqField.getLong(record)

    private fun querySlowTable(
        tableName: String,
        selection: String,
        selectionArgs: Array<*>?,
        orderBy: String,
        limit: Int,
    ): List<Any>? {
        val manager = getSlowTableManager() ?: return null
        val method = findSlowQueryMethod(manager) ?: return null
        val where = inlineSelectionArgs(selection, selectionArgs)
        val sql = "select * from $tableName where $where order by $orderBy limit $limit"
        @Suppress("UNCHECKED_CAST")
        return method.invoke(manager, sql) as? List<Any>
    }

    private fun merge(mainList: List<Any>?, slowList: List<Any>, limit: Int): List<Any> {
        val merged = ArrayList<Any>((mainList?.size ?: 0) + slowList.size)
        if (mainList != null) merged.addAll(mainList)
        merged.addAll(slowList)
        // 合并后按 shmsgseq 倒序，取最新 limit 条；QQ 的分页游标基于该结果继续。
        merged.sortWith(Comparator { a, b -> shmsgseq(b).compareTo(shmsgseq(a)) })
        return if (merged.size > limit) ArrayList(merged.subList(0, limit)) else merged
    }
}
