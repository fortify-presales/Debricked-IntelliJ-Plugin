package com.debricked.intellijplugin.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe

class DebrickedCredentialStore {
    companion object {
        private const val SERVICE_NAME = "Debricked"

        // In-memory cache so background threads never call PasswordSafe directly.
        // Populated by loadFromStorage() (called on EDT at startup) and by setters.
        @Volatile private var _accessToken: String? = null
        @Volatile private var _password: String? = null

        /** Call this once on the EDT (e.g. tool window init) to warm the cache. */
        fun loadFromStorage() {
            _accessToken = PasswordSafe.instance.getPassword(CredentialAttributes("${SERVICE_NAME}_access_token"))
            _password    = PasswordSafe.instance.getPassword(CredentialAttributes("${SERVICE_NAME}_password"))
        }

        fun getAccessToken(): String? = _accessToken

        fun setAccessToken(token: String?) {
            _accessToken = token
            PasswordSafe.instance.setPassword(CredentialAttributes("${SERVICE_NAME}_access_token"), token)
        }

        fun getPassword(): String? = _password

        fun setPassword(password: String?) {
            _password = password
            PasswordSafe.instance.setPassword(CredentialAttributes("${SERVICE_NAME}_password"), password)
        }

        fun clearCredentials() {
            _accessToken = null
            _password = null
            PasswordSafe.instance.setPassword(CredentialAttributes("${SERVICE_NAME}_access_token"), null)
            PasswordSafe.instance.setPassword(CredentialAttributes("${SERVICE_NAME}_password"), null)
        }
    }
}
