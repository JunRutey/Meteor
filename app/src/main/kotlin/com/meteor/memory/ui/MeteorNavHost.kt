package com.meteor.memory.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
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
import com.meteor.memory.R
import com.meteor.memory.core.storage.RecordType
import com.meteor.memory.feature.ai.AiPersonChip
import com.meteor.memory.feature.ai.AiScreen
import com.meteor.memory.feature.ai.AiSettingsSection
import com.meteor.memory.feature.ai.R as AiR
import com.meteor.memory.feature.ai.AiSkillChip
import com.meteor.memory.feature.ai.PersonaScreen
import com.meteor.memory.feature.ai.aiProviderLabel
import com.meteor.memory.feature.calendar.CalendarScreen
import com.meteor.memory.feature.gallery.GalleryScreen
import com.meteor.memory.feature.gallery.ImageViewerScreen
import com.meteor.memory.feature.people.PeopleListScreen
import com.meteor.memory.feature.people.PeopleRow
import com.meteor.memory.feature.people.PersonDetailScreen
import com.meteor.memory.feature.people.PersonEditScreen
import com.meteor.memory.feature.people.PersonRecordRow
import com.meteor.memory.feature.settings.SettingsScreen
import com.meteor.memory.ui.about.AboutScreen
import com.meteor.memory.ui.ai.AiBlocked
import com.meteor.memory.ui.ai.AiViewModel
import com.meteor.memory.ui.ai.PersonaViewModel
import com.meteor.memory.ui.backup.BackupActions
import com.meteor.memory.ui.chats.ChatsScreen
import com.meteor.memory.ui.calendar.CalendarViewModel
import com.meteor.memory.ui.diagnostics.StorageDiagnosticsScreen
import com.meteor.memory.ui.diary.DiaryEditScreen
import com.meteor.memory.ui.diary.DiaryEditViewModel
import com.meteor.memory.ui.diary.DiaryPreviewScreen
import com.meteor.memory.ui.diary.DiaryPreviewViewModel
import com.meteor.memory.ui.gallery.GalleryViewModel
import com.meteor.memory.ui.gallery.ImageViewerViewModel
import com.meteor.memory.ui.log.LogActions
import com.meteor.memory.ui.people.PeopleViewModel
import com.meteor.memory.ui.people.PersonDetailViewModel
import com.meteor.memory.ui.people.PersonEditViewModel
import com.meteor.memory.ui.search.SearchScreen
import com.meteor.memory.ui.search.SearchViewModel
import com.meteor.memory.ui.settings.AiTestState
import com.meteor.memory.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_DIARY_EDIT = "diary/edit"
private const val ROUTE_DIARY_VIEW = "diary/view/{date}"
private const val ROUTE_ABOUT = "about"
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

/** Shown in the About section; must match README.md. */
private const val REPOSITORY_URL = "https://github.com/JunRutey/Meteor"

