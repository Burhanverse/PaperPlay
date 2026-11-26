package io.max.paperplay.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object AppForegroundState {
    private val _isForeground = MutableStateFlow(true)
    val isForegroundFlow: StateFlow<Boolean> = _isForeground

    private val _isVisible = MutableStateFlow(true)
    val isVisibleFlow: StateFlow<Boolean> = _isVisible

    private val _isMiniVisible = MutableStateFlow(false)
    val isMiniVisibleFlow: StateFlow<Boolean> = _isMiniVisible

    var isForeground: Boolean
        get() = _isForeground.value
        set(value) { _isForeground.value = value }

    var isVisible: Boolean
        get() = _isVisible.value
        set(value) { _isVisible.value = value }

    var isMiniVisible: Boolean
        get() = _isMiniVisible.value
        set(value) { _isMiniVisible.value = value }
}
