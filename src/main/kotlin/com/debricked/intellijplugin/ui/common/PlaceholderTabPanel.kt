package com.debricked.intellijplugin.ui.common

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

internal class PlaceholderTabPanel(
    title: String,
    description: String
) : JPanel(GridBagLayout()) {
    init {
        border = JBUI.Borders.empty(16)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border()),
                JBUI.Borders.empty(20, 24)
            )
            maximumSize = Dimension(420, Int.MAX_VALUE)
            add(JBLabel(title).apply {
                font = font.deriveFont(java.awt.Font.BOLD, 14f)
                alignmentX = Component.CENTER_ALIGNMENT
            })
            add(Box.createVerticalStrut(8))
            add(JBLabel(description).apply {
                alignmentX = Component.CENTER_ALIGNMENT
                foreground = JBColor.GRAY
            })
        })
    }
}

