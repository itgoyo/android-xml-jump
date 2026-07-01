package com.itgoyo.androidxmljump

import com.intellij.icons.AllIcons
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.TextOccurenceProcessor
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.SimpleListCellRenderer
import java.awt.datatransfer.StringSelection

class GoToLayoutOwnerAction : AnAction() {
    override fun update(event: AnActionEvent) {
        val file = event.getTargetVirtualFile()
        event.presentation.isEnabledAndVisible = file?.canJumpBetweenLayoutAndOwner() == true
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val virtualFile = event.getTargetVirtualFile() ?: return
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return

        val result = ReadAction.compute<JumpResult, RuntimeException> {
            if (virtualFile.isAndroidLayoutXml()) {
                val layoutName = virtualFile.nameWithoutExtension
                JumpResult(
                    targets = LayoutOwnerFinder(project).findOwners(psiFile, layoutName),
                    emptyMessage = "No layout owner found for ${virtualFile.name}",
                    chooserTitle = "Choose Layout Owner"
                )
            } else {
                JumpResult(
                    targets = LayoutFileFinder(project).findLayouts(psiFile),
                    emptyMessage = "No Android layout reference found in ${virtualFile.name}",
                    chooserTitle = "Choose Layout XML"
                )
            }
        }

        when (result.targets.size) {
            0 -> notify(project, result.emptyMessage)
            1 -> result.targets.first().navigate()
            else -> showChooser(result.targets, result.chooserTitle)
        }
    }

    private fun showChooser(targets: List<NavigationTarget>, title: String) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(targets)
            .setTitle(title)
            .setRenderer(SimpleListCellRenderer.create { label, value, _ ->
                label.text = value?.label ?: "Unknown"
                label.icon = value?.element?.getIcon(0) ?: AllIcons.FileTypes.Java
            })
            .setItemChosenCallback { target ->
                target.navigate()
            }
            .createPopup()
            .showInFocusCenter()
    }

    private fun notify(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Android XML Jump")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }
}

private data class JumpResult(
    val targets: List<NavigationTarget>,
    val emptyMessage: String,
    val chooserTitle: String
)

private class LayoutOwnerFinder(private val project: Project) {
    private val projectScope = GlobalSearchScope.projectScope(project)

    fun findOwners(layoutFile: PsiFile, layoutName: String): List<NavigationTarget> {
        val targets = linkedMapOf<String, NavigationTarget>()

        findReferenceIndexOwners(layoutFile).forEach { target ->
            targets[target.key] = target
        }

        findTextIndexOwners(layoutName).forEach { target ->
            targets.putIfAbsent(target.key, target)
        }

        return targets.values.sortedWith(compareBy<NavigationTarget> { it.kind.priority }.thenBy { it.label })
    }

    private fun findReferenceIndexOwners(layoutFile: PsiFile): List<NavigationTarget> {
        return ReferencesSearch.search(layoutFile, projectScope)
            .asIterable()
            .mapNotNull { reference -> reference.element.toNavigationTarget(LayoutOwnerKind.ReferenceIndex) }
    }

    private fun findTextIndexOwners(layoutName: String): List<NavigationTarget> {
        val searchHelper = PsiSearchHelper.getInstance(project)
        val found = linkedMapOf<String, NavigationTarget>()
        val searchContext = (
            UsageSearchContext.IN_CODE.toInt() or
                UsageSearchContext.IN_STRINGS.toInt() or
                UsageSearchContext.IN_FOREIGN_LANGUAGES.toInt()
            ).toShort()

        listOf(layoutName, layoutName.toBindingClassName()).distinct().forEach { query ->
            searchHelper.processElementsWithWord(
                TextOccurenceProcessor { element, _ ->
                    if (element.textContainsLayoutReference(layoutName)) {
                        element.toNavigationTarget(LayoutOwnerKind.TextIndex)?.let { target ->
                            found[target.key] = target
                        }
                    }
                    true
                },
                projectScope,
                query,
                searchContext,
                true
            )
        }

        return found.values.toList()
    }
}

private class LayoutFileFinder(private val project: Project) {
    private val projectScope = GlobalSearchScope.projectScope(project)

    fun findLayouts(sourceFile: PsiFile): List<NavigationTarget> {
        val layoutNames = sourceFile.extractLayoutNames()
        val targets = linkedMapOf<String, NavigationTarget>()

        layoutNames.forEach { layoutName ->
            FilenameIndex.getVirtualFilesByName("$layoutName.xml", projectScope).forEach { file ->
                if (file.isAndroidLayoutXml()) {
                    PsiManager.getInstance(project).findFile(file)?.let { psiFile ->
                        val target = psiFile.toLayoutTarget(LayoutOwnerKind.TextIndex)
                        targets[target.key] = target
                    }
                }
            }
        }

        return targets.values.sortedBy { it.label }
    }
}

