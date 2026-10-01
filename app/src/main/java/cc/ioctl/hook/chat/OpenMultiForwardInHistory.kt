/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2026 QAuxiliary developers
 * https://github.com/cinit/QAuxiliary
 *
 * This software is non-free but opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either version 3 of the License,
 * or any later version and our eula as published by QAuxiliary contributors.
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

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import cc.ioctl.util.HookUtils
import cc.ioctl.util.HostInfo
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import io.github.qauxv.util.SyncUtils
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 聊天记录页里合并转发（“群聊的聊天记录 / 查看 N 条转发消息”）点不开。
 *
 * 这条消息是 `MessageForStructing`（msgtype -2011），struct 的 `mMsgAction == "viewMultiMsg"`。
 * 原生打开逻辑在 `StructMsgForGeneralShare.onClickEvent(...)`：
 *
 *   if ("viewMultiMsg".equals(struct.mMsgAction)) {
 *       if (((BaseActivity) context).getChatFragment() != null) { ... startActivity(MultiForwardActivity) }
 *   }
 *
 * 聊天记录页不是 AIO，getChatFragment() 为 null，于是什么都不做。
 *
 * 这里 hook `StructMsgForGeneralShare.onClickEvent`，在没有 ChatFragment 时用 struct 自己的
 * `mResid` 直接打开 MultiForwardActivity。AIO 路径不受影响。
 *
 * 默认关闭。路径：QAuxiliary → 辅助功能 → 聊天和消息。
 */
@FunctionHookEntry
@UiItemAgentEntry
object OpenMultiForwardInHistory : CommonSwitchFunctionHook(
    hookKey = "OpenMultiForwardInHistory",
    defaultEnabled = false,
    targetProc = SyncUtils.PROC_MAIN,
) {

    private const val TAG = "OpenMultiForwardInHistory"

    private val getChatFragmentMethod: Method? by lazy {
        try {
            Initiator.loadClass("com.tencent.mobileqq.app.BaseActivity").getMethod("getChatFragment")
        } catch (t: Throwable) {
            Log.e("$TAG: getChatFragment not found", t)
            null
        }
    }

    override val name = "聊天记录页打开合并转发"
    override val description = "在聊天记录页点击合并转发（struct viewMultiMsg）时，绕过 AIO 限制直接打开 MultiForwardActivity"
    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.CHAT_CATEGORY
    override val isAvailable: Boolean
        get() = HostInfo.isQQ()

    override fun initOnce(): Boolean {
        return try {
            val cls = Initiator.loadClass("com.tencent.mobileqq.structmsg.StructMsgForGeneralShare")
            val method = cls.declaredMethods.firstOrNull {
                it.name == "onClickEvent" && it.parameterTypes.size == 5
            } ?: run {
                Log.e("$TAG: StructMsgForGeneralShare.onClickEvent not found")
                return false
            }
            method.isAccessible = true
            HookUtils.hookBeforeIfEnabled(this, method) { param ->
                try {
                    val ctx = param.args[1] as? Context ?: return@hookBeforeIfEnabled
                    val struct = param.args[2] ?: return@hookBeforeIfEnabled
                    if (handleClick(ctx, struct)) {
                        param.result = null
                    }
                } catch (t: Throwable) {
                    Log.e("$TAG: handle failed", t)
                }
            }
            Log.i("$TAG: installed")
            true
        } catch (t: Throwable) {
            Log.e("$TAG: hook failed", t)
            false
        }
    }

    /** @return true 表示已自行处理，跳过原生逻辑。 */
    private fun handleClick(ctx: Context, struct: Any): Boolean {
        val action = readField(struct, "mMsgAction") as? String
        if (action != "viewMultiMsg") return false
        if (hasChatFragment(ctx)) return false
        val resid = readField(struct, "mResid") as? String
        if (resid.isNullOrEmpty()) {
            Log.e("$TAG: viewMultiMsg but mResid is null")
            return false
        }
        val uin = readField(struct, "uin") as? String ?: readField(struct, "frienduin") as? String
        val uinType = (readField(struct, "uinType") as? Number)?.toInt()
            ?: (readField(struct, "istroop") as? Number)?.toInt() ?: 1
        val uniseq = (readField(struct, "uniseq") as? Number)?.toLong() ?: 0L
        val activityClass = Initiator.loadClass("com.tencent.mobileqq.activity.MultiForwardActivity")
        val intent = Intent(ctx, activityClass).apply {
            putExtra("chat_subType", 3)
            putExtra("uin", uin)
            putExtra("uintype", uinType)
            putExtra("troop_uin", uin)
            putExtra("troop_code", uin)
            putExtra("multi_url", resid)
            putExtra("multimsg_uniseq", uniseq)
            putExtra("callback_type", 2)
        }
        ctx.startActivity(intent)
        Log.i("$TAG: open MultiForwardActivity uniseq=$uniseq uin=$uin uinType=$uinType resid=$resid")
        return true
    }

    private fun hasChatFragment(context: Context): Boolean {
        val method = getChatFragmentMethod ?: return false
        var ctx: Context? = context
        var depth = 0
        while (ctx != null && depth < 8) {
            try {
                if (method.invoke(ctx) != null) return true
            } catch (_: Throwable) {
                // 继续向上拆 wrapper
            }
            ctx = (ctx as? ContextWrapper)?.baseContext
            depth++
        }
        return false
    }

    private fun readField(obj: Any, name: String): Any? {
        return try {
            var cls: Class<*>? = obj.javaClass
            while (cls != null) {
                try {
                    val f: Field = cls.getDeclaredField(name)
                    f.isAccessible = true
                    return f.get(obj)
                } catch (_: NoSuchFieldException) {
                    cls = cls.superclass
                }
            }
            null
        } catch (t: Throwable) {
            null
        }
    }
}
