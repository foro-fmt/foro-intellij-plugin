package com.nahco314.foro

import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitIsFocusOwner
import com.intellij.driver.sdk.ui.components.codeEditor
import com.intellij.driver.sdk.ui.components.ideFrame
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.awt.event.KeyEvent
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val FORO_PLUGIN_ID = "com.nahco314.foro"
private const val PROJECT_FILE_RELATIVE_PATH = "src/main.rs"
private val reportJson = Json { prettyPrint = true; encodeDefaults = true }

class ForoSaveE2ETest {
    @Test
    fun formatOnSaveRecordsLatency() {
        val hostEnv = HostForoEnvironment.create()
        var report = E2ELatencyReport()

        try {
            Starter
                .newContext(
                    "foro-save-e2e",
                    TestCase(
                        IdeProductProvider.IC,
                        LocalProjectInfo(hostEnv.projectDir)
                    ).withVersion("2024.3")
                )
                .apply {
                    PluginConfigurator(this).installPluginFromPath(Path.of(System.getProperty("path.to.build.plugin")))
                    addProjectToTrustedLocations(hostEnv.projectDir)
                    disableAIAssistantToolwindowActivationOnStart()
                    disableInstantIdeShutdown()
                }
                .runIdeWithDriver()
                .useDriverAndCloseIde {
                    waitForIndicators(5.minutes)

                    configureForo(service<ForoSettingsRemote>(), hostEnv)

                    openFile(PROJECT_FILE_RELATIVE_PATH, project = singleProject(), waitForCodeAnalysis = false)

                    val telemetry = service<ForoE2ETelemetryRemote>()

                    ideFrame {
                        val editor = codeEditor().waitFound()
                        val saveActionMeasurements = mutableListOf<LatencyMeasurement>()
                        for (formatCase in hostEnv.measurementCases) {
                            assertTrue(
                                telemetry.replaceFileText(hostEnv.projectFile.absolutePathString(), formatCase.dirtyContent),
                                "Failed to dirty file before save action measurement"
                            )
                            waitFor(
                                message = "Editor text updated for ${formatCase.name}",
                                timeout = 10.seconds
                            ) {
                                editor.text == formatCase.dirtyContent
                            }

                            assertTrue(
                                telemetry.prepareMeasurement(hostEnv.projectFile.absolutePathString()),
                                "Tracked editor was not found for save action measurement"
                            )

                            val startedAt = telemetry.triggerSaveAction()
                            val snapshot = waitForSnapshot(telemetry) {
                                it.saveActionStartedAtNanos == startedAt &&
                                    it.diskSaveCompletedAtNanos != null &&
                                    it.repaintObservedAtNanos != null
                            }

                            waitFor(
                                message = "Editor text formatted for ${formatCase.name}",
                                timeout = 10.seconds
                            ) {
                                editor.text == formatCase.expectedContent
                            }

                            saveActionMeasurements += LatencyMeasurement(
                                name = formatCase.name,
                                trigger = "saveAction",
                                triggerStartedAtNanos = startedAt,
                                documentAppliedAtNanos = snapshot.documentAppliedAtNanos,
                                diskSaveCompletedAtNanos = requireNotNull(snapshot.diskSaveCompletedAtNanos),
                                repaintObservedAtNanos = requireNotNull(snapshot.repaintObservedAtNanos),
                                repaintObservationSource = snapshot.repaintObservationSource,
                            )
                        }

                        report = E2ELatencyReport(
                            saveActionMeasurements = saveActionMeasurements,
                        )
                    }
                }

            val lastExpected = hostEnv.measurementCases.last().expectedContent
            assertEquals(lastExpected, hostEnv.projectFile.readText())
        } finally {
            writeReport(report)
            hostEnv.close()
        }
    }