private fun PsiFile.extractLayoutNames(): List<String> {
    val text = text
    val names = linkedSetOf<String>()

    Regex("""R\.layout\.([A-Za-z0-9_]+)""")
        .findAll(text)
        .mapTo(names) { it.groupValues[1] }

    Regex("""@layout/([A-Za-z0-9_]+)""")
        .findAll(text)
        .mapTo(names) { it.groupValues[1] }

    Regex("""\b([A-Z][A-Za-z0-9]*Binding)\b""")
        .findAll(text)
        .map { it.groupValues[1].removeSuffix("Binding").toLayoutResourceName() }
        .filterTo(names) { it.isNotBlank() }

    return names.toList()
}

private fun String.toBindingClassName(): String {
    return split('_')
        .filter { it.isNotBlank() }
        .joinToString(separator = "") { part ->
            part.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
        } + "Binding"
}

private fun String.toLayoutResourceName(): String {
    return replace(Regex("""([a-z0-9])([A-Z])"""), "$1_$2")
        .replace(Regex("""([A-Z]+)([A-Z][a-z])"""), "$1_$2")
        .lowercase()
}

private fun PsiElement.textContainsLayoutReference(layoutName: String): Boolean {
    val text = containingFile?.text ?: return false
    val bindingClassName = layoutName.toBindingClassName()

    return text.contains("R.layout.$layoutName") ||
        text.contains("@layout/$layoutName") ||
        text.contains(bindingClassName)
}

private fun PsiElement.toNavigationTarget(kind: LayoutOwnerKind): NavigationTarget? {
    val containingFile = containingFile ?: return null
    if (containingFile.fileType == XmlFileType.INSTANCE) return null

    val javaClass = PsiTreeUtil.getParentOfType(this, PsiClass::class.java, false)
    val element = javaClass ?: containingFile
    val label = buildString {
        append((element as? PsiNamedElement)?.name ?: containingFile.name)
        append("  ")
        append(containingFile.virtualFile?.presentableUrl ?: containingFile.name)
    }

    return NavigationTarget(element, label, kind)
}

private fun PsiFile.toLayoutTarget(kind: LayoutOwnerKind): NavigationTarget {
    val virtualFile = virtualFile
    val label = buildString {
        append(name)
        append("  ")
        append(virtualFile?.presentableUrl ?: name)
    }

    return NavigationTarget(this, label, kind)
}

private fun AnActionEvent.getTargetVirtualFile(): VirtualFile? {
    val directFile = getData(CommonDataKeys.VIRTUAL_FILE)
    if (directFile != null) return directFile

    val project = project ?: return null
    return FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
}

private fun VirtualFile.isAndroidLayoutXml(): Boolean {
    if (!isValid || isDirectory || extension != "xml") return false

    val parentName = parent?.name ?: return false
    if (!parentName.startsWith("layout")) return false

    return parent?.parent?.name == "res"
}

private fun VirtualFile.canJumpBetweenLayoutAndOwner(): Boolean {
    return isAndroidLayoutXml() || extension == "java" || extension == "kt"
}

private data class NavigationTarget(
    val element: PsiElement,
    val label: String,
    val kind: LayoutOwnerKind
) {
    val key: String = element.containingFile?.virtualFile?.path ?: label
    val clipboardText: String = element.resolveClipboardText()

    fun navigate() {
        (element as? Navigatable)?.navigate(true)
        copyClipboardText(clipboardText)
    }
}

private fun copyClipboardText(text: String) {
    if (text.isBlank()) return
    CopyPasteManager.getInstance().setContents(StringSelection(text))
}

private fun PsiElement.resolveClipboardText(): String {
    (this as? PsiClass)?.name?.takeIf { it.isNotBlank() }?.let { return it }
    PsiTreeUtil.getParentOfType(this, PsiClass::class.java, false)?.name
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }

    val file = containingFile ?: (this as? PsiFile)
    val fileName = file?.virtualFile?.nameWithoutExtension ?: file?.name.orEmpty()
    return fileName.removeSuffix(".kt").removeSuffix(".java")
}

private enum class LayoutOwnerKind(val priority: Int) {
    ReferenceIndex(0),
    TextIndex(1)
}
