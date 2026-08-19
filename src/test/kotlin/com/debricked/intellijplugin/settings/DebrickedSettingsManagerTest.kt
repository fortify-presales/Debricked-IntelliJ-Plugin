package com.debricked.intellijplugin.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebrickedSettingsManagerTest {

    @Test
    fun pushRecentRepositoryId_keepsMostRecentFirst_withoutDuplicates() {
        val manager = DebrickedSettingsManager()

        manager.pushRecentRepositoryId("repo-a")
        manager.pushRecentRepositoryId("repo-b")
        manager.pushRecentRepositoryId("repo-a")

        assertEquals(listOf("repo-a", "repo-b"), manager.getRecentRepositoryIds())
    }

    @Test
    fun vulnerabilitiesViewState_roundTripsThroughSettings() {
        val manager = DebrickedSettingsManager()

        manager.setVulnerabilitiesVisibleColumns("NAME,CVSS,REVIEW_STATUS")
        manager.setVulnerabilitiesSortMode("CVSS")
        manager.setVulnerabilitiesGroupMode("NONE")
        manager.setVulnerabilitiesSearchText("log4j")
        manager.setVulnerabilitiesRowsPerPage(50)
        manager.setVulnerabilitiesDividerLocation(640)

        assertEquals("NAME,CVSS,REVIEW_STATUS", manager.getVulnerabilitiesVisibleColumns())
        assertEquals("CVSS", manager.getVulnerabilitiesSortMode())
        assertEquals("NONE", manager.getVulnerabilitiesGroupMode())
        assertEquals("log4j", manager.getVulnerabilitiesSearchText())
        assertEquals(50, manager.getVulnerabilitiesRowsPerPage())
        assertEquals(640, manager.getVulnerabilitiesDividerLocation())
    }

    @Test
    fun pushRecentRepositoryId_limitsHistorySize() {
        val manager = DebrickedSettingsManager()

        for (index in 1..60) {
            manager.pushRecentRepositoryId("repo-$index")
        }

        val recent = manager.getRecentRepositoryIds()
        assertEquals(50, recent.size)
        assertEquals("repo-60", recent.first())
        assertTrue("repo-1" !in recent)
    }

    @Test
    fun pushRecentRepositoryId_ignoresBlankInput_andTrimsWhitespace() {
        val manager = DebrickedSettingsManager()

        manager.pushRecentRepositoryId("   ")
        manager.pushRecentRepositoryId("  repo-a  ")

        assertEquals(listOf("repo-a"), manager.getRecentRepositoryIds())
    }

    @Test
    fun vulnerabilitiesDividerLocation_defaultsAndUpdates() {
        val manager = DebrickedSettingsManager()

        assertEquals(-1, manager.getVulnerabilitiesDividerLocation())
        manager.setVulnerabilitiesDividerLocation(720)
        assertEquals(720, manager.getVulnerabilitiesDividerLocation())
    }
}