    @Test
    fun formatOnCtrlS() {
        assumeTrue(
            System.getProperty("os.name").contains("Linux", ignoreCase = true),
            "Ctrl+S E2E is supported only on Linux Starter/Xvfb"
        )

        val hostEnv = HostForoEnvironment.create()

        try {
            Starter
                .newContext(
                    "foro-ctrl-s-e2e",
                    TestCase(
                        IdeProductProvider.IC,
                        LocalProjectInfo(hostEnv.projectDir)
                    ).withVersion("2024.3")
                )
                .apply {
                    PluginConfigurator(this).installPluginFromPath(Path.of(System.getProperty("path.to.build.plugin")))
                    addProjectToTrustedLocations(hostEnv.projectDir)
                    disableAIAssistantToolwindowActivationOnStart()
                    disableInstantIdeShutdown()
                }
                .runIdeWithDriver()
                .useDriverAndCloseIde {
                    waitForIndicators(5.minutes)

                    configureForo(service<ForoSettingsRemote>(), hostEnv)

                    openFile(PROJECT_FILE_RELATIVE_PATH, project = singleProject(), waitForCodeAnalysis = false)

                    val telemetry = service<ForoE2ETelemetryRemote>()

                    ideFrame {
                        val editor = codeEditor().waitFound()

                        for (formatCase in hostEnv.measurementCases) {
                            assertTrue(
                                telemetry.replaceFileText(hostEnv.projectFile.absolutePathString(), formatCase.dirtyContent),
                                "Failed to dirty file before Ctrl+S"
                            )

                            waitFor(
                                message = "Editor text updated for ${formatCase.name}",
                                timeout = 10.seconds
                            ) {
                                editor.text == formatCase.dirtyContent
                            }

                            editor.click()
                            editor.waitIsFocusOwner()
                            editor.keyboard {
                                this.hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_S)
                            }

                            waitFor(
                                message = "Editor text formatted for ${formatCase.name}",
                                timeout = 10.seconds
                            ) {
                                editor.text == formatCase.expectedContent
                            }
                        }
                    }
                }

            val lastExpected = hostEnv.measurementCases.last().expectedContent
            assertEquals(lastExpected, hostEnv.projectFile.readText())
        } finally {
            hostEnv.close()
        }
    }

    private fun configureForo(
        settings: ForoSettingsRemote,
        hostEnv: HostForoEnvironment,
    ) {
        settings.enabled = true
        settings.formatOnManualSave = true
        settings.formatOnAutoSave = false
        settings.foroExecutablePath = hostEnv.foroExecutable.absolutePathString()
        settings.configFile = hostEnv.configFile.absolutePathString()
        settings.cacheDir = hostEnv.cacheDir.absolutePathString()
        settings.socketDir = hostEnv.socketDir.absolutePathString()
    }

    private fun waitForSnapshot(
        telemetry: ForoE2ETelemetryRemote,
        timeout: Duration = 15.seconds,
        predicate: (ForoE2EMeasurementSnapshot) -> Boolean,
    ): ForoE2EMeasurementSnapshot {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        var lastSnapshot = ForoE2EMeasurementSnapshot(null, null, null, null, null)

        while (System.nanoTime() < deadline) {
            lastSnapshot = Json.decodeFromString(telemetry.snapshotJson())
            if (predicate(lastSnapshot)) {
                return lastSnapshot
            }

            Thread.sleep(10)
        }

        error("Timed out waiting for measurement snapshot: $lastSnapshot")
    }

    private fun writeReport(report: E2ELatencyReport) {
        val reportDir = Path.of(System.getProperty("user.dir"), "build", "reports", "foro-e2e")
        Files.createDirectories(reportDir)
        reportDir.resolve("format-on-save-latency.json").writeText(
            reportJson.encodeToString(E2ELatencyReport.serializer(), report)
        )
    }

}

@Remote("com.nahco314.foro.ForoSettings", plugin = FORO_PLUGIN_ID)
interface ForoSettingsRemote {
    var enabled: Boolean
    var formatOnManualSave: Boolean
    var formatOnAutoSave: Boolean
    var foroExecutablePath: String?
    var configFile: String?
    var cacheDir: String?
    var socketDir: String?
}

@Remote("com.nahco314.foro.ForoE2ETelemetryService", plugin = FORO_PLUGIN_ID)
interface ForoE2ETelemetryRemote {
    fun prepareMeasurement(filePath: String): Boolean
    fun triggerSaveAction(): Long
    fun replaceFileText(filePath: String, newText: String): Boolean
    fun snapshotJson(): String
}

