package me.weishu.kernelsu.ui.screen.templateeditor

import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import me.weishu.kernelsu.ui.util.rememberRootOperation
import me.weishu.kernelsu.ui.util.rootIO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.weishu.kernelsu.R
import me.weishu.kernelsu.toOrdinalList
import me.weishu.kernelsu.toRootProfileFlags
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.util.deleteAppProfileTemplate
import me.weishu.kernelsu.ui.viewmodel.TemplateViewModel

@Composable
fun TemplateEditorScreen(template: TemplateViewModel.TemplateInfo, readOnly: Boolean) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val rootOperation = rememberRootOperation()
    val writes = remember { Mutex() }
    val isCreation = template.id.isBlank()
    val autoSave = !isCreation

    var currentTemplate by rememberSaveable { mutableStateOf(template) }
    var idErrorHint by remember { mutableStateOf("") }

    val saveTemplateFailed = stringResource(id = R.string.app_profile_template_save_failed)
    val idConflictError = stringResource(id = R.string.app_profile_template_id_exist)
    val idInvalidError = stringResource(id = R.string.app_profile_template_id_invalid)

    fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun finishEditing() {
        rootOperation {
            writes.withLock {
                if (!readOnly) navigator.setResult("template_edit", true) else navigator.pop()
            }
        }
    }

    fun updateTemplate(updatedTemplate: TemplateViewModel.TemplateInfo) {
        currentTemplate = updatedTemplate
        if (autoSave) rootOperation {
            writes.withLock { check(rootIO { saveTemplate(updatedTemplate) }) { saveTemplateFailed } }
        }
    }

    LaunchedEffect(currentTemplate.id) {
        val id = currentTemplate.id
        if (isCreation) rootOperation {
            val exists = rootIO { isTemplateExist(id) }
            if (currentTemplate.id == id) idErrorHint = when {
                exists -> idConflictError
                !isValidTemplateId(id) -> idInvalidError
                else -> ""
            }
        }
    }

    val uiState = TemplateEditorUiState(
        template = currentTemplate,
        initialTemplate = template,
        readOnly = readOnly,
        isCreation = isCreation,
        idErrorHint = idErrorHint,
    )

    fun saveCurrentTemplate() {
        val snapshot = currentTemplate
        rootOperation {
            when (rootIO { idCheck(snapshot.id) }) {
                1 -> showToast(idConflictError)
                2 -> showToast(idInvalidError)
                else -> {
                    check(rootIO { saveTemplate(snapshot, isCreation) }) { saveTemplateFailed }
                    navigator.setResult("template_edit", true)
                }
            }
        }
    }

    val actions = TemplateEditorActions(
        onBack = dropUnlessResumed { finishEditing() },
        onDelete = {
            rootOperation {
                writes.withLock {
                    check(rootIO { deleteAppProfileTemplate(currentTemplate.id) }) { saveTemplateFailed }
                }
                navigator.setResult("template_edit", true)
            }
        },
        onSave = ::saveCurrentTemplate,
        onNameChange = { value ->
            updateTemplate(currentTemplate.copy(name = value))
        },
        onIdChange = { value ->
            currentTemplate = currentTemplate.copy(id = value)
        },
        onAuthorChange = { value ->
            updateTemplate(currentTemplate.copy(author = value))
        },
        onDescriptionChange = { value ->
            updateTemplate(currentTemplate.copy(description = value))
        },
        onProfileChange = { profile ->
            updateTemplate(
                currentTemplate.copy(
                    uid = profile.uid,
                    gid = profile.gid,
                    groups = profile.groups,
                    capabilities = profile.capabilities,
                    context = profile.context,
                    namespace = profile.namespace,
                    rules = profile.rules.split("\n"),
                    flags = profile.flags.toRootProfileFlags().toOrdinalList(),
                )
            )
        },
    )

    TemplateEditorScreenMiuix(
        state = uiState,
        actions = actions,
    )
}
