package com.github.itechbear.clion.cpplint.nova.settings

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.dsl.builder.*
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.swing.JTextField

/**
 * Provides controller for the Settings Dialog.
 * Uses modern Kotlin UI DSL for building the settings panel.
 */
class CpplintConfigurable : BoundConfigurable("Cpplint") {

    companion object {
        const val OPTION_KEY_PYTHON = "python"
        const val OPTION_KEY_CPPLINT = "cpplint"
        const val OPTION_KEY_CPPLINT_OPTIONS = "cpplintOptions"
        const val OPTION_KEY_ENABLED = "cpplintEnabled"
    }

    private var pythonPath: String = Settings.get(OPTION_KEY_PYTHON) ?: ""
    private var cpplintPath: String = Settings.get(OPTION_KEY_CPPLINT) ?: ""
    private var cpplintOptions: String = Settings.get(OPTION_KEY_CPPLINT_OPTIONS) ?: ""
    private var enabled: Boolean = Settings.getBoolean(OPTION_KEY_ENABLED, true)

    // Store references to UI components for direct access
    private lateinit var pythonTextField: TextFieldWithBrowseButton
    private lateinit var cpplintTextField: TextFieldWithBrowseButton

    override fun createPanel(): DialogPanel {
        return panel {
            row {
                checkBox("Enable cpplint inspection")
                    .bindSelected(::enabled)
                    .comment("When disabled, cpplint will not run on any files")
            }

            row("Python path:") {
                textFieldWithBrowseButton(
                    fileChooserDescriptor = FileChooserDescriptor(true, false, false, false, false, false)
                        .withTitle("Select Python Executable"),
                    project = null
                )
                    .bindText(::pythonPath)
                    .align(AlignX.FILL)
                    .comment("Path to Python executable")
                    .also { pythonTextField = it.component }
            }

            row("cpplint path:") {
                textFieldWithBrowseButton(
                    fileChooserDescriptor = FileChooserDescriptor(true, false, false, false, false, false)
                        .withTitle("Select cpplint.py"),
                    project = null
                )
                    .bindText(::cpplintPath)
                    .align(AlignX.FILL)
                    .comment("Path to cpplint.py script")
                    .also { cpplintTextField = it.component }
            }

            row("cpplint.py options:") {
                textField()
                    .bindText(::cpplintOptions)
                    .align(AlignX.FILL)
                    .comment("Additional command line options for cpplint")
            }

            row {
                button("Test Configuration") {
                    testCpplintConfiguration()
                }
                button("Auto-Detect Paths") {
                    autoDetectPaths()
                }
                    .comment("Verify Python and cpplint installation, or auto-detect paths")
            }
        }
    }

    private fun autoDetectPaths() {
        // Try to find matching Python + cpplint pair from same installation
        val pair = detectPythonCpplintPair()

        var message = "Auto-detection results:\n\n"
        var foundAny = false

        if (pair != null) {
            pythonTextField.text = pair.first
            cpplintTextField.text = pair.second
            message += "✓ Python found: ${pair.first}\n"
            message += "✓ cpplint found: ${pair.second}\n"
            message += "  (from same Python installation)\n"
            foundAny = true
        } else {
            // Fall back to independent detection
            val detectedPython = detectPython()
            val detectedCpplint = detectCpplint()

            if (detectedPython != null) {
                pythonTextField.text = detectedPython
                message += "✓ Python found: $detectedPython\n"
                foundAny = true
            } else {
                message += "✗ Python not found\n"
            }

            if (detectedCpplint != null) {
                cpplintTextField.text = detectedCpplint
                message += "✓ cpplint found: $detectedCpplint\n"
                foundAny = true
            } else {
                message += "✗ cpplint not found\n"
            }

            if (detectedPython != null && detectedCpplint != null) {
                message += "\n⚠ WARNING: Python and cpplint are from different installations.\n" +
                          "This may cause compatibility issues.\n"
            }
        }

        if (!foundAny) {
            message += "\nPlease install Python and cpplint:\n  pip install cpplint"
        }

        Messages.showInfoMessage(message, "Auto-Detect Paths")
    }

