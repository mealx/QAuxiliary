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

package cc.ioctl.util

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import cc.hicore.QApp.QAppUtils
import com.tencent.mobileqq.qroute.QRoute
import com.tencent.mobileqq.qroute.QRouteApi
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log

/**
 * 从聊天记录 / 图片视频查看器跳回聊天窗口并定位到目标消息。
 *
 * 9110 走 NT 的 `com.tencent.qqnt.aio.locate.api.IMsgLocationApi.jumpToTargetNTAIOPosition`；
 * 8890 没有这个 QRoute API，等价实现在
 * `com.tencent.mobileqq.richmediabrowser.utils.GalleryJumpUtils.a(Activity, int, String, String, long, long, int, boolean)`
 * 与 `com.tencent.mobileqq.activity.aio.photo.AIOGalleryScene.a(...)`，
 * 两者最终都是构造 `SplashActivity` + `need_jump_to_msg` bundle。
 *
 * 这里把 8890 的构造过程直接实现一遍，而不是反射 `GalleryJumpUtils.a`，
 * 这样 `ChatHistoryJumpToAio` 才能安全地把 `GalleryJumpUtils.a` 重定向到这里，
 * 不会产生 hook 递归。
 *
 * 参数语义与 8890 保持一致：
 * - [uinType] 0 好友 / 1 群 / 3000 讨论组 / 10014 频道
 * - [uin] 好友号 / 群号 / 讨论组号
 * - [troopUin] 群或讨论组号，好友会话可为 null
 * - [time] 消息时间戳（毫秒）
 * - [shmsgseq] 群 / 讨论组定位用消息 seq
 * - [chatType] 会话类型，默认与 [uinType] 相同
 * - [isEmotion] 是否从表情查看器进入
 *
 * NT 分支没有 `time` 参数，只有 `shmsgseq` + `msgId`，所以调用方在 NT 上需要
 * 额外传 [ntMsgId] / [peerUid]，否则只能退化为 6 参数重载。
 */
object ChatHistoryLocator {

    private const val TAG = "ChatHistoryLocator"

    private const val KEY_PHOTO_INIT_ACTIVITY_CLASS_NAME = "PhotoConst.INIT_ACTIVITY_CLASS_NAME"
    private const val SPLASH_ACTIVITY_CLASS_NAME = "com.tencent.mobileqq.activity.SplashActivity"
    private const val CHAT_ACTIVITY_CLASS_NAME = "com.tencent.mobileqq.activity.ChatActivity"
    private const val KEY_SEARCHED_TIME = "searched_time"
    private const val KEY_SEARCHED_TIMEORSEQ = "searched_timeorseq"
    private const val KEY_SEARCHED_UPDATE_SESSION = "searched_update_session"
    private const val KEY_UIN_TYPE = "uintype"
    private const val KEY_IS_SHOW_ENTRANCE = "entrance"

    private const val GALLERY_JUMP_FLAGS = 603979776
    private const val OPEN_CHAT_FRAGMENT_FLAGS = 67108864

    /**
     * 8890 频道（10014）仍复用 QQ 自己的 `GalleryJumpUtils.a` 走 guild API；
     * 置位期间 `ChatHistoryJumpToAio` 必须放行原方法，避免递归。
     */
    internal val bypassGalleryHook: ThreadLocal<Boolean> = ThreadLocal.withInitial<Boolean> { false }

    /**
     * 统一入口：8890 直接构造 AIO 跳转，NT 走 IMsgLocationApi。
     */
    @JvmStatic
    @JvmOverloads
    fun jumpToTargetAIOPosition(
        activity: Activity,
        uinType: Int,
        uin: String,
        troopUin: String?,
        time: Long,
        shmsgseq: Long,
        chatType: Int = uinType,
        isEmotion: Boolean = false,
        ntMsgId: Long = 0L,
        peerUid: String? = null,
    ) {
        if (QAppUtils.isQQnt()) {
            jumpToTargetNTAIOPosition(
                activity = activity,
                uinType = uinType,
                uin = uin,
                troopUin = troopUin,
                shmsgseq = shmsgseq,
                msgId = if (ntMsgId != 0L) ntMsgId else time,
                chatType = chatType,
                peerUid = peerUid,
            )
        } else {
            jumpToTargetAIOPosition8890(
                activity = activity,
                uinType = uinType,
                uin = uin,
                troopUin = troopUin,
                time = time,
                shmsgseq = shmsgseq,
                chatType = chatType,
                isEmotion = isEmotion,
            )
        }
    }

