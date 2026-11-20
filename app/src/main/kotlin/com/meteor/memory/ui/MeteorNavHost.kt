package com.meteor.memory.ui

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.meteor.memory.BuildConfig
import com.meteor.memory.R
import com.meteor.memory.core.storage.RecordType
import com.meteor.memory.feature.ai.AiPersonChip
import com.meteor.memory.feature.ai.AiScreen
import com.meteor.memory.feature.ai.AiSettingsScreen
import com.meteor.memory.feature.ai.AiSettingsSummary
import com.meteor.memory.feature.ai.AiUsageScreen
import com.meteor.memory.feature.ai.R as AiR
import com.meteor.memory.feature.ai.AiSkillChip
import com.meteor.memory.feature.ai.PersonaScreen
import com.meteor.memory.feature.ai.aiProviderLabel
import com.meteor.memory.feature.calendar.CalendarScreen
import com.meteor.memory.feature.gallery.GalleryScreen
import com.meteor.memory.feature.gallery.ImageViewerScreen
import com.meteor.memory.feature.gallery.R as GalleryR
import com.meteor.memory.feature.people.PeopleListScreen
import com.meteor.memory.feature.people.PeopleRow
import com.meteor.memory.feature.people.PersonDetailScreen
import com.meteor.memory.feature.people.PersonEditScreen
import com.meteor.memory.feature.people.PersonRecordRow
import com.meteor.memory.feature.settings.SettingsScreen
import com.meteor.memory.feature.settings.R as SettingsR
import com.meteor.memory.ui.about.AboutScreen
import com.meteor.memory.ui.about.ALIPAY_DONATE_URL
import com.meteor.memory.ui.about.ChangelogScreen
import com.meteor.memory.ui.about.ChangelogVersionScreen
import com.meteor.memory.ui.about.DISCUSSIONS_URL
import com.meteor.memory.ui.about.DonateScreen
import com.meteor.memory.ui.about.FeedbackScreen
import com.meteor.memory.ui.about.ISSUES_URL
import com.meteor.memory.ui.about.REPOSITORY_URL
import com.meteor.memory.ui.about.SUPPORT_EMAIL
import com.meteor.memory.ui.about.mailtoUrl
import com.meteor.memory.ui.ai.AiBlocked
import com.meteor.memory.ui.ai.AiViewModel
import com.meteor.memory.ui.ai.PersonaViewModel
import com.meteor.memory.ui.ai.aiUsageTotals
import com.meteor.memory.ui.backup.BackupActions
import com.meteor.memory.ui.chats.ChatsScreen
import com.meteor.memory.ui.chats.ChatsViewModel
import com.meteor.memory.ui.calendar.CalendarViewModel
import com.meteor.memory.ui.diagnostics.StorageDiagnosticsScreen
import com.meteor.memory.ui.diary.DiaryEditScreen
import com.meteor.memory.ui.diary.DiaryEditViewModel
import com.meteor.memory.ui.diary.DiaryPreviewScreen
import com.meteor.memory.ui.diary.DiaryPreviewViewModel
import com.meteor.memory.ui.gallery.GalleryViewModel
import com.meteor.memory.ui.gallery.ImageViewerViewModel
import com.meteor.memory.ui.home.HomeTabsScreen
import com.meteor.memory.ui.life.BirthDateSettingsScreen
import com.meteor.memory.ui.life.LifeClockScreen
import com.meteor.memory.ui.life.LifeViewModel
import com.meteor.memory.ui.log.LogActions
import com.meteor.memory.ui.people.PeopleViewModel
import com.meteor.memory.ui.people.PersonDetailViewModel
import com.meteor.memory.ui.people.PersonEditViewModel
import com.meteor.memory.ui.people.PersonAttachmentView
import com.meteor.memory.ui.search.SearchScreen
import com.meteor.memory.ui.search.SearchViewModel
import com.meteor.memory.ui.settings.AiTestState
import com.meteor.memory.ui.settings.SettingsViewModel
import com.meteor.memory.update.UpdateError
import com.meteor.memory.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private const val ROUTE_SETTINGS = "settings"

/**
 * The primary destinations use a shared fade-through transition. Settings
 * detail pages are the only destinations that slide laterally.
 */
private const val SETTINGS_ENTER_MS = 340
private const val SETTINGS_EXIT_MS = 200
private const val SETTINGS_ENTER_SCALE = 0.97f
private const val DETAIL_SLIDE_MS = 300
private const val PAGE_FADE_ENTER_MS = 280
private const val PAGE_FADE_EXIT_MS = 220
private const val ROUTE_BIRTH_DATE = "settings/birth-date"
private const val ROUTE_AI_SETTINGS = "settings/ai"
private const val ROUTE_AI_USAGE = "settings/ai/usage"
private const val ROUTE_DIARY_EDIT = "diary/edit"
private const val ROUTE_DIARY_VIEW = "diary/view/{date}"
private const val ROUTE_ABOUT = "about"
private const val ROUTE_ABOUT_DONATE = "about/donate"
private const val ROUTE_ABOUT_FEEDBACK = "about/feedback"
private const val ROUTE_ABOUT_CHANGELOG = "about/changelog"
private const val ROUTE_ABOUT_CHANGELOG_VERSION = "about/changelog/{version}"
private const val ARG_CHANGELOG_VERSION = "version"
private const val ROUTE_CHATS = "chats"
private const val ROUTE_DIAGNOSTICS = "diagnostics"
private const val ROUTE_SEARCH = "search"
private const val ROUTE_CALENDAR = "calendar"
private const val ROUTE_PEOPLE = "people"
private const val ROUTE_PERSON = "people/{personId}"
private const val ROUTE_PERSON_EDIT = "people/{personId}/edit"
private const val ROUTE_GALLERY = "gallery?date={date}"
private const val ROUTE_IMAGE = "gallery/{token}"
private const val ROUTE_AI = "ai?personId={personId}"
private const val ROUTE_PERSONA = "ai/persona/{personId}"

