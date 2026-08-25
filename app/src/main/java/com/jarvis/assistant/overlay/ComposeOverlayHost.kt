package com.jarvis.assistant.overlay

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Lets a Service put Compose content into a WindowManager window.
 *
 * `ComposeView` refuses to compose unless it can find a LifecycleOwner, a
 * SavedStateRegistryOwner and a ViewModelStoreOwner on its view tree. An
 * Activity supplies all three; a Service supplies none, which is why the usual
 * first attempt at a Compose overlay crashes with
 * "ViewTreeLifecycleOwner not found" the moment the window is added.
 *
 * This class is those three owners, driven by the service's own lifecycle.
 */
class ComposeOverlayHost(private val context: Context) :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    /**
     * @return a view ready to be handed to WindowManager.addView. Must be called
     *   before [onResumed], because the registry has to be restored while the
     *   lifecycle is still INITIALIZED.
     */
    fun createView(content: @Composable () -> Unit): View {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        return ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@ComposeOverlayHost)
            setViewTreeViewModelStoreOwner(this@ComposeOverlayHost)
            setViewTreeSavedStateRegistryOwner(this@ComposeOverlayHost)
            setContent(content)
        }
    }

    fun onResumed() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onDestroyed() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
