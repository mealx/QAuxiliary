/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2026 QAuxiliary developers
 * https://github.com/cinit/QAuxiliary
 *
 * This software is non-free but opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version and our eula as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * and eula along with this software.  If not, see
 * <https://www.gnu.org/licenses/>
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */

package cc.ioctl.hook.chat

import cc.hicore.QApp.QAppUtils
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
import io.github.qauxv.util.hostInfo
import io.github.qauxv.util.xpcompat.XC_MethodHook
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 8890 聊天记录页搜索默认只查主库 `mr_*_New`，不会搜索已经迁移到
 * `slowtable_<uin>.db` 的历史消息。
 *
 * 这里不替换 QQ 原有搜索，只在 `MsgProxy.a(String,String,int,String)` 返回后
 * 追加当前会话的 slowtable 命中项，并合并回原 `ChatHistorySearchData`。
 *
 * slowtable 的 `msgData` 是按字节和 kc XOR 的，不能直接 SQL LIKE。这里按 kc
 * 长度生成关键词的多个加密偏移形态，用 `instr(msgData, X'...')` 在 SQLite
 * C 层筛选；只把命中的少量行取回 Java，再用同一个 kc 解密正文。
 */
@FunctionHookEntry
@UiItemAgentEntry
object ChatHistorySearchSlowTable : CommonSwitchFunctionHook(
    hookKey = "ChatHistorySearchSlowTable",
    defaultEnabled = false,
    targetProc = SyncUtils.PROC_MAIN,
) {

    private const val TAG = "ChatHistorySearchSlowTable"
    private const val PAGE_SIZE = 50
    private const val MSG_TYPE_TEXT = -1000
    private const val HEX_DIGITS = "0123456789ABCDEF"

    override val name = "聊天记录搜索包含慢表"
    override val description = "聊天记录页搜索同时查询 slowtable，显示已迁移到慢表的历史文本消息"
    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.CHAT_CATEGORY
    override val isAvailable: Boolean
        get() = HostInfo.isQQ() && !QAppUtils.isQQnt()

    private val kcBytes: ByteArray? by lazy {
        try {
            val key = File(hostInfo.application.filesDir, "kc").readText(Charsets.UTF_8).trim()
            if (key.isEmpty()) null else key.toByteArray(Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.e("$TAG: failed to read kc", t)
            null
        }
    }

    private val messageRecordTableNameMethod: Method? by lazy {
        try {
            Initiator.loadClass("com.tencent.mobileqq.data.MessageRecord").getMethod(
                "getTableName", String::class.java, Int::class.javaPrimitiveType
            )
        } catch (t: Throwable) {
            Log.e("$TAG: MessageRecord.getTableName not found", t)
            null
        }
    }

    private val messageRecordTimeField: Field? by lazy {
        try {
            Initiator.loadClass("com.tencent.mobileqq.data.MessageRecord").getField("time")
        } catch (t: Throwable) {
            Log.e("$TAG: MessageRecord.time not found", t)
            null
        }
    }

    private val messageRecordUniseqField: Field? by lazy {
        try {
            Initiator.loadClass("com.tencent.mobileqq.data.MessageRecord").getField("uniseq")
        } catch (t: Throwable) {
            Log.e("$TAG: MessageRecord.uniseq not found", t)
            null
        }
    }

    private val messageRecordMsgDataField: Field? by lazy {
        try {
            Initiator.loadClass("com.tencent.mobileqq.data.MessageRecord").getField("msgData")
        } catch (t: Throwable) {
            Log.e("$TAG: MessageRecord.msgData not found", t)
            null
        }
    }

    private val messageRecordMsgField: Field? by lazy {
        try {
            Initiator.loadClass("com.tencent.mobileqq.data.MessageRecord").getField("f62936msg")
        } catch (t: Throwable) {
            Log.e("$TAG: MessageRecord.f62936msg not found", t)
            null
        }
    }

    private val chatHistorySearchDataClass: Class<*> by lazy {
        Initiator.loadClass("com.tencent.mobileqq.data.ChatHistorySearchData")
    }

    private val searchData1Field: Field? by lazy {
        try {
            chatHistorySearchDataClass.getField("mSearchData1")
        } catch (t: Throwable) {
            Log.e("$TAG: ChatHistorySearchData.mSearchData1 not found", t)
            null
        }
    }

    private val searchData2Field: Field? by lazy {
        try {
            chatHistorySearchDataClass.getField("mSearchData2")
        } catch (t: Throwable) {
            Log.e("$TAG: ChatHistorySearchData.mSearchData2 not found", t)
            null
        }
    }

    private val slowTableManagerId: Int by lazy {
        try {
            Initiator.loadClass("com.tencent.mobileqq.app.QQManagerFactory")
                .getField("SLOW_TABLE_MANAGER").getInt(null)
        } catch (t: Throwable) {
            Log.e("$TAG: SLOW_TABLE_MANAGER not found", t)
            -1
        }
    }

    /** (String) -> List<MessageRecord> 的慢表查询方法，按 QQ 版本混淆名不同。 */
    private val slowQueryMethodCache = ConcurrentHashMap<Class<*>, Method>()

    override fun initOnce(): Boolean {
        val cls = try {
            Initiator.loadClass("com.tencent.imcore.message.MsgProxy")
        } catch (t: Throwable) {
            Log.e("$TAG: MsgProxy not found", t)
            return false
        }
        val method = cls.declaredMethods.firstOrNull {
            it.name == "a" &&
                it.parameterTypes.size == 4 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java &&
                it.parameterTypes[2] == Int::class.javaPrimitiveType &&
                it.parameterTypes[3] == String::class.java
        } ?: run {
            Log.e("$TAG: MsgProxy.a(String,String,int,String) not found")
            return false
        }
        method.isAccessible = true
        HookUtils.hookAfterIfEnabled(this, method) { param ->
            try {
                handleSearch(param)
            } catch (t: Throwable) {
                Log.e("$TAG: merge failed", t)
            }
        }
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleSearch(param: XC_MethodHook.MethodHookParam) {
        val keyword = param.args[0] as? String ?: return
        val peer = param.args[1] as? String ?: return
        val type = param.args[2] as? Int ?: return
        if (keyword.isBlank() || peer.isEmpty()) return

        val table = try {
            messageRecordTableNameMethod?.invoke(null, peer, type) as? String
        } catch (t: Throwable) {
            Log.e("$TAG: getTableName failed", t)
            null
        } ?: return
        if (!table.startsWith("mr_") || !table.endsWith("_New")) return

        val slow = searchSlowTable(table, keyword) ?: return
        if (slow.isEmpty()) return

        val data = param.result ?: chatHistorySearchDataClass.getDeclaredConstructor().newInstance()
        val searchData1 = searchData1Field ?: return
        val searchData2 = searchData2Field ?: return
        val main = searchData1.get(data) as? List<*> ?: emptyList<Any>()
        val merged = ArrayList<Any>(main.size + slow.size)
        for (record in main) {
            if (record != null) merged.add(record)
        }
        merged.addAll(slow)
        merged.sortWith(Comparator { a, b ->
            val timeField = messageRecordTimeField ?: return@Comparator 0
            timeField.getLong(b).compareTo(timeField.getLong(a))
        })

        val uniseqField = messageRecordUniseqField
        val seen = HashSet<Long>(merged.size)
        val deduped = ArrayList<Any>(merged.size)
        for (record in merged) {
            val key = uniseqField?.getLong(record)
                ?: ((messageRecordTimeField?.getLong(record) ?: 0L) shl 32 xor record.hashCode().toLong())
            if (seen.add(key)) deduped.add(record)
        }

        searchData1.set(data, deduped)
        if (searchData2.get(data) == null) {
            searchData2.set(data, ArrayList<Int>())
        }
        param.result = data
        Log.i("$TAG: injected ${slow.size} slowtable rows peer=$peer type=$type keyword=$keyword")
    }

    private fun searchSlowTable(table: String, keyword: String): List<Any>? {
        val kc = kcBytes ?: return null
        val sql = buildSlowSearchSql(table, keyword, kc) ?: return null
        val list = runSlowQuery(sql) ?: return null
        for (record in list) {
            decodeRecord(record, kc)
        }
        return list
    }

    private fun buildSlowSearchSql(table: String, keyword: String, kc: ByteArray): String? {
        if (kc.isEmpty()) return null
        val variants = LinkedHashSet<String>()
        for (kw in listOf(keyword, keyword.lowercase(Locale.US), keyword.uppercase(Locale.US))) {
            val bytes = kw.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty()) continue
            for (offset in kc.indices) {
                val encoded = ByteArray(bytes.size)
                for (i in bytes.indices) {
                    encoded[i] = (bytes[i].toInt() xor kc[(offset + i) % kc.size].toInt()).toByte()
                }
                variants.add(encoded.toHexString())
            }
        }
        if (variants.isEmpty()) return null
        val cond = variants.joinToString(" or ") { "instr(msgData, X'$it')>0" }
        return "select * from $table where msgtype=$MSG_TYPE_TEXT and ($cond) order by time desc limit $PAGE_SIZE"
    }

    private fun decodeRecord(record: Any, kc: ByteArray) {
        val msgData = messageRecordMsgDataField?.get(record) as? ByteArray ?: return
        val decoded = ByteArray(msgData.size)
        for (i in msgData.indices) {
            decoded[i] = (msgData[i].toInt() xor kc[i % kc.size].toInt()).toByte()
        }
        messageRecordMsgField?.set(record, String(decoded, Charsets.UTF_8))
    }

    private fun ByteArray.toHexString(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xff
            sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0f])
        }
        return sb.toString()
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

    private fun runSlowQuery(sql: String): List<Any>? {
        val manager = getSlowTableManager() ?: return null
        val method = findSlowQueryMethod(manager) ?: return null
        return try {
            @Suppress("UNCHECKED_CAST")
            method.invoke(manager, sql) as? List<Any>
        } catch (t: Throwable) {
            Log.e("$TAG: slowtable query failed", t)
            null
        }
    }
}
