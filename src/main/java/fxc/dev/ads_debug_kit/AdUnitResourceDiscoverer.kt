package fxc.dev.ads_debug_kit

import android.content.Context
import dalvik.system.DexFile

internal object AdUnitResourceDiscoverer {
    fun discover(context: Context, config: AdDebugConfig): List<AdDebugAdUnit> {
        if (!config.autoDiscoverAdUnits) return emptyList()

        val adUnits = linkedMapOf<String, AdDebugAdUnit>()
        resourceClassNames(context, config).forEach { className ->
            runCatching { Class.forName(className) }
                .getOrNull()
                ?.readAdUnits(context, config)
                ?.forEach { adUnit -> adUnits[adUnit.name] = adUnit }
        }
        return adUnits.values.sortedByResourceOrder()
    }

    private fun Class<*>.readAdUnits(context: Context, config: AdDebugConfig): List<AdDebugAdUnit> {
        return declaredFields
            .asSequence()
            .mapNotNull { field ->
                val name = field.name
                if (!name.startsWith(config.adUnitResourcePrefix) || !name.endsWith(config.adUnitResourceSuffix)) {
                    return@mapNotNull null
                }

                val resourceId = runCatching {
                    field.isAccessible = true
                    field.getInt(null)
                }.getOrNull() ?: return@mapNotNull null
                val adUnitId = runCatching { context.getString(resourceId) }.getOrNull() ?: return@mapNotNull null
                if (adUnitId.isBlank()) return@mapNotNull null

                AdDebugAdUnit(
                    name = name,
                    adUnitId = adUnitId,
                    unit = inferUnit(name),
                    resourceId = resourceId
                )
            }
            .toList()
    }

    private fun resourceClassNames(context: Context, config: AdDebugConfig): List<String> {
        val classNames = linkedSetOf<String>()
        classNames += config.resourceClassNames
        classNames += "${context.packageName}.R\$string"

        context.applicationInfo.className
            ?.takeIf { it.isNotBlank() && !it.startsWith(".") }
            ?.substringBeforeLast('.', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
            ?.let { applicationClassPackage -> classNames += "$applicationClassPackage.R\$string" }

        classNames += dexStringResourceClassNames(context)
        return classNames.toList()
    }

    @Suppress("DEPRECATION")
    private fun dexStringResourceClassNames(context: Context): List<String> {
        val classNames = mutableListOf<String>()
        val dexFile = runCatching { DexFile(context.packageCodePath) }.getOrNull() ?: return emptyList()
        try {
            val entries = dexFile.entries()
            while (entries.hasMoreElements()) {
                val className = entries.nextElement()
                if (className.endsWith(".R\$string")) {
                    classNames += className
                }
            }
        } finally {
            runCatching { dexFile.close() }
        }
        return classNames
    }

    private fun inferUnit(resourceName: String): AdDebugUnit {
        val name = resourceName.lowercase()
        return when {
            name == "ads_app_id" || name.endsWith("_app_id") -> AdDebugUnit.APP_ID
            "native" in name -> AdDebugUnit.NATIVE
            "reward" in name -> AdDebugUnit.REWARDED
            "app_open" in name || "appopen" in name -> AdDebugUnit.APP_OPEN
            "banner" in name -> AdDebugUnit.BANNER
            "interstitial" in name || "inter" in name -> AdDebugUnit.INTERSTITIAL
            else -> AdDebugUnit.OTHER
        }
    }

    private fun Collection<AdDebugAdUnit>.sortedByResourceOrder(): List<AdDebugAdUnit> {
        return sortedWith(
            compareBy<AdDebugAdUnit> { if (it.resourceId != 0) it.resourceId else Int.MAX_VALUE }
                .thenBy { it.name }
        )
    }
}
