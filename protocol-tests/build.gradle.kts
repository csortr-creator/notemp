plugins {
    kotlin("jvm") version "2.4.0"
}

repositories { mavenCentral() }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.json:json:20240303")
    implementation("org.yaml:snakeyaml:2.5")
    testImplementation("junit:junit:4.13.2")
}

// Exercise the production parser without loading Android or the native libbox.
sourceSets.main {
    kotlin.srcDir("../app/src/main/java")
    kotlin.include("io/nekohasekai/sfa/utils/ParallelTasks.kt")
    kotlin.include("io/nekohasekai/sfa/utils/SubscriptionMetadata.kt")
    kotlin.include("io/nekohasekai/sfa/utils/NotificationTitle.kt")
    kotlin.include("io/nekohasekai/sfa/utils/NotificationUpdateGate.kt")
    kotlin.include("io/nekohasekai/sfa/utils/PowerUsagePolicy.kt")
    kotlin.include("io/nekohasekai/sfa/utils/ClientSettingsConfig.kt")
    kotlin.include("io/nekohasekai/sfa/utils/ProfileLatencyCache.kt")
    kotlin.include("io/nekohasekai/sfa/utils/ProbeOutcome.kt")
    kotlin.include("io/nekohasekai/sfa/utils/ProbeNetworkReadiness.kt")
    kotlin.include("io/nekohasekai/sfa/utils/InitialInterfaceReadiness.kt")
    kotlin.include("io/nekohasekai/sfa/utils/NetworkErrorPresentation.kt")
    kotlin.include("io/nekohasekai/sfa/utils/SubscriptionFetchRetry.kt")
    kotlin.include("io/nekohasekai/sfa/utils/NodeCoreHarness.kt")
    kotlin.include("io/nekohasekai/sfa/utils/NodeCoreValidationEngine.kt")
    kotlin.include("io/nekohasekai/sfa/utils/NodeValidationCompare.kt")
    kotlin.include("io/nekohasekai/sfa/utils/ProxyLinkParser.kt")
    kotlin.include("io/nekohasekai/sfa/utils/FinalMaskCompatibility.kt")
    kotlin.include("io/nekohasekai/sfa/utils/QuicTlsCompatibility.kt")
    kotlin.include("io/nekohasekai/sfa/utils/XrayCompatibility.kt")
    kotlin.include("io/nekohasekai/sfa/utils/SubscriptionContentParser.kt")
    kotlin.include("io/nekohasekai/sfa/utils/SubscriptionDiagnostics.kt")
    kotlin.include("io/nekohasekai/sfa/utils/DnsFakeIpMigration.kt")
    kotlin.include("io/nekohasekai/sfa/utils/ForeignSubscriptionParser.kt")
        kotlin.include("io/nekohasekai/sfa/utils/ImportIssueCode.kt")
    kotlin.include("io/nekohasekai/sfa/utils/ImportOperationOutcome.kt")
    kotlin.include("io/nekohasekai/sfa/utils/PartialUpdatePolicy.kt")
    kotlin.include("io/nekohasekai/sfa/utils/ImportConfirmationQueue.kt")
    kotlin.include("io/nekohasekai/sfa/bg/RuntimeProfileState.kt")
    kotlin.include("io/nekohasekai/sfa/bg/ProfileSelectionQueue.kt")
    kotlin.include("io/nekohasekai/sfa/bg/SwitchStartResolver.kt")
    kotlin.include("io/nekohasekai/sfa/utils/SubscriptionImportReport.kt")
    kotlin.include("io/nekohasekai/sfa/utils/SubscriptionRouting.kt")
        kotlin.include("io/nekohasekai/sfa/utils/ProfileOverflowMenuModel.kt")
    kotlin.include("io/nekohasekai/sfa/utils/ProfilePathGuard.kt")
    kotlin.include("io/nekohasekai/sfa/utils/ProfileConfigCommit.kt")
    kotlin.include("io/nekohasekai/sfa/utils/OutboundProfileState.kt")
    kotlin.include("io/nekohasekai/sfa/utils/UserRoutingConfig.kt")
    kotlin.include("io/nekohasekai/sfa/utils/RoutingPresets.kt")
    kotlin.include("io/nekohasekai/sfa/utils/RuleSetPolicy.kt")
    kotlin.include("io/nekohasekai/sfa/models/DnsConfig.kt")
    kotlin.include("io/nekohasekai/sfa/models/GeoFileSource.kt")
    kotlin.include("io/nekohasekai/sfa/models/RoutingRule.kt")
}

tasks.test { useJUnit() }
