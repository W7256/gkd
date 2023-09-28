package li.songe.gkd.shizuku


import android.app.ActivityManager
import android.app.IActivityTaskManager
import android.os.Build
import android.view.Display
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.RomUtils
import com.blankj.utilcode.util.ToastUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import li.songe.gkd.composition.CanOnDestroy
import li.songe.gkd.data.DeviceInfo
import li.songe.gkd.util.launchWhile
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.typeOf

/**
 * https://github.com/gkd-kit/gkd/issues/44
 */

val skipShizuku by lazy {
    Build.VERSION.SDK_INT == Build.VERSION_CODES.P && RomUtils.isVivo()
}

fun newActivityTaskManager(): IActivityTaskManager? {
    return SystemServiceHelper.getSystemService("activity_task").let(::ShizukuBinderWrapper)
        .let(IActivityTaskManager.Stub::asInterface)
}

/**
 * -1: invalid fc
 * 1: (int) -> List<Task>
 * 3: (int, boolean, boolean) -> List<Task>
 * 4: (int, boolean, boolean, int) -> List<Task>
 */
private var getTasksFcType: Int? = null

fun IActivityTaskManager.safeGetTasks(): List<ActivityManager.RunningTaskInfo>? {
    if (getTasksFcType == null) {
        val fcs = this::class.declaredMemberFunctions
        val parameters = fcs.find { d -> d.name == "getTasks" }?.parameters
        if (parameters != null) {
            if (parameters.size == 5 && parameters[1].type == typeOf<Int>() && parameters[2].type == typeOf<Boolean>() && parameters[3].type == typeOf<Boolean>() && parameters[4].type == typeOf<Int>()) {
                getTasksFcType = 4
            } else if (parameters.size == 4 && parameters[1].type == typeOf<Int>() && parameters[2].type == typeOf<Boolean>() && parameters[3].type == typeOf<Boolean>()) {
                getTasksFcType = 3
            } else if (parameters.size == 2 && parameters[1].type == typeOf<Int>()) {
                getTasksFcType = 1
            } else {
                getTasksFcType = -1
                LogUtils.d(DeviceInfo.instance)
                LogUtils.d(fcs)
                ToastUtils.showShort("Shizuku获取方法签名错误,[设置-问题反馈]可反应此问题")
            }
        }
    }
    return when (getTasksFcType) {
        1 -> this.getTasks(1)
        3 -> this.getTasks(1, false, true)
        4 -> this.getTasks(1, false, true, Display.DEFAULT_DISPLAY)
        else -> null
    }
}


fun CanOnDestroy.useShizukuAliveState(): StateFlow<Boolean> {
    val shizukuAliveFlow = MutableStateFlow(Shizuku.pingBinder())
    val receivedListener = Shizuku.OnBinderReceivedListener { shizukuAliveFlow.value = true }
    val deadListener = Shizuku.OnBinderDeadListener { shizukuAliveFlow.value = false }
    Shizuku.addBinderReceivedListener(receivedListener)
    Shizuku.addBinderDeadListener(deadListener)
    onDestroy {
        Shizuku.removeBinderReceivedListener(receivedListener)
        Shizuku.removeBinderDeadListener(deadListener)
    }
    return shizukuAliveFlow
}

fun CanOnDestroy.useSafeGetTasksFc(scope: CoroutineScope): () -> List<ActivityManager.RunningTaskInfo>? {
    if (skipShizuku) {
        return { null }
    }
    val shizukuAliveFlow = useShizukuAliveState()
    val shizukuGrantFlow = MutableStateFlow(false)
    scope.launchWhile(Dispatchers.IO) {
        shizukuGrantFlow.value = if (shizukuAliveFlow.value) shizukuIsSafeOK() else false
        delay(3000)
    }
    val activityTaskManagerFlow =
        combine(shizukuAliveFlow, shizukuGrantFlow) { shizukuAlive, shizukuGrant ->
            if (shizukuAlive && shizukuGrant) newActivityTaskManager() else null
        }.flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.Lazily, null)
    return {
        activityTaskManagerFlow.value?.safeGetTasks()
    }
}