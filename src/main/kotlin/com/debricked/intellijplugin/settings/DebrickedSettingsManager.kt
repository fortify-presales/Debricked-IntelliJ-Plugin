package com.debricked.intellijplugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

enum class DebrickedAuthMethod {
    ACCESS_TOKEN,
    USER_PASSWORD,
    SSO
}

enum class DebrickedDefaultTab {
    DASHBOARD,
    VULNERABILITIES,
    DEPENDENCIES,
    LICENSES
}

data class DebrickedProjectSettings(
    var apiUrl: String = "https://debricked.com/api",
    var authMethod: DebrickedAuthMethod = DebrickedAuthMethod.ACCESS_TOKEN,
    var username: String = "",
    var organizationId: String = "",
    var repositoryId: String = "",
    var repositoryName: String = "",
    var defaultBranch: String? = null,
    var selectedBranchId: String = "",
    var selectedBranchName: String = "",
    var defaultTab: DebrickedDefaultTab = DebrickedDefaultTab.DASHBOARD
)

@Service(Service.Level.APP)
@State(
    name = "DebrickedSettingsManager",
    storages = [Storage("debricked.xml")]
)
class DebrickedSettingsManager :
    PersistentStateComponent<DebrickedProjectSettings> {

    private var settings = DebrickedProjectSettings()

    override fun getState(): DebrickedProjectSettings {
        return settings
    }

    override fun loadState(state: DebrickedProjectSettings) {
        XmlSerializerUtil.copyBean(state, settings)
    }

    fun getApiUrl(): String = settings.apiUrl

    fun setApiUrl(url: String) {
        settings.apiUrl = url
    }

    fun getAuthMethod(): DebrickedAuthMethod = settings.authMethod

    fun setAuthMethod(method: DebrickedAuthMethod) {
        settings.authMethod = method
    }

    fun getUsername(): String = settings.username

    fun setUsername(username: String) {
        settings.username = username
    }

    fun getOrganizationId(): String = settings.organizationId

    fun setOrganizationId(id: String) {
        settings.organizationId = id
    }

    fun getRepositoryId(): String = settings.repositoryId

    fun setRepositoryId(id: String) {
        settings.repositoryId = id
    }

    fun getRepositoryName(): String = settings.repositoryName

    fun setRepositoryName(name: String) {
        settings.repositoryName = name
    }

    fun getDefaultBranch(): String? = settings.defaultBranch

    fun setDefaultBranch(branch: String?) {
        settings.defaultBranch = branch
    }

    fun getSelectedBranchId(): String = settings.selectedBranchId

    fun setSelectedBranchId(branchId: String) {
        settings.selectedBranchId = branchId
    }

    fun getSelectedBranchName(): String = settings.selectedBranchName

    fun setSelectedBranchName(branchName: String) {
        settings.selectedBranchName = branchName
    }

    fun getDefaultTab(): DebrickedDefaultTab = settings.defaultTab

    fun setDefaultTab(defaultTab: DebrickedDefaultTab) {
        settings.defaultTab = defaultTab
    }

    fun isConfigured(): Boolean {
        return settings.repositoryId.isNotEmpty()
    }

    fun reset() {
        settings = DebrickedProjectSettings()
    }

    companion object {
        fun getInstance(): DebrickedSettingsManager {
            return ApplicationManager.getApplication()
                .getService(DebrickedSettingsManager::class.java)
        }
    }
}
