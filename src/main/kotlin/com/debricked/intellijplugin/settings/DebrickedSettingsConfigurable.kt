package com.debricked.intellijplugin.settings

import com.debricked.intellijplugin.api.DebrickedApiClient
import com.debricked.intellijplugin.core.DebrickedPluginManager
import com.debricked.intellijplugin.core.DebrickedSettingsNotifier
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

private data class RepositoryChoice(
    val id: String,
    val name: String,
    val organizationId: String
) {
    override fun toString(): String = "$name [$id]"
}

private data class ConnectionInputs(
    val apiUrl: String,
    val authMethod: DebrickedAuthMethod,
    val accessToken: String,
    val username: String,
    val password: String
)

class DebrickedSettingsConfigurable : Configurable {
    private val settingsManager = DebrickedSettingsManager.getInstance()
    private val apiClient = ApplicationManager.getApplication().getService(DebrickedApiClient::class.java)

    private var apiUrlField: JTextField? = null
    private var usernameField: JTextField? = null
    private var passwordField: JPasswordField? = null
    private var accessTokenField: JPasswordField? = null
    private var repositoryCombo: JComboBox<RepositoryChoice>? = null
    private var statusLabel: JLabel? = null
    private var connectionSummaryLabel: JLabel? = null
    private var verifyButton: JButton? = null
    private var refreshReposButton: JButton? = null

    private var accessTokenRadio: JRadioButton? = null
    private var userAuthRadio: JRadioButton? = null
    private var ssoRadio: JRadioButton? = null

    override fun getDisplayName(): String = "Debricked"

    override fun createComponent(): JComponent {
        val panel = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(12)
        }

        var row = 0
        addRow(panel, row++, "Server URL", buildFieldRow {
            apiUrlField = JTextField(settingsManager.getApiUrl(), 30).apply {
                maximumSize = Dimension(420, preferredSize.height)
            }
            apiUrlField!!
        })

        addRow(panel, row++, "Authentication Method", buildAuthMethodPanel())

        addRow(panel, row++, "Username", buildFieldRow {
            usernameField = JTextField(settingsManager.getUsername(), 30).apply {
                maximumSize = Dimension(420, preferredSize.height)
            }
            usernameField!!
        })

        addRow(panel, row++, "Password", buildFieldRow {
            passwordField = JPasswordField(DebrickedCredentialStore.getPassword() ?: "", 30).apply {
                maximumSize = Dimension(420, preferredSize.height)
            }
            passwordField!!
        })

        addRow(panel, row++, "Access Token", buildFieldRow {
            accessTokenField = JPasswordField(DebrickedCredentialStore.getAccessToken() ?: "", 30).apply {
                maximumSize = Dimension(420, preferredSize.height)
            }
            accessTokenField!!
        })

