package com.itgoyo.androidxmljump

import com.intellij.icons.AllIcons
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
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
import com.intellij.psi.xml.XmlTag
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

        val editor = resolveEditorForFile(event, project, virtualFile)
        val viewIdAtCaret = if (editor != null) {
            val offset = editor.caretModel.offset
            ReadAction.compute<String?, RuntimeException> {
                if (virtualFile.isAndroidLayoutXml()) {
                    extractViewIdAtOffset(psiFile, offset)
                } else {
                    extractViewIdFromCodeAtOffset(psiFile, offset)
                }
            }
        } else {
            null
        }

        val result = ReadAction.compute<JumpResult, RuntimeException> {
            if (virtualFile.isAndroidLayoutXml()) {
                JumpResult(
                    targets = LayoutOwnerFinder(project).findOwners(psiFile, virtualFile.nameWithoutExtension),
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
            1 -> result.targets.first().navigate(project, viewIdAtCaret)
            else -> showChooser(project, result.targets, result.chooserTitle, viewIdAtCaret)
        }
    }

    private fun showChooser(
        project: Project,
        targets: List<NavigationTarget>,
        title: String,
        viewIdAtCaret: String?
    ) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(targets)
            .setTitle(title)
            .setRenderer(SimpleListCellRenderer.create { label, value, _ ->
                label.text = value?.label ?: "Unknown"
                label.icon = value?.element?.getIcon(0) ?: AllIcons.FileTypes.Java
            })
            .setItemChosenCallback { target ->
                ApplicationManager.getApplication().invokeLater(
                    { target.navigate(project, viewIdAtCaret) },
                    ModalityState.defaultModalityState()
                )
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

// ---------------------------------------------------------------------------
// Navigation
// ---------------------------------------------------------------------------

private data class NavigationTarget(
    val element: PsiElement,
    val label: String,
    val kind: LayoutOwnerKind
) {
    val key: String = element.containingFile?.virtualFile?.path ?: label
    val clipboardText: String = element.resolveClipboardText()

    fun navigate(project: Project, viewId: String? = null) {
        val targetFile = element.containingFile ?: (element as? PsiFile)
        val targetVirtualFile = targetFile?.virtualFile ?: return
        val isTargetXml = targetVirtualFile.isAndroidLayoutXml()

        if (isTargetXml && !viewId.isNullOrBlank() && targetFile != null) {
            val xmlCaretOffset = ReadAction.compute<Int?, RuntimeException> {
                findXmlTagByViewId(targetFile, viewId)?.let { tag ->
                    tag.getAttribute("id", "http://schemas.android.com/apk/res/android")?.valueElement?.textOffset
                        ?: tag.getAttribute("id")?.valueElement?.textOffset
                        ?: tag.textOffset
                }
            }
            if (xmlCaretOffset != null) {
                navigateToXmlOffset(project, targetVirtualFile, xmlCaretOffset)
                copyClipboardText(clipboardText)
                return
            }
        }

        val caretOffset = if (!viewId.isNullOrBlank() && targetFile != null && !isTargetXml) {
            val ownerClass = element as? PsiClass
                ?: PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)
            ReadAction.compute<Int?, RuntimeException> {
                findViewIdCaretOffset(targetFile, viewId, ownerClass)
            }
        } else if (!viewId.isNullOrBlank() && targetFile != null && isTargetXml) {
            ReadAction.compute<Int?, RuntimeException> {
                findViewIdOffsetInXml(targetFile.text, viewId)
            }
        } else {
            null
        }

        if (caretOffset != null) {
            openFileAtOffset(project, targetVirtualFile, caretOffset, isTargetXml)
        } else {
            (element as? Navigatable)?.navigate(true)
        }

        copyClipboardText(clipboardText)
    }
}

/**
 * Opens a layout XML and lands the caret on a pre-computed offset inside android:id.
 * Uses retry because Android Studio attaches the text editor asynchronously.
 */
private fun navigateToXmlOffset(project: Project, virtualFile: VirtualFile, caretOffset: Int) {
    ApplicationManager.getApplication().invokeLater({
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
        OpenFileDescriptor(project, virtualFile, caretOffset).navigate(true)
        applyCaretWithRetry(project, virtualFile, caretOffset, scrollCenter = true) {
            trySwitchToSplitView(project, virtualFile, caretOffset)
        }
    }, ModalityState.defaultModalityState())
}

private fun openFileAtOffset(
    project: Project,
    virtualFile: VirtualFile,
    offset: Int,
    isTargetXml: Boolean
) {
    ApplicationManager.getApplication().invokeLater({
        val descriptor = OpenFileDescriptor(project, virtualFile, offset)
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
        val textEditor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        if (textEditor != null) {
            textEditor.caretModel.moveToOffset(offset.coerceIn(0, textEditor.document.textLength))
            textEditor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            if (isTargetXml) {
                trySwitchToSplitView(project, virtualFile, offset)
            }
        } else {
            descriptor.navigate(true)
            applyCaretWithRetry(project, virtualFile, offset, scrollCenter = true) {
                if (isTargetXml) trySwitchToSplitView(project, virtualFile, offset)
            }
        }
    }, ModalityState.defaultModalityState())
}

/**
 * Android Studio opens layout files asynchronously. Retry a few times so caret
 * placement and scroll-to-center happen after the text editor is attached.
 */
private fun applyCaretWithRetry(
    project: Project,
    virtualFile: VirtualFile,
    offset: Int,
    scrollCenter: Boolean,
    retriesLeft: Int = 8,
    onDone: (() -> Unit)? = null
) {
    val editor = findTextEditorForVirtualFile(project, virtualFile)
    if (editor != null) {
        val safeOffset = offset.coerceIn(0, editor.document.textLength)
        editor.caretModel.moveToOffset(safeOffset)
        if (scrollCenter) {
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        } else {
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        }
        editor.contentComponent.requestFocusInWindow()
        onDone?.invoke()
        return
    }

    if (retriesLeft <= 0) {
        onDone?.invoke()
        return
    }

    ApplicationManager.getApplication().invokeLater({
        applyCaretWithRetry(project, virtualFile, offset, scrollCenter, retriesLeft - 1, onDone)
    }, ModalityState.defaultModalityState())
}

private fun findTextEditorForVirtualFile(project: Project, virtualFile: VirtualFile): Editor? {
    val fem = FileEditorManager.getInstance(project)
    fem.getEditors(virtualFile).forEach { fileEditor ->
        extractEditor(fileEditor)?.let { return it }
    }
    fem.selectedTextEditor?.let { editor ->
        if (FileDocumentManager.getInstance().getFile(editor.document) == virtualFile) {
            return editor
        }
    }
    return null
}

private fun extractEditor(fileEditor: FileEditor): Editor? {
    if (fileEditor is TextEditor) return fileEditor.editor

    // TextEditorWithPreview, Android SplitEditor, etc.
    for (methodName in listOf("getEditor", "getTextEditor", "getMainEditor")) {
        try {
            val method = fileEditor.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
                ?: continue
            when (val result = method.invoke(fileEditor)) {
                is Editor -> return result
                is TextEditor -> return result.editor
            }
        } catch (_: Throwable) {
            // try next method
        }
    }
    return null
}

private fun trySwitchToSplitView(project: Project, virtualFile: VirtualFile, caretOffset: Int) {
    ApplicationManager.getApplication().invokeLater({
        try {
            FileEditorManager.getInstance(project).getEditors(virtualFile).forEach { fe ->
                if (tryInvokeShowEditorAndPreview(fe)) {
                    // Split mode can reset caret; restore it after switching
                    applyCaretWithRetry(project, virtualFile, caretOffset, scrollCenter = true)
                    return@invokeLater
                }
            }
        } catch (_: Throwable) {
            // best effort
        }
    }, ModalityState.defaultModalityState())
}

private fun tryInvokeShowEditorAndPreview(target: Any): Boolean {
    for (className in listOf(
        null,
        "com.intellij.openapi.fileEditor.TextEditorWithPreview",
        "com.android.tools.idea.common.editor.SplitEditor"
    )) {
        try {
            val cls = className?.let { Class.forName(it) } ?: target.javaClass
            if (className != null && !cls.isInstance(target)) continue
            cls.getMethod("showEditorAndPreview").invoke(target)
            return true
        } catch (_: Throwable) {
            // try next
        }
    }
    return false
}

// ---------------------------------------------------------------------------
// XML tag lookup (PSI – reliable in Android Studio)
// ---------------------------------------------------------------------------

private fun findXmlTagByViewId(layoutFile: PsiFile, viewId: String): XmlTag? {
    return PsiTreeUtil.findChildrenOfType(layoutFile, XmlTag::class.java)
        .firstOrNull { tag ->
            val idValue = tag.getAttributeValue("id", "http://schemas.android.com/apk/res/android")
                ?: tag.getAttributeValue("id")
                ?: return@firstOrNull false
            val xmlId = Regex("""@\+?id/([A-Za-z0-9_]+)""")
                .find(idValue.trim())?.groupValues?.getOrNull(1)
                ?: return@firstOrNull false
            viewIdsMatch(xmlId, viewId)
        }
}

private fun viewIdsMatch(xmlId: String, queryId: String): Boolean {
    if (xmlId.equals(queryId, ignoreCase = true)) return true
    if (xmlId.snakeToCamel().equals(queryId, ignoreCase = true)) return true
    if (queryId.snakeToCamel().equals(xmlId, ignoreCase = true)) return true
    if (xmlId.replace("_", "").equals(queryId.replace("_", ""), ignoreCase = true)) return true
    return false
}

private fun findViewIdOffsetInXml(xmlText: String, viewId: String): Int? {
    val candidates = linkedSetOf(viewId)
    val snake = viewId
        .replace(Regex("""([a-z0-9])([A-Z])"""), "$1_$2")
        .replace(Regex("""([A-Z]+)([A-Z][a-z])"""), "$1_$2")
        .lowercase()
    if (snake != viewId) candidates.add(snake)

    for (id in candidates) {
        val regex = Regex("""@\+?id/$id(?=["'\s/>])""")
        val match = regex.find(xmlText) ?: continue
        return match.range.first + match.value.lastIndexOf('/') + 1
    }
    return null
}

// ---------------------------------------------------------------------------
// Code-side view id extraction & lookup
// ---------------------------------------------------------------------------

private fun extractViewIdFromCodeAtOffset(psiFile: PsiFile, offset: Int): String? {
    val element = psiFile.findElementAt(offset) ?: return null

    // Walk up a few levels for qualified expressions: binding.guideIV / R.id.guide_iv
    var current: PsiElement? = element
    repeat(6) {
        current?.text?.let { resolveViewIdFromReferenceText(it) }?.let { return it }
        current = current?.parent
    }

    // Plain identifier token: guideIV
    val token = element.text
    if (token.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*")) &&
        token !in IGNORED_IDENTIFIERS
    ) {
        return token
    }
    return null
}

private val IGNORED_IDENTIFIERS = setOf(
    "id", "layout", "R", "binding", "mBinding", "viewBinding", "_binding",
    "findViewById", "inflate", "setContentView", "get", "let", "apply", "also", "run"
)

private fun resolveViewIdFromReferenceText(text: String): String? {
    Regex("""R\.id\.([A-Za-z0-9_]+)""").find(text)?.groupValues?.getOrNull(1)?.let { return it }
    Regex("""\.([A-Za-z_][A-Za-z0-9_]*)$""").find(text.trim())?.groupValues?.getOrNull(1)?.let { name ->
        if (name !in IGNORED_IDENTIFIERS) return name
    }
    return null
}

private fun findViewIdCaretOffset(file: PsiFile, viewId: String, ownerClass: PsiClass?): Int? {
    val text = file.text
    val start = ownerClass?.textRange?.startOffset ?: 0
    val end = ownerClass?.textRange?.endOffset ?: text.length
    val scope = text.substring(start, end.coerceAtMost(text.length))
    val abs = { local: Int -> start + local }

    Regex("""R\.id\.$viewId\b""").find(scope)?.let { return abs(it.range.first) }

    val camelId = viewId.snakeToCamel()
    for (prefix in listOf("binding.", "mBinding.", "_binding.", "viewBinding.")) {
        val pattern = prefix + camelId
        val idx = scope.indexOf(pattern)
        if (idx >= 0) return abs(idx + pattern.length - camelId.length)
    }

    Regex("""\.$camelId\b""").find(scope)?.let { return abs(it.range.first + 1) }
    Regex("""\b$camelId\b""").find(scope)?.let { return abs(it.range.first) }
    Regex("""\b$viewId\b""").find(scope)?.let { return abs(it.range.first) }

    return null
}

private fun extractViewIdAtOffset(psiFile: PsiFile, offset: Int): String? {
    val element = psiFile.findElementAt(offset) ?: return null
    val tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java, false) ?: return null
    val idValue = tag.getAttributeValue("id", "http://schemas.android.com/apk/res/android")
        ?: tag.getAttributeValue("id")
        ?: return null
    return Regex("""@\+?id/([A-Za-z0-9_]+)""").find(idValue.trim())?.groupValues?.getOrNull(1)
}

