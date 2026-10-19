package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compat.LazyColumnCompat
import io.nekohasekai.sfa.compat.rememberOverscrollEffectCompat
import io.nekohasekai.sfa.compose.component.SnackbarHost
import io.nekohasekai.sfa.compose.model.Group
import io.nekohasekai.sfa.compose.model.GroupItem
import io.nekohasekai.sfa.compose.screen.dashboard.groups.GroupsUiState
import io.nekohasekai.sfa.compose.screen.dashboard.groups.GroupsViewModel
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.compose.util.rememberSheetDismissFromContentOnlyIfGestureStartedAtTopModifier
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.RemoteControlManager
import io.nekohasekai.sfa.utils.SubscriptionRouting

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsCard(
    serviceStatus: Status,
    commandClient: CommandClient? = null,
    viewModel: GroupsViewModel? = null,
    showTopBar: Boolean = false,
    profiles: List<Profile> = emptyList(),
    onProfileSelected: (Long) -> Unit = {},
    onToggleConnection: () -> Unit = {},
    serviceStartTime: Long? = null,
    downlink: String = "0 B/s",
    uplink: String = "0 B/s",
    trafficAvailable: Boolean = false,
    listHeaderContent: (@Composable () -> Unit)? = null,
    asSheet: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val actualViewModel: GroupsViewModel =
        viewModel
            ?: viewModel(
                factory =
                object : ViewModelProvider.Factory {
                    override fun <T : androidx.lifecycle.ViewModel> create(
                        modelClass: Class<T>,
                    ): T {
                        @Suppress("UNCHECKED_CAST")
                        return GroupsViewModel(commandClient) as T
                    }
                },
            )
    val snackbarHostState = remember { SnackbarHostState() }
    val uiState by actualViewModel.uiState.collectAsState()

    var query by rememberSaveable(uiState.profileId) { mutableStateOf("") }
    var protocol by rememberSaveable(uiState.profileId) { mutableStateOf("") }
    var grid by rememberSaveable { mutableStateOf(false) }

    if (showTopBar) {
        val allCollapsed = uiState.expandedGroups.isEmpty()
        OverrideTopBar {
            TopAppBar(
                title = { Text(stringResource(R.string.title_dashboard)) },
                actions = {
                    IconButton(onClick = { grid = !grid }) {
                        Icon(
                            if (grid) Icons.Outlined.ViewList else Icons.Outlined.GridView,
                            if (grid) "Показать список" else "Показать сетку",
                        )
                    }
                    IconButton(onClick = { actualViewModel.toggleSortByPing() }) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.Sort,
                            contentDescription =
                            if (uiState.sortByPing) {
                                "Порядок подписки"
                            } else {
                                "Сортировать по задержке"
                            },
                            tint =
                            if (uiState.sortByPing) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                    if (uiState.groups.isNotEmpty()) {
                        IconButton(onClick = { actualViewModel.toggleAllGroups() }) {
                            Icon(
                                imageVector =
                                if (allCollapsed) {
                                    Icons.Default.UnfoldMore
                                } else {
                                    Icons.Default.UnfoldLess
                                },
                                contentDescription =
                                if (allCollapsed) {
                                    stringResource(R.string.expand_all)
                                } else {
                                    stringResource(R.string.collapse_all)
                                },
                            )
                        }
                    }
                },
            )
        }
    }

    val onToggleExpanded =
        remember(actualViewModel) {
            { groupTag: String -> actualViewModel.toggleGroupExpand(groupTag) }
        }
    val onItemSelected =
        remember(actualViewModel) {
            { groupTag: String, itemTag: String ->
                actualViewModel.selectGroupItem(groupTag, itemTag)
            }
        }
    val onUrlTest =
        remember(actualViewModel) { { groupTag: String -> actualViewModel.urlTestGroup(groupTag) } }
    val onItemUrlTest =
        remember(actualViewModel) { { itemTag: String -> actualViewModel.urlTest(itemTag) } }

    LaunchedEffect(serviceStatus) { actualViewModel.updateServiceStatus(serviceStatus) }

    val closeConnectionsMessage = stringResource(R.string.close_connections_confirm)
    val closeConnectionsAction = stringResource(R.string.close)

    LaunchedEffect(uiState.showCloseConnectionsSnackbar) {
        if (uiState.showCloseConnectionsSnackbar) {
            val result =
                snackbarHostState.showSnackbar(
                    message = closeConnectionsMessage,
                    actionLabel = closeConnectionsAction,
                    duration = SnackbarDuration.Indefinite,
                    withDismissAction = true,
                )

            when (result) {
                SnackbarResult.ActionPerformed -> {
                    actualViewModel.closeConnections()
                }

                SnackbarResult.Dismissed -> {
                    actualViewModel.dismissCloseConnectionsSnackbar()
                }
            }
        }
    }

    Box(modifier = modifier) {
        GroupsCardContent(
            uiState = uiState,
            serviceStatus = serviceStatus,
            onToggleExpanded = onToggleExpanded,
            onItemSelected = onItemSelected,
            onUrlTest = onUrlTest,
            onItemUrlTest = { tag -> if (uiState.testingGroups.isEmpty()) onItemUrlTest(tag) },
            query = query,
            protocol = protocol,
            grid = grid,
            searchHeader =
            if (showTopBar) {
                (
                    {
                        ServerOverview(
                            uiState,
                            serviceStatus,
                            profiles,
                            onProfileSelected,
                            onToggleConnection,
                            query,
                            { query = it },
                            protocol,
                            { protocol = it },
                            serviceStartTime,
                            downlink,
                            uplink,
                            trafficAvailable,
                        )
                    }
                    )
            } else {
                null
            },
            listHeaderContent = listHeaderContent,
            asSheet = asSheet,
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupsCardContent(
    uiState: GroupsUiState,
    serviceStatus: Status,
    query: String,
    protocol: String,
    grid: Boolean,
    searchHeader: (@Composable () -> Unit)?,
    onToggleExpanded: (String) -> Unit,
    onItemSelected: (String, String) -> Unit,
    onUrlTest: (String) -> Unit,
    onItemUrlTest: (String) -> Unit,
    listHeaderContent: (@Composable () -> Unit)? = null,
    asSheet: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val lazyListState =
        rememberSaveable(uiState.profileId, saver = LazyListState.Saver) { LazyListState() }

    val scrollModifier =
        if (asSheet) {
            rememberSheetDismissFromContentOnlyIfGestureStartedAtTopModifier {
                lazyListState.firstVisibleItemIndex == 0 &&
                    lazyListState.firstVisibleItemScrollOffset == 0
            }
        } else {
            Modifier.nestedScroll(rememberBounceBlockingNestedScrollConnection(lazyListState))
        }

    val scaffoldPadding =
        if (asSheet) {
            PaddingValues(0.dp)
        } else {
            LocalScaffoldPadding.current
        }

    val overscrollEffect =
        if (asSheet) {
            null
        } else {
            rememberOverscrollEffectCompat()
        }

    val palette = rememberUrlTestPalette()
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val columns =
        when {
            !grid || LocalDensity.current.fontScale > 1.3f || screenWidth < 360 -> 1
            screenWidth >= 840 -> 3
            else -> 2
        }

    LazyColumnCompat(
        modifier =
        modifier
            .then(
                if (asSheet) {
                    Modifier.fillMaxSize()
                } else {
                    // Full-tab mode (Servers): use all available height, no artificial 600.dp
                    // cap
                    Modifier.fillMaxSize()
                },
            )
            .then(scrollModifier)
            .padding(scaffoldPadding),
        state = lazyListState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        overscrollEffect = overscrollEffect,
    ) {
        if (searchHeader != null) item(key = "server_overview") { searchHeader() }
        if (listHeaderContent != null) {
            item(key = "groups_list_header") { listHeaderContent() }
        }

        when {
            uiState.isLoading -> {
                item(key = "groups_loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            uiState.groups.isEmpty() -> {
                item(key = "groups_empty") {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    ) {
                        Text(
                            text =
                            if (
                                serviceStatus == Status.Started ||
                                serviceStatus == Status.Starting
                            ) {
                                "В профиле нет selector/urltest-групп"
                            } else {
                                "Нет групп в выбранном профиле. Добавьте selector/urltest или обновите подписку."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
            }

            else -> {
                uiState.groups.forEach { group ->
                    val isExpanded = uiState.expandedGroups.contains(group.tag)

                    val headerContent: @Composable (Modifier) -> Unit = { headerModifier ->
                        GroupHeader(
                            group = group,
                            title =
                            if (
                                uiState.subscriptionName.isNotBlank() &&
                                (
                                    group.tag == SubscriptionRouting.NORMAL_SELECTOR_TAG ||
                                        group == uiState.groups.firstOrNull()
                                    )
                            ) {
                                uiState.subscriptionName
                            } else {
                                group.tag
                            },
                            isExpanded = isExpanded,
                            isTesting = uiState.testingGroups.contains(group.tag),
                            onToggleExpanded = { onToggleExpanded(group.tag) },
                            onUrlTest = { onUrlTest(group.tag) },
                            modifier = headerModifier,
                        )
                    }

                    if (isExpanded) {
                        stickyHeader(key = "header:${group.tag}", contentType = "GroupHeader") {
                            headerContent(Modifier.animateItem())
                        }
                    } else {
                        item(key = "header:${group.tag}", contentType = "GroupHeader") {
                            headerContent(Modifier.animateItem())
                        }
                    }

                    if (isExpanded) {
                        val itemsToSort =
                            group.items
                                .filter {
                                    (
                                        query.isBlank() ||
                                            it.tag.contains(query, ignoreCase = true)
                                        ) &&
                                        (protocol.isBlank() || it.displayType == protocol)
                                }
                                .toMutableList()
                        if (uiState.sortByPing) {
                            itemsToSort.sortWith(
                                compareBy<io.nekohasekai.sfa.compose.model.GroupItem> {
                                    it.urlTestDelay == 0
                                }
                                    .thenBy { it.urlTestDelay },
                            )
                        }
                        if (itemsToSort.isEmpty()) {
                            item(key = "empty:${group.tag}") {
                                Text(
                                    "Серверы не найдены",
                                    Modifier.fillMaxWidth().padding(24.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        val rowItems = itemsToSort.chunked(columns)

                        rowItems.forEachIndexed { rowIndex, row ->
                            item(
                                key = "row:${group.tag}:${row.first().tag}",
                                contentType = "GroupItemRow",
                            ) {
                                GroupItemRow(
                                    row = row,
                                    columns = columns,
                                    selectedTag = group.selected,
                                    isSelectable = group.selectable,
                                    isLast = rowIndex == rowItems.lastIndex,
                                    palette = palette,
                                    onItemSelected = { itemTag ->
                                        onItemSelected(group.tag, itemTag)
                                    },
                                    onItemUrlTest = { tag -> if (uiState.testingGroups.isEmpty()) onItemUrlTest(tag) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    } else {
                        item(key = "dots:${group.tag}", contentType = "GroupDots") {
                            GroupDotsGrid(
                                group = group,
                                palette = palette,
                                onClick = { onToggleExpanded(group.tag) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }
}

private val GroupCardTopShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

private val GroupCardBottomShape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)

@Immutable
private data class UrlTestPalette(
    val good: Color,
    val medium: Color,
    val bad: Color,
    val neutral: Color,
) {
    fun forDelay(delay: Int): Color = when {
        delay <= 0 -> neutral
        delay < 800 -> good
        delay < 1500 -> medium
        else -> bad
    }
}

@Composable
private fun rememberUrlTestPalette(): UrlTestPalette {
    val darkTheme = (MaterialTheme.colorScheme.surface.luminance() < 0.5f)

    val neutral = MaterialTheme.colorScheme.onSurface.copy(alpha = if (darkTheme) 0.09f else 0.07f)

    return remember(darkTheme, neutral) {
        if (darkTheme) {
            UrlTestPalette(
                good = Color(0xFF7CB89E),
                medium = Color(0xFFD3A45E),
                bad = Color(0xFFDB8A62),
                neutral = neutral,
            )
        } else {
            UrlTestPalette(
                good = Color(0xFF3D8168),
                medium = Color(0xFFA8742F),
                bad = Color(0xFFC25E32),
                neutral = neutral,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupHeader(
    group: Group,
    title: String,
    isExpanded: Boolean,
    isTesting: Boolean,
    onToggleExpanded: () -> Unit,
    onUrlTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onToggleExpanded,
        modifier = modifier.fillMaxWidth(),
        shape = GroupCardTopShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 0.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                Text(
                    text = if (group.type == "urltest") "Авто" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            ) {
                Text(
                    text = "${group.items.size}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }

            IconButton(onClick = onUrlTest, enabled = !isTesting, modifier = Modifier.size(48.dp)) {
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = stringResource(R.string.url_test),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val rotationAngle by
                animateFloatAsState(
                    targetValue = if (isExpanded) 180f else 0f,
                    animationSpec = tween(200),
                    label = "ExpandIcon",
                )

            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription =
                if (isExpanded) {
                    stringResource(R.string.collapse)
                } else {
                    stringResource(R.string.expand)
                },
                modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = rotationAngle },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GroupDotsGrid(
    group: Group,
    palette: UrlTestPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(bottom = 12.dp).fillMaxWidth(),
        shape = GroupCardBottomShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        BoxWithConstraints(
            modifier =
            Modifier.clickable(onClick = onClick)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
        ) {
            val dotSize = 11.dp
            val dotSpacing = 4.dp

            val columns = maxOf(1, ((maxWidth + dotSpacing) / (dotSize + dotSpacing)).toInt())

            val rows = (group.items.size + columns - 1) / columns

            val gridHeight = dotSize * rows + dotSpacing * maxOf(0, rows - 1)

            Canvas(modifier = Modifier.fillMaxWidth().height(gridHeight)) {
                val dotSizePx = dotSize.toPx()
                val dotSpacingPx = dotSpacing.toPx()
                val cornerRadius = CornerRadius(4.dp.toPx())
                val selectedDotRadius = 2.dp.toPx()

                group.items.forEachIndexed { index, item ->
                    val x = (index % columns) * (dotSizePx + dotSpacingPx)

                    val y = (index / columns) * (dotSizePx + dotSpacingPx)

                    drawRoundRect(
                        color = palette.forDelay(item.urlTestDelay),
                        topLeft = Offset(x, y),
                        size = Size(dotSizePx, dotSizePx),
                        cornerRadius = cornerRadius,
                    )

                    if (item.tag == group.selected) {
                        drawCircle(
                            color = Color.White,
                            radius = selectedDotRadius,
                            center = Offset(x + dotSizePx / 2, y + dotSizePx / 2),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupItemRow(
    row: List<GroupItem>,
    columns: Int,
    selectedTag: String,
    isSelectable: Boolean,
    isLast: Boolean,
    palette: UrlTestPalette,
    onItemSelected: (String) -> Unit,
    onItemUrlTest: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(bottom = if (isLast) 12.dp else 0.dp).fillMaxWidth(),
        shape =
        if (isLast) {
            GroupCardBottomShape
        } else {
            RectangleShape
        },
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            row.forEach { item ->
                if (columns == 1) {
                    ServerListRow(
                        item = item,
                        isSelected = item.tag == selectedTag,
                        isSelectable = isSelectable,
                        palette = palette,
                        onClick = { onItemSelected(item.tag) },
                        onUrlTest = { onItemUrlTest(item.tag) },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    ProxyChip(
                        item = item,
                        isSelected = item.tag == selectedTag,
                        isSelectable = isSelectable,
                        palette = palette,
                        onClick = { onItemSelected(item.tag) },
                        onUrlTest = { onItemUrlTest(item.tag) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProxyChip(
    item: GroupItem,
    isSelected: Boolean,
    isSelectable: Boolean,
    palette: UrlTestPalette,
    onClick: () -> Unit,
    onUrlTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showContextMenu by remember { mutableStateOf(false) }

    val chipShape = MaterialTheme.shapes.medium
    val container by
        animateColorAsState(
            if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLowest
            },
            animationSpec = tween(200),
            label = "serverSelection",
        )

    Box(modifier = modifier) {
        Surface(
            modifier =
            Modifier.fillMaxWidth()
                .clip(chipShape)
                .semantics { selected = isSelected }
                .combinedClickable(
                    onClick = {
                        if (isSelectable) {
                            onClick()
                        }
                    },
                    onLongClick = { showContextMenu = true },
                ),
            shape = chipShape,
            color = container,
            border =
            BorderStroke(
                1.dp,
                if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                },
            ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = item.tag,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color =
                        if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isSelected) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            "Выбран",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.displayType,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color =
                        if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )

                    if (item.urlTestDelay > 0) {
                        Text(
                            text = "${item.urlTestDelay} мс",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = palette.forDelay(item.urlTestDelay),
                        )
                    } else if (item.urlTestTime > 0L) {
                        Text(
                            text = probeStatusLabel(item.probeStatus),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        DropdownMenu(expanded = showContextMenu, onDismissRequest = { showContextMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.url_test)) },
                leadingIcon = { Icon(Icons.Default.Speed, contentDescription = null) },
                onClick = {
                    showContextMenu = false
                    onUrlTest()
                },
            )
        }
    }
}

@Composable
private fun rememberBounceBlockingNestedScrollConnection(
    lazyListState: LazyListState,
): NestedScrollConnection = remember(lazyListState) {
    object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            // Only block upward scroll (y < 0) at bottom
            // to prevent sheet expansion.
            // Allow downward scroll (y > 0) at top
            // to let sheet collapse.
            return if (available.y < 0) {
                available
            } else {
                Offset.Zero
            }
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            // Only block upward fling (y < 0)
            // to prevent sheet expansion.
            // Allow downward fling (y > 0)
            // to let sheet collapse.
            return if (available.y < 0) {
                available
            } else {
                Velocity.Zero
            }
        }
    }
}

@Composable
private fun ServerOverview(
    state: GroupsUiState,
    status: Status,
    profiles: List<Profile>,
    onSelect: (Long) -> Unit,
    onToggle: () -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    protocol: String,
    onProtocol: (String) -> Unit,
    serviceStartTime: Long?,
    downlink: String,
    uplink: String,
    trafficAvailable: Boolean,
) {
    val remote by RemoteControlManager.remoteServer.collectAsState()
    var picker by remember { mutableStateOf(false) }
    var protocolMenu by remember { mutableStateOf(false) }
    val selected = state.groups.firstOrNull { it.selectable }?.selected.orEmpty()
    Card(
        colors =
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.Shield, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(
                        when (status) {
                            Status.Started -> "Подключено"
                            Status.Starting -> "Подключение…"
                            Status.Stopping -> "Отключение…"
                            else -> "Не подключено"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        selected.ifBlank { "Выберите сервер" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box {
                Surface(
                    onClick = { picker = true },
                    enabled = profiles.isNotEmpty() && remote == null,
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Subscriptions, null, modifier = Modifier.size(20.dp))
                        Text(
                            remote?.displayName
                                ?: state.subscriptionName.ifBlank { "Выбрать подписку" },
                            Modifier.weight(1f).padding(horizontal = 12.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Icon(Icons.Default.ExpandMore, "Выбрать подписку")
                    }
                }
                DropdownMenu(expanded = picker, onDismissRequest = { picker = false }) {
                    profiles.forEach { profile ->
                        DropdownMenuItem(
                            text = { Text(profile.name) },
                            leadingIcon = {
                                if (profile.id == state.profileId) {
                                    Icon(Icons.Outlined.CheckCircle, null)
                                }
                            },
                            onClick = {
                                picker = false
                                onSelect(profile.id)
                            },
                        )
                    }
                }
            }
            if (status == Status.Started && remote == null) {
                io.nekohasekai.sfa.compose.component.ConnectionInfoPill(
                    serviceStartTime,
                    downlink,
                    uplink,
                    trafficAvailable,
                )
            }
            if (status != Status.Started || remote != null) {
                Button(
                    onClick = onToggle,
                    enabled =
                    (remote != null || state.profileId != -1L) &&
                        status !in listOf(Status.Starting, Status.Stopping),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(
                        if (remote != null) {
                            "Выйти из удалённой сессии"
                        } else if (status == Status.Started) {
                            "Отключить"
                        } else {
                            "Подключить"
                        },
                    )
                }
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            query,
            onQuery,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Поиск серверов") },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Outlined.Close, "Очистить поиск")
                    }
                }
            },
        )
        Box {
            IconButton(onClick = { protocolMenu = true }) {
                Icon(
                    Icons.Outlined.FilterList,
                    "Фильтр протокола",
                    tint =
                    if (protocol.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            DropdownMenu(protocolMenu, { protocolMenu = false }) {
                (
                    listOf("") +
                        state.groups
                            .flatMap { it.items }
                            .map { it.displayType }
                            .distinct()
                            .sorted()
                    )
                    .forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.ifBlank { "Все протоколы" }) },
                            onClick = {
                                onProtocol(type)
                                protocolMenu = false
                            },
                        )
                    }
            }
        }
    }
    if (protocol.isNotBlank()) {
        InputChip(
            selected = true,
            onClick = { onProtocol("") },
            label = { Text(protocol) },
            trailingIcon = { Icon(Icons.Outlined.Close, "Сбросить фильтр", Modifier.size(16.dp)) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerListRow(
    item: GroupItem,
    isSelected: Boolean,
    isSelectable: Boolean,
    palette: UrlTestPalette,
    onClick: () -> Unit,
    onUrlTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val color by
        animateColorAsState(
            if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
            label = "serverRowSelection",
        )
    Box(modifier) {
        Surface(
            color = color,
            shape = MaterialTheme.shapes.medium,
            modifier =
            Modifier.fillMaxWidth()
                .semantics { selected = isSelected }
                .combinedClickable(
                    onClick = { if (isSelectable) onClick() },
                    onLongClick = { menu = true },
                ),
        ) {
            Row(
                Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.tag,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color =
                        if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        item.displayType,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.urlTestDelay > 0) {
                    Text(
                        "${item.urlTestDelay} мс",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.forDelay(item.urlTestDelay),
                    )
                } else if (item.urlTestTime > 0) {
                    Text(
                        probeStatusLabel(item.probeStatus),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (isSelected) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        "Выбран",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, "Действия с сервером")
                }
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(
                text = { Text("Проверить соединение") },
                leadingIcon = { Icon(Icons.Default.Speed, null) },
                onClick = {
                    menu = false
                    onUrlTest()
                },
            )
        }
    }
}

private fun probeStatusLabel(status: String): String = when (status) {
    "TIMEOUT" -> "Таймаут"
    "CONNECT_TIMEOUT" -> "Нет TCP-связи"
    "DNS_ERROR" -> "DNS"
    "HANDSHAKE_ERROR" -> "Handshake"
    "HTTP_ERROR" -> "HTTP"
    "CANCELLED" -> "Отменено"
    "TESTING" -> "…"
    "SUCCESS" -> ""
    "UNTESTED", "" -> ""
    else -> "Ошибка проверки"
}
