package com.example.azdoreviewer.ui

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/** Opens a file at a given line inside the project, without blocking the EDT (bails fast in dumb mode). */
object EditorNavigator {

    fun open(project: Project, serverPath: String, line: Int, onStatus: (String) -> Unit) {
        val dumb = DumbService.getInstance(project)
        if (dumb.isDumb) { onStatus("⏳ IDE is indexing — try again in a moment."); return }

        onStatus("Locating ${serverPath.substringAfterLast('/')}…")
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Locating file", true) {
                @Volatile private var vFile: VirtualFile? = null
                override fun run(indicator: ProgressIndicator) {
                    vFile = ReadAction.nonBlocking<VirtualFile?> { findFileInProject(project, serverPath) }
                        .inSmartMode(project).executeSynchronously()
                }
                override fun onSuccess() {
                    val f = vFile
                    if (f == null) { onStatus("File not found locally: ${serverPath.substringAfterLast('/')}"); return }
                    OpenFileDescriptor(project, f, (line - 1).coerceAtLeast(0), 0).navigate(true)
                    onStatus("Opened ${serverPath.substringAfterLast('/')}:$line")
                }
                override fun onThrowable(error: Throwable) { onStatus("⚠ Could not open file: ${error.message}") }
            }
        )
    }

    private fun findFileInProject(project: Project, serverPath: String): VirtualFile? {
        val rel  = serverPath.trimStart('/')
        val name = rel.substringAfterLast('/')
        val scope = GlobalSearchScope.projectScope(project)
        val candidates = FilenameIndex.getVirtualFilesByName(name, scope)
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.path.replace('\\', '/').endsWith(rel) } ?: candidates.firstOrNull()
    }
}
