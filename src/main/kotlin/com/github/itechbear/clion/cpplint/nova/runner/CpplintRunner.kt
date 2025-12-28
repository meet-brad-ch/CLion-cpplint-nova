package com.github.itechbear.clion.cpplint.nova.runner

import com.github.itechbear.clion.cpplint.nova.quickfixes.QuickFixesManager
import com.github.itechbear.clion.cpplint.nova.settings.CpplintConfigurable
import com.github.itechbear.clion.cpplint.nova.settings.Settings
import com.github.itechbear.clion.cpplint.nova.util.CygwinUtil
import com.github.itechbear.clion.cpplint.nova.util.MinGWUtil
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import java.io.File
import java.io.IOException

/**
 * Core logic for executing cpplint.py and parsing results.
 */
object CpplintRunner {
    private val logger = Logger.getInstance(CpplintRunner::class.java)
    private val pattern = Regex("""^.+:([0-9]+):\s+(.+)\s+\[([^\]]+)+\]\s+\[([0-9]+)\]$""")

    // Throttle notifications to avoid spamming the user
    private var lastNotificationTime: Long = 0
    private const val NOTIFICATION_THROTTLE_MS = 60_000L // 1 minute between notifications

    /**
     * Sealed class representing the result of configuration validation.
     */
    private sealed class ConfigValidationResult {
        object Valid : ConfigValidationResult()
        data class Invalid(val message: String, val isPythonError: Boolean) : ConfigValidationResult()
    }

    /**
     * Validates the cpplint configuration (Python and cpplint paths).
     * Returns a validation result indicating success or the specific error.
     */
    private fun validateConfiguration(): ConfigValidationResult {
        val pythonPath = Settings.get(CpplintConfigurable.OPTION_KEY_PYTHON)
        val cpplintPath = Settings.get(CpplintConfigurable.OPTION_KEY_CPPLINT)

        // Check cpplint path
        if (cpplintPath.isNullOrEmpty()) {
            return ConfigValidationResult.Invalid(
                "Cpplint path is not configured.",
                isPythonError = false
            )
        }

        val cpplintFile = File(cpplintPath)
        if (!cpplintFile.exists()) {
            return ConfigValidationResult.Invalid(
                "Cpplint not found at: $cpplintPath",
                isPythonError = false
            )
        }

        // Check python path
        if (pythonPath.isNullOrEmpty()) {
            return ConfigValidationResult.Invalid(
                "Python path is not configured.",
                isPythonError = true
            )
        }

        val pythonFile = File(pythonPath)
        if (!pythonFile.exists()) {
            return ConfigValidationResult.Invalid(
                "Python not found at: $pythonPath\n\nPython may have been uninstalled or moved.",
                isPythonError = true
            )
        }

        return ConfigValidationResult.Valid
    }

