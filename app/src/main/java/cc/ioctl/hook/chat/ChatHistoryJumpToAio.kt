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

import android.app.Activity
import android.os.SystemClock
import cc.hicore.QApp.QAppUtils
import cc.ioctl.util.ChatHistoryLocator
import cc.ioctl.util.HookUtils
import cc.ioctl.util.hookBeforeIfEnabled
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
 * 把 8890 的“聊天记录里点一条消息”/“媒体查看器定位”从
 * `ChatHistoryActivity`（只打开聊天记录列表）改成直接跳回真实聊天窗口并定位。
 *
 * 9110 的等价行为由 `IMsgLocationApi.jumpToTargetNTAIOPosition` 提供；
 * 8890 侧统一走 [ChatHistoryLocator.jumpToTargetAIOPosition]。
 *
 * 被重定向的入口：
 * - `ChatHistoryActivity.a(Activity, String, int, String, long, long, int)`
 *   好友/群「按日期」或搜索里点消息条目时调用。
 * - `ChatHistoryBubbleListForTroopFragment.a(Activity, String, MessageRecord, int, int)`
 *   群聊搜索/按成员查看里点消息条目时调用。
 * - `ChatHistoryBubbleListForTroopFragment.a(Activity, String, long, int, int)`
 *   同上，拿不到 MessageRecord 时只有 shmsgseq。
 * - `GalleryJumpUtils.a(Activity, int, String, String, long, long, int, boolean)`
 *   媒体查看器「定位到聊天」/返回时调用。
 * - `AIOGalleryScene.a(Activity, int, String, String, long, long, int, boolean)`
 *   旧版媒体查看器路径，逻辑与 GalleryJumpUtils 相同。
 */