    /**
     * Attempts to find a matching Python + cpplint pair from the same installation.
     * Returns Pair(pythonPath, cpplintPath) if found, null otherwise.
     */
    private fun detectPythonCpplintPair(): Pair<String, String>? {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")

        if (isWindows) {
            // First, try 'where python' to get all Python installations in PATH
            try {
                val process = ProcessBuilder("where", "python").start()
                val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                if (process.waitFor() == 0) {
                    // Check each Python in PATH
                    output.lines().forEach { pythonPath ->
                        val trimmed = pythonPath.trim()
                        if (trimmed.endsWith(".exe") && java.io.File(trimmed).exists()) {
                            val cpplintPath = findCpplintForPython(trimmed, isWindows)
                            if (cpplintPath != null) {
                                return Pair(trimmed, cpplintPath)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Continue to next method
            }

            // Check common Windows Python installation paths
            for (major in 3..3) {
                for (minor in 20 downTo 6) {
                    val version = "$major$minor"
                    val pythonPaths = listOf(
                        "C:\\Python$version\\python.exe",
                        "C:\\Program Files\\Python$version\\python.exe",
                        "C:\\Program Files (x86)\\Python$version\\python.exe"
                    )
                    pythonPaths.forEach { pythonPath ->
                        if (java.io.File(pythonPath).exists()) {
                            val cpplintPath = findCpplintForPython(pythonPath, isWindows)
                            if (cpplintPath != null) {
                                return Pair(pythonPath, cpplintPath)
                            }
                        }
                    }
                }
            }
        } else {
            // Unix-like systems: try 'which' to get Python paths
            listOf("python3", "python").forEach { cmd ->
                try {
                    val process = ProcessBuilder("which", cmd).start()
                    val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                    if (process.waitFor() == 0 && output.isNotBlank()) {
                        val pythonPath = output.trim()
                        val cpplintPath = findCpplintForPython(pythonPath, isWindows)
                        if (cpplintPath != null) {
                            return Pair(pythonPath, cpplintPath)
                        }
                    }
                } catch (e: Exception) {
                    // Try next option
                }
            }
        }

        return null
    }

    /**
     * Given a Python executable path, attempts to find cpplint in the same installation.
     * Returns cpplint path if found, null otherwise.
     */
    private fun findCpplintForPython(pythonPath: String, isWindows: Boolean): String? {
        // Extract Python base directory from python executable path
        val pythonFile = java.io.File(pythonPath)
        val pythonDir = pythonFile.parentFile ?: return null

        if (isWindows) {
            // Check Scripts/cpplint.exe and Lib/site-packages/cpplint.py
            val candidatePaths = listOf(
                java.io.File(pythonDir, "Scripts\\cpplint.exe"),
                java.io.File(pythonDir, "Scripts\\cpplint"),
                java.io.File(pythonDir, "Lib\\site-packages\\cpplint.py")
            )
            candidatePaths.forEach { file ->
                if (file.exists()) {
                    return file.absolutePath
                }
            }
        } else {
            // Unix-like: check bin/cpplint
            val binCpplint = java.io.File(pythonDir, "cpplint")
            if (binCpplint.exists()) {
                return binCpplint.absolutePath
            }

            // Check ../lib/python*/site-packages/cpplint for virtual environments
            val parentDir = pythonDir.parentFile
            if (parentDir != null) {
                val libDir = java.io.File(parentDir, "lib")
                if (libDir.exists() && libDir.isDirectory) {
                    libDir.listFiles()?.forEach { pythonVersionDir ->
                        if (pythonVersionDir.isDirectory && pythonVersionDir.name.startsWith("python")) {
                            val sitePackages = java.io.File(pythonVersionDir, "site-packages/cpplint.py")
                            if (sitePackages.exists()) {
                                return sitePackages.absolutePath
                            }
                        }
                    }
                }
            }
        }

        return null
    }

    private fun detectPython(): String? {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")

        if (isWindows) {
            // Windows: prioritize finding actual file paths over command names

            // First, try 'where python' to get full path
            try {
                val process = ProcessBuilder("where", "python").start()
                val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                if (process.waitFor() == 0) {
                    val path = output.lines().firstOrNull { it.endsWith(".exe") }?.trim()
                    if (path != null) return path
                }
            } catch (e: Exception) {
                // Continue to next method
            }

            // Check common Windows Python installation paths
            // Try Python 3.6 through 3.20 (future-proof)
            for (major in 3..3) {
                for (minor in 20 downTo 6) {
                    val version = "$major$minor"
                    val paths = listOf(
                        "C:\\Python$version\\python.exe",
                        "C:\\Program Files\\Python$version\\python.exe",
                        "C:\\Program Files (x86)\\Python$version\\python.exe"
                    )
                    paths.forEach { path ->
                        if (java.io.File(path).exists()) {
                            return path
                        }
                    }
                }
            }
        } else {
            // Unix-like systems: try 'which' to get full path
            listOf("python3", "python").forEach { cmd ->
                try {
                    val process = ProcessBuilder("which", cmd).start()
                    val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                    if (process.waitFor() == 0 && output.isNotBlank()) {
                        return output.trim()
                    }
                } catch (e: Exception) {
                    // Try next option
                }
            }
        }

        // Last resort: verify command works and return command name
        listOf("python3", "python").forEach { cmd ->
            try {
                val process = ProcessBuilder(cmd, "--version").start()
                val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                if (process.waitFor() == 0 && output.contains("Python", ignoreCase = true)) {
                    return cmd
                }
            } catch (e: Exception) {
                // Try next option
            }
        }

        return null
    }

    private fun detectCpplint(): String? {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")

        if (isWindows) {
            // Check Python site-packages and Scripts folders
            // Try Python 3.6 through 3.20 (future-proof)
            for (major in 3..3) {
                for (minor in 20 downTo 6) {
                    val version = "$major$minor"
                    val paths = listOf(
                        "C:\\Python$version\\Lib\\site-packages\\cpplint.py",
                        "C:\\Python$version\\Scripts\\cpplint.exe",
                        "C:\\Program Files\\Python$version\\Lib\\site-packages\\cpplint.py",
                        "C:\\Program Files\\Python$version\\Scripts\\cpplint.exe"
                    )
                    paths.forEach { path ->
                        if (java.io.File(path).exists()) {
                            return path
                        }
                    }
                }
            }

            // Try 'where cpplint' on Windows
            try {
                val process = ProcessBuilder("where", "cpplint").start()
                val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                if (process.waitFor() == 0) {
                    return output.lines().firstOrNull()?.trim()
                }
            } catch (e: Exception) {
                // Ignore
            }
        } else {
            // Unix-like systems
            val commonPaths = listOf(
                "/usr/local/bin/cpplint",
                "/usr/bin/cpplint",
                "/opt/homebrew/bin/cpplint",  // macOS Homebrew
                System.getProperty("user.home") + "/.local/bin/cpplint"
            )
            commonPaths.forEach { path ->
                if (java.io.File(path).exists()) {
                    return path
                }
            }

            // Try 'which cpplint'
            try {
                val process = ProcessBuilder("which", "cpplint").start()
                val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                if (process.waitFor() == 0 && output.isNotBlank()) {
                    return output.trim()
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        return null
    }

    private fun testCpplintConfiguration() {
        // Read current values directly from UI components
        val python = pythonTextField.text.ifEmpty { "python" }
        val cpplint = cpplintTextField.text

        if (cpplint.isEmpty()) {
            Messages.showErrorDialog(
                "Please specify the path to cpplint.py first.",
                "Cpplint Configuration Test"
            )
            return
        }

        try {
            val command = listOf(python, cpplint, "--version")
            val process = ProcessBuilder(command).start()

            // Read both stdout and stderr separately
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readText()
            }
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                reader.readText()
            }

            val exitCode = process.waitFor()
            val output = (stdout + stderr).trim()

            // Check for common error patterns
            val hasError = output.contains("can't open file", ignoreCase = true) ||
                          output.contains("No such file", ignoreCase = true) ||
                          output.contains("cannot find", ignoreCase = true) ||
                          output.contains("not found", ignoreCase = true) ||
                          output.contains("error:", ignoreCase = true) ||
                          output.isEmpty()

            if (hasError) {
                Messages.showErrorDialog(
                    "Failed to run cpplint.\n\nExit code: $exitCode\n\nOutput:\n$output\n\nPlease verify your Python and cpplint paths are correct.",
                    "Cpplint Configuration Test"
                )
            } else {
                // Success - cpplint version output found
                Messages.showInfoMessage(
                    "Configuration test successful!\n\nOutput:\n$output",
                    "Cpplint Configuration Test"
                )
            }
        } catch (e: Exception) {
            Messages.showErrorDialog(
                "Error testing configuration:\n${e.message}\n\nPlease verify your Python and cpplint paths are correct.",
                "Cpplint Configuration Test"
            )
        }
    }

    override fun apply() {
        super.apply()
        Settings.set(OPTION_KEY_PYTHON, pythonPath)
        Settings.set(OPTION_KEY_CPPLINT, cpplintPath)
        Settings.set(OPTION_KEY_CPPLINT_OPTIONS, cpplintOptions)
        Settings.setBoolean(OPTION_KEY_ENABLED, enabled)

        // Restart code analysis to apply inspection changes immediately
        ProjectManager.getInstance().openProjects.forEach { project ->
            DaemonCodeAnalyzer.getInstance(project).restart("Cpplint settings changed")
        }
    }

    override fun reset() {
        pythonPath = Settings.get(OPTION_KEY_PYTHON) ?: ""
        cpplintPath = Settings.get(OPTION_KEY_CPPLINT) ?: ""
        cpplintOptions = Settings.get(OPTION_KEY_CPPLINT_OPTIONS) ?: ""
        enabled = Settings.getBoolean(OPTION_KEY_ENABLED, true)
        super.reset()
    }
}
