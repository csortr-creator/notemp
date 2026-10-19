package io.nekohasekai.sfa.compose.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.component.PreferenceSection
import io.nekohasekai.sfa.compose.component.RoutingSectionLink
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.models.DnsConfig
import io.nekohasekai.sfa.models.DnsServer
import io.nekohasekai.sfa.models.GeoFileSources
import io.nekohasekai.sfa.models.RoutingRule
import io.nekohasekai.sfa.utils.RoutingPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RoutingSettingsScreen(navController: NavController, section: String = "overview") {
    OverrideTopBar {
        TopAppBar(
            title = {
                Text(
                    when (section) {
                        "rules" -> "Правила маршрутизации"
                        "dns" -> "DNS"
                        "connection" -> "Параметры подключения"
                        "sources" -> "Списки сайтов и IP"
                        "advanced" -> "Дополнительные настройки"
                        else -> "Маршрутизация"
                    },
                )
            },
            navigationIcon = {
                run {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_description_back),
                        )
                    }
                }
            },
        )
    }

    val scaffoldPadding = LocalScaffoldPadding.current
    val scope = rememberCoroutineScope()
    var geoAdvanced by remember { mutableStateOf(false) }

    var strategy by remember { mutableStateOf(DnsConfig.Strategy.AUTO) }
    var cacheEnabled by remember { mutableStateOf(true) }
    var independentCache by remember { mutableStateOf(false) }
    var reverseMapping by remember { mutableStateOf(false) }
    var finalServer by remember { mutableStateOf("") }
    var servers by remember { mutableStateOf<List<DnsServer>>(emptyList()) }
    var geoSourceId by remember { mutableStateOf("") }
    var geoGeositeUrl by remember { mutableStateOf("") }
    var geoGeoipUrl by remember { mutableStateOf("") }
    var blockIpv6 by remember { mutableStateOf(Settings.routingBlockIpv6) }
    var tunStack by remember { mutableStateOf(Settings.tunStack) }
    var tunStackMenuOpen by remember { mutableStateOf(false) }
    var updateInterval by remember { mutableStateOf(Settings.ruleSetUpdateInterval) }
    var intervalMenuOpen by remember { mutableStateOf(false) }
    var rules by remember { mutableStateOf<List<RoutingRule>>(emptyList()) }
    var finalOutbound by remember { mutableStateOf(settingsObject(Settings.routingConfigJson).optString("finalOutbound", "profile")) }
    var loaded by remember { mutableStateOf(false) }
    var showResetRules by remember { mutableStateOf(false) }

    var strategyMenuOpen by remember { mutableStateOf(false) }
    var showAddServer by remember { mutableStateOf(false) }
    var editServerIndex by remember { mutableStateOf<Int?>(null) }
    var draftTag by remember { mutableStateOf("") }
    var draftAddress by remember { mutableStateOf("") }
    var draftDetour by remember { mutableStateOf("") }

    fun persist(
        nextStrategy: DnsConfig.Strategy = strategy,
        nextCache: Boolean = cacheEnabled,
        nextIndependent: Boolean = independentCache,
        nextReverse: Boolean = reverseMapping,
        nextFinal: String = finalServer,
        nextServers: List<DnsServer> = servers,
        nextGeoSourceId: String = geoSourceId,
        nextGeoGeositeUrl: String = geoGeositeUrl,
        nextGeoGeoipUrl: String = geoGeoipUrl,
        nextBlockIpv6: Boolean = blockIpv6,
        nextTunStack: String = tunStack,
        nextUpdateInterval: Long = updateInterval,
        nextRules: List<RoutingRule> = rules,
    ) {
        strategy = nextStrategy
        cacheEnabled = nextCache
        independentCache = nextIndependent
        reverseMapping = nextReverse
        finalServer = nextFinal
        servers = nextServers
        geoSourceId = nextGeoSourceId
        geoGeositeUrl = nextGeoGeositeUrl
        geoGeoipUrl = nextGeoGeoipUrl
        blockIpv6 = nextBlockIpv6
        Settings.routingBlockIpv6 = nextBlockIpv6
        tunStack = nextTunStack
        Settings.tunStack = nextTunStack
        updateInterval = nextUpdateInterval
        Settings.ruleSetUpdateInterval = nextUpdateInterval
        rules = nextRules
        Settings.routingConfigJson =
            encodeRoutingConfig(
                nextStrategy,
                nextCache,
                nextReverse,
                nextFinal,
                nextServers,
                nextGeoSourceId,
                nextGeoGeositeUrl,
                nextGeoGeoipUrl,
                nextRules,
            )
    }

    if (showResetRules) {
        AlertDialog(
            onDismissRequest = { showResetRules = false },
            title = { Text("Удалить мои правила?") },
            text = { Text("Будут использоваться маршруты из подписки. Настройки DNS сохранятся.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetRules = false
                        persist(nextRules = emptyList())
                    },
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = { TextButton(onClick = { showResetRules = false }) { Text("Отмена") } },
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, section) {
        var reloadJob: kotlinx.coroutines.Job? = null
        fun reload() {
            reloadJob?.cancel()
            reloadJob =
                scope.launch {
                    val raw =
                        withContext(Dispatchers.IO) {
                            val old = Settings.routingConfigJson
                            RoutingPresets.migrateConfig(old).also {
                                if (it != old) Settings.routingConfigJson = it
                            }
                        }
                    val parsed = decodeRoutingConfig(raw)
                    finalOutbound = settingsObject(raw).optString("finalOutbound", "profile")
                    strategy = parsed.strategy
                    cacheEnabled = parsed.cacheEnabled
                    independentCache = parsed.independentCache
                    reverseMapping = parsed.reverseMapping
                    finalServer = parsed.finalServer
                    servers = parsed.servers
                    geoSourceId = parsed.geoSourceId
                    geoGeositeUrl = parsed.geoGeositeUrl
                    geoGeoipUrl = parsed.geoGeoipUrl
                    rules = parsed.rules
                    blockIpv6 = Settings.routingBlockIpv6
                    tunStack = Settings.tunStack
                    updateInterval = Settings.ruleSetUpdateInterval
                    loaded = true
                }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        reload()
        onDispose {
            reloadJob?.cancel()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (!loaded) {
        Box(Modifier.fillMaxSize().padding(scaffoldPadding), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator()
        }
        return
    }

    fun openServer(index: Int? = null) {
        editServerIndex = index
        val server = index?.let { servers.getOrNull(it) }
        draftTag = server?.tag.orEmpty()
        draftAddress = server?.address.orEmpty()
        draftDetour = server?.detour.orEmpty()
        showAddServer = true
    }

    val itemColors = ListItemDefaults.colors(containerColor = Color.Transparent)
    Column(
        modifier =
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(
                top = scaffoldPadding.calculateTopPadding() + 8.dp,
                bottom = scaffoldPadding.calculateBottomPadding() + 24.dp,
            ),
    ) {
        if (section == "overview") {
            PreferenceSection(
                "Куда идёт трафик",
                Icons.Outlined.Route,
                "Ваши правила дополняют настройки подписки",
            ) {
                FlowRow(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = finalOutbound == "profile",
                        onClick = {
                            finalOutbound = "profile"
                            Settings.routingConfigJson = settingsObject(Settings.routingConfigJson).put("finalOutbound", "profile").toString()
                        },
                        label = { Text("Из подписки") },
                        leadingIcon = { Icon(Icons.Outlined.Subscriptions, null) },
                    )
                    FilterChip(
                        selected = finalOutbound == "proxy",
                        onClick = {
                            finalOutbound = "proxy"
                            Settings.routingConfigJson = settingsObject(Settings.routingConfigJson).put("finalOutbound", "proxy").toString()
                        },
                        label = { Text("Через VPN") },
                        leadingIcon = { Icon(Icons.Outlined.Shield, null) },
                    )
                    FilterChip(
                        selected = finalOutbound == "direct",
                        onClick = {
                            finalOutbound = "direct"
                            Settings.routingConfigJson = settingsObject(Settings.routingConfigJson).put("finalOutbound", "direct").toString()
                        },
                        label = { Text("Напрямую") },
                        leadingIcon = { Icon(Icons.Outlined.Public, null) },
                    )
                    FilterChip(
                        selected = finalOutbound == "block",
                        onClick = {
                            finalOutbound = "block"
                            Settings.routingConfigJson = settingsObject(Settings.routingConfigJson).put("finalOutbound", "block").toString()
                        },
                        label = { Text("Блокировать") },
                        leadingIcon = { Icon(Icons.Outlined.Block, null) },
                    )
                }
                Text(
                    "Кнопки выбирают направление трафика, который не совпал с вашими правилами и правилами подписки. Изменения применяются после переподключения VPN.",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RoutingSectionLink(
                    "Мои правила",
                    "Активно: ${rules.count { it.enabled }} · всего: ${rules.size}",
                    Icons.Outlined.AltRoute,
                ) {
                    navController.navigate("settings/routing/section/rules")
                }
                rules
                    .withIndex()
                    .filter { it.value.enabled }
                    .take(3)
                    .forEach { indexed ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    indexed.value.displayTitle(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Text(indexed.value.displaySubtitle(), maxLines = 2)
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Outlined.CheckCircle,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            colors = itemColors,
                            modifier =
                            Modifier.clickable {
                                navController.navigate("settings/routing/rule/${indexed.index}")
                            },
                        )
                    }
                FilledTonalButton(
                    onClick = { navController.navigate("settings/routing/rule/-1") },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Icon(Icons.Default.Add, null)
                    Text("Добавить правило", Modifier.padding(start = 8.dp))
                }
            }
            PreferenceSection(
                "Быстрые настройки",
                Icons.Outlined.AutoAwesome,
                "Можно включать и выключать по отдельности",
            ) {
                val whitelistEnabled =
                    listOf(RoutingPresets.WHITELIST_DOMAINS, RoutingPresets.WHITELIST_IPS).all { tag ->
                        rules.any { it.enabled && tag in RoutingPresets.tags(it.ruleSet) }
                    }
                RoutingPresetRow(
                    "Whitelist для России",
                    "Для ограничений мобильного интернета: ресурсы белого списка напрямую, остальное через VPN",
                    Icons.Outlined.VerifiedUser,
                    whitelistEnabled,
                ) { enabled ->
                    persist(
                        nextRules =
                        if (enabled) {
                            RoutingPresets.whitelist(rules)
                        } else {
                            rules.filterNot(RoutingPresets::isWhitelist)
                        },
                    )
                }
                RoutingPresetRow(
                    "РФ напрямую",
                    "Российские домены и IP из обновляемых SRS-списков",
                    Icons.Outlined.Public,
                    rules.any { it.enabled && RoutingPresets.isRf(it) },
                ) { enabled ->
                    persist(
                        nextRules =
                        if (enabled) {
                            RoutingPresets.rfDirect(rules)
                        } else {
                            rules.filterNot(RoutingPresets::isRf)
                        },
                    )
                }
                RoutingPresetRow(
                    "Блокировать рекламу",
                    "Рекламные домены из готового списка",
                    Icons.Outlined.Block,
                    rules.any { it.name == "ads" && it.enabled },
                ) { enabled ->
                    val keep = rules.filter { it.name != "ads" }
                    persist(
                        nextRules =
                        if (!enabled) {
                            keep
                        } else {
                            keep +
                                RoutingRule(
                                    name = "ads",
                                    ruleSet = "geosite-category-ads-all",
                                    outbound = RoutingRule.OUTBOUND_BLOCK,
                                )
                        },
                    )
                }
            }
            PreferenceSection("Параметры маршрутизации", Icons.Outlined.Tune) {
                RoutingSectionLink(
                    "Файлы маршрутизации",
                    "Импорт SRS / JSON и собственные URL",
                    Icons.Outlined.Public,
                ) {
                    navController.navigate("settings/rule-sets")
                }

                RoutingSectionLink(
                    "Сниффинг и перехват DNS",
                    "Протоколы, тайм-аут и DNS hijack",
                    Icons.Outlined.Tune,
                ) {
                    navController.navigate("settings/network-options")
                }

                RoutingSectionLink(
                    "DNS · адреса сайтов",
                    if (servers.isEmpty()) {
                        "Используются настройки подписки"
                    } else {
                        "Своих DNS-серверов: ${servers.size}"
                    },
                    Icons.Outlined.Dns,
                ) {
                    navController.navigate("settings/dns")
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                RoutingSectionLink(
                    "Дополнительно",
                    "Источники geosite и geoip",
                    Icons.Outlined.Tune,
                ) {
                    navController.navigate("settings/routing/section/advanced")
                }
            }
            Text(
                "Изменения применятся после переподключения VPN.",
                Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (section == "advanced") {
            PreferenceSection("Дополнительные настройки", Icons.Outlined.Tune) {
                RoutingSectionLink(
                    "Списки сайтов и IP",
                    GeoFileSources.ALL.firstOrNull { it.id == geoSourceId }?.name ?: "Из подписки",
                    Icons.Outlined.Public,
                ) {
                    navController.navigate("settings/routing/section/sources")
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                RoutingSectionLink(
                    "Сниффинг и обновление списков",
                    "Протоколы, тайм-аут и частота обновлений",
                    Icons.Outlined.Tune,
                ) {
                    navController.navigate("settings/network-options")
                }
            }
        }

        if (section == "rules") {
            PreferenceSection(
                "Правила",
                Icons.Outlined.AltRoute,
                "Первое совпавшее правило определяет направление трафика",
                action = {
                    IconButton(onClick = { navController.navigate("settings/routing/rule/-1") }) {
                        Icon(Icons.Default.Add, "Добавить правило")
                    }
                },
            ) {
                if (rules.isEmpty()) {
                    EmptyStateBox(
                        "Для остальных сайтов используются маршруты подписки",
                        "Добавить правило",
                    ) {
                        navController.navigate("settings/routing/rule/-1")
                    }
                }
                rules.forEachIndexed { index, rule ->
                    var menuOpen by remember(rule.displayTitle(), index) { mutableStateOf(false) }
                    ListItem(
                        headlineContent = {
                            Text(
                                rule.displayTitle(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(
                                rule.displaySubtitle(),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = {
                            Icon(
                                when (rule.outbound) {
                                    "direct" -> Icons.Outlined.Public
                                    "block" -> Icons.Outlined.Block
                                    else -> Icons.Outlined.Shield
                                },
                                null,
                                tint =
                                if (rule.enabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = rule.enabled,
                                    onCheckedChange = { on ->
                                        persist(
                                            nextRules =
                                            rules.toMutableList().also {
                                                it[index] = rule.copy(enabled = on)
                                            },
                                        )
                                    },
                                )
                                Box {
                                    IconButton(onClick = { menuOpen = true }) {
                                        Icon(Icons.Default.MoreVert, "Действия с правилом")
                                    }
                                    DropdownMenu(
                                        expanded = menuOpen,
                                        onDismissRequest = { menuOpen = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Изменить") },
                                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                            onClick = {
                                                menuOpen = false
                                                navController.navigate(
                                                    "settings/routing/rule/$index",
                                                )
                                            },
                                        )
                                        if (index > 0) {
                                            DropdownMenuItem(
                                                text = { Text("Поднять правило") },
                                                onClick = {
                                                    menuOpen = false
                                                    val next = rules.toMutableList()
                                                    val previous = next[index - 1]
                                                    next[index - 1] = next[index]
                                                    next[index] = previous
                                                    persist(nextRules = next)
                                                },
                                            )
                                        }
                                        if (index < rules.lastIndex) {
                                            DropdownMenuItem(
                                                text = { Text("Опустить правило") },
                                                onClick = {
                                                    menuOpen = false
                                                    val next = rules.toMutableList()
                                                    val following = next[index + 1]
                                                    next[index + 1] = next[index]
                                                    next[index] = following
                                                    persist(nextRules = next)
                                                },
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("Удалить") },
                                            leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                            onClick = {
                                                menuOpen = false
                                                persist(
                                                    nextRules =
                                                    rules.toMutableList().also {
                                                        it.removeAt(index)
                                                    },
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        },
                        colors = itemColors,
                        modifier =
                        Modifier.clickable {
                            navController.navigate("settings/routing/rule/$index")
                        },
                    )
                    if (index < rules.lastIndex) {
                        HorizontalDivider(
                            Modifier.padding(start = 56.dp, end = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
                if (rules.isNotEmpty()) {
                    TextButton(
                        onClick = { showResetRules = true },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Text("Удалить все мои правила")
                    }
                }
            }
        }
        if (section == "dns") {
            PreferenceSection("Пресеты DNS", Icons.Outlined.AutoAwesome) {
                Text(
                    "DNS",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = finalServer == "cld" && servers.any { it.tag == "ynd" },
                        onClick = {
                            persist(
                                nextStrategy = DnsConfig.Strategy.IPV4_ONLY,
                                nextCache = true,
                                nextReverse = true,
                                nextFinal = "cld",
                                nextServers =
                                listOf(
                                    DnsServer("cld", "https://1.1.1.1/dns-query", "proxy"),
                                    DnsServer("ynd", "77.88.8.8", "direct"),
                                ),
                            )
                        },
                        label = { Text("Cloudflare + Яндекс") },
                    )
                    FilterChip(
                        selected = finalServer == "cld" && servers.size == 1,
                        onClick = {
                            persist(
                                nextStrategy = DnsConfig.Strategy.IPV4_ONLY,
                                nextCache = true,
                                nextReverse = false,
                                nextFinal = "cld",
                                nextServers =
                                listOf(DnsServer("cld", "https://1.1.1.1/dns-query", "proxy")),
                            )
                        },
                        label = { Text("Cloudflare") },
                    )
                    TextButton(
                        onClick = {
                            persist(
                                nextStrategy = DnsConfig.Strategy.AUTO,
                                nextCache = true,
                                nextReverse = false,
                                nextFinal = "",
                                nextServers = emptyList(),
                            )
                        },
                    ) {
                        Text("Сброс DNS")
                    }
                }
            }

            PreferenceSection(
                "DNS",
                Icons.Outlined.Dns,
                "${strategyLabel(strategy)} · " +
                    if (servers.isEmpty()) "Из подписки" else "Серверов: ${servers.size}",
                collapsible = false,
            ) {
                ListItem(
                    headlineContent = { Text("Стратегия адресов") },
                    supportingContent = { Text(strategyLabel(strategy)) },
                    trailingContent = { Icon(Icons.Outlined.ExpandMore, null) },
                    colors = itemColors,
                    modifier = Modifier.clickable { strategyMenuOpen = true },
                )
                DropdownMenu(
                    expanded = strategyMenuOpen,
                    onDismissRequest = { strategyMenuOpen = false },
                ) {
                    DnsConfig.Strategy.entries.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(strategyLabel(item)) },
                            onClick = {
                                strategyMenuOpen = false
                                persist(nextStrategy = item)
                            },
                        )
                    }
                }
                ListItem(
                    headlineContent = { Text("Кэш запросов") },
                    supportingContent = { Text("Повторные запросы разрешаются быстрее") },
                    trailingContent = {
                        Switch(
                            checked = cacheEnabled,
                            onCheckedChange = { persist(nextCache = it) },
                        )
                    },
                    colors = itemColors,
                )
                ListItem(
                    headlineContent = { Text("Обратное сопоставление") },
                    supportingContent = { Text("Связывать адреса с доменами для правил") },
                    trailingContent = {
                        Switch(
                            checked = reverseMapping,
                            onCheckedChange = { persist(nextReverse = it) },
                        )
                    },
                    colors = itemColors,
                )
                var finalMenu by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text("Сервер по умолчанию") },
                    supportingContent = { Text(finalServer.ifBlank { "Из подписки" }) },
                    colors = itemColors,
                    trailingContent = { Icon(Icons.Outlined.ExpandMore, null) },
                    modifier = Modifier.clickable { finalMenu = true },
                )
                DropdownMenu(expanded = finalMenu, onDismissRequest = { finalMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Из подписки") },
                        onClick = {
                            finalMenu = false
                            persist(nextFinal = "")
                        },
                    )
                    servers.forEach { server ->
                        DropdownMenuItem(
                            text = { Text(server.tag) },
                            onClick = {
                                finalMenu = false
                                persist(nextFinal = server.tag)
                            },
                        )
                    }
                }
                SectionHeaderWithAction("DNS-серверы") { openServer() }
                if (servers.isEmpty()) {
                    Text(
                        "Используются серверы подписки",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                servers.forEachIndexed { index, server ->
                    ListItem(
                        headlineContent = { Text(server.tag) },
                        supportingContent = {
                            Text(
                                server.address +
                                    "\n" +
                                    if (server.detour == "direct") "Напрямую" else "Через сервер",
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = { Icon(Icons.Outlined.Dns, null) },
                        trailingContent = {
                            IconButton(
                                onClick = {
                                    val next = servers.toMutableList().also { it.removeAt(index) }
                                    persist(
                                        nextServers = next,
                                        nextFinal =
                                        if (finalServer == server.tag) "" else finalServer,
                                    )
                                },
                            ) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    "Удалить DNS-сервер",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        colors = itemColors,
                        modifier = Modifier.clickable { openServer(index) },
                    )
                }
            }
        }
        if (section == "connection") {
            PreferenceSection(
                "Подключение",
                Icons.Outlined.Tune,
                "IPv6, сетевой стек и обновления",
                collapsible = false,
            ) {
                ListItem(
                    headlineContent = { Text("Блокировать IPv6") },
                    supportingContent = { Text("Использовать только IPv4") },
                    trailingContent = {
                        Switch(
                            checked = blockIpv6,
                            onCheckedChange = { persist(nextBlockIpv6 = it) },
                        )
                    },
                    colors = itemColors,
                )
                ListItem(
                    headlineContent = { Text("Сетевой стек") },
                    supportingContent = { Text(tunStack) },
                    trailingContent = { Icon(Icons.Outlined.ExpandMore, null) },
                    colors = itemColors,
                    modifier = Modifier.clickable { tunStackMenuOpen = true },
                )
                DropdownMenu(
                    expanded = tunStackMenuOpen,
                    onDismissRequest = { tunStackMenuOpen = false },
                ) {
                    listOf("system", "gvisor", "mixed").forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item) },
                            onClick = {
                                tunStackMenuOpen = false
                                persist(nextTunStack = item)
                            },
                        )
                    }
                }
                ListItem(
                    headlineContent = { Text("Обновлять списки правил") },
                    supportingContent = { Text(intervalLabel(updateInterval)) },
                    trailingContent = { Icon(Icons.Outlined.ExpandMore, null) },
                    colors = itemColors,
                    modifier = Modifier.clickable { intervalMenuOpen = true },
                )
                DropdownMenu(
                    expanded = intervalMenuOpen,
                    onDismissRequest = { intervalMenuOpen = false },
                ) {
                    listOf(0L, 3600000L, 86400000L, 604800000L, -1L).forEach { interval ->
                        DropdownMenuItem(
                            text = { Text(intervalLabel(interval)) },
                            onClick = {
                                intervalMenuOpen = false
                                persist(nextUpdateInterval = interval)
                            },
                        )
                    }
                }
            }
        }
        if (section == "sources") {
            PreferenceSection(
                "Источники геоданных",
                Icons.Outlined.Public,
                "Дополнительные списки доменов и IP",
                collapsible = false,
            ) {
                ListItem(
                    headlineContent = { Text("Из подписки") },
                    leadingContent = {
                        RadioButton(
                            selected = geoSourceId.isBlank(),
                            onClick = { persist(nextGeoSourceId = "") },
                        )
                    },
                    colors = itemColors,
                    modifier = Modifier.clickable { persist(nextGeoSourceId = "") },
                )
                GeoFileSources.ALL.forEach { source ->
                    ListItem(
                        headlineContent = { Text(source.name) },
                        supportingContent = { Text(source.description) },
                        leadingContent = {
                            RadioButton(
                                selected = geoSourceId == source.id,
                                onClick = { persist(nextGeoSourceId = source.id) },
                            )
                        },
                        colors = itemColors,
                        modifier = Modifier.clickable { persist(nextGeoSourceId = source.id) },
                    )
                }
                TextButton(
                    onClick = { geoAdvanced = !geoAdvanced },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Text(if (geoAdvanced) "Скрыть свои источники" else "Указать свои источники")
                }
                androidx.compose.animation.AnimatedVisibility(geoAdvanced) {
                    Column {
                        OutlinedTextField(
                            value = geoGeositeUrl,
                            onValueChange = { persist(nextGeoGeositeUrl = it) },
                            label = { Text("Базовый URL доменов") },
                            singleLine = true,
                            modifier =
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        OutlinedTextField(
                            value = geoGeoipUrl,
                            onValueChange = { persist(nextGeoGeoipUrl = it) },
                            label = { Text("Базовый URL IP-адресов") },
                            singleLine = true,
                            modifier =
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }

    if (showAddServer) {
        AlertDialog(
            onDismissRequest = { showAddServer = false },
            title = {
                Text(if (editServerIndex == null) "Добавить DNS-сервер" else "Изменить DNS-сервер")
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = draftTag,
                        onValueChange = { draftTag = it },
                        label = { Text("Tag") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draftAddress,
                        onValueChange = { draftAddress = it },
                        label = { Text("Address (IP / URL)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draftDetour,
                        onValueChange = { draftDetour = it },
                        label = { Text("Detour (outbound tag, опц.)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val next = servers.toMutableList()
                        val srv = DnsServer(draftTag, draftAddress, draftDetour.ifBlank { null })
                        if (editServerIndex != null) {
                            next[editServerIndex!!] = srv
                        } else {
                            next.add(srv)
                        }
                        persist(nextServers = next)
                        showAddServer = false
                    },
                    enabled = draftTag.isNotBlank() && draftAddress.isNotBlank(),
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = { TextButton(onClick = { showAddServer = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun SectionHeaderWithAction(title: String, onActionClick: () -> Unit) {
    Row(
        modifier =
        Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        IconButton(onClick = onActionClick) {
            Icon(Icons.Default.Add, contentDescription = "Добавить")
        }
    }
}

@Composable
private fun EmptyStateBox(text: String, buttonText: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            FilledTonalButton(onClick = onClick) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(buttonText)
            }
        }
    }
}

private fun intervalLabel(intervalMs: Long): String = when (intervalMs) {
    0L -> "Каждый запуск"
    60 * 60 * 1000L -> "Раз в час"
    24 * 60 * 60 * 1000L -> "Раз в день"
    7 * 24 * 60 * 60 * 1000L -> "Раз в неделю"
    -1L -> "Никогда (только при отсутствии)"
    else -> "Пользовательский (${intervalMs / 1000} сек)"
}

private fun strategyLabel(strategy: DnsConfig.Strategy): String = when (strategy) {
    DnsConfig.Strategy.AUTO -> "Auto"
    DnsConfig.Strategy.PREFER_IPV4 -> "Prefer IPv4"
    DnsConfig.Strategy.PREFER_IPV6 -> "Prefer IPv6"
    DnsConfig.Strategy.IPV4_ONLY -> "IPv4 only"
    DnsConfig.Strategy.IPV6_ONLY -> "IPv6 only"
}

private data class RoutingConfigState(
    val strategy: DnsConfig.Strategy = DnsConfig.Strategy.AUTO,
    val cacheEnabled: Boolean = true,
    val independentCache: Boolean = false,
    val reverseMapping: Boolean = false,
    val finalServer: String = "",
    val servers: List<DnsServer> = emptyList(),
    val geoSourceId: String = "",
    val geoGeositeUrl: String = "",
    val geoGeoipUrl: String = "",
    val rules: List<RoutingRule> = emptyList(),
)

private fun decodeRoutingConfig(raw: String): RoutingConfigState {
    if (raw.isBlank()) return RoutingConfigState()
    return try {
        val root = JSONObject(raw)
        val dns = root.optJSONObject("dns")
        val geo = root.optJSONObject("geo")
        val rulesJson = root.optJSONArray("rules")

        var strategy = DnsConfig.Strategy.AUTO
        var cacheEnabled = true
        var independentCache = false
        var reverseMapping = false
        var finalServer = ""
        var servers: List<DnsServer> = emptyList()

        if (dns != null) {
            strategy =
                try {
                    DnsConfig.Strategy.valueOf(
                        dns.optString("strategy", DnsConfig.Strategy.AUTO.name),
                    )
                } catch (_: Exception) {
                    DnsConfig.Strategy.AUTO
                }
            cacheEnabled = dns.optBoolean("cacheEnabled", true)
            independentCache = dns.optBoolean("independentCache", false)
            reverseMapping = dns.optBoolean("reverseMapping", false)
            finalServer = dns.optString("finalServer", "")
            val serversJson = dns.optJSONArray("servers") ?: JSONArray()
            servers = buildList {
                for (i in 0 until serversJson.length()) {
                    val obj = serversJson.optJSONObject(i) ?: continue
                    val tag = obj.optString("tag")
                    val address = obj.optString("address")
                    if (tag.isBlank() || address.isBlank()) continue
                    val detour = obj.optString("detour").ifBlank { null }
                    add(DnsServer(tag = tag, address = address, detour = detour))
                }
            }
        }

        val rules = buildList {
            if (rulesJson != null) {
                for (i in 0 until rulesJson.length()) {
                    val obj = rulesJson.optJSONObject(i) ?: continue
                    val type =
                        try {
                            RoutingRule.Type.valueOf(
                                obj.optString("type", RoutingRule.Type.DOMAIN.name),
                            )
                        } catch (_: Exception) {
                            RoutingRule.Type.DOMAIN
                        }
                    val value = obj.optString("value")
                    val outbound =
                        obj.optString("outbound", RoutingRule.OUTBOUND_PROXY).ifBlank {
                            RoutingRule.OUTBOUND_PROXY
                        }
                    val rule =
                        RoutingRule(
                            name = obj.optString("name"),
                            domain = obj.optString("domain"),
                            domainSuffix = obj.optString("domainSuffix"),
                            domainKeyword = obj.optString("domainKeyword"),
                            ipCidr = obj.optString("ipCidr"),
                            port = obj.optString("port"),
                            sourceIpCidr = obj.optString("sourceIpCidr"),
                            sourcePort = obj.optString("sourcePort"),
                            packageName = obj.optString("packageName"),
                            ruleSet = obj.optString("ruleSet"),
                            network = obj.optString("network"),
                            protocol = obj.optString("protocol"),
                            wifiSsid = obj.optString("wifiSsid"),
                            wifiBssid = obj.optString("wifiBssid"),
                            clashMode = obj.optString("clashMode"),
                            outbound = outbound,
                            dnsRule = obj.optBoolean("dnsRule", false),
                            type = type,
                            value = value,
                            enabled = obj.optBoolean("enabled", true),
                        )
                    val hasMatch =
                        listOf(
                            rule.domain,
                            rule.domainSuffix,
                            rule.domainKeyword,
                            rule.ipCidr,
                            rule.port,
                            rule.sourceIpCidr,
                            rule.sourcePort,
                            rule.packageName,
                            rule.ruleSet,
                            rule.protocol,
                            rule.wifiSsid,
                            rule.wifiBssid,
                            rule.clashMode,
                            rule.value,
                        )
                            .any { it.isNotBlank() }
                    if (hasMatch) add(rule)
                }
            }
        }

        RoutingConfigState(
            strategy = strategy,
            cacheEnabled = cacheEnabled,
            independentCache = independentCache,
            reverseMapping = reverseMapping,
            finalServer = finalServer,
            servers = servers,
            geoSourceId = geo?.optString("sourceId").orEmpty(),
            geoGeositeUrl = geo?.optString("geositeUrl").orEmpty(),
            geoGeoipUrl = geo?.optString("geoipUrl").orEmpty(),
            rules = rules,
        )
    } catch (_: Exception) {
        RoutingConfigState()
    }
}

private fun encodeRoutingConfig(
    strategy: DnsConfig.Strategy,
    cacheEnabled: Boolean,
    reverseMapping: Boolean,
    finalServer: String,
    servers: List<DnsServer>,
    geoSourceId: String,
    geoGeositeUrl: String,
    geoGeoipUrl: String,
    rules: List<RoutingRule>,
): String {
    val root =
        runCatching { JSONObject(Settings.routingConfigJson.ifBlank { "{}" }) }
            .getOrDefault(JSONObject())
    val dns =
        (root.optJSONObject("dns") ?: JSONObject())
            .put("strategy", strategy.name)
            .put("cacheEnabled", cacheEnabled)
            .put("reverseMapping", reverseMapping)
            .put("finalServer", finalServer)
    val serversJson = JSONArray()
    servers.forEach { server ->
        val oldServers = root.optJSONObject("dns")?.optJSONArray("servers") ?: JSONArray()
        val obj =
            (0 until oldServers.length())
                .mapNotNull { oldServers.optJSONObject(it) }
                .firstOrNull { it.optString("tag") == server.tag }
                ?.let { JSONObject(it.toString()) } ?: JSONObject()
        obj.put("tag", server.tag).put("address", server.address)
        if (!server.detour.isNullOrBlank()) {
            obj.put("detour", server.detour)
        }
        serversJson.put(obj)
    }
    dns.put("servers", serversJson)

    val rulesJson = JSONArray()
    rules.forEach { rule ->
        val obj =
            JSONObject()
                .put("name", rule.name)
                .put("domain", rule.domain)
                .put("domainSuffix", rule.domainSuffix)
                .put("domainKeyword", rule.domainKeyword)
                .put("ipCidr", rule.ipCidr)
                .put("port", rule.port)
                .put("sourceIpCidr", rule.sourceIpCidr)
                .put("sourcePort", rule.sourcePort)
                .put("packageName", rule.packageName)
                .put("ruleSet", rule.ruleSet)
                .put("network", rule.network)
                .put("protocol", rule.protocol)
                .put("wifiSsid", rule.wifiSsid)
                .put("wifiBssid", rule.wifiBssid)
                .put("clashMode", rule.clashMode)
                .put("outbound", rule.outbound)
                .put("dnsRule", rule.dnsRule)
                .put("type", rule.type.name)
                .put("value", rule.value)
                .put("enabled", rule.enabled)
        rulesJson.put(obj)
    }

    return root
        .put("dns", dns)
        .put(
            "geo",
            JSONObject()
                .put("sourceId", geoSourceId)
                .put("geositeUrl", geoGeositeUrl)
                .put("geoipUrl", geoGeoipUrl),
        )
        .put("rules", rulesJson)
        .toString()
}

@Composable
private fun RoutingPresetRow(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Switch(enabled, onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
