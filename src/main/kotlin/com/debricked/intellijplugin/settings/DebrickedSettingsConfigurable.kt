package com.debricked.intellijplugin.settings

import com.debricked.intellijplugin.api.DebrickedApiClient
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

private data class ConnectionInputs(
    val apiUrl: String,
    val authMethod: DebrickedAuthMethod,
    val accessToken: String,
    val username: String,
    val password: String
)

/**
 * Debricked Settings panel — authentication only.
 * Repository selection has moved to the Debricked tool window.
 */
class DebrickedSettingsConfigurable : Configurable {
    private val settingsManager = DebrickedSettingsManager.getInstance()
    private val apiClient = ApplicationManager.getApplication().getService(DebrickedApiClient::class.java)

    private var apiUrlField: JTextField? = null
    private var usernameField: JTextField? = null
    private var passwordField: JPasswordField? = null
    private var accessTokenField: JPasswordField? = null
    private var statusLabel: JLabel? = null
    private var verifyButton: JButton? = null

    private var accessTokenRadio: JRadioButton? = null
    private var userAuthRadio: JRadioButton? = null
    private var ssoRadio: JRadioButton? = null
    private var defaultTabCombo: JComboBox<DebrickedDefaultTab>? = null

    override fun getDisplayName(): String = "Debricked"

    override fun createComponent(): JComponent {
        val panel = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(12)
        }

        var row = 0
        addRow(panel, row++, "Server URL", buildFieldRow {
            apiUrlField = JTextField(settingsManager.getApiUrl(), 35).apply {
                maximumSize = Dimension(460, preferredSize.height)
            }
            apiUrlField!!
        })

        addRow(panel, row++, "Authentication", buildAuthMethodPanel())