// ---------------------------------------------------------------------------
// Editor resolution (tab popup often has no EDITOR in data context)
// ---------------------------------------------------------------------------

private fun resolveEditorForFile(
    event: AnActionEvent,
    project: Project,
    virtualFile: VirtualFile
): Editor? {
    event.getData(CommonDataKeys.EDITOR)?.let { editor ->
        if (FileDocumentManager.getInstance().getFile(editor.document) == virtualFile) {
            return editor
        }
    }

    FileEditorManager.getInstance(project).getEditors(virtualFile).forEach { fe ->
        extractEditor(fe)?.let { return it }
    }

    FileEditorManager.getInstance(project).selectedTextEditor?.let { editor ->
        if (FileDocumentManager.getInstance().getFile(editor.document) == virtualFile) {
            return editor
        }
    }
    return null
}

// ---------------------------------------------------------------------------
// Finders & utilities (unchanged logic)
// ---------------------------------------------------------------------------

private data class JumpResult(
    val targets: List<NavigationTarget>,
    val emptyMessage: String,
    val chooserTitle: String
)

private class LayoutOwnerFinder(private val project: Project) {
    private val projectScope = GlobalSearchScope.projectScope(project)

    fun findOwners(layoutFile: PsiFile, layoutName: String): List<NavigationTarget> {
        val targets = linkedMapOf<String, NavigationTarget>()
        findReferenceIndexOwners(layoutFile).forEach { targets[it.key] = it }
        findTextIndexOwners(layoutName).forEach { targets.putIfAbsent(it.key, it) }
        return targets.values.sortedWith(
            compareBy<NavigationTarget> { it.kind.priority }.thenBy { it.label }
        )
    }