private data class FormatCase(
    val name: String,
    val dirtyContent: String,
    val expectedContent: String,
)

private class HostForoEnvironment(
    val rootDir: Path,
    val projectDir: Path,
    val projectFile: Path,
    val foroExecutable: Path,
    val configFile: Path,
    val cacheDir: Path,
    val socketDir: Path,
    val measurementCases: List<FormatCase>,
) : AutoCloseable {
    companion object {
        fun create(): HostForoEnvironment {
            val rootDir = Files.createTempDirectory("foro-plugin-e2e-")
            val projectDir = rootDir.resolve("project")
            val projectFile = projectDir.resolve(PROJECT_FILE_RELATIVE_PATH)
            val foroExecutable = resolveForoExecutable()
            val configFile = rootDir.resolve("foro.json")
            val cacheDir = rootDir.resolve("cache")
            val socketDir = rootDir.resolve("socket")

            projectFile.parent.createDirectories()
            cacheDir.createDirectories()
            socketDir.createDirectories()

            val dirtyA = """fn main(){println!("alpha");}
"""
            val dirtyB = """fn main(){println!("beta");}
"""

            val minimalConfig = minimalRustConfig(runForo(listOf(foroExecutable.absolutePathString(), "config", "default"), rootDir).stdout)
            configFile.writeText(minimalConfig)

            runForo(
                listOf(
                    foroExecutable.absolutePathString(),
                    "--config-file", configFile.absolutePathString(),
                    "--cache-dir", cacheDir.absolutePathString(),
                    "--socket-dir", socketDir.absolutePathString(),
                    "install",
                ),
                rootDir
            )

            runForo(
                listOf(
                    foroExecutable.absolutePathString(),
                    "--config-file", configFile.absolutePathString(),
                    "--cache-dir", cacheDir.absolutePathString(),
                    "--socket-dir", socketDir.absolutePathString(),
                    "daemon",
                    "start",
                ),
                rootDir
            )

            val expectedA = formatWithForo(foroExecutable, rootDir, configFile, cacheDir, socketDir, dirtyA)
            val expectedB = formatWithForo(foroExecutable, rootDir, configFile, cacheDir, socketDir, dirtyB)

            projectFile.writeText(expectedB)

            return HostForoEnvironment(
                rootDir = rootDir,
                projectDir = projectDir,
                projectFile = projectFile,
                foroExecutable = foroExecutable,
                configFile = configFile,
                cacheDir = cacheDir,
                socketDir = socketDir,
                measurementCases = listOf(
                    FormatCase("alpha", dirtyA, expectedA),
                    FormatCase("beta", dirtyB, expectedB),
                    FormatCase("alpha-repeat", dirtyA, expectedA),
                    FormatCase("beta-repeat", dirtyB, expectedB),
                )
            )
        }

        private fun resolveForoExecutable(): Path {
            val configuredPath = System.getenv("FORO_EXECUTABLE")?.takeIf { it.isNotBlank() }
            if (configuredPath != null) {
                val candidate = Path.of(configuredPath)
                if (Files.isExecutable(candidate)) {
                    return candidate
                }
            }

            val pathEntries = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            for (entry in pathEntries) {
                if (entry.isBlank()) {
                    continue
                }

                val candidate = Path.of(entry, "foro")
                if (Files.isExecutable(candidate)) {
                    return candidate
                }
            }

            error("`foro` executable was not found in PATH. Set FORO_EXECUTABLE if needed.")
        }

        private fun minimalRustConfig(defaultConfigText: String): String {
            val defaultConfig = Json.parseToJsonElement(defaultConfigText).jsonObject
            val rules = defaultConfig.getValue("rules").jsonArray
            val rustRule = rules.firstOrNull { ruleMatchesExtension(it, ".rs") }
                ?: error("Rust rule not found in `foro config default` output")

            return reportJson.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("rules", JsonArray(listOf(rustRule)))
                }
            )
        }

        private fun ruleMatchesExtension(rule: JsonElement, extension: String): Boolean {
            val on = rule.jsonObject.getValue("on")
            return when (on) {
                is JsonPrimitive -> on.content == extension
                is JsonArray -> on.any { it is JsonPrimitive && it.content == extension }
                else -> false
            }
        }

        private fun formatWithForo(
            foroExecutable: Path,
            workingDir: Path,
            configFile: Path,
            cacheDir: Path,
            socketDir: Path,
            content: String,
        ): String {
            val tempFile = workingDir.resolve("format-target.rs")
            tempFile.writeText(content)

            runForo(
                listOf(
                    foroExecutable.absolutePathString(),
                    "--config-file", configFile.absolutePathString(),
                    "--cache-dir", cacheDir.absolutePathString(),
                    "--socket-dir", socketDir.absolutePathString(),
                    "format",
                    tempFile.absolutePathString(),
                ),
                workingDir
            )

            return tempFile.readText()
        }

        private fun runForo(command: List<String>, workingDir: Path): CommandResult {
            val process = ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(false)
                .start()

            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                error(
                    buildString {
                        appendLine("Command failed: ${command.joinToString(" ")}")
                        appendLine("exitCode=$exitCode")
                        appendLine("stdout:")
                        appendLine(stdout)
                        appendLine("stderr:")
                        appendLine(stderr)
                    }
                )
            }

            return CommandResult(stdout = stdout, stderr = stderr)
        }
    }

    @OptIn(ExperimentalPathApi::class)
    override fun close() {
        try {
            runCatching {
                runForo(
                    listOf(
                        foroExecutable.absolutePathString(),
                        "--config-file", configFile.absolutePathString(),
                        "--cache-dir", cacheDir.absolutePathString(),
                        "--socket-dir", socketDir.absolutePathString(),
                        "daemon",
                        "stop",
                    ),
                    rootDir
                )
            }
        } finally {
            if (rootDir.exists()) {
                rootDir.deleteRecursively()
            }
        }
    }
}

