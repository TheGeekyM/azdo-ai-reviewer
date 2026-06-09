package com.example.azdoreviewer.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * Fetches all remotes then checks out [branch]. Runs in the background (never blocks the UI).
 * Calls [onDone] (on the EDT) with success=true after checkout completes.
 */
object GitCheckoutHelper {

    fun checkout(project: Project, branch: String, onDone: ((Boolean) -> Unit)? = null) {
        val repo = git4idea.repo.GitRepositoryManager.getInstance(project).repositories.firstOrNull()
        if (repo == null) {
            Messages.showErrorDialog(project, "No Git repository found in this project.", "Cannot Checkout")
            onDone?.invoke(false)
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Fetching $branch", true) {
            override fun run(indicator: ProgressIndicator) {
                git4idea.fetch.GitFetchSupport.fetchSupport(project)
                    .fetchAllRemotes(listOf(repo))
                    .showNotificationIfFailed()
            }
            override fun onSuccess() {
                git4idea.branch.GitBrancher.getInstance(project).checkout(branch, false, listOf(repo)) {
                    onDone?.invoke(true)
                }
            }
            override fun onThrowable(error: Throwable) {
                Messages.showErrorDialog(project, error.message ?: "Fetch failed", "Checkout Failed")
                onDone?.invoke(false)
            }
        })
    }
}
