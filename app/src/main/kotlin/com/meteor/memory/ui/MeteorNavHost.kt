package com.meteor.memory.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
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
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.meteor.memory.BuildConfig
import com.meteor.memory.R
import com.meteor.memory.core.storage.MediaKind
import com.meteor.memory.core.storage.MediaRef
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
import com.meteor.memory.feature.gallery.FileImportSummary
import com.meteor.memory.feature.gallery.GalleryScreen
import com.meteor.memory.feature.gallery.MediaViewerScreen
import com.meteor.memory.feature.gallery.R as GalleryR
import com.meteor.memory.feature.people.PeopleListScreen
import com.meteor.memory.feature.people.PeopleRow
import com.meteor.memory.feature.people.PersonDetailScreen
import com.meteor.memory.feature.people.PersonEditScreen
import com.meteor.memory.feature.people.PersonRecordRow
import com.meteor.memory.feature.question.QuestionComposeScreen
import com.meteor.memory.feature.question.QuestionDetailScreen
import com.meteor.memory.feature.question.QuestionListScreen
import com.meteor.memory.feature.media.MediaPickerScreen
import com.meteor.memory.feature.media.PickedMedia
import com.meteor.memory.feature.settings.AppearanceSettingsScreen
import com.meteor.memory.feature.settings.MediaPickerMode
import com.meteor.memory.feature.settings.NotificationSettingsScreen
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
import com.meteor.memory.ui.calendar.CalendarViewModel
import com.meteor.memory.ui.diagnostics.StorageDiagnosticsScreen
import com.meteor.memory.ui.diary.DiaryEditScreen
import com.meteor.memory.ui.diary.DiaryEditViewModel
import com.meteor.memory.ui.diary.DiaryPreviewScreen
import com.meteor.memory.ui.diary.DiaryPreviewViewModel
import com.meteor.memory.ui.gallery.GalleryViewModel
import com.meteor.memory.ui.gallery.MediaViewerViewModel
import com.meteor.memory.ui.home.DEFAULT_DRAWER_ORDER
import com.meteor.memory.ui.home.DEFAULT_DRAWER_VISIBLE_ITEMS
import com.meteor.memory.ui.home.HomeTabsScreen
import com.meteor.memory.ui.life.BirthDateSettingsScreen
import com.meteor.memory.ui.life.LifeClockScreen
import com.meteor.memory.ui.life.LifeViewModel
import com.meteor.memory.ui.log.LogActions
import com.meteor.memory.ui.people.PeopleViewModel
import com.meteor.memory.ui.people.PersonDetailViewModel
import com.meteor.memory.ui.people.PersonEditViewModel
import com.meteor.memory.ui.question.QuestionComposeViewModel
import com.meteor.memory.ui.question.QuestionDetailViewModel
import com.meteor.memory.ui.question.QuestionListViewModel
import com.meteor.memory.ui.question.QuestionOpenEntryPoint
import com.meteor.memory.ui.search.SearchScreen
import com.meteor.memory.ui.search.SearchViewModel
import com.meteor.memory.ui.settings.AiTestState
import com.meteor.memory.ui.settings.SettingsViewModel
import com.meteor.memory.ui.theme.MotionTokens
import com.meteor.memory.update.UpdateError
import com.meteor.memory.update.UpdateState
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private const val ROUTE_SETTINGS = "settings"

/**
 * The primary destinations use a shared fade-through transition. Settings
 * detail pages are the only destinations that slide laterally. All durations
 * and easings come from [MotionTokens] so every screen moves on one scale.
 */
private const val SETTINGS_ENTER_SCALE = 0.97f
private const val ROUTE_BIRTH_DATE = "settings/birth-date"
private const val ROUTE_SETTINGS_APPEARANCE = "settings/appearance"
private const val ROUTE_SETTINGS_NOTIFICATIONS = "settings/notifications"
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
private const val ARG_LEGACY_TOKEN = "token"
private const val ROUTE_CHATS = "chats"
private const val ROUTE_DIAGNOSTICS = "diagnostics"
private const val ROUTE_SEARCH = "search"
private const val ROUTE_CALENDAR = "calendar"
private const val ROUTE_PEOPLE = "people"
private const val ROUTE_PERSON = "people/{personId}"
private const val ROUTE_PERSON_EDIT = "people/{personId}/edit"
// 相册总入口（ADR-065）：媒体与导入文件同一个网格，不再有独立的「记录」页。
// date 为可选查询参数：日历入口可带 ?date= 过滤到某天。
private const val ROUTE_ALBUM = "album?date={date}"
// 统一媒体查看器；ref 是完整 meteor://media/<id> 或旧 meteor://image/<token>，Uri.encode 传输。
private const val ROUTE_MEDIA = "media/{ref}"
// 独立的内置媒体选择器（ADR-059）：不在编辑页上叠加，选完把媒体 id 回传给上一个页面。
private const val ROUTE_MEDIA_PICKER = "media-picker?person={person}&kinds={kinds}"
private const val ARG_MEDIA_PICKER_PERSON = "person"
private const val ARG_MEDIA_PICKER_KINDS = "kinds"
private const val MEDIA_PICKER_RESULT = "media_picker_result"
// 旧深链，保留兼容：gallery?date= 与 gallery/{token} 内部重定向到新路由。
private const val ROUTE_GALLERY = "gallery?date={date}"
private const val ROUTE_IMAGE = "gallery/{token}"
private const val ROUTE_AI = "ai?personId={personId}"
private const val ROUTE_PERSONA = "ai/persona/{personId}"
// 每日一问（ADR-068 / ADR-070）：列表、按天新建/补写、单条详情。
private const val ROUTE_QUESTIONS = "questions"
private const val ROUTE_QUESTION_NEW = "questions/new?date={date}"
private const val ROUTE_QUESTION = "questions/{questionId}"
private const val ARG_QUESTION_DATE = "date"

