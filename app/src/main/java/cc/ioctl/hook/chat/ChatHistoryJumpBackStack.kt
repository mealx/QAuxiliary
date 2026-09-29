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
import cc.ioctl.util.HostInfo
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.SyncUtils

/**
 * 纯开关：控制「聊天记录 / 媒体查看器定位到聊天」跳过去之后，按下返回键能回到
 * 发起跳转的聊天记录页。
 *
 * 它不是 hook，只保存一个布尔偏好。开启后：
 * - `ChatHistoryLocator` 去掉跳转 Intent 的 `FLAG_ACTIVITY_CLEAR_TOP`，
 *   并把 AIO 落到独立的 `ChatActivity`，它的返回键就是 `finish()`；
 * - `ChatHistoryJumpToAio` 在媒体查看器里跳转后 `finish()` 掉查看器。
 *
 * 返回栈变成 `聊天记录页 → AIO`，按返回直接回聊天记录页。
 * 关闭时保持 QQ 原行为：`CLEAR_TOP` + `SplashActivity` 会清掉聊天记录页，
 * 返回只会退到消息列表 / 退出。
 */
@FunctionHookEntry
@UiItemAgentEntry
object ChatHistoryJumpBackStack : CommonSwitchFunctionHook(
    hookKey = "ChatHistoryJumpBackStack",
    defaultEnabled = false,
    targetProc = SyncUtils.PROC_MAIN or SyncUtils.PROC_PEAK,
) {

    override val name = "定位后返回聊天记录"
    override val description = "定位到聊天窗口后，按返回键回到发起定位的聊天记录页（需先开启「聊天记录直接定位到聊天」）"
    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.CHAT_CATEGORY
    override val isAvailable: Boolean
        get() = HostInfo.isQQ() && !QAppUtils.isQQnt()

    override fun initOnce(): Boolean {
        // 纯偏好开关，不需要 hook 任何方法。
        return true
    }
}
