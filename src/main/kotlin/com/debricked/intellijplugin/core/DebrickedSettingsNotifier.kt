package com.debricked.intellijplugin.core

import com.intellij.util.messages.Topic

interface DebrickedSettingsNotifier {
    fun onSettingsApplied()

    companion object {
        @JvmField
        val TOPIC: Topic<DebrickedSettingsNotifier> =
            Topic.create("DebrickedSettingsApplied", DebrickedSettingsNotifier::class.java)
    }
}
