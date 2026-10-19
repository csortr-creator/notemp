package io.nekohasekai.sfa.compose.screen.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.compose.component.PreferenceSection
import io.nekohasekai.sfa.database.Settings

@Composable
fun NetworkOptionsScreen(navController: NavController) {
    ClientSettingsPage("Параметры маршрутизации", navController) { NetworkOptionsContent(false) }
}

@Composable
internal fun NetworkOptionsContent(core: Boolean) {
    var raw by remember { mutableStateOf(Settings.coreOptionsJson) }
    var advancedSniff by remember { mutableStateOf(false) }
    val options = settingsObject(raw)
    fun set(key: String, value: Any) {
        val current = settingsObject(Settings.coreOptionsJson).put(key, value)
        raw = current.toString()
        Settings.coreOptionsJson = raw
    }
    if (core) {
        var stack by remember { mutableStateOf(Settings.tunStack) }
        var ipv6Block by remember { mutableStateOf(Settings.routingBlockIpv6) }
        PreferenceSection("Туннель", Icons.Outlined.Settings) {
            SettingChoice(
                "Сетевой стек",
                stack,
                listOf(
                    "system" to "System · системный",
                    "gvisor" to "gVisor · пользовательский",
                    "mixed" to "Mixed · смешанный",
                ),
            ) {
                stack = it
                Settings.tunStack = it
            }
            SettingToggle(
                "Задать MTU",
                "Выключено: использовать MTU из подписки",
                options.has("mtu"),
            ) {
                if (it) {
                    set("mtu", 1500)
                } else {
                    val current = settingsObject(Settings.coreOptionsJson)
                    current.remove("mtu")
                    raw = current.toString()
                    Settings.coreOptionsJson = raw
                }
            }
            if (options.has("mtu")) {
                SettingNumber(
                    "MTU",
                    options.optInt("mtu", 1500),
                    1280..9000,
                    { set("mtu", it) },
                    "байт",
                )
            }
            SettingToggle(
                "IPv6",
                "Включено: IPv4 и IPv6. Выключено: только IPv4; IPv6-трафик отклоняется. Стратегия DNS настраивается отдельно.",
                !ipv6Block && options.optString("ipMode", "profile") != "ipv4",
            ) {
                ipv6Block = !it
                Settings.routingBlockIpv6 = !it
                set("ipMode", if (it) "dual" else "ipv4")
            }
            SettingChoice(
                "Строгая маршрутизация",
                if (options.has("strictRoute")) {
                    options.optBoolean("strictRoute").toString()
                } else {
                    "profile"
                },
                listOf("profile" to "Из подписки", "true" to "Включить", "false" to "Выключить"),
            ) {
                if (it == "profile") {
                    val current = settingsObject(Settings.coreOptionsJson)
                    current.remove("strictRoute")
                    raw = current.toString()
                    Settings.coreOptionsJson = raw
                } else {
                    set("strictRoute", it.toBoolean())
                }
            }
            Text(
                "В Android строгая маршрутизация запрещает приложениям обходить активный VPN. Исключения маршрутов и приложений сохраняются. Для блокировки сети после остановки VPN включите «Блокировать соединения без VPN» в системных настройках Android.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            var allowBypass by remember { mutableStateOf(Settings.allowBypass) }
            SettingToggle(
                "Разрешить обход VPN",
                "Если включено, приложения могут обойти это VPN-соединение и использовать базовую сеть напрямую (VpnService.allowBypass). При включённой строгой маршрутизации обход всё равно ограничивается.",
                allowBypass,
            ) {
                allowBypass = it
                Settings.allowBypass = it
            }
        }
        PreferenceSection("Проверка задержки", Icons.Outlined.Speed) {
            SettingNumber(
                "Тайм-аут проверки сервера",
                options.optInt("pingTimeoutSeconds", 12),
                1..60,
                { set("pingTimeoutSeconds", it) },
                "с",
            )
            SettingToggle(
                "Мерить по прогретому соединению",
                "Задержка измеряется вторым запросом к ответившему сайту. Выключено — с учётом установки соединения. При задержке основного сайта проверяется резервный в рамках общего тайм-аута.",
                options.optBoolean("pingWarm", false),
            ) {
                set("pingWarm", it)
            }
        }
        PreferenceSection("Диагностика ядра", Icons.Outlined.BugReport) {
            SettingChoice(
                "Уровень журнала",
                options.optString("logLevel", "profile"),
                listOf(
                    "profile" to "Из подписки",
                    "trace" to "Trace",
                    "debug" to "Debug",
                    "info" to "Info",
                    "warn" to "Warning",
                    "error" to "Error",
                ),
            ) {
                set("logLevel", it)
            }
            SettingToggle(
                "Определять приложения",
                "Определение процесса для правил приложений",
                options.optBoolean("findProcess", false),
            ) {
                set("findProcess", it)
            }
        }
    } else {
        PreferenceSection("Сниффинг трафика", Icons.Outlined.Route) {
            Text(
                "Определяет протокол и домен соединения для правил маршрутизации: например, Host в HTTP или имя сервера в TLS/QUIC. Содержимое зашифрованного HTTPS не расшифровывается.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            SettingChoice(
                "Сниффинг",
                options.optString("sniff", "profile"),
                listOf(
                    "profile" to "Из подписки",
                    "on" to "Sniff для маршрутов",
                    "off" to "Выключить",
                ),
            ) {
                set("sniff", it)
            }
            Text(
                "Найденный домен используется для правил; соединение сохраняет адрес назначения, выбранный приложением.",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = { advancedSniff = !advancedSniff },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Text(
                    if (advancedSniff) {
                        "Скрыть дополнительные параметры"
                    } else {
                        "Дополнительные параметры"
                    },
                )
            }
            if (advancedSniff) {
                SettingNumber(
                    "Тайм-аут сниффинга",
                    options.optInt("sniffTimeoutMs", 300),
                    50..5000,
                    { set("sniffTimeoutMs", it) },
                    "мс",
                )
                val supported =
                    listOf(
                        "tls",
                        "http",
                        "quic",
                        "dns",
                        "stun",
                        "bittorrent",
                        "dtls",
                        "ssh",
                        "rdp",
                        "ntp",
                    )
                val selected =
                    options.optString("sniffers").split(',').filter(String::isNotBlank).toSet()
                Text(
                    "Дополнительно: какие протоколы распознавать. Это не протокол VPN-сервера. Пустой список использует набор ядра; параметры применяются при включённом сниффинге.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                supported.forEach { name ->
                    SettingToggle(name.uppercase(), "", name in selected) { enabled ->
                        set(
                            "sniffers",
                            (if (enabled) selected + name else selected - name).joinToString(","),
                        )
                    }
                }
            }
        }
        PreferenceSection("Разрешение доменов для маршрутов", Icons.Outlined.Route) {
            SettingChoice(
                "Разрешать назначение до выбора маршрута",
                options.optString("resolveMode", "profile"),
                listOf(
                    "profile" to "Из подписки",
                    "on" to "Разрешать домены",
                    "off" to "Отключить предварительный resolve",
                ),
            ) {
                set("resolveMode", it)
            }
            SettingChoice(
                "IPv4 / IPv6 для разрешения назначения",
                options.optString("resolveStrategy", "prefer_ipv4"),
                dnsStrategies,
            ) {
                set("resolveStrategy", it)
            }
            Text(
                "Resolve позволяет IP/CIDR и GeoIP-правилам проверять адреса доменного назначения. Использует маршрутизацию DNS. AsIs/IPIfNonMatch/IPOnDemand — режимы Xray; эти названия не являются параметрами sing-box.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            SettingToggle(
                "Быстрее пробовать альтернативный адрес",
                "Happy Eyeballs: уменьшить задержку между попытками IPv4/IPv6 до 10 мс. Это не одновременный дозвон по всем IP.",
                options.optBoolean("fastFallback", false),
            ) {
                set("fastFallback", it)
            }
        }
        PreferenceSection("Обновление списков", Icons.Outlined.Update) {
            var interval by remember { mutableStateOf(Settings.ruleSetUpdateInterval) }
            SettingChoice(
                "Период обновления",
                if (interval <= 0) {
                    "-1"
                } else if (interval == 1800000L) {
                    "0.5"
                } else {
                    (interval / 3600000L).toString()
                },
                listOf(
                    "-1" to "Не обновлять автоматически",
                    "0.5" to "Каждые 30 минут",
                    "1" to "Каждый час",
                    "6" to "Каждые 6 часов",
                    "12" to "Каждые 12 часов",
                    "24" to "Раз в день",
                    "72" to "Раз в 3 дня",
                    "168" to "Раз в неделю",
                ),
            ) {
                interval = if (it == "-1") -1 else (it.toDouble() * 3600000L).toLong()
                Settings.ruleSetUpdateInterval = interval
            }
        }
    }
}