    /**
     * Shows a notification to the user about configuration errors.
     * Notifications are throttled to avoid spamming.
     */
    private fun notifyConfigurationError(project: Project, message: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastNotificationTime < NOTIFICATION_THROTTLE_MS) {
            return // Throttled - don't show notification
        }
        lastNotificationTime = currentTime

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Cpplint")
            .createNotification(
                "Cpplint Configuration Error",
                message,
                NotificationType.WARNING
            )
            .addAction(object : com.intellij.notification.NotificationAction("Open Settings") {
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent, notification: com.intellij.notification.Notification) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, CpplintConfigurable::class.java)
                    notification.expire()
                }
            })

        notification.notify(project)
    }

    /**
     * Maps cpplint confidence level (1-5) to IntelliJ ProblemHighlightType.
     * Higher confidence = more severe highlighting.
     */
    private fun mapConfidenceToSeverity(confidence: Int): ProblemHighlightType {
        return when (confidence) {
            5 -> ProblemHighlightType.ERROR           // Confidence 5: Error (red underline)
            4 -> ProblemHighlightType.WARNING         // Confidence 4: Warning (yellow underline)
            3 -> ProblemHighlightType.WARNING         // Confidence 3: Warning (yellow underline)
            2 -> ProblemHighlightType.WEAK_WARNING    // Confidence 2: Weak warning (gray underline)
            1 -> ProblemHighlightType.WEAK_WARNING    // Confidence 1: Weak warning (gray underline)
            else -> ProblemHighlightType.WEAK_WARNING // Default: Weak warning
        }
    }

    /**
     * Extracts the expected header guard name from cpplint's error message.
     * Message format: "#ifndef header guard has wrong style, please use: GUARD_NAME"
     *
     * @param message The cpplint error message
     * @return The expected guard name, or null if it couldn't be extracted
     */
    private fun extractExpectedGuardName(message: String): String? {
        // Pattern to match "please use: GUARD_NAME"
        val pattern = Regex("please use:\\s+(\\w+)", RegexOption.IGNORE_CASE)
        val matchResult = pattern.find(message)
        return matchResult?.groupValues?.getOrNull(1)
    }

    fun lint(file: PsiFile, manager: InspectionManager, document: Document): List<ProblemDescriptor> {
        // Validate configuration before attempting to run
        when (val validationResult = validateConfiguration()) {
            is ConfigValidationResult.Invalid -> {
                notifyConfigurationError(file.project, validationResult.message)
                logger.warn("Cpplint configuration invalid: ${validationResult.message}")
                return emptyList()
            }
            is ConfigValidationResult.Valid -> {
                // Continue with execution
            }
        }

        val cpplintPath = Settings.get(CpplintConfigurable.OPTION_KEY_CPPLINT)!!
        var cpplintOptions = Settings.get(CpplintConfigurable.OPTION_KEY_CPPLINT_OPTIONS)

        val canonicalPath = file.project.basePath
        if (canonicalPath.isNullOrEmpty()) {
            logger.error("No valid base directory found!")
            return emptyList()
        }

        // First time users will not have this option set
        if (cpplintOptions == null) {
            cpplintOptions = ""
        }

        val args = buildCommandLineArgs(cpplintPath, cpplintOptions, file)
        return runCpplint(file, manager, document, canonicalPath, args)
    }

    private fun runCpplint(
        file: PsiFile,
        manager: InspectionManager,
        document: Document,
        canonicalPath: String,
        args: List<String>
    ): List<ProblemDescriptor> {
        val workingDirectory = File(canonicalPath)
        val processBuilder = ProcessBuilder(args).directory(workingDirectory)

        val process = try {
            processBuilder.start()
        } catch (e: IOException) {
            logger.error("Failed to run lint against file: ${file.virtualFile.canonicalPath}", e)
            // Provide user-friendly error message
            val errorMessage = when {
                e.message?.contains("CreateProcess error=2") == true ||
                e.message?.contains("No such file or directory") == true ->
                    "Failed to execute cpplint: Python or cpplint executable not found.\n\n" +
                    "Please verify your Python and cpplint paths in Settings."
                else ->
                    "Failed to execute cpplint: ${e.message}"
            }
            notifyConfigurationError(file.project, errorMessage)
            return emptyList()
        }

        val problemDescriptors = mutableListOf<ProblemDescriptor>()

        try {
            process.inputStream.bufferedReader().use { stdInput ->
                process.errorStream.bufferedReader().use { stdError ->
                    // Consume stdout
                    stdInput.lineSequence().forEach { }

                    // Parse stderr for cpplint violations
                    stdError.lineSequence().forEach { line ->
                        parseLintResult(file, manager, document, line)?.let {
                            problemDescriptors.add(it)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            logger.error("Failed to run lint against file: ${file.virtualFile.canonicalPath}", e)
            return emptyList()
        }

        return problemDescriptors
    }

    private fun buildCommandLineArgs(
        cpplint: String,
        cpplintOptions: String,
        file: PsiFile
    ): List<String> {
        val python = Settings.get(CpplintConfigurable.OPTION_KEY_PYTHON) ?: "python"
        var cppFilePath = file.virtualFile.canonicalPath ?: ""

        if (CygwinUtil.isCygwinEnvironment()) {
            cppFilePath = CygwinUtil.toCygwinPath(cppFilePath)
        }

        val args = mutableListOf<String>()

        if (MinGWUtil.isMinGWEnvironment()) {
            // MinGW: direct command
            args.add(python)
            args.add(cpplint)
            args.addAll(cpplintOptions.split("\\s+".toRegex()))
            args.add(cppFilePath)
        } else {
            // Unix/Cygwin: use bash
            args.add(CygwinUtil.getBashPath())
            args.add("-c")

            val joinedArgs = if (CygwinUtil.isCygwinEnvironment()) {
                "\"\\\"$python\\\" \\\"$cpplint\\\" $cpplintOptions \\\"$cppFilePath\\\"\""
            } else {
                "\"$python\" \"$cpplint\" $cpplintOptions \"$cppFilePath\""
            }
            args.add(joinedArgs)
        }

        return args
    }

    private fun parseLintResult(
        file: PsiFile,
        manager: InspectionManager,
        document: Document,
        line: String
    ): ProblemDescriptor? {
        val matchResult = pattern.matchEntire(line) ?: return null

        val (lineNumberStr, message, ruleName, confidenceStr) = matchResult.destructured
        var lineNumber = lineNumberStr.toInt()
        val confidence = confidenceStr.toIntOrNull() ?: 1  // Default to lowest confidence if parsing fails
        val lineCount = document.lineCount

        if (lineCount == 0) {
            return null
        }

        // Adjust line number to be within bounds
        lineNumber = when {
            lineNumber >= lineCount -> lineCount - 1
            lineNumber > 0 -> lineNumber - 1
            else -> 0
        }

        val errorMessage = "cpplint: $message"
        val lineStartOffset = document.getLineStartOffset(lineNumber)
        val lineEndOffset = document.getLineEndOffset(lineNumber)

        // Do not highlight empty whitespace prepended to lines
        val text = document.immutableCharSequence.subSequence(lineStartOffset, lineEndOffset).toString()
        val numberOfPrependedSpaces = text.length - text.trimStart().length

        // Get rule-specific fix (if available)
        // For build/header_guard, try to extract the expected guard name from the message
        val specificFix = if (ruleName == "build/header_guard") {
            val expectedGuardName = extractExpectedGuardName(message)
            com.github.itechbear.clion.cpplint.nova.quickfixes.AddHeaderGuardFix(expectedGuardName)
        } else {
            QuickFixesManager.get(ruleName)
        }

        // Always offer NOLINT suppression as a fix option
        val nolintFix = com.github.itechbear.clion.cpplint.nova.quickfixes.SuppressWithNoLintFix(ruleName)

        // Combine fixes: specific fix first (if available), then NOLINT
        val fixes = if (specificFix != null) {
            arrayOf(specificFix, nolintFix)
        } else {
            arrayOf(nolintFix)
        }

        // Map cpplint confidence level to IntelliJ severity
        val highlightType = mapConfidenceToSeverity(confidence)

        return manager.createProblemDescriptor(
            file,
            TextRange.create(lineStartOffset + numberOfPrependedSpaces, lineEndOffset),
            errorMessage,
            highlightType,
            true,
            *fixes
        )
    }
}