        addRow(panel, row++, "Default Tab", buildFieldRow {
            defaultTabCombo = JComboBox(DebrickedDefaultTab.values()).apply {
                maximumSize = Dimension(220, preferredSize.height)
                selectedItem = settingsManager.getDefaultTab()
                renderer = object : DefaultListCellRenderer() {
                    override fun getListCellRendererComponent(
                        list: JList<*>?,
                        value: Any?,
                        index: Int,
                        isSelected: Boolean,
                        cellHasFocus: Boolean
                    ) = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).also {
                        text = if (value is DebrickedDefaultTab) defaultTabLabel(value) else "Select tab"
                    }
                }
            }
            defaultTabCombo!!
        })

        addRow(panel, row++, "Username", buildFieldRow {
            usernameField = JTextField(settingsManager.getUsername(), 35).apply {
                maximumSize = Dimension(460, preferredSize.height)
            }
            usernameField!!
        })

        addRow(panel, row++, "Password", buildFieldRow {
            passwordField = JPasswordField(DebrickedCredentialStore.getPassword() ?: "", 35).apply {
                maximumSize = Dimension(460, preferredSize.height)
            }
            passwordField!!
        })

        addRow(panel, row++, "Access Token", buildFieldRow {
            accessTokenField = JPasswordField(DebrickedCredentialStore.getAccessToken() ?: "", 35).apply {
                maximumSize = Dimension(460, preferredSize.height)
            }
            accessTokenField!!
        })

        val verifyPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            verifyButton = JButton("Verify Connection").apply {
                addActionListener { verifyConnectionClicked() }
            }
            statusLabel = JLabel("Enter credentials and click Verify.")
            add(verifyButton!!)
            add(Box.createHorizontalStrut(12))
            add(statusLabel!!)
        }
        addRow(panel, row++, "", verifyPanel)

        // Filler to push rows to the top
        panel.add(JPanel(), GridBagConstraints().apply {
            gridx = 0; gridy = row; gridwidth = 2
            weighty = 1.0; fill = GridBagConstraints.VERTICAL
        })

        applyAuthModeToForm(settingsManager.getAuthMethod())
        return panel
    }

    override fun isModified(): Boolean =
        apiUrlField?.text != settingsManager.getApiUrl() ||
        usernameField?.text != settingsManager.getUsername() ||
        (accessTokenField?.password?.concatToString() ?: "") != (DebrickedCredentialStore.getAccessToken() ?: "") ||
        (passwordField?.password?.concatToString() ?: "") != (DebrickedCredentialStore.getPassword() ?: "") ||
        currentDefaultTab() != settingsManager.getDefaultTab() ||
        currentAuthMethod() != settingsManager.getAuthMethod()

    override fun apply() {
        settingsManager.setApiUrl(apiUrlField?.text ?: "https://debricked.com/api")
        settingsManager.setAuthMethod(currentAuthMethod())
        settingsManager.setUsername(usernameField?.text ?: "")
        settingsManager.setDefaultTab(currentDefaultTab())
        DebrickedCredentialStore.setAccessToken((accessTokenField?.password?.concatToString() ?: "").ifBlank { null })
        DebrickedCredentialStore.setPassword((passwordField?.password?.concatToString() ?: "").ifBlank { null })

        // Notify all open projects so the tool window refreshes (credentials may have changed)
        ApplicationManager.getApplication().messageBus
            .syncPublisher(DebrickedSettingsNotifier.TOPIC)
            .onSettingsApplied()
    }

    override fun reset() {
        apiUrlField?.text = settingsManager.getApiUrl()
        usernameField?.text = settingsManager.getUsername()
        accessTokenField?.text = DebrickedCredentialStore.getAccessToken() ?: ""
        passwordField?.text = DebrickedCredentialStore.getPassword() ?: ""
        defaultTabCombo?.selectedItem = settingsManager.getDefaultTab()
        applyAuthModeToForm(settingsManager.getAuthMethod())
        setStatus("Enter credentials and click Verify.")
    }

    override fun disposeUIResources() {
        apiUrlField = null
        usernameField = null
        passwordField = null
        accessTokenField = null
        statusLabel = null
        verifyButton = null
        accessTokenRadio = null
        userAuthRadio = null
        ssoRadio = null
        defaultTabCombo = null
    }

    private fun buildAuthMethodPanel(): JComponent {
        val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        val group = ButtonGroup()
        accessTokenRadio = JRadioButton("Access Token")
        userAuthRadio = JRadioButton("Username / Password")
        ssoRadio = JRadioButton("SSO (future)").apply { isEnabled = false }

        listOf(accessTokenRadio!!, userAuthRadio!!, ssoRadio!!).forEach { group.add(it) }

        accessTokenRadio!!.addActionListener { applyAuthModeToForm(DebrickedAuthMethod.ACCESS_TOKEN) }
        userAuthRadio!!.addActionListener { applyAuthModeToForm(DebrickedAuthMethod.USER_PASSWORD) }

        listOf(accessTokenRadio!!, userAuthRadio!!, ssoRadio!!).forEach { panel.add(it) }
        return panel
    }

    private fun buildFieldRow(factory: () -> JComponent): JComponent =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(factory())
        }

    private fun addRow(panel: JPanel, row: Int, label: String, component: JComponent) {
        panel.add(JBLabel(label), GridBagConstraints().apply {
            gridx = 0; gridy = row
            anchor = GridBagConstraints.NORTHWEST
            insets = Insets(8, 0, 4, 16)
        })
        panel.add(component, GridBagConstraints().apply {
            gridx = 1; gridy = row
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
            insets = Insets(8, 0, 4, 0)
        })
    }

    private fun applyAuthModeToForm(method: DebrickedAuthMethod) {
        accessTokenRadio?.isSelected = method == DebrickedAuthMethod.ACCESS_TOKEN
        userAuthRadio?.isSelected    = method == DebrickedAuthMethod.USER_PASSWORD
        ssoRadio?.isSelected         = method == DebrickedAuthMethod.SSO

        accessTokenField?.isEnabled = method == DebrickedAuthMethod.ACCESS_TOKEN
        usernameField?.isEnabled    = method == DebrickedAuthMethod.USER_PASSWORD
        passwordField?.isEnabled    = method == DebrickedAuthMethod.USER_PASSWORD
    }

    private fun currentAuthMethod(): DebrickedAuthMethod = when {
        userAuthRadio?.isSelected == true -> DebrickedAuthMethod.USER_PASSWORD
        ssoRadio?.isSelected == true      -> DebrickedAuthMethod.SSO
        else -> DebrickedAuthMethod.ACCESS_TOKEN
    }

    private fun currentDefaultTab(): DebrickedDefaultTab =
        defaultTabCombo?.selectedItem as? DebrickedDefaultTab ?: DebrickedDefaultTab.DASHBOARD

    private fun defaultTabLabel(tab: DebrickedDefaultTab): String = when (tab) {
        DebrickedDefaultTab.DASHBOARD -> "Dashboard"
        DebrickedDefaultTab.VULNERABILITIES -> "Vulnerabilities"
        DebrickedDefaultTab.DEPENDENCIES -> "Dependencies"
        DebrickedDefaultTab.LICENSES -> "Licenses"
    }

    private fun snapshotInputs() = ConnectionInputs(
        apiUrl      = apiUrlField?.text?.ifBlank { "https://debricked.com/api" } ?: "https://debricked.com/api",
        authMethod  = currentAuthMethod(),
        accessToken = accessTokenField?.password?.concatToString() ?: "",
        username    = usernameField?.text ?: "",
        password    = passwordField?.password?.concatToString() ?: ""
    )

    // ModalityState.any() is required — invokeLater is suppressed inside modal Settings dialog otherwise
    private fun onEdt(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any())

    private fun verifyConnectionClicked() {
        val inputs = snapshotInputs()
        settingsManager.setApiUrl(inputs.apiUrl)
        settingsManager.setAuthMethod(inputs.authMethod)
        settingsManager.setUsername(inputs.username)
        verifyButton?.isEnabled = false
        setStatus("Verifying...")
        Thread({
            try {
                apiClient.verifyConnection(inputs.apiUrl, inputs.authMethod, inputs.accessToken, inputs.username, inputs.password)
                // Cache the credentials immediately so they're available in-memory
                DebrickedCredentialStore.setAccessToken(inputs.accessToken.ifBlank { null })
                DebrickedCredentialStore.setPassword(inputs.password.ifBlank { null })
                onEdt {
                    setStatus("✓ Connection verified. Use the repository selector in the Debricked panel.")
                    verifyButton?.isEnabled = true
                }
            } catch (e: Exception) {
                onEdt {
                    setStatus("✗ ${e.message ?: "Connection failed"}")
                    verifyButton?.isEnabled = true
                }
            }
        }, "debricked-verify").apply { isDaemon = true }.start()
    }

    private fun setStatus(message: String) {
        statusLabel?.text = message
    }
}
