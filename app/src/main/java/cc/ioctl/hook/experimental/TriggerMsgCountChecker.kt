/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2026 QAuxiliary developers
 * https://github.com/cinit/QAuxiliary
 *
 * This software is an opensource software: you can redistribute it
 * and/or modify it under the terms of the General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version.
 */

package cc.ioctl.hook.experimental

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import cc.ioctl.util.LayoutHelper
import io.github.qauxv.base.IEntityAgent
import io.github.qauxv.base.ISwitchCellAgent
import io.github.qauxv.base.IUiItemAgent
import io.github.qauxv.base.IUiItemAgentProvider
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.bridge.AppRuntimeHelper
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.ui.CommonContextWrapper
import io.github.qauxv.util.Log
import io.github.qauxv.util.Toasts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.concurrent.thread

/**
 * 手动触发 QQ MsgCountChecker 的等效迁移。
 *
 * 迁移阈值可通过 UI 指定；每张表超过阈值后，按 QQ 官方逻辑保留最新 5000 条，
 * 更早的消息移动到 slowtable。
 */
@UiItemAgentEntry
object TriggerMsgCountChecker : IUiItemAgent, IUiItemAgentProvider {

    private const val TAG = "TriggerMsgCountChecker"
    private const val DEFAULT_THRESHOLD = 6000
    private const val KEEP_COUNT = 5000

    override val titleProvider: (IEntityAgent) -> String = { "触发消息裁剪迁移" }
    override val summaryProvider: ((IEntityAgent, Context) -> String?)? = { _, _ ->
        "点击输入迁移阈值，默认 $DEFAULT_THRESHOLD，超过阈值的表保留 $KEEP_COUNT 条"
    }
    override val valueState: MutableStateFlow<String?>? = null
    override val validator: ((IUiItemAgent) -> Boolean)? = null
    override val switchProvider: ISwitchCellAgent? = null
    override val extraSearchKeywordProvider: ((IUiItemAgent, Context) -> Array<String>?)? = null
    override val uiItemAgent: IUiItemAgent = this
    override val uiItemLocation: Array<String> = FunctionEntryRouter.Locations.Auxiliary.EXPERIMENTAL_CATEGORY

    override val onClickListener: ((IUiItemAgent, Activity, View) -> Unit) = { _, activity, _ ->
        showThresholdDialog(activity)
    }

    private fun showThresholdDialog(activity: Activity) {
        val ctx = CommonContextWrapper.createAppCompatContext(activity)
        val input = EditText(ctx).apply {
            hint = "迁移阈值，默认 $DEFAULT_THRESHOLD"
            textSize = 16f
            setSingleLine()
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                input,
                LayoutHelper.newLinearLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            )
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("触发消息裁剪迁移")
            .setMessage("主库表条数超过阈值时，保留最新 $KEEP_COUNT 条，其余迁到 slowtable。")
            .setView(layout)
            .setPositiveButton("开始迁移", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val threshold = input.text.toString().trim().toIntOrNull()
            if (threshold == null || threshold <= KEEP_COUNT) {
                Toasts.error(ctx, "请输入大于 $KEEP_COUNT 的整数")
                return@setOnClickListener
            }
            dialog.dismiss()
            Toasts.info(ctx, "开始迁移，阈值 $threshold")
            thread(name = "TriggerMsgCountChecker") {
                try {
                    val moved = runMigration(threshold)
                    Log.i("$TAG: migration finished, moved=$moved")
                    activity.runOnUiThread {
                        Toasts.success(activity, "迁移完成，共移动 $moved 条")
                    }
                } catch (t: Throwable) {
                    Log.e("$TAG: migration failed", t)
                    activity.runOnUiThread {
                        Toasts.error(activity, "迁移失败: ${t.message}")
                    }
                }
            }
        }
    }