    /**
     * 8890 专用入口，直接构造 `SplashActivity` + `need_jump_to_msg` bundle。
     *
     * 对应原实现：
     * - `GalleryJumpUtils.a(...)` 非 from-history 分支
     * - `AIOGalleryScene.a(...)` 非 from-history 分支
     *
     * 频道（10014）交给原 `GalleryJumpUtils.a`，因为那条路要转 guild API。
     */
    @JvmStatic
    fun jumpToTargetAIOPosition8890(
        activity: Activity,
        uinType: Int,
        uin: String,
        troopUin: String?,
        time: Long,
        shmsgseq: Long,
        chatType: Int,
        isEmotion: Boolean,
    ) {
        if (activity.isFinishing) {
            return
        }
        if (chatType == 10014) {
            invokeGalleryJumpUtilsForGuild(activity, uinType, uin, troopUin, time, shmsgseq, chatType, isEmotion)
            return
        }
        try {
            val intent = Intent()
            // 从聊天记录进查看器时，PeakUtils 会把 INIT_ACTIVITY_CLASS_NAME 填成
            // ChatHistoryActivity，直接 setClassName 会又打开聊天记录页。
            // 只有真正的 AIO 宿主类才复用，否则一律回 SplashActivity。
            val initClassName = activity.intent?.getStringExtra(KEY_PHOTO_INIT_ACTIVITY_CLASS_NAME)
            if (initClassName == SPLASH_ACTIVITY_CLASS_NAME || initClassName == CHAT_ACTIVITY_CLASS_NAME) {
                intent.setClassName(activity, initClassName)
            } else {
                val splashActivity = Initiator.loadClass(SPLASH_ACTIVITY_CLASS_NAME)
                intent.setClass(activity, splashActivity)
            }
            // AIOUtils.a(intent, null) / BaseAIOUtils.a
            intent.putExtra("open_chatfragment", true)
            intent.addFlags(OPEN_CHAT_FRAGMENT_FLAGS)

            val timeOrSeq = if (chatType == 1 || chatType == 3000) shmsgseq else time
            val bundle = Bundle()
            bundle.putBoolean("need_jump_to_msg", true)
            bundle.putLong(KEY_SEARCHED_TIMEORSEQ, timeOrSeq)
            bundle.putLong(KEY_SEARCHED_TIME, time)
            bundle.putLong("target_shmsgseq", shmsgseq)
            bundle.putBoolean(KEY_SEARCHED_UPDATE_SESSION, true)
            bundle.putBoolean("jump_from_shmsgseq", true)
            intent.putExtras(bundle)

            intent.putExtra("uin", uin)
            intent.putExtra(KEY_UIN_TYPE, uinType)
            intent.putExtra("troop_uin", troopUin)
            intent.addFlags(GALLERY_JUMP_FLAGS)
            intent.putExtra(KEY_IS_SHOW_ENTRANCE, 1)
            activity.startActivity(intent)
        } catch (t: Throwable) {
            Log.e("$TAG: jumpToTargetAIOPosition8890 failed", t)
            throw IllegalStateException("8890 jump to AIO failed", t)
        }
    }

    private fun invokeGalleryJumpUtilsForGuild(
        activity: Activity,
        uinType: Int,
        uin: String,
        troopUin: String?,
        time: Long,
        shmsgseq: Long,
        chatType: Int,
        isEmotion: Boolean,
    ) {
        bypassGalleryHook.set(true)
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
            method.isAccessible = true
            method.invoke(null, activity, uinType, uin, troopUin, time, shmsgseq, chatType, isEmotion)
        } catch (t: Throwable) {
            Log.e("$TAG: GalleryJumpUtils.a (guild) failed", t)
            throw IllegalStateException("8890 guild jump failed", t)
        } finally {
            bypassGalleryHook.set(false)
        }
    }

    /**
     * NT（9110+）入口。优先调用带 `peerUid` 的 8 参数重载，拿不到 peerUid 时退化到 6 参数重载。
     */
    @JvmStatic
    fun jumpToTargetNTAIOPosition(
        activity: Activity,
        uinType: Int,
        uin: String,
        troopUin: String?,
        shmsgseq: Long,
        msgId: Long,
        chatType: Int,
        peerUid: String?,
    ) {
        try {
            @Suppress("UNCHECKED_CAST")
            val apiClass = Initiator.loadClass("com.tencent.qqnt.aio.locate.api.IMsgLocationApi") as Class<QRouteApi>
            val impl = QRoute.api(apiClass)
            if (peerUid != null) {
                val method = apiClass.getMethod(
                    "jumpToTargetNTAIOPosition",
                    Activity::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java,
                    Long::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                )
                method.invoke(impl, activity, uinType, uin, troopUin, shmsgseq, msgId, chatType, peerUid)
            } else {
                val method = apiClass.getMethod(
                    "jumpToTargetNTAIOPosition",
                    Activity::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java,
                    Long::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                )
                method.invoke(impl, activity, uinType, uin, troopUin, shmsgseq, msgId)
            }
        } catch (t: Throwable) {
            Log.e("$TAG: IMsgLocationApi.jumpToTargetNTAIOPosition not available", t)
            throw IllegalStateException("NT IMsgLocationApi not available", t)
        }
    }
}
