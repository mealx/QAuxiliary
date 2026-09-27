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
import io.github.qauxv.util.Log
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.QQVersion
import io.github.qauxv.util.SyncUtils
import io.github.qauxv.util.hostInfo
import io.github.qauxv.util.xpcompat.XposedHelpers
import org.json.JSONObject

/**
 * ArkApp 服务端会对旧 QQ 返回 RET_SDK_VER_TOO_LOW(6) 并拒绝下载，
 * 即使 response 中已经带了 .ark 下载地址。这里把 retCode 改成成功，
 * 让旧 QQ 尝试下载和渲染。
 *
 * 两种情况：
 * - 服务端给了可下载的 ark 包（state=1 + url/ver/sign）：任何 app 都放行；
 * - 图文模板 `com.tencent.tuwen.lua`：服务端返回 state=2 + 无 url，
 *   这时强制使用本地已有的 .ark。
 *
 * 实验性：如果旧 Ark 引擎确实缺少新模板能力，仍可能加载失败。
 */
@FunctionHookEntry
@UiItemAgentEntry
object BypassArkAppVersion : CommonSwitchFunctionHook(targetProc = SyncUtils.PROC_MAIN) {

    private const val TAG = "BypassArkAppVersion"
    private const val RET_SDK_VER_TOO_LOW = 6
    private const val TARGET_APP = "com.tencent.tuwen.lua"
    private const val TARGET_APP_VERSION = "1.0.0.55"

    override val name = "绕过 ArkApp 版本限制"
    override val description = "实验性：允许旧 QQ 尝试加载被服务端版本限制的图文 / 小程序 ArkApp"
    override val uiItemLocation = FunctionEntryRouter.Locations.Simplify.CHAT_OTHER
    override val isAvailable = HostInfo.isQQ() &&
        !HostInfo.requireMinQQVersion(QQVersion.QQ_8_9_63_BETA_11345)

    override fun initOnce(): Boolean {
        Log.i("$TAG: initOnce start")
        hookHandleAppUpdate()
        hookArkAppMgrOnUpdateAppResult()
        hookParserReply()
        Log.i("$TAG: initOnce finish")
        return true
    }

    private fun hookParserReply() {
        val clazz = Initiator.loadClass("com.tencent.ark.open.internal.ArkAppCGIMgr")
        val method = clazz.getDeclaredMethod(
            "parserReply_queryAppInfoByAppNameBatch",
            String::class.java
        )
        Log.i("$TAG: hooking parserReply_queryAppInfoByAppNameBatch")
        HookUtils.hookBeforeIfEnabled(this, method) { param ->
            val raw = param.args[0] as? String ?: return@hookBeforeIfEnabled
            val patched = patchRawArkAppReply(raw) ?: return@hookBeforeIfEnabled
            param.args[0] = patched
            Log.i("$TAG: patched raw ArkApp reply")
        }
    }

    private fun patchRawArkAppReply(raw: String): String? {
        return try {
            val root = JSONObject(raw)
            val data = root.optJSONObject("data") ?: return null
            val apps = data.optJSONArray("apps") ?: return null
            var patched = false
            for (i in 0 until apps.length()) {
                val app = apps.optJSONObject(i) ?: continue
                val appName = app.optString("app")
                if (app.optInt("ret", -1) != RET_SDK_VER_TOO_LOW) {
                    continue
                }
                if (!shouldBypassVersionGate(appName, app)) {
                    continue
                }
                app.put("ret", 0)
                app.put("msg", "")
                patched = true
                Log.i("$TAG: parser raw ret 6 -> 0 for $appName")
            }
            if (patched) root.toString() else null
        } catch (e: Throwable) {
            Log.e("$TAG: patchRawArkAppReply failed", e)
            null
        }
    }

    /**
     * 只有服务端确实给了可下载的 ark 包时才放行；否则保持原样，
     * 避免强行加载确实不兼容的 app。`com.tencent.tuwen.lua` 是已知的
     * 本地图文模板（服务端 state=2 + 无 url），单独放行。
     */
    private fun shouldBypassVersionGate(appName: String, app: JSONObject): Boolean {
        if (appName == TARGET_APP) {
            return true
        }
        if (app.optInt("state", 0) != 1) {
            return false
        }
        return app.optString("url").isNotEmpty() &&
            app.optString("ver").isNotEmpty() &&
            app.optString("sign").isNotEmpty()
    }

