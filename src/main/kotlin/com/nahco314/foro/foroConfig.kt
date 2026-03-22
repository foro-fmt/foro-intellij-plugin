package com.nahco314.foro

import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener
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
import com.intellij.openapi.util.SystemInfo
import com.intellij.psi.PsiDocumentManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Path

val FORO_COMMAND = "foro"

class ForoConfigException(message: String): Exception(message)

data class ForoConfigResult(
    val foroExecutablePath: Path,
    val configFile: Path,
    val cacheDir: Path,
    val socketDir: Path
)

fun findForoFromPath(): Path? {
    val path = System.getenv("PATH")
    val paths = path.split(File.pathSeparator)

    if (SystemInfo.isUnix) {
        val home = System.getenv("HOME")
        val cargoBin = Path.of(home, ".cargo", "bin", "foro")
        if (cargoBin.toFile().exists()) {
            return cargoBin
        }
    }

    // todo: finding on windows

    return paths.map { Path.of(it) }
        .map { it.resolve(FORO_COMMAND) }
        .firstOrNull { it.toFile().exists() }
}

fun loadForoConfig(givenForoExecutablePath: Path?, givenConfigFile: Path?, givenCacheDir: Path?, givenSocketDir: Path?): ForoConfigResult {
    val foroExecutablePath = givenForoExecutablePath ?: findForoFromPath()

    if (foroExecutablePath == null) {
        throw ForoConfigException("Foro executable not found. Please set the path to the Foro executable.")
    }

    val inputMap = mapOf(
        "given_config_file" to givenConfigFile,
        "given_cache_dir" to givenCacheDir,
        "given_socket_dir" to givenSocketDir
    )

    val inputJson = Json.encodeToString(inputMap)

    val parts = arrayOf(foroExecutablePath.toString(), "internal", "info")

    // /home/nahco314/RustroverProjects/foro/target/release/foro

    val result: ForoConfigResult

    try {
        val proc = ProcessBuilder(*parts)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        val writer = proc.outputWriter()
        writer.write(inputJson)
        writer.flush()
        writer.close()

        proc.waitFor()

        val reader = proc.inputReader()
        val outputString = reader.use { it.readText() }
        val outputMap = Json.decodeFromString<Map<String, String>>(outputString)

        result = ForoConfigResult(
            foroExecutablePath,
            Path.of(outputMap["config_file"]!!),
            Path.of(outputMap["cache_dir"]!!),
            Path.of(outputMap["socket_dir"]!!)
        )
    } catch (e: Exception) {
        throw ForoConfigException("Error running Foro: ${e.message}")
    }

    return result
}
