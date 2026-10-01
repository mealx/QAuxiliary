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
 * and our eula along with this software.  If not, see
 * <https://www.gnu.org/licenses/>
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */

package cc.ioctl.hook.chat

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import cc.hicore.QApp.QAppUtils
import cc.ioctl.util.HookUtils
import cc.ioctl.util.HostInfo
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import io.github.qauxv.util.SyncUtils
import io.github.qauxv.util.hostInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * QQ 8.8.90 的群漫游回填（MessageSvc.PbGetGroupMsg）会把旧消息重新插进主库
 * `mr_*_New`，即使这些消息属于 slowtable 的时间区间，导致主库 / 慢表交叉。
 *
 * 在 `com.tencent.mobileqq.app.SQLiteDatabase` 的 insert/replace 入口拦截：
 *
 *  1. 主库里已有 slowtable 同一条消息（msgUid + time + msgtype）→ 丢弃主库写入
 *  2. 该消息不在慢表，但 time <= slowtable.max(time) → 属于慢表区间：
 *     先写进 slowtable，再丢弃主库写入
 *  3. time=0 的 -2006 已删除/不可用 PTT 占位行 → 直接丢弃
 *  4. 其余正常放行
 *
 * 这样即使漫游拉到 slowtable 里没有的旧消息（guard 原来的盲区），也不会污染主库，
 * 也不会让 time=0 的占位行污染 slowtable 首条时间。
 * 默认关闭。路径：QAuxiliary → 辅助功能 → 聊天和消息。
 */
