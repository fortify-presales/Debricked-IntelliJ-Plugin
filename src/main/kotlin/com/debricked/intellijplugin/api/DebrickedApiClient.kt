package com.debricked.intellijplugin.api

import com.debricked.intellijplugin.domain.*
import com.debricked.intellijplugin.settings.DebrickedCredentialStore
import com.debricked.intellijplugin.settings.DebrickedAuthMethod
import com.debricked.intellijplugin.settings.DebrickedSettingsManager
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@Service(Service.Level.APP)
class DebrickedApiClient {

    private val LOG = logger<DebrickedApiClient>()
    private val gson = Gson()

    private var cachedJwt: String? = null

    private var jwtExpiresAt: Long = 0
    private var lastRefreshError: String? = null

    fun verifyConnection(
        apiUrl: String,
        authMethod: DebrickedAuthMethod,
        accessToken: String,
        username: String,
        password: String
    ) {
        val jwt = obtainJwtDirect(apiUrl, authMethod, accessToken, username, password)
        cachedJwt = jwt
        jwtExpiresAt = System.currentTimeMillis() + (3600 * 1000)
        lastRefreshError = null
    }
    /**
     * Connect-time method: obtains a JWT directly from provided credentials (no PasswordSafe),
     * caches it for subsequent calls, then returns the repository list.
     */
    fun connectAndGetRepositories(
        apiUrl: String,
        authMethod: DebrickedAuthMethod,
        accessToken: String,
        username: String,
        password: String
    ): List<DebrickedRepository> {
        val jwt = obtainJwtDirect(apiUrl, authMethod, accessToken, username, password)
        cachedJwt = jwt
        jwtExpiresAt = System.currentTimeMillis() + (3600 * 1000)
        lastRefreshError = null

        val fullUrl = "$apiUrl/1.0/open/repositories/get-repositories"
        val response = httpGet(fullUrl, jwt)
        return parseRepositoriesResponse(response)
    }

