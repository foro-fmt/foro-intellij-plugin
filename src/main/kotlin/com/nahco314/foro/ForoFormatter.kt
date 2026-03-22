package com.nahco314.foro

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlin.text.Charsets

class ForoUnexpectedErrorException(message: String): Exception(message)

data class FormatArgs(val targetPath: Path, val targetContent: String, val currentDir: Path, val foroExecutable: Path, val configFile: Path, val cacheDir: Path, val socketDir: Path)
sealed class FormatResult {
    data class Success(val formattedContent: String): FormatResult()
    data object Ignored: FormatResult()
    data class Error(val error: String): FormatResult()
}

class ForoFormatter {
    private fun parseInfo(infoStr: String): Pair<Int, Long>? {
        val parts = infoStr.trim().split(',')

        if (parts.size < 2) {
            return null
        }

        val pid = parts[0].toIntOrNull() ?: return null
        val startTime = parts[1].toLongOrNull() ?: return null

        return Pair(pid, startTime)
    }

    private fun getProcessInfo(pid: Long): Long? {
        val process = ProcessHandle.of(pid)

        if (process.isEmpty) {
            return null
        }

        val start = process.get().info().startInstant()

        return start.get().epochSecond
    }

    fun daemonIsAlive(socketDir: Path): Boolean {
        val infoPath = socketDir.resolve("daemon-cmd.sock.info").toFile()
        if (!infoPath.exists()) {
            return false
        }
        val content = infoPath.readText()

        val (pid, startTime) = parseInfo(content) ?: return false

        val realStartTime = getProcessInfo(pid.toLong()) ?: return false

        return realStartTime == startTime
    }

    fun start(foroExecutable: Path, configFile: Path, cacheDir: Path, socketDir: Path) {
        val parts = listOf(
            foroExecutable.toString(),
            "--config-file", configFile.toString(),
            "--cache-dir", cacheDir.toString(),
            "--socket-dir", socketDir.toString(),
            "daemon", "start"
        )
        val proc = ProcessBuilder(parts).start()
        proc.waitFor()

        if (proc.exitValue() != 0) {
            throw ForoUnexpectedErrorException("Failed to start daemon")
        }
    }

    private fun formatInner(args: FormatArgs): FormatResult {
        val commandJson = buildJsonObject {
            put("command", buildJsonObject {
                put("PureFormat", buildJsonObject {
                    put("path", args.targetPath.toString())
                    put("content", args.targetContent)
                })
            })
            put("current_dir", args.currentDir.toString())
            put("global_options", buildJsonObject {
                put("config_file", args.configFile.toString())
                put("cache_dir", args.cacheDir.toString())
                put("socket_dir", args.socketDir.toString())
                put("no_cache", false)
                put("long_log", false)
                put("ignore_build_id_mismatch", false)
            })
        }
        val commandJsonString = Json.encodeToString(JsonObject.serializer(), commandJson)

        val socketPath = args.socketDir.resolve("daemon-cmd.sock")
        val address = UnixDomainSocketAddress.of(socketPath)

        val result: FormatResult

        SocketChannel.open(StandardProtocolFamily.UNIX).use { sc ->
            sc.connect(address)

            val byteBuffer = ByteBuffer.wrap(commandJsonString.toByteArray())
            sc.write(byteBuffer)
            sc.shutdownOutput()

            val outputBuffer = ByteArrayOutputStream()
            val readBuffer = ByteBuffer.allocate(4096)

            while (true) {
                readBuffer.clear()
                val bytesRead = sc.read(readBuffer)
                if (bytesRead == -1) {
                    break
                }
                outputBuffer.write(readBuffer.array(), 0, bytesRead)
            }

            val response = outputBuffer.toByteArray().toString(Charsets.UTF_8)
            val responseJson = Json.decodeFromString<JsonObject>(response)
            val content = responseJson["PureFormat"]!!.jsonObject
            result = when {
                content.containsKey("Success") -> FormatResult.Success(content["Success"]!!.jsonPrimitive.content)
                content.containsKey("Ignored") -> FormatResult.Ignored
                else -> FormatResult.Error(content["Error"]!!.jsonPrimitive.toString())
            }
        }

        return result
    }

    fun format(args: FormatArgs): FormatResult {
        if (!daemonIsAlive(args.socketDir)) {
            start(args.foroExecutable, args.configFile, args.cacheDir, args.socketDir)
        }

        return formatInner(args)
    }
}