        // Verify button + status label row
        val verifyPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            verifyButton = JButton("Verify Connection").apply {
                addActionListener { verifyConnectionClicked() }
            }
            statusLabel = JLabel("Enter credentials and verify the connection.")
            add(verifyButton!!)
            add(Box.createHorizontalStrut(12))
            add(statusLabel!!)
        }
        addRow(panel, row++, "", verifyPanel)

        connectionSummaryLabel = JLabel(connectionSummaryText())
        addRow(panel, row++, "", connectionSummaryLabel!!)

        // Repository dropdown + Refresh button inline
        val repoPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            repositoryCombo = JComboBox<RepositoryChoice>().apply {
                preferredSize = Dimension(300, preferredSize.height)
                maximumSize = Dimension(380, preferredSize.height)
                isEnabled = false
            }
            repositoryCombo!!.addActionListener {
                val selected = repositoryCombo!!.selectedItem as? RepositoryChoice ?: return@addActionListener
                settingsManager.setRepositoryId(selected.id)
                settingsManager.setRepositoryName(selected.name)
                updateConnectionSummary()
            }
            refreshReposButton = JButton("Refresh Repositories").apply {
                addActionListener { loadRepositoriesClicked() }
            }
            add(repositoryCombo!!)
            add(Box.createHorizontalStrut(8))
            add(refreshReposButton!!)
        }
        addRow(panel, row, "Repository", repoPanel)

        applyAuthModeToForm(settingsManager.getAuthMethod())
        preloadConfiguredRepository()
        updateConnectionSummary()

        return panel
    }

    override fun isModified(): Boolean {
        val selected = repositoryCombo?.selectedItem as? RepositoryChoice
        return apiUrlField?.text != settingsManager.getApiUrl() ||
            usernameField?.text != settingsManager.getUsername() ||
            (accessTokenField?.password?.concatToString() ?: "") != (DebrickedCredentialStore.getAccessToken() ?: "") ||
            (passwordField?.password?.concatToString() ?: "") != (DebrickedCredentialStore.getPassword() ?: "") ||
            selected?.id != settingsManager.getRepositoryId() ||
            currentAuthMethod() != settingsManager.getAuthMethod()
    }

    override fun apply() {
        settingsManager.setApiUrl(apiUrlField?.text ?: "https://debricked.com/api")
        settingsManager.setAuthMethod(currentAuthMethod())
        settingsManager.setUsername(usernameField?.text ?: "")
        DebrickedCredentialStore.setAccessToken((accessTokenField?.password?.concatToString() ?: "").ifBlank { null })
        DebrickedCredentialStore.setPassword((passwordField?.password?.concatToString() ?: "").ifBlank { null })

        val selected = repositoryCombo?.selectedItem as? RepositoryChoice
        if (selected != null) {
            settingsManager.setRepositoryId(selected.id)
            settingsManager.setRepositoryName(selected.name)
        }

        // Notify all open projects via MessageBus — the reliable way to trigger refresh
        ApplicationManager.getApplication().messageBus
            .syncPublisher(DebrickedSettingsNotifier.TOPIC)
            .onSettingsApplied()
    }

    override fun reset() {
        apiUrlField?.text = settingsManager.getApiUrl()
        usernameField?.text = settingsManager.getUsername()
        accessTokenField?.text = DebrickedCredentialStore.getAccessToken() ?: ""
        passwordField?.text = DebrickedCredentialStore.getPassword() ?: ""
        applyAuthModeToForm(settingsManager.getAuthMethod())
        preloadConfiguredRepository()
        updateConnectionSummary()
        setStatus("Enter credentials and verify the connection.")
    }

    override fun disposeUIResources() {
        apiUrlField = null
        usernameField = null
        passwordField = null
        accessTokenField = null
        repositoryCombo = null
        statusLabel = null
        connectionSummaryLabel = null
        verifyButton = null
        refreshReposButton = null
        accessTokenRadio = null
        userAuthRadio = null
        ssoRadio = null
    }

    private fun buildAuthMethodPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        val group = ButtonGroup()
        accessTokenRadio = JRadioButton("Access Token Authentication")
        userAuthRadio = JRadioButton("User Authentication")
        ssoRadio = JRadioButton("SSO Authentication (future)").apply { isEnabled = false }

        group.add(accessTokenRadio!!)
        group.add(userAuthRadio!!)
        group.add(ssoRadio!!)

        accessTokenRadio!!.addActionListener { applyAuthModeToForm(DebrickedAuthMethod.ACCESS_TOKEN) }
        userAuthRadio!!.addActionListener { applyAuthModeToForm(DebrickedAuthMethod.USER_PASSWORD) }

        panel.add(accessTokenRadio!!)
        panel.add(userAuthRadio!!)
        panel.add(ssoRadio!!)
        return panel
    }

    private fun buildFieldRow(factory: () -> JComponent): JComponent {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(factory())
        }
    }

    private fun addRow(panel: JPanel, row: Int, label: String, component: JComponent) {
        panel.add(JBLabel(label), GridBagConstraints().apply {
            gridx = 0; gridy = row
            anchor = GridBagConstraints.NORTHWEST
            fill = GridBagConstraints.NONE
            insets = Insets(8, 0, 8, 16)
        })
        panel.add(component, GridBagConstraints().apply {
            gridx = 1; gridy = row
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
            insets = Insets(8, 0, 8, 0)
        })
    }

    private fun applyAuthModeToForm(method: DebrickedAuthMethod) {
        accessTokenRadio?.isSelected = method == DebrickedAuthMethod.ACCESS_TOKEN
        userAuthRadio?.isSelected = method == DebrickedAuthMethod.USER_PASSWORD
        ssoRadio?.isSelected = method == DebrickedAuthMethod.SSO

        accessTokenField?.isEnabled = method == DebrickedAuthMethod.ACCESS_TOKEN
        usernameField?.isEnabled = method == DebrickedAuthMethod.USER_PASSWORD
        passwordField?.isEnabled = method == DebrickedAuthMethod.USER_PASSWORD
    }

    private fun currentAuthMethod(): DebrickedAuthMethod = when {
        userAuthRadio?.isSelected == true -> DebrickedAuthMethod.USER_PASSWORD
        ssoRadio?.isSelected == true -> DebrickedAuthMethod.SSO
        else -> DebrickedAuthMethod.ACCESS_TOKEN
    }

    private fun snapshotInputs(): ConnectionInputs = ConnectionInputs(
        apiUrl = apiUrlField?.text?.ifBlank { "https://debricked.com/api" } ?: "https://debricked.com/api",
        authMethod = currentAuthMethod(),
        accessToken = accessTokenField?.password?.concatToString() ?: "",
        username = usernameField?.text ?: "",
        password = passwordField?.password?.concatToString() ?: ""
    )

    // ModalityState.any() is REQUIRED here — invokeLater without it is suppressed inside modal Settings dialog
    private fun onEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any())
    }

    private fun verifyConnectionClicked() {
        val inputs = snapshotInputs()
        settingsManager.setApiUrl(inputs.apiUrl)
        settingsManager.setAuthMethod(inputs.authMethod)
        settingsManager.setUsername(inputs.username)
        setBusy(true)
        setStatus("Authenticating...")
        Thread({
            try {
                apiClient.verifyConnection(inputs.apiUrl, inputs.authMethod, inputs.accessToken, inputs.username, inputs.password)
                onEdt {
                    setStatus("Connection verified.")
                    setBusy(false)
                }
            } catch (e: Exception) {
                onEdt {
                    setStatus("Connection failed: ${e.message ?: "unknown error"}")
                    setBusy(false)
                }
            }
        }, "debricked-verify").apply { isDaemon = true }.start()
    }

    private fun loadRepositoriesClicked() {
        val inputs = snapshotInputs()
        settingsManager.setApiUrl(inputs.apiUrl)
        settingsManager.setAuthMethod(inputs.authMethod)
        settingsManager.setUsername(inputs.username)
        setBusy(true)
        setStatus("Loading repositories...")
        Thread({
            try {
                val repositories = apiClient.connectAndGetRepositories(
                    inputs.apiUrl, inputs.authMethod, inputs.accessToken, inputs.username, inputs.password
                )
                val choices = repositories.mapNotNull { repo ->
                    if (repo.id.isBlank()) null
                    else RepositoryChoice(repo.id, repo.name.ifBlank { repo.id }, repo.organizationId)
                }.sortedBy { it.name.lowercase() }

                onEdt {
                    val model = DefaultComboBoxModel<RepositoryChoice>()
                    choices.forEach { model.addElement(it) }
                    repositoryCombo?.model = model
                    repositoryCombo?.isEnabled = choices.isNotEmpty()
                    selectConfiguredRepository()
                    setStatus(if (choices.isNotEmpty()) "Loaded ${choices.size} repositories — select one below." else "Connected, but no repositories found.")
                    updateConnectionSummary()
                    setBusy(false)
                }
            } catch (e: Exception) {
                onEdt {
                    setStatus("Repository load failed: ${e.message ?: "unknown error"}")
                    setBusy(false)
                }
            }
        }, "debricked-load-repos").apply { isDaemon = true }.start()
    }

    private fun setBusy(isBusy: Boolean) {
        verifyButton?.isEnabled = !isBusy
        refreshReposButton?.isEnabled = !isBusy
    }

    private fun preloadConfiguredRepository() {
        val configuredId = settingsManager.getRepositoryId()
        val configuredName = settingsManager.getRepositoryName()
        val model = DefaultComboBoxModel<RepositoryChoice>()
        if (configuredId.isNotBlank()) {
            model.addElement(RepositoryChoice(configuredId, configuredName.ifBlank { configuredId }, settingsManager.getOrganizationId()))
            repositoryCombo?.isEnabled = true
        } else {
            repositoryCombo?.isEnabled = false
        }
        repositoryCombo?.model = model
        selectConfiguredRepository()
    }

    private fun selectConfiguredRepository() {
        val configuredId = settingsManager.getRepositoryId()
        if (configuredId.isBlank()) return
        val model = repositoryCombo?.model as? DefaultComboBoxModel<RepositoryChoice> ?: return
        for (i in 0 until model.size) {
            if (model.getElementAt(i).id == configuredId) {
                repositoryCombo?.selectedIndex = i
                return
            }
        }
    }

    private fun setStatus(message: String) {
        statusLabel?.text = message
    }

    private fun connectionSummaryText(): String {
        val repositoryName = settingsManager.getRepositoryName()
        val repositoryId = settingsManager.getRepositoryId()
        return when {
            repositoryId.isBlank() -> "No Debricked repository selected."
            repositoryName.isBlank() -> "Connected repository ID: $repositoryId"
            else -> "Connected repository: $repositoryName [$repositoryId]"
        }
    }

    private fun updateConnectionSummary() {
        connectionSummaryLabel?.text = connectionSummaryText()
    }

}
