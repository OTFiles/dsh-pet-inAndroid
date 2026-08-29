package com.dshpet.android.util

import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.ViewTreeSavedStateRegistryOwner

/**
 * 为"非 Activity 上下文"里创建的 ComposeView（如悬浮窗菜单/气泡）挂接
 * LifecycleOwner / SavedStateRegistryOwner —— 否则 Compose 组合时会抛
 * "ViewTreeLifecycleOwner not found" 崩溃。
 */
object ComposeHost {

    fun install(view: ComposeView) {
        if (ViewTreeLifecycleOwner.get(view) == null) {
            ViewTreeLifecycleOwner.set(view, SimpleOwner)
        }
        if (ViewTreeSavedStateRegistryOwner.get(view) == null) {
            ViewTreeSavedStateRegistryOwner.set(view, SimpleOwner)
        }
    }

    private object SimpleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry
    }
}
