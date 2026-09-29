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

import cc.ioctl.util.HookUtils
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import io.github.qauxv.util.SyncUtils

/**
 * 临时 hook：在 QQ 主进程启动自动化后置步骤里，主动调用 QQ 自己的
 * MsgCountChecker，让主库到 slowtable 的迁移走官方逻辑。
 */
@FunctionHookEntry
@UiItemAgentEntry
object TriggerMsgCountChecker : CommonSwitchFunctionHook(
    hookKey = "TriggerMsgCountChecker",
    defaultEnabled = false,
    targetProc = SyncUtils.PROC_MAIN,
) {

    override val name = "触发消息裁剪迁移"
    override val description = "启动后调用 QQ MsgCountChecker，让主库到 slowtable 的迁移走官方逻辑"
    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.EXPERIMENTAL_CATEGORY
    override val isAvailable = true

    override fun initOnce(): Boolean {
        val stepClass = Initiator.loadClass("com.tencent.mobileqq.app.automator.step.CameraCategoryMaterialStep")
        val doStep = stepClass.getDeclaredMethod("doStep")
        HookUtils.hookBeforeIfEnabled(this, doStep) { param ->
            try {
                val automator = getFieldValue(param.thisObject, "mAutomator")
                val qq = getFieldValue(automator, "k")
                val checkerClass = Initiator.loadClass("com.tencent.imcore.message.MsgCountChecker")
                val checker = checkerClass.getDeclaredConstructor().newInstance()
                val qqClass = Initiator.loadClass("com.tencent.mobileqq.app.QQAppInterface")
                val method = checkerClass.getMethod("a", qqClass)
                method.invoke(checker, qq)
                Log.i("TriggerMsgCountChecker: MsgCountChecker.a returned")
            } catch (t: Throwable) {
                Log.e("TriggerMsgCountChecker: failed", t)
            }
        }
        return true
    }

    @Throws(NoSuchFieldException::class)
    private fun getFieldValue(obj: Any, name: String): Any {
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
        throw NoSuchFieldException(name)
    }
}
