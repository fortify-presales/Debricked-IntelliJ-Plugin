package com.debricked.intellijplugin.ui.dependency

import com.debricked.intellijplugin.domain.DependencyItem
import javax.swing.table.AbstractTableModel

private val COLUMN_NAMES = arrayOf("Name", "Version", "Ecosystem", "Licenses", "Vulnerabilities", "Scope")

/** Column indices matching [COLUMN_NAMES]. */
object DependencyColumns {
    const val NAME = 0
    const val VERSION = 1
    const val ECOSYSTEM = 2
    const val LICENSES = 3
    const val VULNERABILITIES = 4
    const val SCOPE = 5
}

class DependencyTableModel : AbstractTableModel() {

    private var allItems: List<DependencyItem> = emptyList()
    private var viewItems: List<DependencyItem> = emptyList()

    override fun getRowCount(): Int = viewItems.size
    override fun getColumnCount(): Int = COLUMN_NAMES.size
    override fun getColumnName(column: Int): String = COLUMN_NAMES.getOrElse(column) { "" }
    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        val item = viewItems.getOrNull(rowIndex) ?: return null
        return when (columnIndex) {
            DependencyColumns.NAME -> item.name
            DependencyColumns.VERSION -> item.version.ifBlank { "-" }
            DependencyColumns.ECOSYSTEM -> item.ecosystem ?: "-"
            DependencyColumns.LICENSES -> item.licenses.joinToString(", ").ifBlank { "-" }
            DependencyColumns.VULNERABILITIES -> item.vulnerabilityCount
            DependencyColumns.SCOPE -> if (item.isIndirect) "Transitive" else "Direct"
            else -> null
        }
    }

    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        DependencyColumns.VULNERABILITIES -> Int::class.java
        else -> String::class.java
    }

    fun setItems(items: List<DependencyItem>) {
        allItems = items
        viewItems = items
        fireTableDataChanged()
    }

    fun getItemAt(viewRow: Int): DependencyItem? = viewItems.getOrNull(viewRow)

    fun visibleCount(): Int = viewItems.size
}

