package com.nahco314.foro

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import java.nio.file.Path

class ForoEditorFormatHandler(val project: Project) {
    private val undoManager: UndoManager = UndoManager.getInstance(project)
    private val psiDocumentManager: PsiDocumentManager = PsiDocumentManager.getInstance(project)
    private val foroSettings: ForoSettings = ForoSettings.getInstance()
    private val commandProcessor: CommandProcessor = CommandProcessor.getInstance()
    private val e2eTelemetryService: ForoE2ETelemetryService = service()

    fun format(document: Document, isAutoSave: Boolean) {
        if (!foroSettings.state.enabled) {
            return
        }

        if ((!foroSettings.state.formatOnManualSave) && !isAutoSave) {
            return
        }

        if ((!foroSettings.state.formatOnAutoSave) && isAutoSave) {
            return
        }

        val psiFile = psiDocumentManager.getPsiFile(document) ?: return

        if ((psiFile.text != document.text) && isAutoSave) {
            return
        }

        val foroExecutable = foroSettings.state.foroExecutablePath
        val configFile = foroSettings.state.configFile
        val cacheDir = foroSettings.state.cacheDir
        val socketDir = foroSettings.state.socketDir

        if (foroExecutable == null || configFile == null || cacheDir == null || socketDir == null) {
            Notifications.Bus.notify(
                Notification("Foro", "Foro not configured", "Please open Settings → Foro and click Apply.", NotificationType.WARNING),
                project
            )
            return
        }

        val foroExecutablePath = Path.of(foroExecutable)
        val configFilePath = Path.of(configFile)
        val cacheDirPath = Path.of(cacheDir)
        val socketDirPath = Path.of(socketDir)

        val formatter = ForoFormatter()

        // If daemon is not running, start it in background and skip this format.
        // The next save will find the daemon ready.
        if (!formatter.daemonIsAlive(socketDirPath)) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    formatter.start(foroExecutablePath, configFilePath, cacheDirPath, socketDirPath)
                } catch (e: ForoUnexpectedErrorException) {
                    runInEdt {
                        val notification = Notification(
                            "Foro",
                            "Foro error",
                            "Failed to start foro daemon: ${e.message}",
                            NotificationType.ERROR
                        )
                        notification.addAction(object : NotificationAction("Open settings") {
                            override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                                ShowSettingsUtil.getInstance().showSettingsDialog(e.project, ForoApplicationConfigurable::class.java)
                            }
                        })
                        Notifications.Bus.notify(notification, project)
                    }
                }
            }
            return
        }

        val path = psiFile.virtualFile.path
        val parent = psiFile.virtualFile.parent.path

        val args = FormatArgs(
            Path.of(path),
            document.text,
            Path.of(parent),
            foroExecutablePath,
            configFilePath,
            cacheDirPath,
            socketDirPath
        )

        val result: FormatResult

        try {
            result = formatter.format(args)
        } catch (e: ForoUnexpectedErrorException) {
            val notification = Notification(
                "Foro",
                "Foro error",
                String.format("Error formatting file: %s", e.message),
                NotificationType.ERROR
            )
            notification.addAction(object : NotificationAction("Open settings") {
                override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(e.project, ForoApplicationConfigurable::class.java)
                }
            })

            Notifications.Bus.notify(notification, project)
            return
        }

        when (result) {
            is FormatResult.Success -> {}
            is FormatResult.Ignored -> return
            is FormatResult.Error -> return
        }

        // Don't create undo entry if content hasn't changed
        if (result.formattedContent == document.text) {
            return
        }

        runInEdt {
            commandProcessor.executeCommand(
                project,
                {
                    runWriteAction {
                        if (undoManager.isUndoInProgress) {
                            return@runWriteAction
                        }

                        document.setText(result.formattedContent)
                        e2eTelemetryService.recordFormatApplied(path, result.formattedContent)
                    }
                },
                "Foro Format",
                "Foro" // Group ID for command merging
            )
        }
    }
}