    private fun obtainJwtDirect(
        apiUrl: String,
        authMethod: DebrickedAuthMethod,
        accessToken: String,
        username: String,
        password: String
    ): String {
        val (loginPath, body) = when (authMethod) {
            DebrickedAuthMethod.ACCESS_TOKEN -> {
                if (accessToken.isBlank()) throw IllegalArgumentException("Access token is required")
                "login_refresh" to "refresh_token=${URLEncoder.encode(accessToken, "UTF-8")}"
            }
            DebrickedAuthMethod.USER_PASSWORD -> {
                if (username.isBlank()) throw IllegalArgumentException("Username is required")
                if (password.isBlank()) throw IllegalArgumentException("Password is required")
                "login_check" to "_username=${URLEncoder.encode(username, "UTF-8")}&_password=${URLEncoder.encode(password, "UTF-8")}"
            }
            DebrickedAuthMethod.SSO -> throw IllegalArgumentException("SSO not yet implemented")
        }

        val connection = URL("$apiUrl/$loginPath").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.doOutput = true
        val postData = body.toByteArray(Charsets.UTF_8)
        connection.outputStream.use { it.write(postData) }

        val statusCode = connection.responseCode
        if (statusCode != 200) {
            val err = try { connection.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
            throw ApiException("Authentication failed ($statusCode): $err", statusCode)
        }

        val response = connection.inputStream.bufferedReader().use { it.readText() }
        return gson.fromJson(response, JsonObject::class.java)
            .get("token")?.asString
            ?: throw ApiException("No token in auth response", 200)
    }

    private fun httpGet(fullUrl: String, jwt: String): String {
        val connection = URL(fullUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "Bearer $jwt")
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = 15000
        connection.readTimeout = 15000

        val statusCode = connection.responseCode
        if (statusCode == 200) {
            return connection.inputStream.bufferedReader().use { it.readText() }
        }
        val err = try { connection.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
        throw ApiException("Request to $fullUrl failed ($statusCode): $err", statusCode)
    }

    fun getRepositories(): List<DebrickedRepository> {
        return try {
            val response = makeAuthenticatedRequest("GET", "/repositories/get-repositories")
            parseRepositoriesResponse(response)
        } catch (e: Exception) {
            LOG.warn("Failed to fetch repositories: ${e.message}", e)
            emptyList()
        }
    }

    fun getCommits(repositoryId: String, branchName: String? = null): List<DebrickedCommit> {
        return try {
            val params = buildString {
                append("repositoryId=$repositoryId")
                if (!branchName.isNullOrBlank()) {
                    append("&branchId=$branchName")
                }
            }
            val response = makeAuthenticatedRequest("GET", "/commits/get-commits?$params")
            parseCommitsResponse(response)
        } catch (e: Exception) {
            LOG.warn("Failed to fetch commits: ${e.message}", e)
            emptyList()
        }
    }

    fun getVulnerabilities(
        repositoryId: String,
        commitId: String? = null,
        branchName: String? = null
    ): List<VulnerabilityFinding> {
        return try {
            val allFindings = mutableListOf<VulnerabilityFinding>()
            var page = 1
            val rowsPerPage = 100

            while (true) {
                val result = getVulnerabilitiesPage(
                    repositoryId = repositoryId,
                    commitId = commitId,
                    branchName = branchName,
                    query = VulnerabilityQuery(page = page, rowsPerPage = rowsPerPage)
                )
                if (result.findings.isEmpty()) break
                allFindings.addAll(result.findings)
                if (!result.hasNext) break
                page++
            }

            LOG.info("Fetched ${allFindings.size} vulnerabilities for repository $repositoryId over $page page(s)")
            allFindings
        } catch (e: Exception) {
            LOG.warn("Failed to fetch vulnerabilities: ${e.message}", e)
            emptyList()
        }
    }

    fun getVulnerabilitiesPage(
        repositoryId: String,
        commitId: String? = null,
        branchName: String? = null,
        query: VulnerabilityQuery = VulnerabilityQuery()
    ): VulnerabilityPageResult {
        val params = buildString {
            append("repositoryId=$repositoryId")
            append("&rowsPerPage=${query.rowsPerPage.coerceAtLeast(1)}")
            append("&page=${query.page.coerceAtLeast(1)}")
            if (commitId != null && commitId.all { it.isDigit() }) {
                append("&commitId=$commitId")
            }
            if (!branchName.isNullOrBlank()) {
                append("&branchId=${URLEncoder.encode(branchName, "UTF-8")}")
            }
            if (query.search.isNotBlank()) {
                append("&search=${URLEncoder.encode(query.search, "UTF-8")}")
            }
            if (query.sortColumn.isNotBlank()) {
                append("&sortColumn=${URLEncoder.encode(query.sortColumn, "UTF-8")}")
            }
            if (query.order.isNotBlank()) {
                append("&order=${URLEncoder.encode(query.order, "UTF-8")}")
            }
        }
        val response = makeAuthenticatedRequest("GET", "/vulnerabilities/get-vulnerabilities?$params")
        return parseVulnerabilitiesPageResponse(
            json = response,
            repositoryId = repositoryId,
            commitId = commitId,
            branchName = branchName,
            requestedPage = query.page.coerceAtLeast(1),
            requestedRowsPerPage = query.rowsPerPage.coerceAtLeast(1)
        )
    }

    fun getBranches(repositoryId: String): List<DebrickedBranch> {
        return try {
            val response = makeAuthenticatedRequest("GET", "/repository/$repositoryId/get-branches")
            parseBranchesResponse(response)
        } catch (e: Exception) {
            LOG.warn("Failed to fetch branches: ${e.message}", e)
            emptyList()
        }
    }

    private fun makeAuthenticatedRequest(method: String, endpoint: String, body: String? = null): String {
        ensureValidJwt()

        val jwt = cachedJwt
            ?: throw IllegalStateException("JWT token not available")

        val settings = DebrickedSettingsManager.getInstance()
        val apiUrl = settings.getApiUrl()
        val fullUrl = "$apiUrl/1.0/open$endpoint"

        val connection = URL(fullUrl).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer $jwt")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
        }

        return when (connection.responseCode) {
            401 -> {
                refreshJwt()
                makeAuthenticatedRequest(method, endpoint, body)
            }
            200, 201 -> connection.inputStream.bufferedReader().use { it.readText() }
            204 -> ""
            else -> throw ApiException(
                "API request failed with status ${connection.responseCode}",
                connection.responseCode
            )
        }
    }

    private fun refreshJwt() {
        try {
            val settings = DebrickedSettingsManager.getInstance()
            val apiUrl = settings.getApiUrl()

            val (loginPath, body) = when (settings.getAuthMethod()) {
                DebrickedAuthMethod.ACCESS_TOKEN -> {
                    val accessToken = DebrickedCredentialStore.getAccessToken()
                        ?: throw IllegalStateException("Access token not configured")
                    "login_refresh" to "refresh_token=${URLEncoder.encode(accessToken, "UTF-8")}"
                }
                DebrickedAuthMethod.USER_PASSWORD -> {
                    val username = settings.getUsername()
                    val password = DebrickedCredentialStore.getPassword()
                        ?: throw IllegalStateException("Password not configured")
                    if (username.isBlank()) {
                        throw IllegalStateException("Username not configured")
                    }
                    "login_check" to "_username=${URLEncoder.encode(username, "UTF-8")}&_password=${URLEncoder.encode(password, "UTF-8")}"
                }
                DebrickedAuthMethod.SSO -> {
                    throw IllegalStateException("SSO authentication is not implemented yet")
                }
            }

            val connection = URL("$apiUrl/$loginPath").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            val postData = body.toByteArray(Charsets.UTF_8)
            connection.doOutput = true
            connection.outputStream.write(postData)

            if (connection.responseCode != 200) {
                throw ApiException("JWT refresh failed", connection.responseCode)
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = gson.fromJson(response, JsonObject::class.java)
            val newJwt = json.get("token")?.asString
                ?: throw ApiException("No token in refresh response", 400)

            cachedJwt = newJwt
            jwtExpiresAt = System.currentTimeMillis() + (3600 * 1000)
            lastRefreshError = null
        } catch (e: Exception) {
            lastRefreshError = e.message
            LOG.error("Failed to refresh JWT: ${e.message}", e)
            throw e
        }
    }

    private fun ensureValidJwt() {
        if (jwtExpiresAt < System.currentTimeMillis() + 300000) {
            refreshJwt()
        }
    }

    private fun parseRepositoriesResponse(json: String): List<DebrickedRepository> {
        return try {
            val root = gson.fromJson(json, JsonElement::class.java)
            val repos = mutableListOf<DebrickedRepository>()

            when {
                root.isJsonArray -> root.asJsonArray.forEach { element ->
                    if (element.isJsonObject) {
                        repos.add(parseRepository(element.asJsonObject))
                    }
                }
                root.isJsonObject -> {
                    val objectRoot = root.asJsonObject
                    listOf("repositories", "data", "items", "results", "rows").forEach { key ->
                        if (objectRoot.has(key) && objectRoot.get(key).isJsonArray) {
                            objectRoot.getAsJsonArray(key).forEach { element ->
                                if (element.isJsonObject) {
                                    repos.add(parseRepository(element.asJsonObject))
                                }
                            }
                        }
                    }
                }
            }

            repos
        } catch (e: Exception) {
            LOG.error("Error parsing repositories: ${e.message}", e)
            emptyList()
        }
    }

    private fun parseRepository(repo: JsonObject): DebrickedRepository {
        return DebrickedRepository(
            id = textValue(repo.get("id")) ?: textValue(repo.get("repositoryId")) ?: "",
            name = textValue(repo.get("name")) ?: "",
            organizationId = textValue(repo.get("organizationId")) ?: "",
            defaultBranch = textValue(repo.get("defaultBranch"))
        )
    }

    private fun parseCommitsResponse(json: String): List<DebrickedCommit> {
        return try {
            val root = gson.fromJson(json, JsonElement::class.java)
            val commits = mutableListOf<DebrickedCommit>()

            when {
                root.isJsonArray -> root.asJsonArray.forEach { element ->
                    if (element.isJsonObject) {
                        commits.add(parseCommit(element.asJsonObject))
                    }
                }
                root.isJsonObject -> {
                    val objectRoot = root.asJsonObject
                    listOf("commits", "data", "items", "results", "rows").forEach { key ->
                        if (objectRoot.has(key) && objectRoot.get(key).isJsonArray) {
                            objectRoot.getAsJsonArray(key).forEach { element ->
                                if (element.isJsonObject) {
                                    commits.add(parseCommit(element.asJsonObject))
                                }
                            }
                        }
                    }
                }
            }

            commits
        } catch (e: Exception) {
            LOG.error("Error parsing commits: ${e.message}", e)
            emptyList()
        }
    }

    private fun parseBranchesResponse(json: String): List<DebrickedBranch> {
        return try {
            val root = gson.fromJson(json, JsonElement::class.java)
            val branches = mutableListOf<DebrickedBranch>()

            fun addBranch(element: JsonElement) {
                if (element.isJsonObject) {
                    branches.add(parseBranch(element.asJsonObject))
                } else if (element.isJsonPrimitive) {
                    val value = element.asString
                    branches.add(DebrickedBranch(value, value))
                }
            }

            when {
                root.isJsonArray -> root.asJsonArray.forEach(::addBranch)
                root.isJsonObject -> {
                    val objectRoot = root.asJsonObject
                    listOf("branches", "data", "items", "results", "rows").forEach { key ->
                        if (objectRoot.has(key) && objectRoot.get(key).isJsonArray) {
                            objectRoot.getAsJsonArray(key).forEach(::addBranch)
                        }
                    }
                }
            }

            branches.distinctBy { it.id.ifBlank { it.name } }
        } catch (e: Exception) {
            LOG.error("Error parsing branches: ${e.message}", e)
            emptyList()
        }
    }

    private fun parseCommit(commit: JsonObject): DebrickedCommit {
        return DebrickedCommit(
            id = commit.get("id")?.asString ?: "",
            sha = commit.get("sha")?.asString ?: commit.get("name")?.asString ?: "",
            branch = commit.get("branch")?.asString ?: "",
            timestamp = commit.get("timestamp")?.asString ?: commit.get("releaseDate")?.asString ?: ""
        )
    }

    private fun parseBranch(branch: JsonObject): DebrickedBranch {
        return DebrickedBranch(
            id = textValue(branch.get("id"))
                ?: textValue(branch.get("branchId"))
                ?: textValue(branch.get("value"))
                ?: textValue(branch.get("name"))
                ?: "",
            name = textValue(branch.get("name"))
                ?: textValue(branch.get("branchName"))
                ?: textValue(branch.get("shortName"))
                ?: textValue(branch.get("id"))
                ?: textValue(branch.get("branchId"))
                ?: ""
        )
    }

    private fun parseVulnerabilitiesPageResponse(
        json: String,
        repositoryId: String,
        commitId: String?,
        branchName: String?,
        requestedPage: Int,
        requestedRowsPerPage: Int
    ): VulnerabilityPageResult {
        return try {
            val findings = mutableListOf<VulnerabilityFinding>()
            val root = gson.fromJson(json, JsonElement::class.java)
            var totalCount: Int? = null

            when {
                root.isJsonArray -> root.asJsonArray.forEach { element ->
                    if (element.isJsonObject) {
                        findings.add(parseVulnerabilityToFinding(element.asJsonObject, repositoryId, commitId, branchName))
                    }
                }
                root.isJsonObject -> {
                    val objectRoot = root.asJsonObject
                    totalCount = intValue(objectRoot.get("total"))
                        ?: intValue(objectRoot.get("totalCount"))
                        ?: intValue(objectRoot.get("totalRows"))
                        ?: intValue(objectRoot.get("count"))
                        ?: intValue(objectRoot.get("recordsTotal"))
                        ?: intValue(objectRoot.get("filteredCount"))
                        ?: jsonObjectValue(objectRoot, "pagination")?.let { pagination ->
                            intValue(pagination.get("total"))
                                ?: intValue(pagination.get("totalCount"))
                                ?: intValue(pagination.get("count"))
                        }
                    listOf("vulnerabilities", "data", "items", "results", "rows").forEach { key ->
                        if (objectRoot.has(key) && objectRoot.get(key).isJsonArray) {
                            objectRoot.getAsJsonArray(key).forEach { element ->
                                if (element.isJsonObject) {
                                    findings.add(parseVulnerabilityToFinding(element.asJsonObject, repositoryId, commitId, branchName))
                                }
                            }
                        }
                    }
                }
            }

            if (findings.isEmpty()) {
                LOG.debug("No vulnerabilities parsed from response: ${json.take(1000)}")
            }

            val page = requestedPage.coerceAtLeast(1)
            val rowsPerPage = requestedRowsPerPage.coerceAtLeast(1)
            val hasNext = when {
                totalCount != null -> page * rowsPerPage < totalCount
                else -> findings.size >= rowsPerPage
            }
            VulnerabilityPageResult(
                findings = findings,
                page = page,
                rowsPerPage = rowsPerPage,
                totalCount = totalCount,
                hasNext = hasNext
            )
        } catch (e: Exception) {
            LOG.error("Error parsing vulnerabilities: ${e.message}", e)
            VulnerabilityPageResult(
                findings = emptyList(),
                page = requestedPage.coerceAtLeast(1),
                rowsPerPage = requestedRowsPerPage.coerceAtLeast(1),
                totalCount = 0,
                hasNext = false
            )
        }
    }

    private fun parseVulnerabilityToFinding(
        vuln: JsonObject,
        repositoryId: String,
        commitId: String?,
        branchName: String?
    ): VulnerabilityFinding {
        val vulnerabilityObject = jsonObjectValue(vuln, "vulnerability")
        val cvssSource = vulnerabilityObject ?: vuln
        val cvssType = textValue(jsonObjectValue(vuln, "cvss")?.get("type"))
            ?: textValue(jsonObjectValue(vulnerabilityObject, "cvss")?.get("type"))
        val genericCvssScore = extractCvssScore(cvssSource, "cvss")
            ?: extractCvssScore(vuln, "cvss")
        val ecosystemStr = textValue(vuln.get("ecosystem")) ?: "MAVEN"
        val ecosystem = try {
            Ecosystem.valueOf(ecosystemStr.uppercase())
        } catch (e: Exception) {
            Ecosystem.MAVEN
        }

        val deepScores = extractCvssScoresDeep(cvssSource)
        val cvss3Score = extractCvssScore(cvssSource, "cvss3", "cvssV3", "cvss3Score", "cvssv3", "cvss3BaseScore", "cvssV3BaseScore")
            ?: extractCvssScore(vuln, "cvss3", "cvssV3", "cvss3Score", "cvssv3")
            ?: extractCvssScoreFromNested(cvssSource, listOf("cvss3", "cvssv3", "cvss_3"))
            ?: deepScores.first
            ?: if (cvssType != null && listOf("critical", "high", "medium", "low").contains(cvssType.lowercase())) genericCvssScore else null
        val cvss2Score = extractCvssScore(cvssSource, "cvss2", "cvssV2", "cvss2Score", "cvssv2", "cvss2BaseScore", "cvssV2BaseScore")
            ?: extractCvssScore(vuln, "cvss2", "cvssV2", "cvss2Score", "cvssv2")
            ?: extractCvssScoreFromNested(cvssSource, listOf("cvss2", "cvssv2", "cvss_2"))
            ?: deepScores.second
            ?: if (cvss3Score == null) genericCvssScore else null
        val severity = parseSeverity(vuln, cvss3Score, cvss2Score, vulnerabilityObject)
        val reviewStatus = parseReviewStatus(vuln.get("reviewStatus"))
            ?: parseReviewStatus(vuln.get("vulnerabilityStatus"))
            ?: parseReviewStatus(vulnerabilityObject?.get("reviewStatus"))
            ?: parseReviewStatus(vulnerabilityObject?.get("status"))
        val pausedUntil = textValue(jsonObjectValue(vuln, "vulnerabilityStatus")?.get("pausedUntil"))
            ?: textValue(jsonObjectValue(vulnerabilityObject, "reviewStatus")?.get("pausedUntil"))
        val introducedAt = longValue(vuln.get("discovered"))
            ?: longValue(vulnerabilityObject?.get("discovered"))
        val reachabilityMessage = textValue(vuln.get("reachAnalysisMessage"))
            ?: textValue(vulnerabilityObject?.get("reachAnalysisMessage"))
        val reachablePath = parseReachablePath(vuln, vulnerabilityObject, reachabilityMessage)
        val exploited = parseExploited(vuln, vulnerabilityObject)

        val affectedDependencies = extractAffectedDependencies(vuln)
        val firstDependency = affectedDependencies.firstOrNull()
        val dependencyName = firstDependency?.name ?: extractDependencyName(vuln)
        val dependencyVersion = firstDependency?.version ?: extractDependencyVersion(vuln)
        val vulnerabilityName = extractVulnerabilityName(vuln)
        val vulnerabilityId = extractVulnerabilityInternalId(vuln)
        val debrickedCommitId = extractDebrickedCommitId(vuln)
            ?: commitId?.takeIf { it.all(Char::isDigit) }
        val packageName = dependencyName ?: vulnerabilityName ?: ""
        val version = dependencyVersion ?: textValue(vuln.get("version")) ?: ""
        val dependencies = affectedDependencies.ifEmpty {
            if (packageName.isBlank()) {
                emptyList()
            } else {
                listOf(AffectedDependency(packageName, version.ifBlank { null }))
            }
        }

        return VulnerabilityFinding(
            id = textValue(vuln.get("id"))
                ?: textValue(vuln.get("vulnerabilityId"))
                ?: vulnerabilityName
                ?: dependencyName
                ?: "",
            vulnerabilityId = vulnerabilityId,
            debrickedCommitId = debrickedCommitId,
            title = vulnerabilityName,
            ecosystem = ecosystem,
            packageName = packageName,
            groupId = textValue(vuln.get("groupId")),
            version = version,
            affectedDependencies = dependencies,
            severity = severity,
            fixedVersion = textValue(vuln.get("fixedVersion"))
                ?: textValue(vuln.get("suggestedVersion"))
                ?: textValue(vuln.get("upgradeVersion")),
            description = textValue(vuln.get("description")) ?: textValue(vulnerabilityObject?.get("description")),
            cveId = textValue(vuln.get("cveId")) ?: textValue(vulnerabilityObject?.get("cveId")),
            reviewStatus = reviewStatus,
            pausedUntil = pausedUntil,
            introducedAt = introducedAt,
            reachablePath = reachablePath,
            reachabilityMessage = reachabilityMessage,
            exploited = exploited,
            cvss2Score = cvss2Score,
            cvss3Score = cvss3Score,
            exploitabilityScore = cvss3Score ?: cvss2Score,
            scanContext = DebrickedScanContext(
                repositoryId = repositoryId,
                repositoryName = "",
                branchName = branchName,
                commitSha = commitId,
                scanId = textValue(vuln.get("scanId")),
                displayedCommitSha = commitId,
                isExactCommitMatch = commitId != null,
                fallbackReason = null
            )
        )
    }

    private fun parseSeverity(
        vuln: JsonObject,
        cvss3Score: Double?,
        cvss2Score: Double?,
        vulnerabilityObject: JsonObject?
    ): Severity {
        val raw = textValue(vuln.get("severity"))
            ?: textValue(vulnerabilityObject?.get("severity"))
            ?: textValue(jsonObjectValue(vuln, "cvss")?.get("type"))
            ?: textValue(jsonObjectValue(vulnerabilityObject, "cvss")?.get("type"))
            ?: textValue(vuln.get("cvss"))
            ?: textValue(vulnerabilityObject?.get("cvss"))
        val explicit = raw?.uppercase()?.let { value ->
            try {
                Severity.valueOf(value)
            } catch (_: Exception) {
                null
            }
        }
        if (explicit != null) return explicit
        if (cvss3Score != null) return severityFromCvss3(cvss3Score)
        if (cvss2Score != null) return severityFromCvss2(cvss2Score)
        return Severity.UNKNOWN
    }

    private fun parseReachablePath(
        vuln: JsonObject,
        vulnerabilityObject: JsonObject?,
        message: String?
    ): String {
        val reachability = textValue(vuln.get("reachabilityAnalysis"))
            ?: textValue(vulnerabilityObject?.get("reachabilityAnalysis"))
        if (message != null) {
            val m = message.lowercase()
            if (m.contains("not_been_run") || m.contains("not_available")) return "Unknown"
            if (m.contains("reachable")) return "Reachable"
            if (m.contains("not_reachable")) return "Not reachable"
        }
        val value = reachability?.toDoubleOrNull()
        return when {
            value == null -> "Unknown"
            value >= 1.0 -> "Reachable"
            value > 0.0 -> "Potentially reachable"
            else -> "Not reachable"
        }
    }

    private fun parseExploited(vuln: JsonObject, vulnerabilityObject: JsonObject?): Boolean? {
        val raw = textValue(vuln.get("cisaKevExploited"))
            ?: textValue(vulnerabilityObject?.get("cisaKevExploited"))
            ?: return null
        if (raw.isBlank()) return false
        return when (raw.lowercase()) {
            "true", "yes", "1", "exploited" -> true
            "false", "no", "0", "not exploited" -> false
            else -> false
        }
    }

    private fun parseReviewStatus(element: JsonElement?): String? {
        if (element == null || element.isJsonNull) return null
        return when {
            element.isJsonPrimitive -> element.asString
            element.isJsonObject -> {
                val obj = element.asJsonObject
                textValue(obj.get("type"))
                    ?: textValue(obj.get("status"))
                    ?: textValue(obj.get("value"))
            }
            else -> null
        }
    }

    private fun severityFromCvss3(score: Double): Severity = when {
        score >= 9.0 -> Severity.CRITICAL
        score >= 7.0 -> Severity.HIGH
        score >= 4.0 -> Severity.MEDIUM
        score > 0.0 -> Severity.LOW
        else -> Severity.UNKNOWN
    }

    private fun severityFromCvss2(score: Double): Severity = when {
        score >= 7.0 -> Severity.HIGH
        score >= 4.0 -> Severity.MEDIUM
        score > 0.0 -> Severity.LOW
        else -> Severity.UNKNOWN
    }

    private fun extractCvssScore(vuln: JsonObject, vararg keys: String): Double? {
        keys.forEach { key ->
            val direct = numberValue(vuln.get(key))
            if (direct != null) return direct
            val nested = vuln.get(key)
            if (nested != null && nested.isJsonObject) {
                numberValue(nested.asJsonObject.get("score"))?.let { return it }
                numberValue(nested.asJsonObject.get("baseScore"))?.let { return it }
                numberValue(nested.asJsonObject.get("value"))?.let { return it }
                numberValue(nested.asJsonObject.get("text"))?.let { return it }
            }
        }
        return null
    }

    private fun extractCvssScoreFromNested(source: JsonObject, keyHints: List<String>): Double? {
        val queue = ArrayDeque<com.google.gson.JsonElement>()
        source.entrySet().forEach { queue.add(it.value) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current.isJsonObject) {
                val obj = current.asJsonObject
                obj.entrySet().forEach { (key, value) ->
                    val normalized = key.lowercase()
                    if (keyHints.any { normalized.contains(it) }) {
                        numberValue(value)?.let { return it }
                        if (value.isJsonObject) {
                            numberValue(value.asJsonObject.get("score"))?.let { return it }
                            numberValue(value.asJsonObject.get("baseScore"))?.let { return it }
                            numberValue(value.asJsonObject.get("value"))?.let { return it }
                        }
                    }
                    if (value.isJsonObject || value.isJsonArray) {
                        queue.add(value)
                    }
                }
            } else if (current.isJsonArray) {
                current.asJsonArray.forEach { entry ->
                    if (entry.isJsonObject || entry.isJsonArray) queue.add(entry)
                }
            }
        }
        return null
    }

    private fun extractCvssScoresDeep(source: JsonObject): Pair<Double?, Double?> {
        var cvss3: Double? = null
        var cvss2: Double? = null
        val queue = ArrayDeque<com.google.gson.JsonElement>()
        queue.add(source)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current.isJsonObject) {
                val obj = current.asJsonObject
                val markerValues = listOf("method", "type", "version", "name", "source", "vector", "scheme")
                    .mapNotNull { key -> textValue(obj.get(key))?.lowercase() }
                val scoreCandidate = numberValue(obj.get("score"))
                    ?: numberValue(obj.get("baseScore"))
                    ?: numberValue(obj.get("value"))
                    ?: numberValue(obj.get("cvssScore"))
                    ?: numberValue(obj.get("cvssBaseScore"))

                if (scoreCandidate != null && markerValues.isNotEmpty()) {
                    if (cvss3 == null && markerValues.any { it.contains("cvss3") || it.contains("cvss v3") }) {
                        cvss3 = scoreCandidate
                    }
                    if (cvss2 == null && markerValues.any { it.contains("cvss2") || it.contains("cvss v2") }) {
                        cvss2 = scoreCandidate
                    }
                }

                obj.entrySet().forEach { (_, value) ->
                    if (value.isJsonObject || value.isJsonArray) {
                        queue.add(value)
                    }
                }
            } else if (current.isJsonArray) {
                current.asJsonArray.forEach { entry ->
                    if (entry.isJsonObject || entry.isJsonArray) queue.add(entry)
                }
            }
        }
        return Pair(cvss3, cvss2)
    }

    private fun numberValue(element: com.google.gson.JsonElement?): Double? {
        if (element == null || element.isJsonNull) return null
        return if (element.isJsonPrimitive) {
            try {
                element.asDouble
            } catch (_: Exception) {
                val text = element.asString
                text.toDoubleOrNull()
                    ?: Regex("""-?\d+(\.\d+)?""").find(text)?.value?.toDoubleOrNull()
            }
        } else {
            null
        }
    }

    private fun longValue(element: com.google.gson.JsonElement?): Long? {
        if (element == null || element.isJsonNull) return null
        return if (element.isJsonPrimitive) {
            try {
                element.asLong
            } catch (_: Exception) {
                element.asString.toLongOrNull()
            }
        } else {
            null
        }
    }

    private fun intValue(element: com.google.gson.JsonElement?): Int? {
        if (element == null || element.isJsonNull) return null
        return if (element.isJsonPrimitive) {
            try {
                element.asInt
            } catch (_: Exception) {
                element.asString.toIntOrNull()
            }
        } else {
            null
        }
    }

    private fun extractVulnerabilityName(vuln: JsonObject): String? {
        val nameElement = vuln.get("name") ?: return null
        return if (nameElement.isJsonObject) {
            val nameObject = nameElement.asJsonObject
            textValue(nameObject.get("name"))
                ?: textValue(nameObject.get("shortName"))
        } else {
            textValue(nameElement)
        }
    }

    private fun extractVulnerabilityInternalId(vuln: JsonObject): String? {
        val candidates = listOf(
            textValue(vuln.get("vulnerabilityId")),
            textValue(vuln.get("id")),
            textValue(jsonObjectValue(vuln, "vulnerability")?.get("id")),
            textValue(jsonObjectValue(vuln, "vulnerability")?.get("vulnerabilityId")),
            extractIdFromVulnerabilityLink(vuln)
        )
        return candidates.firstOrNull { !it.isNullOrBlank() && it.all(Char::isDigit) }
    }

    private fun extractDebrickedCommitId(vuln: JsonObject): String? {
        val commitObject = jsonObjectValue(vuln, "commit")
        val vulnerabilityObject = jsonObjectValue(vuln, "vulnerability")
        val candidates = listOf(
            textValue(vuln.get("commitId")),
            textValue(vuln.get("scanCommitId")),
            textValue(vulnerabilityObject?.get("commitId")),
            textValue(vulnerabilityObject?.get("scanCommitId")),
            textValue(commitObject?.get("id")),
            textValue(commitObject?.get("commitId")),
            textValue(vuln.get("scanId")),
            extractCommitIdFromLinks(vuln)
        )
        return candidates.firstOrNull { !it.isNullOrBlank() && it.all(Char::isDigit) }
    }

    private fun extractIdFromVulnerabilityLink(vuln: JsonObject): String? {
        val link = textValue(jsonObjectValue(vuln, "name")?.get("link"))
            ?: textValue(jsonObjectValue(vuln, "name")?.get("absoluteLink"))
            ?: return null
        return Regex("/vulnerability/(\\d+)").find(link)?.groupValues?.getOrNull(1)
    }

    private fun extractCommitIdFromLinks(vuln: JsonObject): String? {
        val links = mutableListOf<String>()
        textValue(jsonObjectValue(vuln, "name")?.get("link"))?.let { links += it }
        textValue(jsonObjectValue(vuln, "name")?.get("absoluteLink"))?.let { links += it }
        vuln.getAsJsonArray("dependencies")?.forEach { element ->
            if (element.isJsonObject) {
                textValue(element.asJsonObject.get("link"))?.let { links += it }
                textValue(element.asJsonObject.get("absoluteLink"))?.let { links += it }
            }
        }
        links.forEach { link ->
            Regex("[?&]commitId=(\\d+)").find(link)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    private fun extractDependencyName(vuln: JsonObject): String? {
        val dependencies = vuln.getAsJsonArray("dependencies") ?: return null
        val firstDependency = dependencies.firstOrNull { it.isJsonObject }?.asJsonObject ?: return null
        return textValue(firstDependency.get("shortName"))
            ?: textValue(firstDependency.get("name"))
    }

    private fun extractDependencyVersion(vuln: JsonObject): String? {
        val dependencies = vuln.getAsJsonArray("dependencies") ?: return textValue(vuln.get("version"))
        val firstDependency = dependencies.firstOrNull { it.isJsonObject }?.asJsonObject ?: return textValue(vuln.get("version"))
        return textValue(firstDependency.get("version"))
            ?: textValue(firstDependency.get("shortVersion"))
            ?: textValue(vuln.get("version"))
    }

    private fun extractAffectedDependencies(vuln: JsonObject): List<AffectedDependency> {
        val dependencies = vuln.getAsJsonArray("dependencies") ?: return emptyList()
        return dependencies.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val dependency = element.asJsonObject
            val name = textValue(dependency.get("shortName"))
                ?: textValue(dependency.get("name"))
                ?: return@mapNotNull null
            val version = textValue(dependency.get("version"))
                ?: textValue(dependency.get("shortVersion"))
            AffectedDependency(name, version?.ifBlank { null })
        }.distinctBy { "${it.name.lowercase()}@${it.version.orEmpty().lowercase()}" }
    }

    private fun textValue(element: com.google.gson.JsonElement?): String? {
        if (element == null || element.isJsonNull) return null
        return if (element.isJsonPrimitive) {
            element.asString
        } else if (element.isJsonObject) {
            val obj = element.asJsonObject
            textValue(obj.get("name"))
                ?: textValue(obj.get("id"))
                ?: textValue(obj.get("value"))
        } else {
            null
        }
    }

    fun getVulnerabilityRefSummary(vulnerabilityId: String): List<VulnerabilitySummarySource> {
        val root = readJsonObject("/vulnerability/$vulnerabilityId/refsummary")
        return root.entrySet().mapNotNull { (key, value) ->
            if (!value.isJsonObject) return@mapNotNull null
            val obj = value.asJsonObject
            VulnerabilitySummarySource(
                key = key,
                category = textValue(obj.get("category")) ?: key,
                title = textValue(obj.get("title")) ?: key,
                description = textValue(obj.get("description")) ?: "",
                explanation = textValue(obj.get("explanation")),
                link = textValue(obj.get("link")),
                missing = boolValue(obj.get("missing")) ?: false
            )
        }.filter { !it.missing && it.description.isNotBlank() }
    }

    fun getVulnerabilityCveSummary(vulnerabilityId: String): List<VulnerabilityScoreSummary> {
        val root = readJsonObject("/vulnerability/$vulnerabilityId/cvesummary")
        return root.entrySet().mapNotNull { (_, value) ->
            if (!value.isJsonObject) return@mapNotNull null
            val obj = value.asJsonObject
            VulnerabilityScoreSummary(
                category = textValue(obj.get("category")) ?: "",
                label = textValue(obj.get("label")) ?: "",
                scoreText = textValue(obj.get("score")) ?: "N/A",
                highlighted = boolValue(obj.get("highlighted")) ?: false
            )
        }
    }

    fun getVulnerabilityCvssDetails(vulnerabilityId: String): VulnerabilityCvssDetails {
        val root = readJsonObject("/vulnerability/$vulnerabilityId/cvssdetails")
        return VulnerabilityCvssDetails(explanation = textValue(root.get("explanation")))
    }

    fun getVulnerabilityDates(vulnerabilityId: String, repositoryId: String, isDatabase: Boolean): VulnerabilityDates {
        val suffix = if (isDatabase) "?repositoryId=$repositoryId&isDatabase=true" else "?repositoryId=$repositoryId"
        val root = readJsonObject("/vulnerability/$vulnerabilityId/dates$suffix")
        return VulnerabilityDates(
            discoveredAt = longValue(jsonObjectValue(root, "discovered")?.get("date")),
            publishedAt = longValue(jsonObjectValue(root, "published")?.get("date")),
            updatedAt = longValue(jsonObjectValue(root, "updated")?.get("date"))
        )
    }

    fun getVulnerabilityAffectedDependencies(
        vulnerabilityId: String,
        repositoryId: String,
        commitId: String?
    ): List<AffectedDependency> {
        val params = buildString {
            append("?repositoryId=$repositoryId")
            if (!commitId.isNullOrBlank()) append("&commitId=$commitId")
        }
        val root = readJsonObject("/vulnerability/$vulnerabilityId/affected-dependencies$params")
        val dependencies = root.getAsJsonArray("dependencies") ?: return emptyList()
        return dependencies.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            val name = textValue(obj.get("shortName")) ?: textValue(obj.get("name")) ?: return@mapNotNull null
            AffectedDependency(name, textValue(obj.get("version")) ?: textValue(obj.get("shortVersion")))
        }
    }

    fun getVulnerabilityFiles(
        vulnerabilityId: String,
        repositoryId: String,
        commitId: String?
    ): List<VulnerabilityFileRef> {
        val params = buildString {
            append("?repositoryId=$repositoryId")
            if (!commitId.isNullOrBlank()) append("&commitId=$commitId")
        }
        val root = readJson("/vulnerability/$vulnerabilityId/files$params")
        val items = when {
            root == null || root.isJsonNull -> emptyList()
            root.isJsonArray -> root.asJsonArray.toList()
            root.isJsonObject -> listOf(root)
            else -> emptyList()
        }
        return items.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            val id = textValue(obj.get("id")) ?: return@mapNotNull null
            val name = textValue(obj.get("name")) ?: return@mapNotNull null
            VulnerabilityFileRef(id = id, name = name, url = textValue(obj.get("url")))
        }
    }

    fun getVulnerabilityDependencyTree(
        vulnerabilityId: String,
        fileId: String,
        repositoryId: String,
        commitId: String?
    ): VulnerabilityDependencyTree? {
        val params = buildString {
            append("?repositoryId=$repositoryId")
            if (!commitId.isNullOrBlank()) append("&commitId=$commitId")
        }
        val root = readJson("/vulnerability/$vulnerabilityId/files/$fileId/dependency-tree$params") ?: return null
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject
        val trees = obj.getAsJsonArray("trees")?.mapNotNull { parseDependencyTreeNode(it) } ?: emptyList()
        return VulnerabilityDependencyTree(
            fileName = textValue(obj.get("name")),
            fileUrl = textValue(obj.get("url")),
            roots = trees
        )
    }

    fun getVulnerabilityRepositoryInformation(vulnerabilityId: String, repositoryId: String): List<VulnerabilityRepositoryStatus> {
        val root = readJsonObject("/vulnerability/$vulnerabilityId/repository-information?repositoryId=$repositoryId")
        val repositories = root.getAsJsonArray("repositories") ?: return emptyList()
        return repositories.mapNotNull { parseRepositoryStatus(it) }
    }

    fun getVulnerabilityReferences(vulnerabilityId: String): List<VulnerabilityReferenceLink> {
        val root = readJsonObject("/vulnerability/$vulnerabilityId/references")
        val references = root.getAsJsonArray("references") ?: return emptyList()
        return references.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            val title = textValue(obj.get("title")) ?: textValue(obj.get("link")) ?: return@mapNotNull null
            val link = textValue(obj.get("link")) ?: return@mapNotNull null
            val tags = obj.getAsJsonArray("tags")?.mapNotNull { textValue(it) } ?: emptyList()
            VulnerabilityReferenceLink(title = title, link = link, domain = textValue(obj.get("domain")), tags = tags)
        }
    }

    fun getVulnerabilityRootFixes(
        vulnerabilityId: String,
        repositoryId: String,
        commitId: String?
    ): VulnerabilityRootFixes {
        val suffix = commitId?.takeIf { it.isNotBlank() }?.let { "?commitId=$it" } ?: ""
        val root = readJsonObject("/vulnerability/$vulnerabilityId/repositories/$repositoryId/root-fixes$suffix")
        val fixes = linkedMapOf<String, String>()
        jsonObjectValue(root, "fixes")?.entrySet()?.forEach { (key, value) ->
            textValue(value)?.let { fixes[key] = it }
        }
        val commands = root.getAsJsonArray("commands")?.mapNotNull { textValue(it) } ?: emptyList()
        return VulnerabilityRootFixes(
            rootFixesCount = intValue(root.get("rootFixesCount")) ?: fixes.size,
            fixes = fixes,
            commands = commands,
            isReady = boolValue(root.get("isReady")) ?: false
        )
    }

    fun getVulnerabilityReviewStatus(vulnerabilityId: String, repositoryId: String): VulnerabilityReviewStatusInfo {
        val root = readJsonObject("/vulnerability/$vulnerabilityId/review-status?repositoryId=$repositoryId")
        val repositories = root.getAsJsonArray("repositories")?.mapNotNull { parseRepositoryStatus(it) } ?: emptyList()
        return VulnerabilityReviewStatusInfo(
            repositoryStatuses = repositories,
            enforceComment = boolValue(root.get("enforceComment")) ?: false,
            commentMinLength = intValue(root.get("commentMinLength")),
            oldComment = textValue(root.get("oldComment")),
            oldCommentAuthor = textValue(root.get("oldCommentAuthor"))
        )
    }

    fun setVulnerabilityReviewStatus(
        vulnerabilityId: String,
        repositoryId: String,
        type: String,
        comment: String?
    ) {
        val body = JsonObject().apply {
            addProperty("repoId", repositoryId.toIntOrNull())
            addProperty("type", type)
            if (!comment.isNullOrBlank()) {
                addProperty("comment", comment)
            }
        }
        makeAuthenticatedRequest("POST", "/vulnerability/$vulnerabilityId/set-review-status", gson.toJson(body))
    }

    fun getVulnerabilityReachabilityData(vulnerabilityId: String, commitId: String): VulnerabilityReachabilityDetails {
        return try {
            val root = readJsonObject("/vulnerability/$vulnerabilityId/reachability-analysis/$commitId/data")
            VulnerabilityReachabilityDetails(
                supported = true,
                reachAnalysisLanguage = textValue(root.get("reachAnalysisLanguage")),
                reachAnalysisMessage = textValue(root.get("reachAnalysisMessage")),
                reachAnalysis = textValue(root.get("reachAnalysis"))
            )
        } catch (e: ApiException) {
            if (e.statusCode == 403) {
                VulnerabilityReachabilityDetails(supported = false)
            } else {
                throw e
            }
        }
    }

    private fun parseRepositoryStatus(element: JsonElement): VulnerabilityRepositoryStatus? {
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        val id = textValue(obj.get("id")) ?: return null
        val name = textValue(obj.get("name")) ?: return null
        val branches = obj.getAsJsonArray("branches")?.mapNotNull { branchElement ->
            if (!branchElement.isJsonObject) return@mapNotNull null
            val branch = branchElement.asJsonObject
            VulnerabilityRepositoryBranch(
                id = textValue(branch.get("id")) ?: return@mapNotNull null,
                name = textValue(branch.get("name")) ?: "",
                latestCommitId = textValue(branch.get("latestCommitId")),
                isVulnerable = boolValue(branch.get("isVulnerable")) ?: false
            )
        } ?: emptyList()
        return VulnerabilityRepositoryStatus(
            id = id,
            name = name,
            link = textValue(obj.get("link")),
            type = parseReviewStatus(obj.get("type")) ?: textValue(obj.get("type")) ?: "unknown",
            pausedUntil = textValue(obj.get("pausedUntil")),
            branches = branches
        )
    }

    private fun parseDependencyTreeNode(element: JsonElement): VulnerabilityDependencyTreeNode? {
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        val name = textValue(obj.get("name")) ?: return null
        val children = obj.getAsJsonArray("children")?.mapNotNull { parseDependencyTreeNode(it) } ?: emptyList()
        return VulnerabilityDependencyTreeNode(
            name = name,
            version = textValue(obj.get("version")),
            url = textValue(obj.get("url")),
            vulnerable = boolValue(obj.get("vulnerable")) ?: false,
            children = children
        )
    }

    private fun readJson(endpoint: String): JsonElement? {
        val response = makeAuthenticatedRequest("GET", endpoint)
        if (response.isBlank()) return null
        return gson.fromJson(response, JsonElement::class.java)
    }

    private fun readJsonObject(endpoint: String): JsonObject {
        val element = readJson(endpoint)
        return if (element != null && element.isJsonObject) element.asJsonObject else JsonObject()
    }

    private fun boolValue(element: JsonElement?): Boolean? {
        if (element == null || element.isJsonNull) return null
        return if (element.isJsonPrimitive) {
            try {
                element.asBoolean
            } catch (_: Exception) {
                when (element.asString.lowercase()) {
                    "true", "1", "yes" -> true
                    "false", "0", "no" -> false
                    else -> null
                }
            }
        } else {
            null
        }
    }

    private fun jsonObjectValue(source: JsonObject?, key: String): JsonObject? {
        val element = source?.get(key) ?: return null
        return if (element.isJsonObject) element.asJsonObject else null
    }

    fun getLastRefreshError(): String? = lastRefreshError
}

data class DebrickedRepository(
    val id: String,
    val name: String,
    val organizationId: String,
    val defaultBranch: String?
)

data class DebrickedCommit(
    val id: String,
    val sha: String,
    val branch: String,
    val timestamp: String
)

data class DebrickedBranch(
    val id: String,
    val name: String
)

class ApiException(message: String, val statusCode: Int) : Exception(message)
