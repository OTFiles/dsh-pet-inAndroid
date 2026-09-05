package com.dshpet.android.util

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * 为"非 Activity 上下文"里创建的 ComposeView（悬浮窗菜单/气泡）提供
 * LifecycleOwner / SavedStateRegistryOwner —— 否则 Compose 组合时会抛
 * "ViewTreeLifecycleOwner not found" 崩溃。
 *
 * 注意：WindowRecomposer 在 ComposeView.onAttachedToWindow 时就会沿 ViewTree
 * 查找 owners，发生在组合之前 —— 仅在 setContent 里 CompositionLocalProvider
 * 提供是来不及的。必须在 addView 之前调用 [attachComposeHost] 设置
 * ViewTreeLifecycleOwner / ViewTreeSavedStateRegistryOwner。
 */
internal object ComposeHostOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this).apply {
        currentState = Lifecycle.State.RESUMED
    }
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    init {
        // 非 Activity 组件：必须显式 attach 才允许消费恢复状态
        // （否则 rememberSaveable 触发 "consumeRestoredStateForKey only
        // after super.onCreate" 崩溃）
        savedStateRegistryController.performAttach()
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry
}

/** 悬浮窗 ComposeView addView 前必须调用：把宿主 owners 挂到 ViewTree */
fun View.attachComposeHost() {
    setViewTreeLifecycleOwner(ComposeHostOwner)
    setViewTreeSavedStateRegistryOwner(ComposeHostOwner)
}

@Composable
fun ProvideComposeHost(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalLifecycleOwner provides ComposeHostOwner,
        LocalSavedStateRegistryOwner provides ComposeHostOwner,
        content = content,
    )
}
