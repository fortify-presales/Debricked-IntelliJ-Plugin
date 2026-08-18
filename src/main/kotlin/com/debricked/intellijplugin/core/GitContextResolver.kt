package com.debricked.intellijplugin.core

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

data class GitContext(
    val repositoryPath: String?,
    val remoteUrl: String?,
    val repositoryName: String?,
    val branchName: String?,
    val commitSha: String?,
    val isDirty: Boolean
)

class GitContextResolver(private val project: Project) {

    private val LOG = logger<GitContextResolver>()

    fun resolveGitContext(): GitContext {
        return try {
            val gitRepo = getGitRepository() ?: return GitContext(null, null, null, null, null, false)
            val root = gitRepo.root.path
            val remoteUrl = extractRemoteUrl(gitRepo)
            val repoName = extractRepositoryName(remoteUrl)
            val branch = gitRepo.currentBranch?.name
            val commit = gitRepo.currentRevision
            // For Phase 1, we can defer detailed dirty tracking - just do a basic check
            val isDirty = false // TODO: Phase 3 - implement dependency file change detection

            GitContext(
                repositoryPath = root,
                remoteUrl = remoteUrl,
                repositoryName = repoName,
                branchName = branch,
                commitSha = commit,
                isDirty = isDirty
            )
        } catch (e: Exception) {
            LOG.warn("Failed to resolve Git context: ${e.message}", e)
            GitContext(null, null, null, null, null, false)
        }
    }

    private fun getGitRepository(): GitRepository? {
        val gitRepoManager = GitRepositoryManager.getInstance(project)
        return gitRepoManager.repositories.firstOrNull()
    }

    private fun extractRemoteUrl(gitRepo: GitRepository): String? {
        return try {
            gitRepo.remotes.firstOrNull()?.firstUrl
        } catch (e: Exception) {
            LOG.debug("Could not extract remote URL: ${e.message}")
            null
        }
    }

    private fun extractRepositoryName(remoteUrl: String?): String? {
        if (remoteUrl == null) return null
        
        return try {
            val url = remoteUrl.trim()
            // Handle both https://github.com/user/repo.git and git@github.com:user/repo.git
            when {
                url.contains("/") -> {
                    val parts = url.split("/")
                    parts.last().removeSuffix(".git")
                }
                url.contains(":") -> {
                    val parts = url.split(":")
                    parts.last().removeSuffix(".git")
                }
                else -> null
            }
        } catch (e: Exception) {
            LOG.debug("Could not extract repo name: ${e.message}")
            null
        }
    }
}