/** Read-only preview of the diary written for [date]. */
private fun diaryViewRoute(date: LocalDate): String = "diary/view/$date"

/** 每日一问详情页：与 [ROUTE_QUESTION] 的模板一一对应。 */
private fun questionRoute(questionId: String): String = "questions/$questionId"

/**
 * 提问页（ADR-070）：[date] 为空表示今天，非空表示按那一天归档。
 *
 * 路由模板带一个可缺省的 query，所以这里要区分「不带参数」与「带日期」两种写法；
 * 不能直接把 null 拼进 URL。
 */
private fun questionComposeRoute(date: LocalDate? = null): String =
    if (date == null) "questions/new" else "questions/new?date=$date"

private fun changelogVersionRoute(number: String): String =
    "$ROUTE_ABOUT_CHANGELOG/${Uri.encode(number)}"

private val MAIN_FADE_ROUTES = setOf(
    ROUTE_SETTINGS,
    ROUTE_SEARCH,
    ROUTE_GALLERY,
    ROUTE_CHATS,
    ROUTE_ALBUM,
    ROUTE_PEOPLE,
    ROUTE_AI,
    ROUTE_QUESTIONS,
    ROUTE_QUESTION_NEW,
    ROUTE_QUESTION,
)

private fun usesMainFadeTransition(route: String?): Boolean = route != null && route in MAIN_FADE_ROUTES

/** Settings root or any second/third-level settings page. */
private fun isSettingsRoute(route: String?): Boolean =
    route == ROUTE_SETTINGS || route?.startsWith("$ROUTE_SETTINGS/") == true

private fun fadeThroughEnterTransition(): EnterTransition =
    fadeIn(MotionTokens.pageIn()) +
        scaleIn(
            initialScale = SETTINGS_ENTER_SCALE,
            animationSpec = MotionTokens.pageIn(),
        )

private fun fadeThroughExitTransition(): ExitTransition =
    fadeOut(MotionTokens.pageOut())

private fun Context.openInBrowser(url: String, failureMessage: String? = null) {
    val opened = runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!opened && failureMessage != null) {
        Toast.makeText(this, failureMessage, Toast.LENGTH_SHORT).show()
    }
}

/**
 * The version the update rows should name: the release currently pending
 * (announced / downloading / ready to install), or the running version when
 * nothing is pending. The rows read the pending release, not the running one.
 */
private fun pendingUpdateVersion(state: UpdateState, runningVersionName: String): String =
    when (state) {
        is UpdateState.Available -> state.versionName
        is UpdateState.Downloading -> state.versionName
        is UpdateState.ReadyToInstall -> state.versionName
        is UpdateState.InstallPermissionRequired -> state.versionName
        else -> runningVersionName
    }

