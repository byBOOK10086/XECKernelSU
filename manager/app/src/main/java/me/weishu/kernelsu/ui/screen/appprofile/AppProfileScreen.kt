package me.weishu.kernelsu.ui.screen.appprofile

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.util.rememberRootOperation
import me.weishu.kernelsu.ui.util.rootIO
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.util.forceStopApp
import me.weishu.kernelsu.ui.util.getSepolicy
import me.weishu.kernelsu.ui.util.launchApp
import me.weishu.kernelsu.ui.util.restartApp
import me.weishu.kernelsu.ui.util.setSepolicy
import me.weishu.kernelsu.ui.viewmodel.SuperUserViewModel
import me.weishu.kernelsu.ui.viewmodel.getTemplateInfoById

@Composable
fun AppProfileScreen(uid: Int) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rootOperation = rememberRootOperation()
    val viewModel: SuperUserViewModel = viewModel()
    val appGroupState = remember(uid) {
        derivedStateOf {
            viewModel.uiState.value.groupedApps.find { it.uid == uid } ?: SuperUserViewModel.getGroupedApp(uid)
        }
    }
    val appGroup = appGroupState.value
    val primaryAppInfo = appGroup?.primary
    if (primaryAppInfo == null) {
        LaunchedEffect(Unit) {
            navigator.pop()
        }
        return
    }

    val packageName = primaryAppInfo.profileKey
    val sharedUserId = remember(uid) {
        primaryAppInfo.packageInfo.sharedUserId
            ?: appGroup.apps.firstOrNull { it.packageInfo.sharedUserId != null }?.packageInfo?.sharedUserId
            ?: ""
    }

    val initialProfile = remember(uid, packageName, primaryAppInfo.special) {
        Natives.getAppProfile(packageName, uid).let {
            if (primaryAppInfo.special) it.copy(allowSu = false) else it
        }
    }
    var profile by rememberSaveable(uid, packageName) {
        mutableStateOf(initialProfile)
    }

    LaunchedEffect(uid, packageName) {
        rootOperation {
            if (profile.allowSu && !primaryAppInfo.special) {
                val snapshot = profile
                val rules = rootIO { getSepolicy(packageName) }
                if (profile == snapshot) profile = snapshot.copy(rules = rules)
            }
        }
    }

    val failToUpdateAppProfile = stringResource(R.string.failed_to_update_app_profile).format(primaryAppInfo.label)
    val failToUpdateSepolicy = stringResource(R.string.failed_to_update_sepolicy).format(primaryAppInfo.label)
    val suNotAllowed = stringResource(R.string.su_not_allowed).format(primaryAppInfo.label)

    fun showMessage(message: String) {
        scope.launch {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val state = AppProfileUiState(
        uid = uid,
        packageName = packageName,
        profile = profile,
        appGroup = appGroup,
        sharedUserId = sharedUserId,
    )

    val actions = AppProfileActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onLaunchApp = { name, user -> rootOperation { rootIO { launchApp(name, user) } } },
        onForceStopApp = { name, user -> rootOperation { rootIO { forceStopApp(name, user) } } },
        onRestartApp = { name, user -> rootOperation { rootIO { restartApp(name, user) } } },
        onViewTemplate = { templateId ->
            rootOperation {
                rootIO { getTemplateInfoById(templateId) }?.let { info ->
                    navigator.push(Route.TemplateEditor(info, true))
                }
            }
        },
        onManageTemplate = {
            navigator.push(Route.AppProfileTemplate)
        },
        onProfileChange = { updatedProfile ->
            rootOperation {
                val profileToSave = if (primaryAppInfo.special) {
                    updatedProfile.copy(allowSu = false)
                } else {
                    updatedProfile
                }
                if (profileToSave.allowSu) {
                    if (uid < 2000 && uid != 1000) {
                        showMessage(suNotAllowed)
                        return@rootOperation
                    }
                    if (!profileToSave.rootUseDefault
                        && profileToSave.rules.isNotEmpty()
                        && !primaryAppInfo.special
                        && !rootIO { setSepolicy(profileToSave.name, profileToSave.rules) }
                    ) {
                        showMessage(failToUpdateSepolicy)
                        return@rootOperation
                    }
                }
                if (!rootIO { Natives.setAppProfile(profileToSave) }) {
                    showMessage(failToUpdateAppProfile)
                } else {
                    profile = profileToSave
                    viewModel.loadAppList()
                }
            }
        },
    )

    AppProfileScreenMiuix(
        state = state,
        actions = actions,
    )
}
