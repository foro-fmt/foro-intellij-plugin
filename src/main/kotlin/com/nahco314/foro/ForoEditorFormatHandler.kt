package com.nahco314.foro

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnActionEvent
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

    fun format(document: Document, isAutoSave: Boolean) {
        val start = System.currentTimeMillis()

        println("Foro: Formatting $isAutoSave")

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

        val formatter = ForoFormatter()

        val path = psiFile.virtualFile.path
        val parent = psiFile.virtualFile.parent.path

        val args = FormatArgs(
            Path.of(path),
            psiFile.text,
            Path.of(parent),
            Path.of(foroSettings.state.foroExecutablePath!!),
            Path.of(foroSettings.state.configFile!!),
            Path.of(foroSettings.state.cacheDir!!),
            Path.of(foroSettings.state.socketDir!!)
        )

        val result: FormatResult

        try {
            result = formatter.format(args)
        println("Foro: aaa ${System.currentTimeMillis() - start}ms")
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

            foroSettings.state.formatOnAutoSave = false

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
                    }
                },
                "Foro Format",
                "Foro" // Group ID for command merging
            )
        }

        val end = System.currentTimeMillis()
        println("Foro: Formatted in ${end - start}ms")
    }
}