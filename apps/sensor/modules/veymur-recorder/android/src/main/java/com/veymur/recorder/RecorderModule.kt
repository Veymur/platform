package com.veymur.recorder

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class RecorderModule : Module() {
    override fun definition() = ModuleDefinition {
        Name("VeymurRecorder")
        AsyncFunction("getModuleInfo") {
            mapOf(
                "moduleName" to "VeymurRecorder",
                "platform" to "android",
                "contractVersion" to 1,
                "recorderImplemented" to false,
            )
        }
    }
}
