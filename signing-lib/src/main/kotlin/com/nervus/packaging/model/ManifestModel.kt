package com.nervus.packaging.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ManifestModel(
    val schema: Int = 1,
    @SerialName("package_id")
    val packageId: String,
    val label: String,
    val labels: Map<String, String>? = null,
    val icon: String? = null,
    val version: String,
    @SerialName("version_code")
    val versionCode: ULong,
    @SerialName("min_nervus_api")
    val minNervusApi: Int,
    @SerialName("target_nervus_api")
    val targetNervusApi: Int,
    @SerialName("supported_abis")
    val supportedAbis: List<String>,
    @SerialName("runtime_deps")
    val runtimeDeps: RuntimeDeps? = null,
    val permissions: List<String>? = null,
    @SerialName("uses_features")
    val usesFeatures: List<UsesFeature>? = null,
    val components: List<Component>,
    val digests: Map<String, String>,
)
