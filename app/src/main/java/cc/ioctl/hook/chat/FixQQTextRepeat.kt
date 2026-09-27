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

import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.widget.TextView
import cc.ioctl.util.HookUtils
import cc.ioctl.util.HostInfo
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.QQVersion
import io.github.qauxv.util.SyncUtils

/**
 * Android 15 / HyperOS 2 上，QQ 8.8.x 的 QQText 会让承载它的 TextView 在多行文本
 * 绘制时反复绘制第一行。换成标准 SpannableString 并复制 spans 后恢复正常。
 *
 * 只挂 ETTextView 不够：图文/ArkApp 卡片用的是普通 TextView，主消息文字也可能
 * 由 AnimationTextView（ETTextView 的父类）承载，因此需要挂到 TextView 基类。
 *
 * 另外 QQText 的两个字符串并不总是相同：
 *  - length()/charAt()/getChars() 用的是 mText（表情码已被替换成占位符，span 偏移也基于它）
 *  - toString() 返回的是 mSource（原始文本，带 [表情] 之类）
 * 带表情时二者长度/内容不一致，若用 toString() 构造 SpannableString，
 * copySpansFrom 的区间会对不上而抛异常。所以这里通过 CharSequence 接口读取内容。
 */
@FunctionHookEntry
@UiItemAgentEntry
object FixQQTextRepeat : CommonSwitchFunctionHook(targetProc = SyncUtils.PROC_MAIN) {

    override val name = "修复长消息重复绘制"
    override val description = "修复 Android 15 上 QQ 8.8.x 聊天文本/图文混排重复首行"
    override val uiItemLocation = FunctionEntryRouter.Locations.Simplify.CHAT_OTHER
    override val isAvailable = HostInfo.isQQ() &&
        !HostInfo.requireMinQQVersion(QQVersion.QQ_8_9_63_BETA_11345) &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    override fun initOnce(): Boolean {
        val qqTextClass = Initiator.loadClass("com.tencent.mobileqq.text.QQText")
        // 普通 TextView 直接承载 QQText；AnimationTextView/ETTextView 最终也会走到这里。
        hookSetText(TextView::class.java, qqTextClass)
        // ETTextView 自身 override 了 setText，再挂一层，确保在它做前置处理前就替换掉。
        runCatching {
            hookSetText(Initiator.loadClass("com.etrump.mixlayout.ETTextView"), qqTextClass)
        }
        return true
    }

    private fun hookSetText(clazz: Class<*>, qqTextClass: Class<*>) {
        val method = clazz.getDeclaredMethod(
            "setText",
            CharSequence::class.java,
            TextView.BufferType::class.java
        )
        HookUtils.hookBeforeIfEnabled(this, method) { param ->
            val charSequence = param.args[0] as? CharSequence ?: return@hookBeforeIfEnabled
            if (!qqTextClass.isInstance(charSequence)) {
                return@hookBeforeIfEnabled
            }
            val length = charSequence.length
            if (length < 8) {
                return@hookBeforeIfEnabled
            }
            // 通过 CharSequence 接口读取内容，拿到的是 mText，与 span 偏移一致。
            val chars = CharArray(length)
            for (i in 0 until length) {
                chars[i] = charSequence[i]
            }
            val spannable = SpannableString(String(chars))
            if (charSequence is Spanned) {
                TextUtils.copySpansFrom(
                    charSequence,
                    0,
                    length,
                    Any::class.java,
                    spannable,
                    0
                )
            }
            param.args[0] = spannable
        }
    }
}
