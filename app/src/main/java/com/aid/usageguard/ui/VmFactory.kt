package com.aid.usageguard.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/** 无 DI 框架时的轻量 ViewModel 工厂 */
inline fun <reified VM : ViewModel> vmFactory(crossinline create: () -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