private data class CommandResult(
    val stdout: String,
    val stderr: String,
)

@Serializable
private data class LatencyMeasurement(
    val name: String,
    val trigger: String,
    val triggerStartedAtNanos: Long,
    val documentAppliedAtNanos: Long?,
    val diskSaveCompletedAtNanos: Long,
    val repaintObservedAtNanos: Long,
    val repaintObservationSource: String?,
) {
    val formatAndSaveMillis: Double
        get() = (diskSaveCompletedAtNanos - triggerStartedAtNanos) / 1_000_000.0

    val repaintMillis: Double
        get() = (repaintObservedAtNanos - triggerStartedAtNanos) / 1_000_000.0

    val documentAppliedMillis: Double?
        get() = documentAppliedAtNanos?.let { (it - triggerStartedAtNanos) / 1_000_000.0 }
}

@Serializable
private data class MeasurementSummary(
    val medianFormatAndSaveMillis: Double?,
    val minFormatAndSaveMillis: Double?,
    val maxFormatAndSaveMillis: Double?,
    val medianRepaintMillis: Double?,
    val minRepaintMillis: Double?,
    val maxRepaintMillis: Double?,
)

@Serializable
private data class E2ELatencyReport(
    val saveActionMeasurements: List<LatencyMeasurement> = emptyList(),
    val saveActionSummary: MeasurementSummary = saveActionMeasurements.summary(),
)

private fun List<LatencyMeasurement>.summary(): MeasurementSummary {
    val formatAndSave = map { it.formatAndSaveMillis }
    val repaint = map { it.repaintMillis }

    return MeasurementSummary(
        medianFormatAndSaveMillis = formatAndSave.median(),
        minFormatAndSaveMillis = formatAndSave.minOrNull(),
        maxFormatAndSaveMillis = formatAndSave.maxOrNull(),
        medianRepaintMillis = repaint.median(),
        minRepaintMillis = repaint.minOrNull(),
        maxRepaintMillis = repaint.maxOrNull(),
    )
}

private fun List<Double>.median(): Double? {
    if (isEmpty()) {
        return null
    }

    val sorted = sorted()
    val middle = sorted.size / 2

    return if (sorted.size % 2 == 0) {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    } else {
        sorted[middle]
    }
}
