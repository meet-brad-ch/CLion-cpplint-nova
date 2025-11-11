package com.github.itechbear.clion.cpplint.nova.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*

/**
 * Provides controller for the Settings Dialog.
 * Uses modern Kotlin UI DSL for building the settings panel.
 */
class CpplintConfigurable : BoundConfigurable("Cpplint") {

    companion object {
        const val OPTION_KEY_PYTHON = "python"
        const val OPTION_KEY_CPPLINT = "cpplint"
        const val OPTION_KEY_CPPLINT_OPTIONS = "cpplintOptions"
    }

    private var pythonPath: String = Settings.get(OPTION_KEY_PYTHON) ?: ""
    private var cpplintPath: String = Settings.get(OPTION_KEY_CPPLINT) ?: ""
    private var cpplintOptions: String = Settings.get(OPTION_KEY_CPPLINT_OPTIONS) ?: ""

    override fun createPanel(): DialogPanel {
        return panel {
            row("Python path:") {
                textFieldWithBrowseButton(
                    fileChooserDescriptor = FileChooserDescriptor(true, false, false, false, false, false)
                        .withTitle("Select Python Executable"),
                    project = null
                )
                    .bindText(::pythonPath)
                    .align(AlignX.FILL)
                    .comment("Path to Python executable")
            }

            row("cpplint.py path:") {
                textFieldWithBrowseButton(
                    fileChooserDescriptor = FileChooserDescriptor(true, false, false, false, false, false)
                        .withTitle("Select cpplint.py"),
                    project = null
                )
                    .bindText(::cpplintPath)
                    .align(AlignX.FILL)
                    .comment("Path to cpplint.py script")
            }

            row("cpplint.py options:") {
                textField()
                    .bindText(::cpplintOptions)
                    .align(AlignX.FILL)
                    .comment("Additional command line options for cpplint")
            }
        }
    }

    override fun apply() {
        super.apply()
        Settings.set(OPTION_KEY_PYTHON, pythonPath)
        Settings.set(OPTION_KEY_CPPLINT, cpplintPath)
        Settings.set(OPTION_KEY_CPPLINT_OPTIONS, cpplintOptions)
    }

    override fun reset() {
        pythonPath = Settings.get(OPTION_KEY_PYTHON) ?: ""
        cpplintPath = Settings.get(OPTION_KEY_CPPLINT) ?: ""
        cpplintOptions = Settings.get(OPTION_KEY_CPPLINT_OPTIONS) ?: ""
        super.reset()
    }
}