    private fun findReferenceIndexOwners(layoutFile: PsiFile) =
        ReferencesSearch.search(layoutFile, projectScope)
            .asIterable()
            .mapNotNull { it.element.toNavigationTarget(LayoutOwnerKind.ReferenceIndex) }

    private fun findTextIndexOwners(layoutName: String): List<NavigationTarget> {
        val helper = PsiSearchHelper.getInstance(project)
        val found = linkedMapOf<String, NavigationTarget>()
        val ctx = (UsageSearchContext.IN_CODE.toInt()
            or UsageSearchContext.IN_STRINGS.toInt()
            or UsageSearchContext.IN_FOREIGN_LANGUAGES.toInt()).toShort()

        listOf(layoutName, layoutName.toBindingClassName()).distinct().forEach { q ->
            helper.processElementsWithWord(
                TextOccurenceProcessor { el, _ ->
                    if (el.textContainsLayoutReference(layoutName)) {
                        el.toNavigationTarget(LayoutOwnerKind.TextIndex)?.let { found[it.key] = it }
                    }
                    true
                },
                projectScope, q, ctx, true
            )
        }
        return found.values.toList()
    }
}

private class LayoutFileFinder(private val project: Project) {
    private val projectScope = GlobalSearchScope.projectScope(project)

    fun findLayouts(sourceFile: PsiFile): List<NavigationTarget> {
        val targets = linkedMapOf<String, NavigationTarget>()
        sourceFile.extractLayoutNames().forEach { name ->
            FilenameIndex.getVirtualFilesByName("$name.xml", projectScope).forEach { vf ->
                if (vf.isAndroidLayoutXml()) {
                    PsiManager.getInstance(project).findFile(vf)?.let { pf ->
                        val t = pf.toLayoutTarget(LayoutOwnerKind.TextIndex)
                        targets[t.key] = t
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
    Regex("""R\.layout\.([A-Za-z0-9_]+)""").findAll(text).mapTo(names) { it.groupValues[1] }
    Regex("""@layout/([A-Za-z0-9_]+)""").findAll(text).mapTo(names) { it.groupValues[1] }
    Regex("""\b([A-Z][A-Za-z0-9]*Binding)\b""").findAll(text)
        .map { it.groupValues[1].removeSuffix("Binding").toLayoutResourceName() }
        .filterTo(names) { it.isNotBlank() }
    return names.toList()
}

private fun String.toBindingClassName(): String =
    split('_').filter { it.isNotBlank() }.joinToString("") { p ->
        p.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    } + "Binding"

private fun String.toLayoutResourceName(): String =
    replace(Regex("""([a-z0-9])([A-Z])"""), "$1_$2")
        .replace(Regex("""([A-Z]+)([A-Z][a-z])"""), "$1_$2")
        .lowercase()

private fun String.snakeToCamel(): String {
    if (!contains('_')) return this
    return split('_').filter { it.isNotBlank() }.mapIndexed { i, p ->
        if (i == 0) p else p.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }.joinToString("")
}

private fun PsiElement.textContainsLayoutReference(layoutName: String): Boolean {
    val text = containingFile?.text ?: return false
    return text.contains("R.layout.$layoutName") ||
        text.contains("@layout/$layoutName") ||
        text.contains(layoutName.toBindingClassName())
}

private fun PsiElement.toNavigationTarget(kind: LayoutOwnerKind): NavigationTarget? {
    val file = containingFile ?: return null
    if (file.fileType == XmlFileType.INSTANCE) return null
    val cls = PsiTreeUtil.getParentOfType(this, PsiClass::class.java, false)
    val elem = cls ?: file
    val label = buildString {
        append((elem as? PsiNamedElement)?.name ?: file.name)
        append("  ")
        append(file.virtualFile?.presentableUrl ?: file.name)
    }
    return NavigationTarget(elem, label, kind)
}

private fun PsiFile.toLayoutTarget(kind: LayoutOwnerKind): NavigationTarget {
    val label = buildString {
        append(name)
        append("  ")
        append(virtualFile?.presentableUrl ?: name)
    }
    return NavigationTarget(this, label, kind)
}

private fun AnActionEvent.getTargetVirtualFile(): VirtualFile? {
    getData(CommonDataKeys.VIRTUAL_FILE)?.let { return it }
    val project = project ?: return null
    return FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
}

private fun VirtualFile.isAndroidLayoutXml(): Boolean {
    if (!isValid || isDirectory || extension != "xml") return false
    val parentName = parent?.name ?: return false
    if (!parentName.startsWith("layout")) return false
    return parent?.parent?.name == "res"
}

private fun VirtualFile.canJumpBetweenLayoutAndOwner(): Boolean =
    isAndroidLayoutXml() || extension == "java" || extension == "kt"

private fun copyClipboardText(text: String) {
    if (text.isBlank()) return
    CopyPasteManager.getInstance().setContents(StringSelection(text))
}

private fun PsiElement.resolveClipboardText(): String {
    (this as? PsiClass)?.name?.takeIf { it.isNotBlank() }?.let { return it }
    PsiTreeUtil.getParentOfType(this, PsiClass::class.java, false)?.name
        ?.takeIf { it.isNotBlank() }?.let { return it }
    val file = containingFile ?: (this as? PsiFile)
    return (file?.virtualFile?.nameWithoutExtension ?: file?.name.orEmpty())
        .removeSuffix(".kt").removeSuffix(".java")
}

private enum class LayoutOwnerKind(val priority: Int) {
    ReferenceIndex(0),
    TextIndex(1)
}
