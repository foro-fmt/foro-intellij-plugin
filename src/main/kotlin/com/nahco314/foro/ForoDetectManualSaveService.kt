package com.nahco314.foro

import com.intellij.openapi.components.Service

@Service
class ForoDetectManualSaveService {
    @Volatile
    private var isSavingManually: Boolean = false

    fun setSavingManually(isSavingManually: Boolean) {
        this.isSavingManually = isSavingManually
    }

    fun isSavingManually(): Boolean {
        return isSavingManually
    }
}