@FunctionHookEntry
@UiItemAgentEntry
object ChatHistoryJumpToAio : CommonSwitchFunctionHook(
    hookKey = "ChatHistoryJumpToAio",
    defaultEnabled = false,
    targetProc = SyncUtils.PROC_MAIN or SyncUtils.PROC_PEAK,
) {

    private const val TAG = "ChatHistoryJumpToAio"

    override val name = "聊天记录直接定位到聊天"
    override val description = "点击聊天记录条目或在媒体查看器里定位时，直接跳回真实聊天窗口，而不是打开聊天记录列表"
    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.CHAT_CATEGORY
    override val isAvailable: Boolean
        get() = HostInfo.isQQ() && !QAppUtils.isQQnt()

    private val messageRecordTimeField: Field? by lazy {
        try {
            Initiator.loadClass("com.tencent.mobileqq.data.MessageRecord").getField("time")
        } catch (t: Throwable) {
            Log.e("$TAG: MessageRecord.time not found", t)
            null
        }
    }

    private val messageRecordShmsgseqField: Field? by lazy {
        try {
            Initiator.loadClass("com.tencent.mobileqq.data.MessageRecord").getField("shmsgseq")
        } catch (t: Throwable) {
            Log.e("$TAG: MessageRecord.shmsgseq not found", t)
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

    private val messageRecordIdMethod: Method? by lazy {
        try {
            Initiator.loadClass("com.tencent.mobileqq.data.MessageRecord").getMethod("getId")
        } catch (t: Throwable) {
            Log.e("$TAG: MessageRecord.getId not found", t)
            null
        }
    }

    /** jump fallback 固定用 Integer.MAX_VALUE 作为 endSeq / endTime。 */
    private const val JUMP_END_SEQ = 2147483647L

    private const val FRIEND_TABLE_PREFIX = "mr_friend_"

    /** slowtable 上下文窗口单侧条数；到边界后自动继续加载。 */
    private const val SLOW_PAGE_SIZE = 20

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

    private val slowTableManagerId: Int by lazy {
        try {
            Initiator.loadClass("com.tencent.mobileqq.app.QQManagerFactory")
                .getField("SLOW_TABLE_MANAGER").getInt(null)
        } catch (t: Throwable) {
            Log.e("$TAG: SLOW_TABLE_MANAGER not found", t)
            -1
        }
    }

    private val slowQueryMethodCache = ConcurrentHashMap<Class<*>, Method>()

    /** 记录哪些会话是“从慢表跳转过来的”，只对这些会话启用边界自动加载。 */
    private val slowTableJumpSessions = ConcurrentHashMap.newKeySet<String>()
    private val lastAutoLoadTime = ConcurrentHashMap<String, Long>()
    private val slowTableTailTime = ConcurrentHashMap<String, Long>()
    private val slowTableTailId = ConcurrentHashMap<String, Long>()
    private val chatPieByListView = ConcurrentHashMap<Int, Any>()

    override fun initOnce(): Boolean {
        var ok = false

        // 好友/群「按日期」、消息搜索：ChatHistoryActivity.a(..., time, shmsgseq, ...)
        try {
            val clazz = Initiator.loadClass("com.tencent.mobileqq.activity.history.ChatHistoryActivity")
            val method = clazz.getDeclaredMethod(
                "a",
                Activity::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            hookBeforeIfEnabled(method) { param ->
                if (ChatHistoryLocator.bypassGalleryHook.get()) return@hookBeforeIfEnabled
                val args = param.args
                ChatHistoryLocator.jumpToTargetAIOPosition(
                    activity = args[0] as Activity,
                    uin = args[1] as String,
                    uinType = args[2] as Int,
                    troopUin = args[3] as? String,
                    time = args[4] as Long,
                    shmsgseq = args[5] as Long,
                    chatType = args[6] as Int,
                    keepCallerInBackStack = ChatHistoryJumpBackStack.isEnabled,
                )
                param.result = null
            }
            ok = true
        } catch (t: Throwable) {
            Log.e("$TAG: ChatHistoryActivity.a(7) not found", t)
        }

        // 群聊搜索/按成员查看：ChatHistoryBubbleListForTroopFragment.a(..., MessageRecord, ...)
        try {
            val clazz = Initiator.loadClass("com.tencent.mobileqq.activity.chathistory.ChatHistoryBubbleListForTroopFragment")
            val messageRecordClass = Initiator.loadClass("com.tencent.mobileqq.data.MessageRecord")
            val method = clazz.getDeclaredMethod(
                "a",
                Activity::class.java,
                String::class.java,
                messageRecordClass,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            hookBeforeIfEnabled(method) { param ->
                if (ChatHistoryLocator.bypassGalleryHook.get()) return@hookBeforeIfEnabled
                val args = param.args
                val troopUin = args[1] as? String ?: return@hookBeforeIfEnabled
                val record = args[2]
                val time = (messageRecordTimeField?.get(record) as? Long) ?: 0L
                val shmsgseq = (messageRecordShmsgseqField?.get(record) as? Long) ?: 0L
                ChatHistoryLocator.jumpToTargetAIOPosition(
                    activity = args[0] as Activity,
                    uinType = 1,
                    uin = troopUin,
                    troopUin = troopUin,
                    time = time,
                    shmsgseq = shmsgseq,
                    chatType = 1,
                    keepCallerInBackStack = ChatHistoryJumpBackStack.isEnabled,
                )
                param.result = null
            }
            ok = true
        } catch (t: Throwable) {
            Log.e("$TAG: ChatHistoryBubbleListForTroopFragment.a(MessageRecord) not found", t)
        }

        // 同上，只有 shmsgseq 的入口
        try {
            val clazz = Initiator.loadClass("com.tencent.mobileqq.activity.chathistory.ChatHistoryBubbleListForTroopFragment")
            val method = clazz.getDeclaredMethod(
                "a",
                Activity::class.java,
                String::class.java,
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            hookBeforeIfEnabled(method) { param ->
                if (ChatHistoryLocator.bypassGalleryHook.get()) return@hookBeforeIfEnabled
                val args = param.args
                val troopUin = args[1] as? String ?: return@hookBeforeIfEnabled
                ChatHistoryLocator.jumpToTargetAIOPosition(
                    activity = args[0] as Activity,
                    uinType = 1,
                    uin = troopUin,
                    troopUin = troopUin,
                    time = 0L,
                    shmsgseq = args[2] as Long,
                    chatType = 1,
                    keepCallerInBackStack = ChatHistoryJumpBackStack.isEnabled,
                )
                param.result = null
            }
            ok = true
        } catch (t: Throwable) {
            Log.e("$TAG: ChatHistoryBubbleListForTroopFragment.a(long) not found", t)
        }

        // 媒体查看器「定位到聊天」/返回：GalleryJumpUtils.a(...)
        try {
            val clazz = Initiator.loadClass("com.tencent.mobileqq.richmediabrowser.utils.GalleryJumpUtils")
            val method = clazz.getDeclaredMethod(
                "a",
                Activity::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
            hookBeforeIfEnabled(method) { param ->
                if (ChatHistoryLocator.bypassGalleryHook.get()) return@hookBeforeIfEnabled
                val args = param.args
                val activity = args[0] as Activity
                val keepBackStack = ChatHistoryJumpBackStack.isEnabled
                ChatHistoryLocator.jumpToTargetAIOPosition(
                    activity = activity,
                    uinType = args[1] as Int,
                    uin = args[2] as String,
                    troopUin = args[3] as? String,
                    time = args[4] as Long,
                    shmsgseq = args[5] as Long,
                    chatType = args[6] as Int,
                    isEmotion = args[7] as Boolean,
                    keepCallerInBackStack = keepBackStack,
                )
                if (keepBackStack) {
                    // 查看器夹在聊天记录页和 AIO 之间；finish 掉它，返回键才能直接回历史记录页
                    activity.finish()
                }
                param.result = null
            }
            ok = true
        } catch (t: Throwable) {
            Log.e("$TAG: GalleryJumpUtils.a not found", t)
        }

        // 旧版媒体查看器：AIOGalleryScene.a(...)
        try {
            val clazz = Initiator.loadClass("com.tencent.mobileqq.activity.aio.photo.AIOGalleryScene")
            val method = clazz.getDeclaredMethod(
                "a",
                Activity::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
            hookBeforeIfEnabled(method) { param ->
                if (ChatHistoryLocator.bypassGalleryHook.get()) return@hookBeforeIfEnabled
                val args = param.args
                val activity = args[0] as Activity
                val keepBackStack = ChatHistoryJumpBackStack.isEnabled
                ChatHistoryLocator.jumpToTargetAIOPosition(
                    activity = activity,
                    uinType = args[1] as Int,
                    uin = args[2] as String,
                    troopUin = args[3] as? String,
                    time = args[4] as Long,
                    shmsgseq = args[5] as Long,
                    chatType = args[6] as Int,
                    isEmotion = args[7] as Boolean,
                    keepCallerInBackStack = keepBackStack,
                )
                if (keepBackStack) {
                    // 查看器夹在聊天记录页和 AIO 之间；finish 掉它，返回键才能直接回历史记录页
                    activity.finish()
                }
                param.result = null
            }
            ok = true
        } catch (t: Throwable) {
            Log.e("$TAG: AIOGalleryScene.a not found", t)
        }

        installSlowTableMerge()
        installBoundaryAutoLoadHook()
        return ok
    }

    /**
     * 8890 的 AIO 定位 fallback（`Scroller.c()` 找不到目标时调用的 getAIOMsgList）
     * 只查主库 `mr_*_New`，而绝大多数历史消息已经被迁移到 slowtable。目标消息只在
     * slowtable 时，主库窗口里没有 `shmsgseq == 目标 seq`，Scroller 会滚到底部（最新消息）。
     *
     * 这里 hook `MsgProxy.e(String, int, long, long)`（queryMessageFromSeqRange）：
     * 群 / 讨论组 / 话题的 jump fallback 固定以 `[seq, Integer.MAX_VALUE]` 调它；如果主库
     * 结果里没有目标 seq，就用 slowtable 中目标前/后各一页替换原最新窗口，避免
     * “历史几条 + 最新一堆”的断层列表。
     *
     * 私聊 / C2C 不走这里，而是在 `MsgProxy.a(String,int,long,long,int)` 里按
     * `time` 合并 `mr_friend_*_New`。
     *
     * 注意：慢表查询绝不能写成 `[seq, MAX]`——slowtable 动辄上百万条，会直接卡死。
     */
    private fun installSlowTableMerge() {
        try {
            val cls = Initiator.loadClass("com.tencent.imcore.message.MsgProxy")
            val method = cls.declaredMethods.firstOrNull {
                it.name == "e" &&
                    it.parameterTypes.size == 4 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[2] == Long::class.javaPrimitiveType &&
                    it.parameterTypes[3] == Long::class.javaPrimitiveType &&
                    List::class.java.isAssignableFrom(it.returnType)
            } ?: run {
                Log.e("$TAG: MsgProxy.e(String,int,long,long) not found")
                return
            }
            method.isAccessible = true
            HookUtils.hookAfterIfEnabled(this, method) { param -> mergeSlowTable(param) }
        } catch (t: Throwable) {
            // PEAK 进程等场景没有 MsgProxy，忽略即可，不影响主功能。
            Log.e("$TAG: slowtable seq merge hook not installed", t)
        }

        try {
            val cls = Initiator.loadClass("com.tencent.imcore.message.MsgProxy")
            val method = cls.declaredMethods.firstOrNull {
                it.name == "a" &&
                    it.parameterTypes.size == 5 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[2] == Long::class.javaPrimitiveType &&
                    it.parameterTypes[3] == Long::class.javaPrimitiveType &&
                    it.parameterTypes[4] == Int::class.javaPrimitiveType &&
                    List::class.java.isAssignableFrom(it.returnType)
            } ?: run {
                Log.e("$TAG: MsgProxy.a(String,int,long,long,int) not found")
                return
            }
            method.isAccessible = true
            HookUtils.hookAfterIfEnabled(this, method) { param -> mergeFriendSlowTable(param) }
        } catch (t: Throwable) {
            Log.e("$TAG: slowtable time merge hook not installed", t)
        }

        installPrivateSlowTableHistoryHooks()
    }

    /**
     * 私聊 AIO 上滑继续加载时，QQ 原逻辑只按 uniseq/_id 查主库。跳转到慢表消息后，
     * 顶部消息不在主库，继续上滑会直接停住。这里补两个点：
     * - `MsgProxy.b(String,int,long)`：按 uniseq 从 slowtable 找回锚点消息；
     * - `MsgProxy.a(String,int,long,int,long,int,String)`：继续向更早时间取 slowtable。
     */
    private fun installPrivateSlowTableHistoryHooks() {
        try {
            val cls = Initiator.loadClass("com.tencent.imcore.message.MsgProxy")
            val byUniseq = cls.declaredMethods.firstOrNull {
                it.name == "b" &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[2] == Long::class.javaPrimitiveType
            } ?: run {
                Log.e("$TAG: MsgProxy.b(String,int,long) not found")
                return
            }
            byUniseq.isAccessible = true
            HookUtils.hookAfterIfEnabled(this, byUniseq) { param -> mergeFriendRecordByUniseq(param) }

            val older = cls.declaredMethods.firstOrNull {
                it.name == "a" &&
                    it.parameterTypes.size == 7 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[2] == Long::class.javaPrimitiveType &&
                    it.parameterTypes[3] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[4] == Long::class.javaPrimitiveType &&
                    it.parameterTypes[5] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[6] == String::class.java &&
                    List::class.java.isAssignableFrom(it.returnType)
            } ?: run {
                Log.e("$TAG: MsgProxy.a(String,int,long,int,long,int,String) not found")
                return
            }
            older.isAccessible = true
            HookUtils.hookAfterIfEnabled(this, older) { param -> mergeFriendOlderHistory(param) }
        } catch (t: Throwable) {
            Log.e("$TAG: private slowtable history hooks not installed", t)
        }
    }

    private fun mergeFriendRecordByUniseq(param: XC_MethodHook.MethodHookParam) {
        if (param.result != null) return
        val peer = param.args[0] as? String ?: return
        val type = param.args[1] as? Int ?: return
        val uniseq = param.args[2] as? Long ?: return
        if (uniseq == 0L) return
        val table = getTableName(peer, type) ?: return
        if (!table.startsWith(FRIEND_TABLE_PREFIX)) return
        val list = runSlowQuery("select * from $table where uniseq=$uniseq limit 1") ?: return
        param.result = list.firstOrNull()
        if (param.result != null) {
            Log.i("$TAG: found slowtable uniseq=$uniseq peer=$peer type=$type")
        }
    }

    private fun mergeFriendOlderHistory(param: XC_MethodHook.MethodHookParam) {
        val peer = param.args[0] as? String ?: return
        val type = param.args[1] as? Int ?: return
        val id = param.args[2] as? Long ?: return
        val fromTime = param.args[4] as? Long ?: return
        val count = param.args[5] as? Int ?: return
        if (fromTime <= 0L || count <= 0) return
        val table = getTableName(peer, type) ?: return
        if (!table.startsWith(FRIEND_TABLE_PREFIX)) return

        val where = if (id > 0L) {
            "(time < $fromTime or (time = $fromTime and _id < $id))"
        } else {
            "time < $fromTime"
        }
        val olderDesc = runSlowQuery(
            "select * from $table where $where order by time desc, _id desc limit $count"
        ) ?: return
        if (olderDesc.isEmpty()) return

        val main = param.result as? List<*> ?: emptyList<Any>()
        val merged = ArrayList<Any>(main.size + olderDesc.size)
        for (record in olderDesc.asReversed()) {
            if (record != null) merged.add(record)
        }
        for (record in main) {
            if (record != null) merged.add(record)
        }
        param.result = dedupeAndSort(merged) { it -> messageRecordTimeField?.getLong(it) ?: 0L }
        Log.i("$TAG: merged ${olderDesc.size} older slowtable rows peer=$peer type=$type from=$fromTime id=$id")
    }

    private fun getTableName(peer: String, type: Int): String? {
        return try {
            messageRecordTableNameMethod?.invoke(null, peer, type) as? String
        } catch (t: Throwable) {
            Log.e("$TAG: getTableName failed", t)
            null
        }
    }

    /**
     * 私聊 / C2C 定位走 `MsgProxy.a(String,int,long,long,int)`，按 `time` 查主库。
     *
     * 目标只在 slowtable 时，不能把目标几条插到主库最新窗口前面：那样列表会变成
     * “历史几条 + 最新一堆”，中间断层，划一下看到的还是最新消息。
     * 这里直接替换为主库之外的 slowtable 上下文窗口：目标前后各一页，后续上滑再继续从 slowtable 分页。
     */
    private fun mergeFriendSlowTable(param: XC_MethodHook.MethodHookParam) {
        val peer = param.args[0] as? String ?: return
        val type = param.args[1] as? Int ?: return
        val beginTime = param.args[2] as? Long ?: return
        val endTime = param.args[3] as? Long ?: return
        if (endTime != JUMP_END_SEQ || beginTime <= 0L) return

        val table = try {
            messageRecordTableNameMethod?.invoke(null, peer, type) as? String
        } catch (t: Throwable) {
            Log.e("$TAG: getTableName failed", t)
            null
        } ?: return
        if (!table.startsWith(FRIEND_TABLE_PREFIX)) return

        val timeField = messageRecordTimeField ?: return
        val main = param.result as? List<*> ?: return
        if (main.any { it != null && timeField.getLong(it) == beginTime }) return

        val before = runSlowQuery(
            "select * from $table where time <= $beginTime " +
                "order by time desc, _id desc limit $SLOW_PAGE_SIZE"
        ) ?: emptyList()
        val after = runSlowQuery(
            "select * from $table where time > $beginTime " +
                "order by time asc, _id asc limit $SLOW_PAGE_SIZE"
        ) ?: emptyList()
        val context = dedupeAndSort(before + after) { it ->
            timeField.getLong(it)
        }
        if (context.isEmpty() || context.none { timeField.getLong(it) == beginTime }) return

        param.result = context
        markSlowTableJump(peer, type, context)
        Log.i("$TAG: replaced with ${context.size} slowtable friend context rows peer=$peer type=$type time=$beginTime")
    }

    private fun mergeSlowTable(param: XC_MethodHook.MethodHookParam) {
        val peer = param.args[0] as? String ?: return
        val type = param.args[1] as? Int ?: return
        val beginSeq = param.args[2] as? Long ?: return
        val endSeq = param.args[3] as? Long ?: return
        // 只处理 jump fallback 的无界前向窗口，避免影响正常分页查询。
        if (endSeq < JUMP_END_SEQ || beginSeq <= 0L) return
        // 群 / 讨论组 / 话题才按 shmsgseq 定位（与 UinTypeUtil.h 一致）；好友走 time。
        if (type != 1 && type != 3000 && type != 1026) return

        val seqField = messageRecordShmsgseqField ?: return
        val main = param.result as? List<*> ?: return
        if (main.any { it != null && seqField.getLong(it) == beginSeq }) return

        val table = try {
            messageRecordTableNameMethod?.invoke(null, peer, type) as? String
        } catch (t: Throwable) {
            Log.e("$TAG: getTableName failed", t)
            null
        } ?: return

        val before = runSlowQuery(
            "select * from $table where shmsgseq <= $beginSeq " +
                "order by shmsgseq desc limit $SLOW_PAGE_SIZE"
        ) ?: emptyList()
        val after = runSlowQuery(
            "select * from $table where shmsgseq > $beginSeq " +
                "order by shmsgseq asc limit $SLOW_PAGE_SIZE"
        ) ?: emptyList()
        val context = dedupeAndSort(before + after) { it ->
            seqField.getLong(it)
        }
        if (context.isEmpty() || context.none { seqField.getLong(it) == beginSeq }) return

        param.result = context
        markSlowTableJump(peer, type, context)
        Log.i("$TAG: replaced with ${context.size} slowtable context rows peer=$peer type=$type seq=$beginSeq")
    }

    private fun dedupeAndSort(records: List<Any>, sortKey: (Any) -> Long): List<Any> {
        if (records.isEmpty()) return emptyList()
        val uniseqField = messageRecordUniseqField
        val seen = HashSet<Long>(records.size)
        val deduped = ArrayList<Any>(records.size)
        for (record in records) {
            val key = uniseqField?.getLong(record) ?: record.hashCode().toLong()
            if (seen.add(key)) deduped.add(record)
        }
        deduped.sortWith(Comparator { a, b -> sortKey(a).compareTo(sortKey(b)) })
        return deduped
    }

    private fun markSlowTableJump(peer: String, type: Int, context: List<Any>) {
        val key = sessionKey(peer, type)
        slowTableJumpSessions.add(key)
        updateSlowTableTail(key, context)
    }

    private fun updateSlowTableTail(key: String, records: List<Any>) {
        val timeField = messageRecordTimeField ?: return
        var maxTime = slowTableTailTime[key] ?: Long.MIN_VALUE
        var maxId = slowTableTailId[key] ?: Long.MIN_VALUE
        for (record in records) {
            val t = timeField.getLong(record)
            val id = messageRecordIdMethod?.invoke(record) as? Long ?: 0L
            if (t > maxTime || (t == maxTime && id > maxId)) {
                maxTime = t
                maxId = id
            }
        }
        if (maxTime != Long.MIN_VALUE) {
            slowTableTailTime[key] = maxTime
            slowTableTailId[key] = maxId
        }
    }

    private fun sessionKey(peer: String, type: Int): String = "$type:$peer"

    private fun installBoundaryAutoLoadHook() {
        var installed = false
        try {
            val cls = Initiator.loadClass("com.tencent.mobileqq.bubble.ChatXListView")
            val setChatPie = cls.declaredMethods.firstOrNull {
                it.name == "setChatPie" &&
                    it.parameterTypes.size == 1 &&
                    Initiator.loadClass("com.tencent.mobileqq.activity.aio.core.BaseChatPie")
                        .isAssignableFrom(it.parameterTypes[0])
            }
            if (setChatPie != null) {
                setChatPie.isAccessible = true
                HookUtils.hookAfterIfEnabled(this, setChatPie) { param ->
                    val pie = param.args.getOrNull(0) ?: return@hookAfterIfEnabled
                    chatPieByListView[System.identityHashCode(param.thisObject)] = pie
                }
                installed = true
            } else {
                Log.e("$TAG: ChatXListView.setChatPie not found")
            }
        } catch (t: Throwable) {
            Log.e("$TAG: ChatXListView owner hook not installed", t)
        }

        try {
            val cls = Initiator.loadClass("com.tencent.widget.AbsListView")
            val track = cls.declaredMethods.firstOrNull {
                it.name == "trackMotionScroll" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType
            }
            if (track != null) {
                track.isAccessible = true
                HookUtils.hookAfterIfEnabled(this, track) { param ->
                    autoLoadFromList(param.thisObject)
                }
                installed = true
            } else {
                Log.e("$TAG: AbsListView.trackMotionScroll not found")
            }
        } catch (t: Throwable) {
            Log.e("$TAG: list trackMotionScroll hook not installed", t)
        }

        if (!installed) {
            Log.e("$TAG: boundary auto load hook not installed")
        }
    }

    private fun autoLoadFromList(listView: Any) {
        val firstVisible = invokeNoArg(listView, "getFirstVisiblePosition") as? Int ?: return
        val visibleCount = invokeNoArg(listView, "getChildCount") as? Int ?: return
        val totalCount = invokeNoArg(listView, "getCount") as? Int ?: return
        val atTop = firstVisible <= 2
        val atBottom = firstVisible + visibleCount >= totalCount - 2
        if (!atTop && !atBottom) return

        val pie = findChatPie(listView) ?: return
        val session = getFieldValue(pie, "ai") ?: return
        val peer = getFieldValue(session, "b") as? String ?: return
        val type = (getFieldValue(session, "a") ?: getFieldValue(session, "f50777a")) as? Int ?: return
        val key = sessionKey(peer, type)
        if (!slowTableJumpSessions.contains(key)) return

        val now = SystemClock.uptimeMillis()
        val throttleKey = "$key:${if (atTop) "top" else "bottom"}"
        val last = lastAutoLoadTime[throttleKey] ?: 0L
        if (now - last < 800L) return
        lastAutoLoadTime[throttleKey] = now

        val table = getTableName(peer, type) ?: return
        val msgList = getFieldValue(pie, "k") ?: return
        val listUi = invokeNoArg(msgList, "b") ?: return
        val adapter = getFieldValue(listUi, "g") ?: return
        val current = invokeNoArg(adapter, "a") as? List<*> ?: return
        if (current.isEmpty()) return
        val timeField = messageRecordTimeField ?: return
        val anchor = if (atTop) current.firstOrNull() else null
        val anchorTime: Long
        val anchorId: Long
        if (atTop) {
            val a = anchor ?: return
            anchorTime = timeField.getLong(a)
            anchorId = messageRecordIdMethod?.invoke(a) as? Long ?: 0L
        } else {
            anchorTime = slowTableTailTime[key] ?: timeField.getLong(current.last())
            anchorId = slowTableTailId[key] ?: (messageRecordIdMethod?.invoke(current.last()) as? Long ?: 0L)
        }

        val where = if (atTop) {
            if (anchorId > 0L) {
                "(time < $anchorTime or (time = $anchorTime and _id < $anchorId))"
            } else {
                "time < $anchorTime"
            }
        } else {
            if (anchorId > 0L) {
                "(time > $anchorTime or (time = $anchorTime and _id > $anchorId))"
            } else {
                "time > $anchorTime"
            }
        }
        val order = if (atTop) "time desc, _id desc" else "time asc, _id asc"
        val page = runSlowQuery(
            "select * from $table where $where order by $order limit $SLOW_PAGE_SIZE"
        ) ?: return
        if (page.isEmpty()) return

        val merged = ArrayList<Any>(current.size + page.size)
        if (atTop) {
            for (record in page.asReversed()) if (record != null) merged.add(record)
            for (record in current) if (record != null) merged.add(record)
        } else {
            for (record in current) if (record != null) merged.add(record)
            for (record in page) if (record != null) merged.add(record)
        }
        val newList = dedupeAndSort(merged) { it -> timeField.getLong(it) }
        setAdapterList(adapter, newList)
        if (atTop) {
            setSelection(listView, firstVisible + page.size)
        } else {
            updateSlowTableTail(key, page)
        }
        Log.i("$TAG: direct ${if (atTop) "prepend" else "append"} ${page.size} slowtable rows peer=$peer type=$type")
    }

    private fun findChatPie(listView: Any): Any? {
        val mapped = chatPieByListView[System.identityHashCode(listView)]
        if (isBaseChatPie(mapped)) return mapped

        // ChatXListView 自己持有 BaseChatPie：private BaseChatPie b;
        val owner = getFieldValue(listView, "b")
        if (isBaseChatPie(owner)) return owner

        val direct = getFieldValue(listView, "mOnScrollListener")
        if (isBaseChatPie(direct)) return direct
        val all = getFieldValue(listView, "mOnScrollListenerList") as? Iterable<*>
        return all?.firstOrNull { isBaseChatPie(it) }
    }

    private fun isBaseChatPie(obj: Any?): Boolean {
        if (obj == null) return false
        return try {
            Initiator.loadClass("com.tencent.mobileqq.activity.aio.core.BaseChatPie").isInstance(obj)
        } catch (_: Throwable) {
            false
        }
    }

    private fun invokeNoArg(obj: Any, name: String): Any? {
        return try {
            obj.javaClass.methods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty()
            }?.invoke(obj)
        } catch (t: Throwable) {
            Log.e("$TAG: invoke $name failed", t)
            null
        }
    }

    private fun setAdapterList(adapter: Any, list: List<Any>) {
        try {
            adapter.javaClass.methods.firstOrNull {
                it.name == "a" &&
                    it.parameterTypes.size == 2 &&
                    List::class.java.isAssignableFrom(it.parameterTypes[0])
            }?.invoke(adapter, list, null)
        } catch (t: Throwable) {
            Log.e("$TAG: set adapter list failed", t)
        }
    }

    private fun setSelection(listView: Any, position: Int) {
        try {
            listView.javaClass.methods.firstOrNull {
                it.name == "setSelection" && it.parameterTypes.size == 1
            }?.invoke(listView, position)
        } catch (t: Throwable) {
            Log.e("$TAG: setSelection failed", t)
        }
    }

    private fun getFieldValue(obj: Any, name: String): Any? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                val field = cls.getDeclaredField(name)
                field.isAccessible = true
                return field.get(obj)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        return null
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