private fun Context.openInBrowser(url: String) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
fun MeteorNavHost(
    versionName: String,
    animationsEnabled: Boolean = true,
    aiEnabled: Boolean = true,
) {
    val navController = rememberNavController()

    // Archive R11: turning animations off must kill screen transitions too.
    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (animationsEnabled) fadeIn(tween(durationMillis = 220)) else EnterTransition.None
    }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (animationsEnabled) fadeOut(tween(durationMillis = 180)) else ExitTransition.None
    }

    NavHost(
        navController = navController,
        startDestination = ROUTE_CALENDAR,
        enterTransition = enter,
        exitTransition = exit,
        popEnterTransition = enter,
        popExitTransition = exit,
    ) {
        composable(ROUTE_CALENDAR) {
            val viewModel: CalendarViewModel = hiltViewModel()
            val month by viewModel.month.collectAsStateWithLifecycle()
            val moods by viewModel.moods.collectAsStateWithLifecycle()
            val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
            val diaryDates by viewModel.diaryDates.collectAsStateWithLifecycle()
            val imageDates by viewModel.imageDates.collectAsStateWithLifecycle()

            var pendingImageDate by remember { mutableStateOf<LocalDate?>(null) }
            val pickImage = rememberImagePicker { bytes ->
                pendingImageDate?.let { viewModel.saveImage(it, bytes) }
                pendingImageDate = null
            }

            RefreshOnResume { viewModel.refresh() }

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
                onSelectDate = viewModel::selectDate,
                onPickMood = viewModel::setMood,
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                onOpenDiary = { date -> navController.navigate("diary/view/$date") },
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
            )
        }

        composable(ROUTE_PEOPLE) {
            val viewModel: PeopleViewModel = hiltViewModel()
            val people by viewModel.people.collectAsStateWithLifecycle()
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
                    )
                },
                onCreate = viewModel::create,
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

            RefreshOnResume { viewModel.load() }

            PersonDetailScreen(
                displayName = state.displayName,
                relation = state.relation,
                note = state.note,
                notes = state.notes,
                records = state.records.map {
                    PersonRecordRow(
                        recordId = it.recordId,
                        dateLabel = it.dateLabel,
                        typeLabel = recordTypeLabel(it.type),
                    )
                },
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate("people/$personId/edit") },
                onEditNote = { navController.navigate("people/$personId/edit") },
                onEditBody = { navController.navigate("people/$personId/edit") },
                resolveImage = viewModel::imageFor,
                onActivate = { target ->
                    if (target.startsWith("meteor://image/")) {
                        navController.navigate(
                            "gallery/${Uri.encode(target.removePrefix("meteor://image/"))}",
                        )
                    }
                },
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
                note = state.note,
                notes = state.notes,
                saving = state.saving,
                saved = state.saved,
                error = state.error,
                onNameChanged = viewModel::onNameChanged,
                onRelationChanged = viewModel::onRelationChanged,
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
        ) { entry ->
            val viewModel: GalleryViewModel = hiltViewModel()
            val days by viewModel.days.collectAsStateWithLifecycle()
            val loading by viewModel.loading.collectAsStateWithLifecycle()

            RefreshOnResume { viewModel.refresh() }

            GalleryScreen(
                days = days,
                loading = loading,
                onOpen = { token -> navController.navigate("gallery/${Uri.encode(token)}") },
                onDelete = viewModel::delete,
                onBack = { navController.popBackStack() },
                title = entry.arguments?.getString(GalleryViewModel.ARG_DATE),
            )
        }

        composable(
            route = ROUTE_IMAGE,
            arguments = listOf(navArgument(ImageViewerViewModel.ARG_TOKEN) { type = NavType.StringType }),
        ) {
            val viewModel: ImageViewerViewModel = hiltViewModel()
            val bitmap by viewModel.bitmap.collectAsStateWithLifecycle()

            ImageViewerScreen(
                bitmap = bitmap,
                caption = stringResource(R.string.home_open_gallery),
                onBack = { navController.popBackStack() },
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
                liveReasoning = state.liveReasoning,
                liveTools = state.liveTools,
                onSkillSelect = viewModel::selectSkill,
                onPersonSelect = viewModel::selectPerson,
                onSend = viewModel::send,
                onOpenPersona = { personId -> navController.navigate("ai/persona/$personId") },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                onClear = viewModel::clear,
                onBack = { navController.popBackStack() },
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

            SearchScreen(
                state = state,
                query = query,
                onQueryChange = viewModel::onQueryChange,
                onOpenDiary = { date -> navController.navigate("$ROUTE_DIARY_EDIT/$date") },
                onBack = { navController.popBackStack() },
            )
        }

        composable(ROUTE_DIAGNOSTICS) {
            StorageDiagnosticsScreen(onBack = { navController.popBackStack() })
        }

        composable(ROUTE_SETTINGS) {
            val viewModel: SettingsViewModel = hiltViewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val logSizeBytes by viewModel.logSizeBytes.collectAsStateWithLifecycle()
            val apiKeyPresent by viewModel.apiKeyPresent.collectAsStateWithLifecycle()
            val aiTest by viewModel.aiTest.collectAsStateWithLifecycle()
            val aiModels by viewModel.aiModels.collectAsStateWithLifecycle()

            RefreshOnResume { viewModel.refreshLogSize() }

            SettingsScreen(
                settings = settings,
                versionName = versionName,
                onDarkModeChanged = viewModel::setDarkMode,
                onDynamicColorChanged = viewModel::setDynamicColor,
                onAppLockChanged = viewModel::setAppLockEnabled,
                onVerboseLoggingChanged = viewModel::setVerboseLogging,
                onOpenAbout = { navController.navigate(ROUTE_ABOUT) },
                logSizeBytes = logSizeBytes,
                backupActions = { BackupActions() },
                logActions = { LogActions() },
                aiActions = {
                    AiSettingsSection(
                        enabled = settings.aiEnabled,
                        consentAccepted = settings.aiConsentAccepted,
                        provider = settings.aiProvider,
                        baseUrl = settings.aiBaseUrl,
                        model = settings.aiModel,
                        reasoning = settings.aiReasoning,
                        apiKeyPresent = apiKeyPresent,
                        models = aiModels.items,
                        modelsLoading = aiModels.loading,
                        modelsFailed = aiModels.failed,
                        writeEnabled = settings.aiWriteEnabled,
                        testing = aiTest.running,
                        testMessage = aiTestText(aiTest),
                        onEnabledChange = viewModel::setAiEnabled,
                        onConsentAccepted = viewModel::acceptAiConsent,
                        onProviderChange = viewModel::setAiProvider,
                        onBaseUrlChange = viewModel::setAiBaseUrl,
                        onModelChange = viewModel::setAiModel,
                        onReasoningChange = viewModel::setAiReasoning,
                        onRefreshModels = viewModel::refreshAiModels,
                        onWriteChange = viewModel::setAiWriteEnabled,
                        onSaveApiKey = viewModel::saveApiKey,
                        onClearApiKey = viewModel::clearApiKey,
                        onTestConnection = viewModel::testAiConnection,
                    )
                },
            )
        }

        composable(ROUTE_ABOUT) {
            val context = LocalContext.current
            AboutScreen(
                versionName = versionName,
                onOpenRepository = { context.openInBrowser(REPOSITORY_URL) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(ROUTE_CHATS) {
            ChatsScreen(onBack = { navController.popBackStack() })
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
        detail == "not-configured" || detail == "missing-api-key" -> stringResource(AiR.string.ai_need_config)
        detail == "empty-response" -> stringResource(AiR.string.ai_error_empty)
        detail == "step-limit" -> stringResource(AiR.string.ai_error_step_limit)
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
