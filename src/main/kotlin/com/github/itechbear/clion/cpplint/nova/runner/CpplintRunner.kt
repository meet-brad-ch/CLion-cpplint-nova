package com.github.itechbear.clion.cpplint.nova.runner

import com.github.itechbear.clion.cpplint.nova.quickfixes.QuickFixesManager
import com.github.itechbear.clion.cpplint.nova.settings.CpplintConfigurable
import com.github.itechbear.clion.cpplint.nova.settings.Settings
import com.github.itechbear.clion.cpplint.nova.util.CygwinUtil
import com.github.itechbear.clion.cpplint.nova.util.MinGWUtil
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.wm.StatusBar
import com.intellij.psi.PsiFile
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

/**
 * Core logic for executing cpplint.py and parsing results.
 */
object CpplintRunner {
    private val logger = Logger.getInstance(CpplintRunner::class.java)
    private val pattern = Regex("""^.+:([0-9]+):\s+(.+)\s+\[([^\]]+)+\]\s+\[([0-9]+)\]$""")

    fun lint(file: PsiFile, manager: InspectionManager, document: Document): List<ProblemDescriptor> {
        val cpplintPath = Settings.get(CpplintConfigurable.OPTION_KEY_CPPLINT)
        var cpplintOptions = Settings.get(CpplintConfigurable.OPTION_KEY_CPPLINT_OPTIONS)

        if (cpplintPath.isNullOrEmpty()) {
            StatusBar.Info.set("Please set path of cpplint.py first!", file.project)
            return emptyList()
        }

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

        val fix = QuickFixesManager.get(ruleName)

        return if (fix != null) {
            manager.createProblemDescriptor(
                file,
                TextRange.create(lineStartOffset + numberOfPrependedSpaces, lineEndOffset),
                errorMessage,
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                true,
                fix
            )
        } else {
            manager.createProblemDescriptor(
                file,
                TextRange.create(lineStartOffset + numberOfPrependedSpaces, lineEndOffset),
                errorMessage,
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                true
            )
        }
    }
}