    private fun hookHandleAppUpdate() {
        val appUpdateTaskClass = Initiator.loadClass("com.tencent.ark.open.appmanage.AppUpdateTask")
        val updateTaskClass = Initiator.loadClass("com.tencent.ark.open.appmanage.AppUpdateTask\$UpdateTask")
        val queryAppInfoResultClass = Initiator.loadClass("com.tencent.ark.open.internal.ArkAppCGIMgr\$QueryAppInfoResult")
        val method = appUpdateTaskClass.getDeclaredMethod(
            "handleAppUpdate",
            updateTaskClass,
            String::class.java,
            queryAppInfoResultClass
        )
        Log.i("$TAG: hooking handleAppUpdate")
        HookUtils.hookBeforeIfEnabled(this, method) { param ->
            val appName = param.args[1] as? String ?: return@hookBeforeIfEnabled
            val result = param.args[2] ?: return@hookBeforeIfEnabled
            val retCode = XposedHelpers.getIntField(result, "retCode")
            val state = XposedHelpers.getIntField(result, "state")
            val info = XposedHelpers.getObjectField(result, "info")
            if (retCode == RET_SDK_VER_TOO_LOW && state == 1 && info != null) {
                XposedHelpers.setIntField(result, "retCode", 0)
                Log.i("$TAG: handleAppUpdate patched retCode to 0 for $appName")
            }
        }
    }

    private fun hookArkAppMgrOnUpdateAppResult() {
        val arkAppMgrClass = Initiator.loadClass("com.tencent.ark.open.ArkAppMgr")
        val updateTaskClass = Initiator.loadClass("com.tencent.ark.open.appmanage.AppUpdateTask\$UpdateTask")
        val getAppPathByNameTaskClass = Initiator.loadClass("com.tencent.ark.open.ArkAppMgr\$GetAppPathByNameTask")
        val method = arkAppMgrClass.getDeclaredMethod(
            "onUpdateAppResult",
            updateTaskClass,
            getAppPathByNameTaskClass
        )
        Log.i("$TAG: hooking onUpdateAppResult")
        HookUtils.hookBeforeIfEnabled(this, method) { param ->
            val updateTask = param.args[0] ?: return@hookBeforeIfEnabled
            val appName = XposedHelpers.getObjectField(updateTask, "app")?.let {
                XposedHelpers.getObjectField(it, "appName") as? String
            } ?: return@hookBeforeIfEnabled
            if (appName != TARGET_APP) {
                return@hookBeforeIfEnabled
            }
            val retCode = XposedHelpers.getIntField(updateTask, "retCode")
            Log.i("$TAG: onUpdateAppResult app=$appName ret=$retCode")
            if (retCode != RET_SDK_VER_TOO_LOW) {
                return@hookBeforeIfEnabled
            }
            val localPath = buildLocalArkPath()
            val file = java.io.File(localPath)
            if (!file.isFile) {
                Log.w("$TAG: local ark file missing: $localPath")
                return@hookBeforeIfEnabled
            }
            val appPathInfoClass = Initiator.loadClass("com.tencent.ark.open.ArkAppMgr\$AppPathInfo")
            val appPathInfo = appPathInfoClass.getDeclaredConstructor().newInstance()
            val desc = XposedHelpers.getObjectField(appPathInfo, "desc")
            XposedHelpers.setObjectField(desc, "name", TARGET_APP)
            XposedHelpers.setObjectField(desc, "version", TARGET_APP_VERSION)
            XposedHelpers.setObjectField(appPathInfo, "path", localPath)
            XposedHelpers.setIntField(appPathInfo, "from", 2)
            XposedHelpers.setObjectField(updateTask, "appPathInfo", appPathInfo)
            XposedHelpers.setIntField(updateTask, "retCode", 0)
            XposedHelpers.setIntField(updateTask, "result", 1)
            XposedHelpers.setObjectField(updateTask, "errMsg", null)
            Log.i("$TAG: onUpdateAppResult forced local path for $appName: $localPath")
        }
    }

    private fun buildLocalArkPath(): String {
        val filesDir = hostInfo.application.filesDir.absolutePath
        return "$filesDir/ArkApp/Install/$TARGET_APP/$TARGET_APP_VERSION.ark"
    }
}
