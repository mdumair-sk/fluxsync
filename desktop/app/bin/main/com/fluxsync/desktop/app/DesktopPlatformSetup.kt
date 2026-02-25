package com.fluxsync.desktop.app

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

object DesktopPlatformSetup {
    fun isWindows(): Boolean = osName().contains("win")

    fun isLinux(): Boolean = osName().contains("linux")

    fun configureWindowsFirewall(): PlatformSetupResult {
        if (!isWindows()) {
            val output = "Windows firewall setup skipped: OS is not Windows (${System.getProperty("os.name")})."
            logger.warning(output)
            return PlatformSetupResult(success = false, output = output, exitCode = -1)
        }

        val commands = listOf(
            listOf(
                "netsh",
                "advfirewall",
                "firewall",
                "add",
                "rule",
                "name=FluxSync TCP In",
                "protocol=TCP",
                "dir=in",
                "localport=5001",
                "action=allow",
            ),
            listOf(
                "netsh",
                "advfirewall",
                "firewall",
                "add",
                "rule",
                "name=FluxSync mDNS",
                "protocol=UDP",
                "dir=in",
                "localport=5353",
                "action=allow",
            ),
        )

        val results = commands.map(::runCommand)
        val success = results.all { it.exitCode == 0 && it.success }
        val combinedOutput = buildString {
            results.forEachIndexed { index, result ->
                append("Command ${index + 1}: ${commands[index].joinToString(" ")}\n")
                append(result.output.trimEnd())
                append("\n(exitCode=${result.exitCode}, success=${result.success})\n")
            }
        }.trim()

        if (!success) {
            logger.warning("Windows firewall setup failed. output=$combinedOutput")
        }

        val exitCode = results.firstOrNull { it.exitCode != 0 }?.exitCode ?: 0
        return PlatformSetupResult(success = success, output = combinedOutput, exitCode = exitCode)
    }

    fun registerWindowsStartup(appPath: String): Boolean {
        if (!isWindows()) {
            logger.warning("Windows startup registration skipped: OS is not Windows.")
            return false
        }

        val command = listOf(
            "reg",
            "add",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
            "/v",
            "FluxSync",
            "/t",
            "REG_SZ",
            "/d",
            quotedPath(appPath),
            "/f",
        )
        val result = runCommand(command)
        if (!result.success || result.exitCode != 0) {
            logger.warning("Windows startup registration failed. output=${result.output}")
        }
        return result.success && result.exitCode == 0
    }

    fun registerLinuxStartup(appPath: String): Boolean {
        if (!isLinux()) {
            logger.warning("Linux startup registration skipped: OS is not Linux.")
            return false
        }

        return runCatching {
            val autostartDir = Path.of(System.getProperty("user.home"), ".config", "autostart")
            Files.createDirectories(autostartDir)
            val desktopEntry = autostartDir.resolve("fluxsync.desktop")
            val content = """
                [Desktop Entry]
                Type=Application
                Version=1.0
                Name=FluxSync
                Comment=FluxSync desktop sender/receiver
                Exec=${escapedDesktopExec(appPath)}
                Terminal=false
                Categories=Utility;Network;
                X-GNOME-Autostart-enabled=true
            """.trimIndent() + "\n"
            Files.writeString(desktopEntry, content, StandardCharsets.UTF_8)
            true
        }.onFailure { error ->
            logger.log(Level.WARNING, "Linux startup registration failed.", error)
        }.getOrDefault(false)
    }

    fun checkLinuxUdevRules(): Boolean {
        if (!isLinux()) {
            return false
        }

        val ruleFile = Path.of(UDEV_RULES_FILE)
        if (!Files.exists(ruleFile)) {
            logger.info("Linux udev rule file not found at $UDEV_RULES_FILE")
            return false
        }

        return runCatching {
            val content = Files.readString(ruleFile, StandardCharsets.UTF_8)
            val normalized = content.lines().map(String::trim)
            normalized.any { it == UDEV_RULE_LINE }
        }.onFailure { error ->
            logger.log(Level.WARNING, "Failed to read Linux udev rules.", error)
        }.getOrDefault(false)
    }

    fun setupLinuxUdevRules(): PlatformSetupResult {
        if (!isLinux()) {
            val output = "Linux udev setup skipped: OS is not Linux (${System.getProperty("os.name")})."
            logger.warning(output)
            return PlatformSetupResult(success = false, output = output, exitCode = -1)
        }

        val script = "echo '$UDEV_RULE_LINE' > $UDEV_RULES_FILE && " +
            "udevadm control --reload-rules && udevadm trigger"
        val command = listOf("pkexec", "sh", "-c", script)
        val result = runCommand(command)
        if (!result.success || result.exitCode != 0) {
            logger.warning("Linux udev setup failed. output=${result.output}")
            return PlatformSetupResult(success = false, output = result.output, exitCode = result.exitCode)
        }

        return PlatformSetupResult(success = true, output = result.output, exitCode = result.exitCode)
    }

    private fun runCommand(command: List<String>, timeoutSeconds: Long = 10): PlatformSetupResult {
        return runCatching {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                val timedOutOutput = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                return PlatformSetupResult(
                    success = false,
                    output = "Command timed out after ${timeoutSeconds}s: ${command.joinToString(" ")}\n$timedOutOutput",
                    exitCode = -1,
                )
            }

            val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            PlatformSetupResult(success = process.exitValue() == 0, output = output.trim(), exitCode = process.exitValue())
        }.getOrElse { error ->
            logger.log(Level.WARNING, "Command execution failed: ${command.joinToString(" ")}", error)
            PlatformSetupResult(success = false, output = error.message.orEmpty(), exitCode = -1)
        }
    }

    private fun osName(): String = System.getProperty("os.name").orEmpty().lowercase()

    private fun quotedPath(path: String): String = "\"${path.replace("\"", "\\\"")}\""

    private fun escapedDesktopExec(path: String): String = path.replace("\\", "\\\\").replace(" ", "\\ ")

    private val logger: Logger = Logger.getLogger(DesktopPlatformSetup::class.java.name)

    private const val UDEV_RULES_FILE = "/etc/udev/rules.d/51-android.rules"
    private const val UDEV_RULE_LINE =
        "SUBSYSTEM==\"usb\", ATTR{idVendor}==\"*\", MODE=\"0666\", GROUP=\"plugdev\""
}

data class PlatformSetupResult(
    val success: Boolean,
    val output: String,
    val exitCode: Int,
)
