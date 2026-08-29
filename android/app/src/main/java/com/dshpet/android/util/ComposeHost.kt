package com.dshpet.android.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * 为"非 Activity 上下文"里创建的 ComposeView（悬浮窗菜单/气泡）提供
 * LifecycleOwner / SavedStateRegistryOwner —— 否则 Compose 组合时会抛
 * "ViewTreeLifecycleOwner not found" 崩溃。
 *
 * 用法：ComposeView.setContent { ProvideComposeHost { 你的内容() } }
 */
@Composable
fun ProvideComposeHost(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalLifecycleOwner provides ComposeHostOwner,
        LocalSavedStateRegistryOwner provides ComposeHostOwner,
        content = content,
    )
}

private object ComposeHostOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this).apply {
        currentState = Lifecycle.State.RESUMED
    }
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry
}
