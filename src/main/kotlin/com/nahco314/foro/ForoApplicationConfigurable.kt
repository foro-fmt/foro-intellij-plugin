package com.nahco314.foro

import com.intellij.notification.*
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.getOpenedProjects
import javax.swing.JComponent
import com.intellij.ui.dsl.builder.*
import java.nio.file.Path

class ForoApplicationConfigurable : Configurable {
    var state: ForoSettings = ForoSettings.getInstance().state
    var panel = panel {
        group {
            row {
                checkBox("Enabled").bindSelected(state::enabled)
            }
            row {
                checkBox("Run format on manual-save").bindSelected(state::formatOnManualSave)
            }
            row {
                checkBox("Run format on auto-save").bindSelected(state::formatOnAutoSave)
            }
        }

        group {
            row("Foro executable path:") {
                textFieldWithBrowseButton(fileChooserDescriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()).bindText(state::givenForoExecutablePath)
            }
            row("Config file path:") {
                textFieldWithBrowseButton(fileChooserDescriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()).bindText(state::givenConfigFile)
            }
            row("Cache directory path:") {
                textFieldWithBrowseButton(fileChooserDescriptor = FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor()).bindText(state::givenCacheDir)
            }
            row("Socket directory path:") {
                textFieldWithBrowseButton(fileChooserDescriptor = FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor()).bindText(state::givenSocketDir)
            }
        }
    }

    override fun createComponent(): JComponent {
        return panel
    }

    override fun isModified(): Boolean {
        return panel.isModified()
    }

    override fun apply() {
        fun pPath(pathName: String?): Path? {
            if (pathName == null) {
                return null
            }
            if (pathName.isEmpty()) {
                return null
            }
            return Path.of(pathName)
        }

        panel.apply()

        if (!state.enabled) {
            return
        }

        val res: ForoConfigResult
        try {
            res = loadForoConfig(
                pPath(state.givenForoExecutablePath),
                pPath(state.givenConfigFile),
                pPath(state.givenCacheDir),
                pPath(state.givenSocketDir)
            )
        } catch (e: ForoConfigException) {
            for (project in getOpenedProjects()) {
                val notification = Notification("Foro", "Foro configuration error", String.format("Error loading Foro configuration: %s", e.message), NotificationType.ERROR)
                notification.addAction(object : NotificationAction("Open settings") {
                    override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, ForoApplicationConfigurable::class.java)
                    }
                })

                Notifications.Bus.notify(notification, project)
            }

            state.enabled = false

            return
        }

        state.foroExecutablePath = res.foroExecutablePath.toString()
        state.configFile = res.configFile.toString()
        state.cacheDir = res.cacheDir.toString()
        state.socketDir = res.socketDir.toString()
    }

    override fun getDisplayName(): String {
        return "Foro"
    }
}