@Composable
private fun updateStatusText(state: UpdateState): String = when (state) {
    UpdateState.Idle -> stringResource(SettingsR.string.settings_update_idle)
    UpdateState.Checking -> stringResource(SettingsR.string.settings_update_checking)
    UpdateState.UpToDate -> stringResource(SettingsR.string.settings_update_current)
    is UpdateState.Available ->
        stringResource(SettingsR.string.settings_update_available_body, state.versionName)
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
        UpdateError.CHECKSUM -> stringResource(SettingsR.string.settings_update_error_checksum)
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

/**
 * Every destination of the app.
 *
 * [onSystemScreenOpened] is called right after this graph successfully starts an
 * activity of its own (today: the share sheet). Such a transition also stops our
 * activity, so the shell has to know it was deliberate and must not re-arm the
 * app lock while the person is only picking a share target.
 */
@Composable
fun MeteorNavHost(
    versionName: String,
    animationsEnabled: Boolean = true,
    aiEnabled: Boolean = false,
    /** Which picker the media insertion flows open (ADR-059). */
    mediaPickerMode: MediaPickerMode = MediaPickerMode.IN_APP,
    /** Home drawer visibility and order, persisted in SettingsRepository. */
    drawerOrder: List<String> = DEFAULT_DRAWER_ORDER,
    drawerVisibleItems: Set<String> = DEFAULT_DRAWER_VISIBLE_ITEMS,
    /** Writes a drawer change made inside the drawer itself (hide/sort/manage). */
    onDrawerConfigChanged: (order: List<String>, visibleItems: Set<String>) -> Unit = { _, _ -> },
    /** Drawer covers the hidden status-bar area while the home header stays inset. */
    drawerFullscreen: Boolean = false,
    onSystemScreenOpened: () -> Unit = {},
) {
    val navController = rememberNavController()

    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (animationsEnabled) {
            if (usesMainFadeTransition(targetState.destination.route)) {
                fadeThroughEnterTransition()
            } else if (isSettingsRoute(initialState.destination.route)) {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = MotionTokens.slide(),
                )
            } else {
                fadeIn(MotionTokens.pageIn())
            }
        } else {
            EnterTransition.None
        }
    }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (animationsEnabled) {
            if (usesMainFadeTransition(targetState.destination.route)) {
                fadeThroughExitTransition()
            } else if (isSettingsRoute(initialState.destination.route)) {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = MotionTokens.slide(),
                )
            } else {
                fadeOut(MotionTokens.pageOut())
            }
        } else {
            ExitTransition.None
        }
    }
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (animationsEnabled) {
            if (
                isSettingsRoute(targetState.destination.route) &&
                targetState.destination.route != initialState.destination.route
            ) {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = MotionTokens.slide(),
                )
            } else if (usesMainFadeTransition(targetState.destination.route)) {
                fadeThroughEnterTransition()
            } else {
                fadeIn(MotionTokens.pageIn())
            }
        } else {
            EnterTransition.None
        }
    }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (animationsEnabled) {
            if (
                isSettingsRoute(targetState.destination.route) &&
                targetState.destination.route != initialState.destination.route
            ) {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = MotionTokens.slide(),
                )
            } else if (usesMainFadeTransition(initialState.destination.route)) {
                fadeThroughExitTransition()
            } else {
                fadeOut(MotionTokens.pageOut())
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
            val questionDays by viewModel.questionDays.collectAsStateWithLifecycle()

            RefreshOnResume { viewModel.refresh() }

            HomeTabsScreen(
                appName = stringResource(R.string.app_name),
                versionName = versionName,
                onOpenSearch = { navController.navigate(ROUTE_SEARCH) },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                onOpenQuestions = { navController.navigate(ROUTE_QUESTIONS) },
                onOpenAlbum = { navController.navigate("album") },
                onOpenPeople = { navController.navigate(ROUTE_PEOPLE) },
                onOpenAi = if (aiEnabled) {
                    { navController.navigate("ai") }
                } else {
                    null
                },
                selectedTab = homeTab,
                onSelectTab = viewModel::selectHomeTab,
                animationsEnabled = animationsEnabled,
                drawerOrder = drawerOrder,
                drawerVisibleItems = drawerVisibleItems,
                onDrawerConfigChanged = onDrawerConfigChanged,
                drawerFullscreen = drawerFullscreen,
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
                        onPreviousMonth = viewModel::previousMonth,
                        onNextMonth = viewModel::nextMonth,
                        onToday = viewModel::goToToday,
                        onSelectDate = viewModel::selectDate,
                        onToggleMood = viewModel::toggleMood,
                        onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                        onOpenDiary = { date -> navController.navigate(diaryViewRoute(date)) },
                        onEditDiary = { date -> navController.navigate("$ROUTE_DIARY_EDIT/$date") },
                        onOpenSearch = { navController.navigate(ROUTE_SEARCH) },
                        onOpenAi = if (aiEnabled) {
                            { navController.navigate("ai") }
                        } else {
                            null
                        },
                        onOpenQuestions = { date ->
                            // ADR-070：这一天已有问题就打开它，没有就按这一天补写。
                            // 同一天有多条时取最新的一条，其余在总览里。
                            val existing = questionDays[date]
                            if (existing != null) {
                                navController.navigate(questionRoute(existing))
                            } else {
                                navController.navigate(questionComposeRoute(date))
                            }
                        },
                        onOpenAlbum = { navController.navigate("album") },
                        onOpenPeople = { navController.navigate(ROUTE_PEOPLE) },
                        showHeader = false,
                    )
                },
            )
        }

        // 每日一问（ADR-068 / ADR-069）：列表、独立提问页、单条详情。
        composable(ROUTE_QUESTIONS) {
            val viewModel: QuestionListViewModel = hiltViewModel()
            val rows by viewModel.rows.collectAsStateWithLifecycle()

            RefreshOnResume { viewModel.refresh() }

            QuestionListScreen(
                rows = rows,
                onCompose = { navController.navigate(questionComposeRoute()) },
                onOpen = { id -> navController.navigate(questionRoute(id)) },
                onDelete = viewModel::delete,
                onBack = { navController.popBackStack() },
                animationsEnabled = animationsEnabled,
            )
        }

        composable(
            route = ROUTE_QUESTION_NEW,
            arguments = listOf(
                navArgument(ARG_QUESTION_DATE) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val viewModel: QuestionComposeViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()

            QuestionComposeScreen(
                state = state,
                onDraftChange = viewModel::onDraftChange,
                onAddReminder = viewModel::addReminder,
                onRemoveReminder = viewModel::removeReminder,
                onSave = {
                    viewModel.submit {
                        // 只在这一页仍是栈顶时退回：保存回调迟到时不能把别人顶掉。
                        if (navController.currentBackStackEntry?.id == entry.id) {
                            navController.popBackStack()
                        }
                    }
                },
                onBack = { navController.popBackStack() },
                animationsEnabled = animationsEnabled,
            )
        }

        composable(
            route = ROUTE_QUESTION,
            arguments = listOf(
                navArgument(QuestionDetailViewModel.ARG_ID) { type = NavType.StringType },
            ),
        ) { entry ->
            val viewModel: QuestionDetailViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()

            RefreshOnResume { viewModel.load() }

            QuestionDetailScreen(
                state = state,
                onDraftChange = viewModel::onDraftChange,
                onTogglePreview = viewModel::togglePreview,
                onStartAnswer = viewModel::startAnswer,
                onCancelAnswer = viewModel::cancelAnswer,
                onSaveAnswer = viewModel::saveAnswer,
                onDeleteAnswer = viewModel::deleteAnswer,
                onAddReminder = viewModel::addCustomReminder,
                onRemoveReminder = viewModel::removeReminder,
                onInsertLink = viewModel::insertLink,
                // 删除成功才弹栈，且只在用户仍停在这一页时弹：迟到的回调不能把
                // 别人 (或上一页) 顶掉。
                onDelete = { onDeleted ->
                    viewModel.delete {
                        if (navController.currentBackStackEntry?.id == entry.id) onDeleted()
                    }
                },
                onBack = { navController.popBackStack() },
                animationsEnabled = animationsEnabled,
            )
        }

        composable(ROUTE_PEOPLE) {
            val viewModel: PeopleViewModel = hiltViewModel()
            val people by viewModel.people.collectAsStateWithLifecycle()
            val availableTags by viewModel.customTags.collectAsStateWithLifecycle()
            // Resolved through stringResource, not context.getString: a
            // LocalContext read is not invalidated by a configuration change,
            // so the label would keep the previous locale after a language
            // switch. The map below is not a composable scope, hence the hoist.
            val profileLabel = stringResource(R.string.people_secondary_profile)
            val missingLabel = stringResource(R.string.people_secondary_missing)

            RefreshOnResume { viewModel.refresh() }

            PeopleListScreen(
                people = people.map { entry ->
                    PeopleRow(
                        personId = entry.personId,
                        displayName = entry.displayName ?: entry.personId.take(8),
                        relation = entry.relation,
                        secondary = if (entry.hasProfile) profileLabel else missingLabel,
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
            val pickPersonMedia = rememberMediaUriPicker(viewModel::importMedia)
            // The standalone picker already imported the files; the detail page
            // only has to reload the media section (ADR-059).
            HandleMediaPickerResult(navController) { uris ->
                viewModel.importMedia(uris.map { uri -> Uri.parse(uri) })
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
                },
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate("people/$personId/edit") },
                resolveImage = viewModel::imageFor,
                onActivate = { target ->
                    if (
                        target.startsWith("meteor://image/") ||
                        target.startsWith("meteor://media/")
                    ) {
                        navController.navigate("media/${Uri.encode(target)}")
                    }
                },
                media = state.media,
                onOpenMedia = { ref -> navController.navigate("media/${Uri.encode(ref)}") },
                onAddMedia = {
                    if (mediaPickerMode == MediaPickerMode.IN_APP) {
                        navController.navigate(
                            mediaPickerRoute(personId = personId, kinds = MediaKind.entries.toSet()),
                        )
                    } else {
                        pickPersonMedia()
                    }
                },
                onDeleteMedia = viewModel::deleteMedia,
                mediaImporting = state.importingMedia,
                mediaImportSummary = state.mediaImport,
                mediaLoadFailed = state.mediaError,
                mediaDeleteFailed = state.mediaDeleteFailed,
                resolveMedia = viewModel::mediaBlockFor,
                onAddRecord = pickAttachments,
                importingAttachments = state.isImporting,
                importedAttachmentCount = state.importSummary?.imported,
                failedAttachmentCount = state.importSummary?.failed ?: 0,
                skippedAttachmentCount = state.importSummary?.skipped ?: 0,
                attachmentIndexFailed = state.importSummary?.indexFailed == true,
                onDelete = viewModel::delete,
                deleting = state.deleting,
                deleteFailed = state.deleteFailed,
            )        }

        composable(
            route = ROUTE_PERSON_EDIT,
            arguments = listOf(navArgument(PersonEditViewModel.ARG_PERSON_ID) { type = NavType.StringType }),
        ) { entry ->
            val viewModel: PersonEditViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val personId = entry.arguments
                ?.getString(PersonEditViewModel.ARG_PERSON_ID)
                .orEmpty()
            val pickImage = rememberImagePicker(viewModel::insertImage)
            val pickMedia = rememberMediaUriPicker(viewModel::insertMedia)
            HandleMediaPickerResult(navController) { uris ->
                viewModel.insertMedia(uris.map { uri -> Uri.parse(uri) })
            }

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
                onSave = { viewModel.save() },
                onBack = { navController.popBackStack() },
                // T10: the page asks before dropping unsaved edits; 保存并返回
                // pops only after the write succeeded, and only while this page
                // is still the current destination (a discard during the write
                // must not pop one screen too many).
                hasUnsavedChanges = state.hasUnsavedChanges,
                onSaveAndReturn = { onSaved ->
                    viewModel.save {
                        if (navController.currentBackStackEntry?.id == entry.id) onSaved()
                    }
                },
                missingImages = state.missingImages,
                resolveImage = viewModel::imageFor,
                onImageCaptionChanged = viewModel::setImageCaption,
                onPickMedia = { kinds ->
                    if (mediaPickerMode == MediaPickerMode.IN_APP) {
                        navController.navigate(mediaPickerRoute(personId = personId, kinds = kinds))
                    } else {
                        pickMedia()
                    }
                },
                insertingMedia = state.insertingMedia,
                resolveMedia = viewModel::mediaBlockFor,
            )
        }

        // 相册总入口（ADR-065）：图片 / 视频 / 音频 / 导入文件同一个网格。
        // 原「记录」段已删除，它的导入、列出、导出、删除、阅读能力都在这里。
        composable(
            route = ROUTE_ALBUM,
            arguments = listOf(
                navArgument(GalleryViewModel.ARG_DATE) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            val galleryViewModel: GalleryViewModel = hiltViewModel()
            val days by galleryViewModel.days.collectAsStateWithLifecycle()
            val loading by galleryViewModel.loading.collectAsStateWithLifecycle()
            val filterLabel by galleryViewModel.filterLabel.collectAsStateWithLifecycle()
            val filter by galleryViewModel.filter.collectAsStateWithLifecycle()
            val sortMode by galleryViewModel.sortMode.collectAsStateWithLifecycle()
            val importingFile by galleryViewModel.importingFile.collectAsStateWithLifecycle()
            val importProgress by galleryViewModel.importProgress.collectAsStateWithLifecycle()
            val importSummary by galleryViewModel.importSummary.collectAsStateWithLifecycle()
            val addingMedia by galleryViewModel.addingMedia.collectAsStateWithLifecycle()
            val addMediaProgress by galleryViewModel.addMediaProgress.collectAsStateWithLifecycle()
            val addMediaSummary by galleryViewModel.addMediaSummary.collectAsStateWithLifecycle()

            val pickFiles = rememberAttachmentPicker(galleryViewModel::importFiles)
            // 添加媒体（ADR-068）：设备选择器 → MediaImporter 逐张加密入库。
            val pickMedia = rememberMediaUriPicker(galleryViewModel::importMedia)

            RefreshOnResume { galleryViewModel.refresh() }

            GalleryScreen(
                days = days,
                loading = loading,
                sortMode = sortMode,
                onSortModeChange = galleryViewModel::setSortMode,
                filter = filter,
                onFilterChange = galleryViewModel::setFilter,
                animationsEnabled = animationsEnabled,
                onOpen = { ref -> navController.navigate("media/${Uri.encode(ref)}") },
                onDelete = galleryViewModel::delete,
                onBack = { navController.popBackStack() },
                title = filterLabel,
                onImportFile = pickFiles,
                importingFile = importingFile,
                importProgress = importProgress,
                importSummary = importSummary?.let { summary ->
                    FileImportSummary(
                        imported = summary.imported,
                        failed = summary.failed,
                        skipped = summary.skipped,
                        indexFailed = summary.indexFailed,
                    )
                },
                onDismissImportSummary = galleryViewModel::clearImportSummary,
                onAddMedia = pickMedia,
                addingMedia = addingMedia,
                addMediaProgress = addMediaProgress,
                addMediaSummary = addMediaSummary,
                onDismissAddMediaSummary = galleryViewModel::clearAddMediaSummary,
            )
        }

        // 旧深链兼容：gallery?date= 重定向到相册总入口，保留 date 参数。
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
            val date = entry.arguments?.getString(GalleryViewModel.ARG_DATE)
            // Keyed on `date`: a reused destination (single-top or state restore)
            // must re-run the redirect when the argument differs.
            LaunchedEffect(date) {
                navController.navigate(if (date == null) "album" else "album?date=$date") {
                    popUpTo(ROUTE_CALENDAR) { inclusive = false }
                }
            }
        }

        // 旧图片查看器深链：gallery/{token} 重定向到 media/{ref}。
        composable(
            route = ROUTE_IMAGE,
            arguments = listOf(navArgument(ARG_LEGACY_TOKEN) { type = NavType.StringType }),
        ) { entry ->
            val token = entry.arguments?.getString(ARG_LEGACY_TOKEN).orEmpty()
            // Keyed on `token` for the same reason as the gallery redirect above.
            LaunchedEffect(token) {
                val ref = "meteor://image/$token"
                navController.navigate("media/${Uri.encode(ref)}") {
                    popUpTo(ROUTE_CALENDAR) { inclusive = false }
                }
            }
        }

        // 统一媒体查看器：media/{ref}，ref 为完整 meteor://media/<id> 或旧 meteor://image/<token>。
        composable(
            route = ROUTE_MEDIA,
            arguments = listOf(navArgument(MediaViewerViewModel.ARG_REF) { type = NavType.StringType }),
        ) {
            val viewModel: MediaViewerViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val context = LocalContext.current
            // Hoisted out of the LaunchedEffect below: getString on a
            // LocalContext read is not invalidated by configuration changes.
            val clipLabel = stringResource(GalleryR.string.gallery_share_clip_label)
            val chooserLabel = stringResource(GalleryR.string.gallery_share_chooser)

            LaunchedEffect(state.deleted) {
                if (state.deleted) navController.popBackStack()
            }

            LaunchedEffect(state.shareRequest) {
                val request = state.shareRequest ?: return@LaunchedEffect
                val launched = runCatching {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = request.mimeType
                        putExtra(Intent.EXTRA_STREAM, request.uri)
                        clipData = ClipData(
                            ClipDescription(clipLabel, arrayOf(request.mimeType)),
                            ClipData.Item(request.uri),
                        )
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(
                        Intent.createChooser(send, chooserLabel),
                    )
                    onSystemScreenOpened()
                }
                launched.fold(
                    onSuccess = { viewModel.onShareLaunched() },
                    onFailure = { error -> viewModel.onShareLaunchedFailed(error) },
                )
            }

            MediaViewerScreen(
                ref = state.ref,
                kind = state.kind,
                fileName = state.fileName,
                bitmap = state.bitmap,
                caption = state.fileName ?: stringResource(GalleryR.string.gallery_title),
                dateLabel = state.dateLabel,
                onBack = { navController.popBackStack() },
                onShare = viewModel::share,
                onDelete = viewModel::delete,
                loading = state.loading,
                animationsEnabled = animationsEnabled,
                deleting = state.deleting,
                sharing = state.sharing,
                error = state.error,
                hasContent = state.hasContent,
                fileText = state.fileText,
            )
        }

        // 独立的内置媒体选择器（ADR-059）：全屏页面，不在编辑页上叠加。
        composable(
            route = ROUTE_MEDIA_PICKER,
            arguments = listOf(
                navArgument(ARG_MEDIA_PICKER_PERSON) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(ARG_MEDIA_PICKER_KINDS) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val requestedKinds = entry.arguments
                ?.getString(ARG_MEDIA_PICKER_KINDS)
                .orEmpty()
                .split(',')
                .mapNotNull { name -> runCatching { MediaKind.valueOf(name.trim()) }.getOrNull() }
                .toSet()
                .ifEmpty { MediaKind.entries.toSet() }
            MediaPickerScreen(
                kinds = requestedKinds,
                onConfirm = { picked ->
                    // Only the URIs go back: the host inserts placeholders and runs
                    // the import in the background, so the picker returns at once
                    // and a large video never blocks editing (ADR-061).
                    val uris = ArrayList(picked.map { it.uri.toString() })
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(MEDIA_PICKER_RESULT, uris)
                    navController.popBackStack()
                },
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
                onOpenAttachment = { id ->
                    navController.navigate("media/${Uri.encode("${MediaRef.FILE_SCHEME}$id")}")
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
                viewModel.refreshUpdateInstallPermission()
            }

            SettingsScreen(
                settings = settings,
                versionName = versionName,
                onDarkModeChanged = viewModel::setDarkMode,
                onOpenAppearanceSettings = { navController.navigate(ROUTE_SETTINGS_APPEARANCE) },
                onAppLockChanged = viewModel::setAppLockEnabled,
                appLockUnavailable = appLockUnavailable,
                onDismissAppLockUnavailable = viewModel::dismissAppLockUnavailable,
                onOpenSecuritySettings = { openSecuritySettings(context) },
                onScreenshotProtectionChanged = viewModel::setScreenshotProtectionEnabled,
                onMediaPickerModeChanged = viewModel::setMediaPickerMode,
                onVerboseLoggingChanged = viewModel::setVerboseLogging,
                onOpenAbout = { navController.navigate(ROUTE_ABOUT) },
                onOpenDonate = { navController.navigate(ROUTE_ABOUT_DONATE) },
                onOpenBirthDate = { navController.navigate(ROUTE_BIRTH_DATE) },
                onAutoCheckForUpdatesChanged = viewModel::setAutoCheckForUpdates,
                updateStatusText = updateStatusText(updateState),
                updateBusy = updateState is UpdateState.Checking || updateState is UpdateState.Downloading,
                updateVersionName = pendingUpdateVersion(updateState, versionName),
                updateCanInstall = updateState is UpdateState.ReadyToInstall ||
                    updateState is UpdateState.InstallPermissionRequired,
                onCheckUpdates = viewModel::checkForUpdates,
                onInstallUpdate = viewModel::installUpdate,
                logSizeBytes = logSizeBytes,
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
                onOpenNotificationDetail = {
                    navController.navigate(ROUTE_SETTINGS_NOTIFICATIONS)
                },
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

        composable(ROUTE_SETTINGS_APPEARANCE) {
            val viewModel: SettingsViewModel = hiltViewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()

            AppearanceSettingsScreen(
                settings = settings,
                onDynamicColorChanged = viewModel::setDynamicColor,
                onAnimationsChanged = viewModel::setAnimationsEnabled,
                onHideStatusBarChanged = viewModel::setHideStatusBar,
                onBack = { navController.popBackStack() },
            )
        }

        composable(ROUTE_SETTINGS_NOTIFICATIONS) {
            val viewModel: SettingsViewModel = hiltViewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val notificationsAllowed by viewModel.notificationsAllowed
                .collectAsStateWithLifecycle()
            val canScheduleExactAlarms by viewModel.canScheduleExactAlarms
                .collectAsStateWithLifecycle()

            RefreshOnResume { viewModel.refreshNotificationPermissions() }

            NotificationSettingsScreen(
                settings = settings,
                notificationsAllowed = notificationsAllowed,
                canScheduleExactAlarms = canScheduleExactAlarms,
                onDiaryReminderEnabledChanged = viewModel::setDiaryReminderEnabled,
                onQuestionReminderEnabledChanged = viewModel::setQuestionReminderEnabled,
                onUpdateNotificationEnabledChanged = viewModel::setUpdateNotificationEnabled,
                onOpenExactAlarmSettings = viewModel::openExactAlarmSettings,
                onOpenNotificationSettings = viewModel::openNotificationSettings,
                onBack = { navController.popBackStack() },
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

        // 旧「记录」深链：ADR-065 删除了记录页，重定向到相册（文件段）。
        composable(ROUTE_CHATS) {
            LaunchedEffect(Unit) {
                navController.navigate("album") {
                    popUpTo(ROUTE_CALENDAR) { inclusive = false }
                }
            }
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
                // Without this the preview fell back to "pending" for every
                // `meteor://media/` card (ADR-064).
                resolveMedia = viewModel::mediaState,
                onActivate = { target ->
                    when {
                        target.startsWith("meteor://image/") ||
                            target.startsWith("meteor://media/") ->
                            navController.navigate("media/${Uri.encode(target)}")

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
        ) { entry ->
            val viewModel: DiaryEditViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()

            val context = LocalContext.current
            val pickImage = rememberImagePicker(viewModel::insertImage)
            val systemMediaPicker = rememberMediaUriPicker(viewModel::importMediaUris)
            HandleMediaPickerResult(navController) { uris ->
                viewModel.enqueueMediaImport(uris.map { uri -> Uri.parse(uri) })
            }

            // T10 保存流程的延伸：保存成功后自动进入当天的只读预览页。
            //
            // 写完的日记，成品形态就是预览页（ADR-018 / ADR-021：编辑区只是录入
            // 通道，排版与媒体的呈现归预览），所以保存成功直接把用户送到结果，
            // 不必再让他自己按返回。三条约束决定了这段接线：
            // 1. 只在成功时走 —— viewModel.save 的 onSaved 只在正文真正写进档案后
            //    回调；失败时页面原地不动，错误仍由 state.error 显示，正文不丢。
            // 2. 编辑器必须离开返回栈 —— 否则从预览页按返回会落回一个「已存完」
            //    的旧编辑器，看起来像这次保存没生效。
            // 3. 不能叠出第二张预览页 —— 见下面 onSave 里的两条分支。
            //
            // 连点保存：ViewModel 在 isSaving 时已忽略重复的 save，这里再用
            // savedNavigated 闩住导航，并沿用 entry.id 判据，让迟到的回调（用户
            // 已返回或已删除该页）既不会导航也不会弹错页。
            var savedNavigated by remember { mutableStateOf(false) }

            DiaryEditScreen(
                state = state,
                onTextChanged = viewModel::onTextChanged,
                onInsertLink = viewModel::insertLink,
                onPickImage = pickImage,
                onRemoveImage = viewModel::removeImage,
                // The host owns the picker choice (ADR-059): the in-app picker is
                // its own destination, the system mode opens the document picker.
                onRequestMediaPicker = { kinds ->
                    if (mediaPickerMode == MediaPickerMode.IN_APP) {
                        navController.navigate(mediaPickerRoute(personId = null, kinds = kinds))
                    } else {
                        systemMediaPicker()
                    }
                },
                onSave = {
                    viewModel.save {
                        if (!savedNavigated && navController.currentBackStackEntry?.id == entry.id) {
                            savedNavigated = true
                            // The usual way in is 预览 → 编辑: `onEdit` above pushes
                            // the editor on top of the view, so popping the editor
                            // reveals that very preview. Its `RefreshOnResume` reads
                            // the archive again on the way back, so it shows the body
                            // that was just written — no second view page, no reload
                            // route. Only a direct 日历 → 编辑 entry has nothing to
                            // return to and needs a fresh preview pushed.
                            val below = navController.previousBackStackEntry
                            val viewBelow = below?.destination?.route == ROUTE_DIARY_VIEW &&
                                below.arguments?.getString(DiaryPreviewViewModel.ARG_DATE) ==
                                state.date.toString()
                            if (viewBelow) {
                                navController.popBackStack()
                            } else {
                                navController.navigate(diaryViewRoute(state.date)) {
                                    // Drop the editor: a back press from the preview
                                    // must not land on a stale, already-saved editor.
                                    // The destination id (not `entry.id`, a UUID that
                                    // would bind to the route overload) is what
                                    // NavOptionsBuilder matches here.
                                    popUpTo(entry.destination.id) { inclusive = true }
                                }
                            }
                        }
                    }
                },
                onBack = { navController.popBackStack() },
                // T10: 删除日记 confirms first, then deletes the archive record,
                // drops its search-index entry and pops the editor. The pop is
                // skipped when the user already left this page while it ran, so
                // a late callback cannot pop one screen too many.
                onDeleteDiary = { onDeleted ->
                    viewModel.deleteDiary {
                        if (navController.currentBackStackEntry?.id == entry.id) onDeleted()
                    }
                },
                // T10: 保存并返回 only pops once the save really landed.
                onSaveAndReturn = { onSaved ->
                    viewModel.save {
                        if (navController.currentBackStackEntry?.id == entry.id) onSaved()
                    }
                },
                resolveImage = viewModel::imageFor,
                onImageCaptionChanged = viewModel::setImageCaption,
                resolveMedia = viewModel::mediaState,
                onRemoveMedia = viewModel::removeMedia,
                onMediaCaptionChanged = viewModel::setMediaCaption,
                onActivate = { target ->
                    when {
                        target.startsWith("meteor://image/") ||
                            target.startsWith("meteor://media/") ->
                            navController.navigate("media/${Uri.encode(target)}")

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

    // 通知点击 → 详情页（ADR-068）。通知在 Activity 可能还没起来时投递，所以它只
    // 记下「要打开哪个问题」（QuestionOpenRequest），导航图建好后才在这里消费一次。
    // 放在 NavHost { } 之后：上面的 block 不是 Composable 作用域，拿不到 remember /
    // LaunchedEffect。QuestionOpenRequest 是普通 @Singleton，hiltViewModel() 取不到，
    // 所以经 QuestionOpenEntryPoint 从 Application 拿同一个实例。
    val appContext = LocalContext.current.applicationContext
    val openRequest = remember(appContext) {
        EntryPointAccessors
            .fromApplication(appContext, QuestionOpenEntryPoint::class.java)
            .questionOpenRequest()
    }
    val pendingQuestion by openRequest.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingQuestion) {
        val questionId = openRequest.consume() ?: return@LaunchedEffect
        navController.navigate(questionRoute(questionId))
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

/**
 * Multi-select picker for the person editor's media insertion (ADR-052 §5 E).
 *
 * The person editor already takes plain `Uri`s and resolves metadata inside its
 * view model through [MediaImporter], so this only has to hand the system picker
 * the right MIME filters.
 */
/**
 * Path to the standalone media picker (ADR-059). The result comes back through
 * the previous entry's `savedStateHandle`, so the editor never has to know how
 * the picker was drawn.
 */
private fun mediaPickerRoute(personId: String?, kinds: Set<MediaKind>): String {
    val person = personId?.takeIf { it.isNotBlank() }?.let { Uri.encode(it) }.orEmpty()
    val kindList = kinds.joinToString(",") { it.name }
    return "media-picker?person=${Uri.encode(person)}&kinds=${Uri.encode(kindList)}"
}

/**
 * Delivers the device URIs returned by the standalone picker and clears the
 * result, so a configuration change cannot replay the insertion twice.
 *
 * The picker only chooses; the host inserts placeholder cards and runs the
 * import in the background (ADR-061).
 */
@Composable
private fun HandleMediaPickerResult(
    navController: NavHostController,
    onIds: (List<String>) -> Unit,
) {
    val handle = navController.currentBackStackEntry?.savedStateHandle ?: return
    val result = handle.get<ArrayList<String>>(MEDIA_PICKER_RESULT)
    LaunchedEffect(result) {
        if (result.isNullOrEmpty()) return@LaunchedEffect
        onIds(result)
        handle.remove<ArrayList<String>>(MEDIA_PICKER_RESULT)
    }
}

@Composable
private fun rememberMediaUriPicker(onUris: (List<Uri>) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onUris(uris)
    }
    return { launcher.launch(arrayOf("image/*", "video/*", "audio/*")) }
}

@Composable
private fun rememberAiFilePicker(onUris: (List<Uri>) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onUris(uris)
    }
    return { launcher.launch(arrayOf("*/*")) }
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