    private fun runMigration(threshold: Int): Int {
        val qq = AppRuntimeHelper.getAppRuntime() ?: error("AppRuntime is null")
        val db = invokeNoArg(qq, "getWritableDatabase") ?: error("getWritableDatabase failed")
        val uin = invokeNoArg(qq, "getCurrentAccountUin") as? String ?: error("getCurrentAccountUin failed")
        val app = invokeNoArg(qq, "getApplication") as? Application ?: error("getApplication failed")
        val slowPath = app.getDatabasePath("slowtable_$uin.db").absolutePath
        invokeMethod(db, "execSQL", arrayOf("ATTACH DATABASE ? AS slowdb", arrayOf(slowPath)))
        var movedTotal = 0
        try {
            val allTables = invokeNoArg(db, "getAllTableNameFromCache") as? Array<*> ?: emptyArray<Any?>()
            val tables = allTables
                .mapNotNull { it as? String }
                .filter { it.startsWith("mr_") && it.endsWith("_New") }
            Log.i("$TAG: tables=${tables.size} threshold=$threshold slowPath=$slowPath")
            Log.i("$TAG: firstTables=${tables.take(12)}")
            invokeNoArg(db, "beginTransaction")
            try {
                for (table in tables) {
                    val count = countRows(db, table)
                    if (count > 0) {
                        Log.i("$TAG: count table=$table count=$count threshold=$threshold")
                    }
                    if (count <= threshold) continue
                    val moveCount = (count - KEEP_COUNT).coerceAtLeast(1)
                    movedTotal += migrateTable(db, table, moveCount)
                }
                invokeNoArg(db, "setTransactionSuccessful")
            } finally {
                invokeNoArg(db, "endTransaction")
            }
        } finally {
            try {
                invokeMethod(db, "execSQL", arrayOf("DETACH DATABASE slowdb"))
            } catch (t: Throwable) {
                Log.e("$TAG: detach slowdb failed", t)
            }
        }
        return movedTotal
    }

    private fun migrateTable(db: Any, table: String, moveCount: Int): Int {
        val qt = quoteIdentifier(table)
        invokeMethod(db, "execSQL", arrayOf("CREATE TABLE IF NOT EXISTS slowdb.$qt AS SELECT * FROM main.$qt WHERE 0"))
        val orderColumn = when {
            table.startsWith("mr_friend_") -> "time"
            table.startsWith("mr_troop_") ||
                table.startsWith("mr_discusssion_") ||
                table.startsWith("mr_contact_") ||
                table.startsWith("mr_guild") -> "shmsgseq"
            else -> "_id"
        }
        val cols = ArrayList<String>()
        (invokeMethod(db, "rawQuery", arrayOf("PRAGMA main.table_info($qt)", null)) as android.database.Cursor).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)
                if (name != "_id") cols.add(name)
            }
        }
        val colSql = cols.joinToString(",") { quoteIdentifier(it) }
        val where = "_id in (select _id from main.$qt order by $orderColumn limit ?)"
        invokeMethod(
            db,
            "execSQL",
            arrayOf("INSERT OR IGNORE INTO slowdb.$qt ($colSql) SELECT $colSql FROM main.$qt WHERE $where", arrayOf(moveCount))
        )
        invokeMethod(db, "execSQL", arrayOf("DELETE FROM main.$qt WHERE $where", arrayOf(moveCount)))
        Log.i("$TAG: $table moved=$moveCount countBefore=${moveCount + KEEP_COUNT}")
        return moveCount
    }

    private fun countRows(db: Any, table: String): Int {
        val qqCount = try {
            (invokeMethod(db, "getCount", arrayOf(table)) as? Number)?.toInt() ?: 0
        } catch (t: Throwable) {
            Log.e("$TAG: getCount failed table=$table", t)
            0
        }
        val rawCount = try {
            val qt = quoteIdentifier(table)
            @Suppress("UNCHECKED_CAST")
            val c = invokeMethod(db, "rawQuery", arrayOf("select count(*) from $qt", null)) as? android.database.Cursor
            c?.use { if (it.moveToNext()) it.getInt(0) else 0 } ?: 0
        } catch (t: Throwable) {
            Log.e("$TAG: raw count failed table=$table", t)
            0
        }
        if (qqCount != rawCount) {
            Log.i("$TAG: count mismatch table=$table getCount=$qqCount raw=$rawCount")
        }
        return maxOf(qqCount, rawCount)
    }

    private fun invokeMethod(obj: Any, name: String, args: Array<Any?>): Any? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            val method = cls.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.size == args.size
            }
            if (method != null) {
                method.isAccessible = true
                return method.invoke(obj, *args)
            }
            cls = cls.superclass
        }
        throw NoSuchMethodException(name)
    }

    private fun invokeNoArg(obj: Any, name: String): Any? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                val method = cls.getDeclaredMethod(name)
                method.isAccessible = true
                return method.invoke(obj)
            } catch (_: NoSuchMethodException) {
                cls = cls.superclass
            }
        }
        throw NoSuchMethodException(name)
    }

    private fun quoteIdentifier(name: String): String {
        return "\"" + name.replace("\"", "\"\"") + "\""
    }
}