/** Read-only preview of the diary written for [date]. */
private fun diaryViewRoute(date: LocalDate): String = "diary/view/$date"

private fun changelogVersionRoute(number: String): String =
    "$ROUTE_ABOUT_CHANGELOG/${Uri.encode(number)}"

private val MAIN_FADE_ROUTES = setOf(
    ROUTE_SETTINGS,
    ROUTE_SEARCH,
    ROUTE_GALLERY,
    ROUTE_CHATS,
    ROUTE_PEOPLE,
    ROUTE_AI,
)

private fun usesMainFadeTransition(route: String?): Boolean = route != null && route in MAIN_FADE_ROUTES

private fun fadeThroughEnterTransition(): EnterTransition =
    fadeIn(tween(durationMillis = SETTINGS_ENTER_MS, easing = FastOutSlowInEasing)) +
        scaleIn(
            initialScale = SETTINGS_ENTER_SCALE,
            animationSpec = tween(
                durationMillis = SETTINGS_ENTER_MS,
                easing = FastOutSlowInEasing,
            ),
        )

private fun fadeThroughExitTransition(): ExitTransition =
    fadeOut(tween(durationMillis = SETTINGS_EXIT_MS, easing = FastOutLinearInEasing))

private fun Context.openInBrowser(url: String, failureMessage: String? = null) {
    val opened = runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!opened && failureMessage != null) {
        Toast.makeText(this, failureMessage, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun updateStatusText(state: UpdateState): String = when (state) {
    UpdateState.Idle -> stringResource(SettingsR.string.settings_update_idle)
    UpdateState.Checking -> stringResource(SettingsR.string.settings_update_checking)
    UpdateState.UpToDate -> stringResource(SettingsR.string.settings_update_current)
    is UpdateState.Downloading ->
        stringResource(SettingsR.string.settings_update_downloading, state.versionName)
    is UpdateState.ReadyToInstall ->
        stringResource(SettingsR.string.settings_update_ready, state.versionName)
    is UpdateState.InstallPermissionRequired ->
        stringResource(SettingsR.string.settings_update_install_permission)
    is UpdateState.Failed -> when (state.reason) {
        UpdateError.NETWORK -> stringResource(SettingsR.string.settings_update_error_network)
        UpdateError.INVALID_RELEASE -> stringResource(SettingsR.string.settings_update_error_release)
        UpdateError.NO_APK -> stringResource(SettingsR.string.settings_update_error_asset)
        UpdateError.DOWNLOAD -> stringResource(SettingsR.string.settings_update_error_download)
        UpdateError.INSTALL -> stringResource(SettingsR.string.settings_update_error_install)
    }
}

@Composable
private fun LifeTab(
    viewModel: LifeViewModel,
    onOpenBirthDate: () -> Unit,
) {
    // Collected here, not at the route level: the once-per-second tick otherwise
    // recomposes the header, the tab bar and the diary tab as well.
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifeClockScreen(
        state = state,
        onRetry = viewModel::refresh,
        onStart = viewModel::refresh,
        onStop = viewModel::stop,
        onOpenBirthDate = onOpenBirthDate,
    )
}

@Composable
fun MeteorNavHost(
    versionName: String,
    animationsEnabled: Boolean = true,
    aiEnabled: Boolean = false,
) {
    val navController = rememberNavController()

    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (animationsEnabled) {
            if (usesMainFadeTransition(targetState.destination.route)) {
                fadeThroughEnterTransition()
            } else if (initialState.destination.route == ROUTE_SETTINGS) {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(durationMillis = DETAIL_SLIDE_MS, easing = FastOutSlowInEasing),
                )
            } else {
                fadeIn(tween(durationMillis = PAGE_FADE_ENTER_MS, easing = FastOutSlowInEasing))
            }
        } else {
            EnterTransition.None
        }
    }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (animationsEnabled) {
            if (usesMainFadeTransition(targetState.destination.route)) {
                fadeThroughExitTransition()
            } else if (initialState.destination.route == ROUTE_SETTINGS) {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(durationMillis = DETAIL_SLIDE_MS, easing = FastOutSlowInEasing),
                )
            } else {
                fadeOut(tween(durationMillis = PAGE_FADE_EXIT_MS, easing = FastOutLinearInEasing))
            }
        } else {
            ExitTransition.None
        }
    }
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (animationsEnabled) {
            if (
                targetState.destination.route == ROUTE_SETTINGS &&
                initialState.destination.route != ROUTE_SETTINGS
            ) {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(durationMillis = DETAIL_SLIDE_MS, easing = FastOutSlowInEasing),
                )
            } else if (usesMainFadeTransition(targetState.destination.route)) {
                fadeThroughEnterTransition()
            } else {
                fadeIn(tween(durationMillis = PAGE_FADE_ENTER_MS, easing = FastOutSlowInEasing))
            }
        } else {
            EnterTransition.None
        }
    }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (animationsEnabled) {
            if (
                targetState.destination.route == ROUTE_SETTINGS &&
                initialState.destination.route != ROUTE_SETTINGS
            ) {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(durationMillis = DETAIL_SLIDE_MS, easing = FastOutSlowInEasing),
                )
            } else if (usesMainFadeTransition(initialState.destination.route)) {
                fadeThroughExitTransition()
            } else {
                fadeOut(tween(durationMillis = PAGE_FADE_EXIT_MS, easing = FastOutLinearInEasing))
            }
        } else {
            ExitTransition.None
        }
    }

    NavHost(
        navController = navController,
        startDestination = ROUTE_CALENDAR,
        enterTransition = enter,
        exitTransition = exit,
        popEnterTransition = popEnter,
        popExitTransition = popExit,
    ) {
        composable(ROUTE_CALENDAR) {
            val viewModel: CalendarViewModel = hiltViewModel()
            val lifeViewModel: LifeViewModel = hiltViewModel()
            val month by viewModel.month.collectAsStateWithLifecycle()
            val moods by viewModel.moods.collectAsStateWithLifecycle()
            val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
            val homeTab by viewModel.homeTab.collectAsStateWithLifecycle()
            val diaryDates by viewModel.diaryDates.collectAsStateWithLifecycle()
            val imageDates by viewModel.imageDates.collectAsStateWithLifecycle()

            var pendingImageDate by remember { mutableStateOf<LocalDate?>(null) }
            val pickImage = rememberImagePicker { bytes ->
                pendingImageDate?.let { viewModel.saveImage(it, bytes) }
                pendingImageDate = null
            }

            RefreshOnResume { viewModel.refresh() }

            HomeTabsScreen(
                appName = stringResource(R.string.app_name),
                onOpenSearch = { navController.navigate(ROUTE_SEARCH) },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                selectedTab = homeTab,
                onSelectTab = viewModel::selectHomeTab,
                lifeContent = {
                    LifeTab(
                        viewModel = lifeViewModel,
                        onOpenBirthDate = { navController.navigate(ROUTE_BIRTH_DATE) },
                    )
                },
                diaryContent = {
                    CalendarScreen(
                        appName = stringResource(R.string.app_name),
                        month = month,
                        today = LocalDate.now(),
                        moods = moods,
                        selectedDate = selectedDate,
                        diaryDates = diaryDates,
                        imageDates = imageDates,
                        onPreviousMonth = viewModel::previousMonth,
                        onNextMonth = viewModel::nextMonth,
                        onToday = viewModel::goToToday,
                        onSelectDate = viewModel::selectDate,
                        onToggleMood = viewModel::toggleMood,
                        onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                        onOpenDiary = { date -> navController.navigate(diaryViewRoute(date)) },
                        onEditDiary = { date -> navController.navigate("$ROUTE_DIARY_EDIT/$date") },
                        onOpenImages = { date -> navController.navigate("gallery?date=$date") },
                        onPickImage = { date ->
                            pendingImageDate = date
                            pickImage()
                        },
                        onOpenSearch = { navController.navigate(ROUTE_SEARCH) },
                        onOpenAi = if (aiEnabled) {
                            { navController.navigate("ai") }
                        } else {
                            null
                        },
                        onOpenGallery = { navController.navigate("gallery") },
                        onOpenChats = { navController.navigate(ROUTE_CHATS) },
                        onOpenPeople = { navController.navigate(ROUTE_PEOPLE) },
                        showHeader = false,
                    )
                },
            )
        }

        composable(ROUTE_PEOPLE) {
            val viewModel: PeopleViewModel = hiltViewModel()
            val people by viewModel.people.collectAsStateWithLifecycle()
            val availableTags by viewModel.customTags.collectAsStateWithLifecycle()
            val context = LocalContext.current

            RefreshOnResume { viewModel.refresh() }

            PeopleListScreen(
                people = people.map { entry ->
                    PeopleRow(
                        personId = entry.personId,
                        displayName = entry.displayName ?: entry.personId.take(8),
                        relation = entry.relation,
                        secondary = context.getString(
                            if (entry.hasProfile) {
                                R.string.people_secondary_profile
                            } else {
                                R.string.people_secondary_missing
                            },
                        ),
                        tags = entry.tags,
                    )
                },
                availableTags = availableTags,
                onCreate = viewModel::create,
                onAddTag = viewModel::addTag,
                onDeleteTag = viewModel::removeTag,
                onOpen = { personId -> navController.navigate("people/$personId") },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = ROUTE_PERSON,
            arguments = listOf(navArgument(PersonDetailViewModel.ARG_PERSON_ID) { type = NavType.StringType }),
        ) { entry ->
            val viewModel: PersonDetailViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val personId = entry.arguments?.getString(PersonDetailViewModel.ARG_PERSON_ID).orEmpty()
            val pickAttachments = rememberAttachmentPicker(viewModel::importAttachments)
            var pendingExportId by remember { mutableStateOf<String?>(null) }
            var pendingExportName by remember { mutableStateOf("attachment") }
            val exporter = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/octet-stream"),
            ) { uri ->
                val id = pendingExportId
                pendingExportId = null
                if (uri != null && id != null) viewModel.export(id, uri)
            }

            RefreshOnResume { viewModel.load() }

            LaunchedEffect(state.deleted) {
                if (state.deleted) navController.popBackStack()
            }

            PersonDetailScreen(
                displayName = state.displayName,
                relation = state.relation,
                tags = state.tags,
                note = state.note,
                notes = state.notes,
                records = state.records.map {
                    PersonRecordRow(
                        recordId = it.recordId,
                        dateLabel = it.dateLabel,
                        typeLabel = recordTypeLabel(it.type),
                    )
                } + state.attachments.map { attachment -> attachment.toRecordRow() },
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate("people/$personId/edit") },
                resolveImage = viewModel::imageFor,
                onActivate = { target ->
                    if (target.startsWith("meteor://image/")) {
                        navController.navigate(
                            "gallery/${Uri.encode(target.removePrefix("meteor://image/"))}",
                        )
                    }
                },
                onAddRecord = pickAttachments,
                attachmentsError = state.attachmentsError,
                importingAttachments = state.isImporting,
                importedAttachmentCount = state.importSummary?.imported,
                failedAttachmentCount = state.importSummary?.failed ?: 0,
                skippedAttachmentCount = state.importSummary?.skipped ?: 0,
                attachmentIndexFailed = state.importSummary?.indexFailed == true,
                onExportAttachment = { id ->
                    val attachment = state.attachments.firstOrNull { it.id == id }
                    if (attachment != null) {
                        pendingExportId = attachment.id
                        pendingExportName = attachment.fileName
                        exporter.launch(pendingExportName)
                    }
                },
                exportFailed = state.exportFailed,
                onDelete = viewModel::delete,
                deleting = state.deleting,
                deleteFailed = state.deleteFailed,
            )
        }

        composable(
            route = ROUTE_PERSON_EDIT,
            arguments = listOf(navArgument(PersonEditViewModel.ARG_PERSON_ID) { type = NavType.StringType }),
        ) {
            val viewModel: PersonEditViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val pickImage = rememberImagePicker(viewModel::insertImage)

            PersonEditScreen(
                displayName = state.displayName,
                relation = state.relation,
                tags = state.tags,
                availableTags = state.customTags,
                note = state.note,
                notes = state.notes,
                saving = state.saving,
                insertingImage = state.insertingImage,
                saved = state.saved,
                error = state.error,
                onNameChanged = viewModel::onNameChanged,
                onRelationChanged = viewModel::onRelationChanged,
                onTagsChanged = viewModel::onTagsChanged,
                onAddTag = viewModel::addTag,
                onDeleteTag = viewModel::removeTag,
                onNoteChanged = viewModel::onNoteChanged,
                onNotesChanged = viewModel::onNotesChanged,
                onInsertLink = viewModel::insertLink,
                onPickImage = pickImage,
                onRemoveImage = viewModel::removeImage,
                onSave = viewModel::save,
                onBack = { navController.popBackStack() },
                missingImages = state.missingImages,
                resolveImage = viewModel::imageFor,
            )
        }

        composable(
            route = ROUTE_GALLERY,
            arguments = listOf(
                navArgument(GalleryViewModel.ARG_DATE) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            val viewModel: GalleryViewModel = hiltViewModel()
            val days by viewModel.days.collectAsStateWithLifecycle()
            val loading by viewModel.loading.collectAsStateWithLifecycle()
            val filterLabel by viewModel.filterLabel.collectAsStateWithLifecycle()

            RefreshOnResume { viewModel.refresh() }

            GalleryScreen(
                days = days,
                loading = loading,
                animationsEnabled = animationsEnabled,
                onOpen = { token -> navController.navigate("gallery/${Uri.encode(token)}") },
                onDelete = viewModel::delete,
                onBack = { navController.popBackStack() },
                title = filterLabel,
            )
        }

        composable(
            route = ROUTE_IMAGE,
            arguments = listOf(navArgument(ImageViewerViewModel.ARG_TOKEN) { type = NavType.StringType }),
        ) {
            val viewModel: ImageViewerViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val context = LocalContext.current

            LaunchedEffect(state.deleted) {
                if (state.deleted) navController.popBackStack()
            }

            LaunchedEffect(state.shareRequest) {
                val request = state.shareRequest ?: return@LaunchedEffect
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = request.mimeType
                    putExtra(Intent.EXTRA_STREAM, request.uri)
                    clipData = ClipData.newUri(
                        context.contentResolver,
                        context.getString(GalleryR.string.gallery_share_clip_label),
                        request.uri,
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val launched = runCatching {
                    context.startActivity(
                        Intent.createChooser(
                            send,
                            context.getString(GalleryR.string.gallery_share_chooser),
                        ),
                    )
                }.isSuccess
                if (launched) viewModel.onShareLaunched() else viewModel.onShareLaunchedFailed()
            }

            ImageViewerScreen(
                bitmap = state.bitmap,
                caption = stringResource(GalleryR.string.gallery_title),
                dateLabel = state.dateLabel,
                onBack = { navController.popBackStack() },
                onShare = viewModel::share,
                onDelete = viewModel::delete,
                loading = state.loading,
                animationsEnabled = animationsEnabled,
                deleting = state.deleting,
                sharing = state.sharing,
                error = state.error,
            )
        }

        composable(
            route = ROUTE_AI,
            arguments = listOf(
                navArgument(AiViewModel.ARG_PERSON_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            val viewModel: AiViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val pickAiFiles = rememberAiFilePicker(viewModel::addFiles)

            RefreshOnResume { viewModel.refresh() }

            AiScreen(
                providerLabel = aiProviderLabel(state.provider),
                skills = state.skills.map { skill ->
                    AiSkillChip(skill.name, aiSkillLabel(skill.name), skill.requiresPerson)
                },
                selectedSkillId = state.selectedSkill,
                people = state.people.map { AiPersonChip(it.personId, it.displayName ?: it.personId.take(8)) },
                selectedPersonId = state.selectedPersonId,
                messages = state.messages,
                busy = state.busy,
                error = aiErrorText(state.errorCode, state.errorDetail),
                blockedMessage = aiBlockedText(state.blocked),
                personaStatus = state.selectedPersonId?.let {
                    stringResource(
                        if (state.personaDistilled) AiR.string.ai_persona_ready else AiR.string.ai_persona_missing,
                    )
                },
                sessionUsage = state.sessionUsage,
                sessionElapsedMs = state.sessionElapsedMs,
                streaming = state.streaming,
                liveContent = state.liveContent,
                liveSteps = state.liveSteps,
                pendingToolApprovals = state.pendingToolApprovals,
                chatMode = state.chatMode,
                reasoning = state.reasoning,
                usageTotals = state.usageTotals,
                speechLoadingId = state.speechLoadingId,
                speakingId = state.speakingId,
                conversations = state.conversations,
                activeConversationId = state.activeConversationId,
                pendingFiles = state.pendingFiles,
                fileUploading = state.fileUploading,
                canUploadFiles = state.canUploadFiles,
                canUseWebTools = state.canUseWebTools,
                onSkillSelect = viewModel::selectSkill,
                onPersonSelect = viewModel::selectPerson,
                onSend = viewModel::send,
                onOpenPersona = { personId -> navController.navigate("ai/persona/$personId") },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                onOpenAiSettings = { navController.navigate(ROUTE_AI_SETTINGS) },
                onOpenUsage = { navController.navigate(ROUTE_AI_USAGE) },
                onClear = viewModel::clear,
                onToolApproval = viewModel::decideToolApproval,
                onBack = { navController.popBackStack() },
                onChatModeSelect = viewModel::setChatMode,
                onReasoningChange = viewModel::setReasoning,
                onToggleSpeech = viewModel::toggleSpeech,
                onNewConversation = viewModel::newConversation,
                onSelectConversation = viewModel::selectConversation,
                onRenameConversation = viewModel::renameConversation,
                onDeleteConversation = viewModel::deleteConversation,
                onPickFiles = pickAiFiles,
                onRemovePendingFile = viewModel::removePendingFile,
                onEditMessage = viewModel::editMessage,
                onRegenerate = viewModel::regenerate,
                onContinue = viewModel::continueResponse,
            )
        }

        composable(
            route = ROUTE_PERSONA,
            arguments = listOf(navArgument(PersonaViewModel.ARG_PERSON_ID) { type = NavType.StringType }),
        ) { entry ->
            val viewModel: PersonaViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val personId = entry.arguments?.getString(PersonaViewModel.ARG_PERSON_ID).orEmpty()

            RefreshOnResume { viewModel.load() }

            val providerLabel = aiProviderLabel(state.provider)
            PersonaScreen(
                persona = state.ui.copy(providerLabel = state.ui.providerLabel?.let { aiProviderLabel(it) }),
                providerLabel = providerLabel,
                sendsTo = providerLabel,
                busy = state.busy,
                error = aiErrorText(state.errorCode, state.errorDetail),
                blockedMessage = aiBlockedText(state.blocked),
                onDistill = viewModel::distill,
                onOpenChat = { navController.navigate("ai?personId=${Uri.encode(personId)}") },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(ROUTE_SEARCH) {
            val viewModel: SearchViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val query by viewModel.query.collectAsStateWithLifecycle()

            // Search is opened from the home screen, which stays below it on
            // the back stack. Reuse its view model so a mood hit can focus
            // that day on the calendar after popping back.
            val calendarEntry = remember(navController) {
                runCatching { navController.getBackStackEntry(ROUTE_CALENDAR) }.getOrNull()
            }
            val calendarViewModel: CalendarViewModel? = if (calendarEntry != null) {
                hiltViewModel<CalendarViewModel>(calendarEntry)
            } else {
                null
            }

            SearchScreen(
                state = state,
                query = query,
                onQueryChange = viewModel::onQueryChange,
                onOpenDiary = { date -> navController.navigate(diaryViewRoute(date)) },
                onOpenMood = { date ->
                    calendarViewModel?.showDate(date)
                    navController.popBackStack(ROUTE_CALENDAR, inclusive = false)
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(ROUTE_DIAGNOSTICS) {
            StorageDiagnosticsScreen(onBack = { navController.popBackStack() })
        }

        composable(ROUTE_BIRTH_DATE) {
            BirthDateSettingsScreen(
                onSaved = { navController.popBackStack() },
            )
        }

        composable(ROUTE_SETTINGS) {
            val viewModel: SettingsViewModel = hiltViewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val logSizeBytes by viewModel.logSizeBytes.collectAsStateWithLifecycle()
            val notificationsAllowed by viewModel.notificationsAllowed
                .collectAsStateWithLifecycle()
            val canScheduleExactAlarms by viewModel.canScheduleExactAlarms.collectAsStateWithLifecycle()
            val updateState by viewModel.updateState.collectAsStateWithLifecycle()
            val appLockUnavailable by viewModel.appLockUnavailable.collectAsStateWithLifecycle()
            val context = LocalContext.current
            var notificationPermissionDenied by rememberSaveable { mutableStateOf(false) }
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) {
                    notificationPermissionDenied = false
                    viewModel.setNotificationsEnabled(true)
                } else {
                    notificationPermissionDenied = true
                }
            }

            RefreshOnResume {
                viewModel.refreshLogSize()
                viewModel.refreshNotificationPermissions()
                viewModel.refreshUpdateInstallPermission()
            }

            SettingsScreen(
                settings = settings,
                versionName = versionName,
                onDarkModeChanged = viewModel::setDarkMode,
                onDynamicColorChanged = viewModel::setDynamicColor,
                onAnimationsChanged = viewModel::setAnimationsEnabled,
                onAppLockChanged = viewModel::setAppLockEnabled,
                appLockUnavailable = appLockUnavailable,
                onDismissAppLockUnavailable = viewModel::dismissAppLockUnavailable,
                onOpenSecuritySettings = { openSecuritySettings(context) },
                onScreenshotProtectionChanged = viewModel::setScreenshotProtectionEnabled,
                onVerboseLoggingChanged = viewModel::setVerboseLogging,
                onOpenAbout = { navController.navigate(ROUTE_ABOUT) },
                onOpenDonate = { navController.navigate(ROUTE_ABOUT_DONATE) },
                onOpenBirthDate = { navController.navigate(ROUTE_BIRTH_DATE) },
                onAutoCheckForUpdatesChanged = viewModel::setAutoCheckForUpdates,
                updateStatusText = updateStatusText(updateState),
                updateBusy = updateState is UpdateState.Checking || updateState is UpdateState.Downloading,
                updateCanInstall = updateState is UpdateState.ReadyToInstall ||
                    updateState is UpdateState.InstallPermissionRequired,
                onCheckUpdates = viewModel::checkForUpdates,
                onInstallUpdate = viewModel::installUpdate,
                logSizeBytes = logSizeBytes,
                notificationsAllowed = notificationsAllowed,
                canScheduleExactAlarms = canScheduleExactAlarms,
                notificationPermissionDenied = notificationPermissionDenied,
                onNotificationsEnabledChanged = { enabled ->
                    if (!enabled) {
                        viewModel.setNotificationsEnabled(false)
                    } else if (
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionDenied = false
                        viewModel.setNotificationsEnabled(true)
                    } else {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onOpenExactAlarmSettings = viewModel::openExactAlarmSettings,
                onOpenNotificationSettings = viewModel::openNotificationSettings,
                onDismissNotificationPermissionDenied = { notificationPermissionDenied = false },
                onBack = { navController.popBackStack() },
                onOpenDiagnostics = if (BuildConfig.DEBUG) {
                    { navController.navigate(ROUTE_DIAGNOSTICS) }
                } else {
                    null
                },
                backupActions = { BackupActions() },
                logActions = { LogActions() },
                aiActions = {
                    AiSettingsSummary(
                        enabled = settings.aiEnabled,
                        consentAccepted = settings.aiConsentAccepted,
                        onEnabledChange = viewModel::setAiEnabled,
                        onConsentAccepted = viewModel::acceptAiConsent,
                        onOpenSettings = { navController.navigate(ROUTE_AI_SETTINGS) },
                        usageTotals = settings.aiUsageTotals(),
                        onOpenUsage = { navController.navigate(ROUTE_AI_USAGE) },
                    )
                },
            )
        }

        composable(ROUTE_AI_SETTINGS) {
            val viewModel: SettingsViewModel = hiltViewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val apiKeyPresent by viewModel.apiKeyPresent.collectAsStateWithLifecycle()
            val aiTest by viewModel.aiTest.collectAsStateWithLifecycle()
            val aiModels by viewModel.aiModels.collectAsStateWithLifecycle()

            AiSettingsScreen(
                enabled = settings.aiEnabled,
                consentAccepted = settings.aiConsentAccepted,
                model = settings.aiModel,
                reasoning = settings.aiReasoning,
                ttsVoice = settings.aiTtsVoice,
                apiKeyPresent = apiKeyPresent,
                models = aiModels.items,
                modelsLoading = aiModels.loading,
                modelsFailed = aiModels.failed,
                writeEnabled = settings.aiWriteEnabled,
                writeDiary = settings.aiWriteDiary,
                writePeople = settings.aiWritePeople,
                testing = aiTest.running,
                testMessage = aiTestText(aiTest),
                onModelChange = viewModel::setAiModel,
                onReasoningChange = viewModel::setAiReasoning,
                onTtsVoiceChange = viewModel::setAiTtsVoice,
                onRefreshModels = viewModel::refreshAiModels,
                onWriteChange = viewModel::setAiWriteEnabled,
                onWriteDiaryChange = viewModel::setAiWriteDiary,
                onWritePeopleChange = viewModel::setAiWritePeople,
                onSaveApiKey = viewModel::saveApiKey,
                onClearApiKey = viewModel::clearApiKey,
                onRequestApiKey = viewModel::apiKeyOnce,
                onTestConnection = viewModel::testAiConnection,
                onBack = { navController.popBackStack() },
                animationsEnabled = animationsEnabled,
            )
        }

        composable(ROUTE_AI_USAGE) {
            val viewModel: SettingsViewModel = hiltViewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()

            AiUsageScreen(
                usageTotals = settings.aiUsageTotals(),
                onBack = { navController.popBackStack() },
            )
        }

        composable(ROUTE_ABOUT) {
            val context = LocalContext.current
            val linkUnavailable = stringResource(R.string.about_link_unavailable)
            AboutScreen(
                versionName = versionName,
                onOpenChangelog = { navController.navigate(ROUTE_ABOUT_CHANGELOG) },
                onOpenRepository = { context.openInBrowser(REPOSITORY_URL, linkUnavailable) },
                onOpenDiscussions = { context.openInBrowser(DISCUSSIONS_URL, linkUnavailable) },
                onOpenFeedback = { navController.navigate(ROUTE_ABOUT_FEEDBACK) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(ROUTE_ABOUT_FEEDBACK) {
            val context = LocalContext.current
            val linkUnavailable = stringResource(R.string.about_link_unavailable)
            FeedbackScreen(
                onOpenIssues = { context.openInBrowser(ISSUES_URL, linkUnavailable) },
                onOpenEmail = { context.openInBrowser(mailtoUrl(SUPPORT_EMAIL), linkUnavailable) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(ROUTE_ABOUT_DONATE) {
            val context = LocalContext.current
            val openFailed = stringResource(R.string.about_donate_open_failed)
            DonateScreen(
                onOpenAlipay = { context.openInBrowser(ALIPAY_DONATE_URL, openFailed) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(ROUTE_ABOUT_CHANGELOG) {
            ChangelogScreen(
                onOpenVersion = { number ->
                    navController.navigate(changelogVersionRoute(number))
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = ROUTE_ABOUT_CHANGELOG_VERSION,
            arguments = listOf(navArgument(ARG_CHANGELOG_VERSION) { type = NavType.StringType }),
        ) { entry ->
            ChangelogVersionScreen(
                versionNumber = entry.arguments?.getString(ARG_CHANGELOG_VERSION).orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }

        composable(ROUTE_CHATS) {
            val viewModel: ChatsViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val pickAttachments = rememberAttachmentPicker(viewModel::importFiles)
            var pendingExportId by remember { mutableStateOf<String?>(null) }
            var pendingExportName by remember { mutableStateOf("attachment") }
            val exporter = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/octet-stream"),
            ) { uri ->
                val id = pendingExportId
                pendingExportId = null
                if (uri != null && id != null) viewModel.export(id, uri)
            }
            RefreshOnResume { viewModel.refresh() }

            ChatsScreen(
                state = state,
                onPersonSelected = viewModel::selectPerson,
                onImportFiles = pickAttachments,
                onExport = { row ->
                    pendingExportId = row.id
                    pendingExportName = row.fileName
                    exporter.launch(pendingExportName)
                },
                onDelete = { row -> viewModel.delete(row.id) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = ROUTE_DIARY_VIEW,
            arguments = listOf(navArgument(DiaryPreviewViewModel.ARG_DATE) { type = NavType.StringType }),
        ) { entry ->
            val viewModel: DiaryPreviewViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val date = entry.arguments?.getString(DiaryPreviewViewModel.ARG_DATE).orEmpty()
            val context = LocalContext.current

            RefreshOnResume { viewModel.load() }

            DiaryPreviewScreen(
                state = state,
                onEdit = { navController.navigate("$ROUTE_DIARY_EDIT/$date") },
                onBack = { navController.popBackStack() },
                onActivate = { target ->
                    when {
                        target.startsWith("meteor://image/") ->
                            navController.navigate(
                                "gallery/${Uri.encode(target.removePrefix("meteor://image/"))}",
                            )

                        target.startsWith("meteor://person/") ->
                            navController.navigate(
                                "people/${Uri.encode(target.removePrefix("meteor://person/"))}",
                            )

                        target.startsWith("meteor://diary/") ->
                            navController.navigate(
                                "$ROUTE_DIARY_EDIT/${target.removePrefix("meteor://diary/")}",
                            )

                        else -> context.openInBrowser(target)
                    }
                },
            )
        }

        composable(
            route = "$ROUTE_DIARY_EDIT/{${DiaryEditViewModel.ARG_DATE}}",
            arguments = listOf(navArgument(DiaryEditViewModel.ARG_DATE) { type = NavType.StringType }),
        ) {
            val viewModel: DiaryEditViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()

            val context = LocalContext.current
            val pickImage = rememberImagePicker(viewModel::insertImage)

            DiaryEditScreen(
                state = state,
                onTextChanged = viewModel::onTextChanged,
                onInsertLink = viewModel::insertLink,
                onPickImage = pickImage,
                onRemoveImage = viewModel::removeImage,
                onSave = viewModel::save,
                onBack = { navController.popBackStack() },
                resolveImage = viewModel::imageFor,
                onActivate = { target ->
                    when {
                        target.startsWith("meteor://image/") ->
                            navController.navigate(
                                "gallery/${Uri.encode(target.removePrefix("meteor://image/"))}",
                            )

                        target.startsWith("meteor://person/") ->
                            navController.navigate(
                                "people/${Uri.encode(target.removePrefix("meteor://person/"))}",
                            )

                        target.startsWith("meteor://diary/") ->
                            navController.navigate(
                                "$ROUTE_DIARY_EDIT/${target.removePrefix("meteor://diary/")}",
                            )

                        else -> context.openInBrowser(target)
                    }
                },
            )
        }
    }
}

@Composable
private fun recordTypeLabel(type: RecordType): String = stringResource(
    when (type) {
        RecordType.DIARY -> R.string.type_diary
        RecordType.MOOD -> R.string.type_mood
        RecordType.PROFILE -> R.string.type_profile
        RecordType.CHAT_LOG -> R.string.type_chat_log
        RecordType.PERSONA -> R.string.type_persona
    },
)

/** Human label for a bundled skill; unknown names fall back to the raw id. */
@Composable
private fun aiSkillLabel(name: String): String = when (name) {
    "meteor-persona-chat" -> stringResource(AiR.string.ai_skill_persona_chat)
    "meteor-persona-distill" -> stringResource(AiR.string.ai_skill_persona_distill)
    "meteor-archive-qa" -> stringResource(AiR.string.ai_skill_archive_qa)
    else -> name
}

/** Maps a provider error to copy that never includes archive content. */
@Composable
private fun aiErrorText(code: Int, detail: String?): String? {
    if (code == 0 && detail == null) return null
    return when {
        detail == "web-tools-unavailable" ||
            detail?.startsWith("web-tools-require-") == true -> stringResource(AiR.string.ai_web_unavailable)
        detail == "files-require-deepseek-flash" -> stringResource(AiR.string.ai_file_not_supported)
        detail == "file-delete-failed" -> stringResource(AiR.string.ai_file_delete_error)
        detail == "file-limit" -> stringResource(AiR.string.ai_file_limit)
        detail == "file-expired" -> stringResource(AiR.string.ai_file_expired)
        detail == "unsupported-file-type" ||
            detail == "file-size-out-of-range" ||
            detail?.startsWith("file-") == true -> stringResource(AiR.string.ai_file_error)
        detail?.startsWith("chat-store-") == true -> stringResource(AiR.string.ai_chat_storage_error)
        detail == "not-configured" || detail == "missing-api-key" -> stringResource(AiR.string.ai_need_config)
        detail == "empty-response" -> stringResource(AiR.string.ai_error_empty)
        detail == "content-filter" -> stringResource(AiR.string.ai_error_content_filter)
        detail == "tts-unavailable" -> stringResource(AiR.string.ai_speech_unavailable)
        detail == "tts-failed" -> stringResource(AiR.string.ai_speech_failed)
        code == 401 || code == 403 -> stringResource(AiR.string.ai_error_auth)
        code == 429 -> stringResource(AiR.string.ai_error_rate)
        code >= 500 || code == 0 -> stringResource(AiR.string.ai_error_network)
        else -> stringResource(AiR.string.ai_error_other, detail ?: code.toString())
    }
}

@Composable
private fun aiBlockedText(blocked: AiBlocked?): String? = when (blocked) {
    AiBlocked.DISABLED -> stringResource(AiR.string.ai_need_disabled)
    AiBlocked.NEEDS_CONSENT -> stringResource(AiR.string.ai_need_consent)
    AiBlocked.NEEDS_KEY -> stringResource(AiR.string.ai_need_key)
    AiBlocked.NEEDS_CONFIG -> stringResource(AiR.string.ai_need_config)
    null -> null
}

@Composable
private fun aiTestText(test: AiTestState): String? = when {
    !test.finished -> null
    test.ok -> stringResource(AiR.string.ai_settings_test_ok)
    else -> stringResource(
        AiR.string.ai_settings_test_fail,
        if (test.code == 0) stringResource(AiR.string.ai_need_config) else "HTTP ${test.code}",
    )
}

/**
 * System image picker, reduced to "read these bytes".
 *
 * Shared by the diary and person editors: both hand the result to their view
 * model, which compresses, encrypts and appends the reference itself.
 */
@Composable
private fun rememberImagePicker(onBytes: (ByteArray) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openInputStream(uri)?.readBytes() }
                        .getOrNull()
                }
                if (bytes != null) onBytes(bytes)
            }
        }
    }
    return { launcher.launch("image/*") }
}

@Composable
private fun rememberAttachmentPicker(onUris: (List<Uri>) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onUris(uris)
    }
    return { launcher.launch(arrayOf("*/*")) }
}

@Composable
private fun rememberAiFilePicker(onUris: (List<Uri>) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onUris(uris)
    }
    return { launcher.launch(arrayOf("*/*")) }
}

private fun PersonAttachmentView.toRecordRow(): PersonRecordRow =
    PersonRecordRow(
        recordId = id,
        dateLabel = dateLabel,
        typeLabel = mimeType,
        title = fileName,
        sizeBytes = sizeBytes,
    )

@Composable
private fun RefreshOnResume(block: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) block()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