@FunctionHookEntry
@UiItemAgentEntry
object MainSlowWriteGuard : CommonSwitchFunctionHook(
    hookKey = "MainSlowWriteGuard",
    defaultEnabled = false,
    targetProc = SyncUtils.PROC_MAIN,
) {

    private const val TAG = "MainSlowWriteGuard"
    private val mainTableRe = Regex("^mr_(troop|friend|discusssion)_[0-9A-F]{32}_New$")
    private val blockedCount = AtomicLong(0)
    private val blockedInvalidCount = AtomicLong(0)
    private val redirectedCount = AtomicLong(0)
    private val tablePresent = ConcurrentHashMap<String, Boolean>()
    private val slowMaxTimeCache = ConcurrentHashMap<String, Long>()
    private val slowColumnsCache = ConcurrentHashMap<String, Set<String>>()

    @Volatile
    private var slowDb: SQLiteDatabase? = null

    @Volatile
    private var slowDbName: String? = null

    override val name = "主库重复消息写回守卫"
    override val description = "阻止 QQ 漫游把慢表区旧消息写回主库；慢表没有的旧消息转写进 slowtable；丢弃 time=0 的已删除 PTT 占位行（默认关闭）"
    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.CHAT_CATEGORY
    override val isAvailable: Boolean
        get() = HostInfo.isQQ()

    override fun initOnce(): Boolean {
        val cls = Initiator.loadClass("com.tencent.mobileqq.app.SQLiteDatabase")
        var installed = false
        for (method in cls.declaredMethods) {
            val p = method.parameterTypes
            val isReplace = method.name == "replace" && p.size == 3 &&
                p[0] == String::class.java && p[1] == String::class.java && p[2] == ContentValues::class.java
            val isInsert = method.name == "insert" && p.size == 3 &&
                p[0] == String::class.java && p[1] == String::class.java && p[2] == ContentValues::class.java
            if (!isReplace && !isInsert) continue
            method.isAccessible = true
            HookUtils.hookBeforeIfEnabled(this, method) { param ->
                try {
                    if (skipMainWrite(param.args[0] as? String, param.args[2] as? ContentValues)) {
                        param.result = 0L
                    }
                } catch (t: Throwable) {
                    Log.e("$TAG: guard failed", t)
                }
            }
            installed = true
        }
        if (installed) {
            Log.i("$TAG: installed")
        } else {
            Log.e("$TAG: SQLiteDatabase insert/replace not found")
        }
        return installed
    }

    /** 返回 true 表示跳过主库写入。 */
    private fun skipMainWrite(table: String?, values: ContentValues?): Boolean {
        if (table == null || values == null) return false
        if (!mainTableRe.matches(table)) return false
        if (!values.containsKey("time") || !values.containsKey("msgtype")) return false
        val uid = values.getAsLong("msgUid") ?: 0L
        val time = values.getAsLong("time") ?: return false
        val msgType = values.getAsInteger("msgtype") ?: return false

        // 0) QQ 漫游对已删除/不可用 PTT 会生成 time=0 的 -2006 占位行。
        //    这种行本身不显示，但会被 QSlowTableManager.d() 的 order by time asc 选中，
        //    把 slowtable 首条时间污染成 0，导致日期页退化成只查主库。直接丢弃。
        if (time <= 0L && msgType == -2006) {
            val n = blockedInvalidCount.incrementAndGet()
            Log.i(
                "$TAG: blocked invalid placeholder #$n table=$table seq=${values.getAsLong("shmsgseq")} " +
                    "uid=$uid time=$time type=$msgType"
            )
            return true
        }

        // 1) 慢表已有同一条消息 → 丢弃主库写入
        if (uid != 0L && existsInSlow(table, uid, time, msgType)) {
            val n = blockedCount.incrementAndGet()
            Log.i(
                "$TAG: blocked dup #$n table=$table seq=${values.getAsLong("shmsgseq")} " +
                    "uid=$uid time=$time(${formatTime(time)}) type=$msgType"
            )
            return true
        }

        // 2) 慢表没有，但时间落在慢表区间 → 转写进慢表，再丢弃主库写入
        if (time > 0) {
            val slowMax = slowMaxTime(table)
            if (slowMax != null && time <= slowMax) {
                val ok = insertIntoSlow(table, values)
                val n = redirectedCount.incrementAndGet()
                Log.i(
                    "$TAG: redirected old msg #$n table=$table seq=${values.getAsLong("shmsgseq")} " +
                        "uid=$uid time=$time(${formatTime(time)}) type=$msgType slowMax=$slowMax insert=$ok"
                )
                return true
            }
        }
        return false
    }

    private fun slowMaxTime(table: String): Long? {
        if (slowMaxTimeCache.containsKey(table)) {
            val cached = slowMaxTimeCache[table] ?: return null
            if (cached > 0) return cached
        }
        val db = openSlowDb() ?: return null
        if (!tableExists(db, table)) return null
        val max = try {
            db.rawQuery("SELECT MAX(time) FROM \"$table\"", null).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            }
        } catch (t: Throwable) {
            Log.e("$TAG: slow max(time) failed table=$table", t)
            -1L
        }
        slowMaxTimeCache[table] = max
        return if (max > 0) max else null
    }

    private fun insertIntoSlow(table: String, values: ContentValues): Boolean {
        val db = openSlowDb() ?: return false
        val cols = slowColumns(table) ?: return false
        val cv = ContentValues()
        cv.putAll(values)
        cv.remove("_id")
        for (key in cv.keySet().toList()) {
            if (key !in cols) cv.remove(key)
        }
        return try {
            db.insertWithOnConflict(table, null, cv, SQLiteDatabase.CONFLICT_IGNORE) >= 0
        } catch (t: Throwable) {
            Log.e("$TAG: insert into slow failed table=$table", t)
            false
        }
    }

    private fun existsInSlow(table: String, uid: Long, time: Long, msgType: Int): Boolean {
        val db = openSlowDb() ?: return false
        if (!tableExists(db, table)) return false
        return try {
            db.rawQuery(
                "SELECT 1 FROM \"$table\" WHERE time=? AND msgtype=? AND msgUid=? LIMIT 1",
                arrayOf(time.toString(), msgType.toString(), uid.toString())
            ).use { cursor -> cursor.moveToFirst() }
        } catch (t: Throwable) {
            Log.e("$TAG: slow query failed table=$table", t)
            false
        }
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean {
        tablePresent[table]?.let { return it }
        val exists = try {
            db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                arrayOf(table)
            ).use { cursor -> cursor.moveToFirst() }
        } catch (t: Throwable) {
            Log.e("$TAG: slow sqlite_master query failed", t)
            return false
        }
        tablePresent[table] = exists
        return exists
    }

    private fun slowColumns(table: String): Set<String>? {
        slowColumnsCache[table]?.let { return it.ifEmpty { null } }
        val db = openSlowDb() ?: return null
        if (!tableExists(db, table)) {
            slowColumnsCache[table] = emptySet()
            return null
        }
        val cols = HashSet<String>()
        try {
            db.rawQuery("PRAGMA table_info(\"$table\")", null).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1)
                    if (!name.isNullOrEmpty()) cols.add(name)
                }
            }
        } catch (t: Throwable) {
            Log.e("$TAG: slow PRAGMA failed table=$table", t)
        }
        slowColumnsCache[table] = cols
        return cols.ifEmpty { null }
    }

    private fun formatTime(epochSec: Long): String {
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(epochSec * 1000L))
        } catch (t: Throwable) {
            epochSec.toString()
        }
    }

    @Synchronized
    private fun openSlowDb(): SQLiteDatabase? {
        val uin = try {
            QAppUtils.getCurrentUin()
        } catch (t: Throwable) {
            null
        } ?: return null
        if (uin.isEmpty() || uin == "0") return null
        val name = "slowtable_$uin.db"
        slowDb?.let { if (slowDbName == name) return it }
        return try {
            val path = hostInfo.application.getDatabasePath(name).absolutePath
            // 读 + 写：需要把慢表没有的旧消息转写进慢表
            val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE)
            slowDb = db
            slowDbName = name
            Log.i("$TAG: opened slow db $path")
            db
        } catch (t: Throwable) {
            Log.e("$TAG: open slow db failed name=$name", t)
            null
        }
    }
}
