package com.nahco314.foro

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.PaintEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.SwingUtilities

@Service(Service.Level.APP)
class ForoE2ETelemetryService : Disposable {
    @Volatile
    private var trackedFilePath: String? = null

    @Volatile
    private var trackedEditorComponent: JComponent? = null

    @Volatile
    private var generation: Long = 0

    @Volatile
    private var saveActionStartedAtNanos: Long = 0

    @Volatile
    private var documentAppliedAtNanos: Long = 0

    @Volatile
    private var diskSaveCompletedAtNanos: Long = 0

    @Volatile
    private var repaintObservedAtNanos: Long = 0

    @Volatile
    private var repaintObservationSource: String? = null

    private val awtEventListener = AWTEventListener { event ->
        if (event is PaintEvent) {
            handlePaintEvent(event)
        }
    }

    init {
        Toolkit.getDefaultToolkit().addAWTEventListener(
            awtEventListener,
            AWTEvent.PAINT_EVENT_MASK
        )
    }

    fun prepareMeasurement(filePath: String): Boolean {
        val normalizedPath = normalize(filePath)
        trackedFilePath = normalizedPath
        trackedEditorComponent = findEditorComponent(normalizedPath)
        generation += 1
        saveActionStartedAtNanos = 0
        documentAppliedAtNanos = 0
        diskSaveCompletedAtNanos = 0
        repaintObservedAtNanos = 0
        repaintObservationSource = null

        return trackedEditorComponent != null
    }

    fun triggerSaveAction(): Long {
        val startedAt = System.nanoTime()
        saveActionStartedAtNanos = startedAt
        val measurementGeneration = generation

        ApplicationManager.getApplication().invokeLater {
            if (measurementGeneration != generation) {
                return@invokeLater
            }

            val saveAction = ActionManager.getInstance().getAction("SaveAll") ?: return@invokeLater
            ActionManager.getInstance().tryToExecute(saveAction, null, trackedEditorComponent, "ForoE2E", true)
        }

        return startedAt
    }

    fun replaceFileText(filePath: String, newText: String): Boolean {
        val targetFile = LocalFileSystem.getInstance().findFileByNioFile(Path.of(filePath)) ?: return false
        val document = FileDocumentManager.getInstance().getDocument(targetFile) ?: return false

        ApplicationManager.getApplication().invokeAndWait {
            runWriteAction {
                document.setText(newText)
            }
        }

        return FileDocumentManager.getInstance().isFileModified(targetFile)
    }

    fun snapshotJson(): String {
        return Json.encodeToString(
            ForoE2EMeasurementSnapshot.serializer(),
            ForoE2EMeasurementSnapshot(
                saveActionStartedAtNanos = saveActionStartedAtNanos.takeIf { it != 0L },
                documentAppliedAtNanos = documentAppliedAtNanos.takeIf { it != 0L },
                diskSaveCompletedAtNanos = diskSaveCompletedAtNanos.takeIf { it != 0L },
                repaintObservedAtNanos = repaintObservedAtNanos.takeIf { it != 0L },
                repaintObservationSource = repaintObservationSource,
            )
        )
    }

    fun recordFormatApplied(filePath: String, expectedContent: String) {
        if (normalize(filePath) != trackedFilePath) {
            return
        }

        documentAppliedAtNanos = System.nanoTime()
        val measurementGeneration = generation
        val targetPath = Path.of(filePath)

        ApplicationManager.getApplication().invokeLater {
            ApplicationManager.getApplication().invokeLater {
                if (measurementGeneration != generation || repaintObservedAtNanos != 0L) {
                    return@invokeLater
                }

                repaintObservedAtNanos = System.nanoTime()
                repaintObservationSource = "edtFallback"
            }
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val deadline = System.nanoTime() + 15_000_000_000L

            while (measurementGeneration == generation && System.nanoTime() < deadline) {
                val diskContent = runCatching { Files.readString(targetPath) }.getOrNull()
                if (diskContent == expectedContent) {
                    if (measurementGeneration == generation && diskSaveCompletedAtNanos == 0L) {
                        diskSaveCompletedAtNanos = System.nanoTime()
                    }
                    return@executeOnPooledThread
                }

                Thread.sleep(1)
            }
        }
    }

    override fun dispose() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(awtEventListener)
    }

    private fun handlePaintEvent(event: PaintEvent) {
        if (documentAppliedAtNanos == 0L || repaintObservedAtNanos != 0L) {
            return
        }

        if (!isTrackedComponent(event.component)) {
            return
        }

        repaintObservedAtNanos = System.nanoTime()
        repaintObservationSource = "paintEvent"
    }

    private fun isTrackedComponent(component: Component?): Boolean {
        val trackedComponent = trackedEditorComponent ?: return false
        component ?: return false

        return component === trackedComponent || SwingUtilities.isDescendingFrom(component, trackedComponent)
    }

    private fun findEditorComponent(filePath: String): JComponent? {
        return EditorFactory.getInstance()
            .allEditors
            .firstOrNull { editor ->
                val virtualFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return@firstOrNull false
                normalize(virtualFile.path) == filePath
            }
            ?.contentComponent as? JComponent
    }

    private fun normalize(filePath: String): String {
        return Path.of(filePath).toAbsolutePath().normalize().toString()
    }
}

@Serializable
data class ForoE2EMeasurementSnapshot(
    val saveActionStartedAtNanos: Long?,
    val documentAppliedAtNanos: Long?,
    val diskSaveCompletedAtNanos: Long?,
    val repaintObservedAtNanos: Long?,
    val repaintObservationSource: String?,
)
