package com.nervus.packaging.gradle

import com.nervus.packaging.digest.DigestCalculator
import com.nervus.packaging.model.Component
import com.nervus.packaging.model.ComponentType
import com.nervus.packaging.model.Criticality
import com.nervus.packaging.model.Export
import com.nervus.packaging.model.LaunchMode
import com.nervus.packaging.model.ManifestModel
import com.nervus.packaging.model.ResourceLimits
import com.nervus.packaging.model.RuntimeDeps
import com.nervus.packaging.model.RuntimeType
import com.nervus.packaging.model.UsesFeature
import com.nervus.packaging.nspkg.NspkgBuilder
import com.nervus.packaging.signing.KeyLoader
import com.nervus.packaging.signing.ManifestSigner
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

abstract class NspkgTask : DefaultTask() {

    @get:Internal
    abstract val extension: Property<NspkgExtension>

    @OutputDirectory
    val outputDir: java.io.File = project.buildDir.resolve("outputs/nspkg")

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        prettyPrint = false
    }

    @TaskAction
    fun build() {
        val ext = extension.get()

        val jarFiles = project.files(project.tasks.named("jar")).files
        val nativeFiles = collectNativeLibs(ext)
        val resourceFiles = project.files("src/main/resources").asFileTree.files
        val iconPath = ext.icon.orNull
        val iconFile = iconPath?.let { project.file(it) }

        for (spec in ext.components.components) {
            if (spec.runtime.lowercase() == "jvm" && spec.mainClass == null) {
                throw GradleException("Component '${spec.id}' with runtime 'jvm' must specify mainClass")
            }
        }

        val thinJarDir = project.buildDir.resolve("tmp/thin-jars").also { it.mkdirs() }

        val fileMap = buildFileMap(ext, jarFiles, nativeFiles, resourceFiles, iconFile, iconPath, thinJarDir)

        val manifest = buildManifestModel(ext, fileMap)

        val manifestJson = json.encodeToString(manifest)
        val manifestJsonBytes = manifestJson.toByteArray()

        val keyFile = ext.signing.keyFile
            ?: throw GradleException("signing.keyFile must be configured")

        val keyPair = KeyLoader.loadKeyPair(keyFile)

        val sigBlock = ManifestSigner.sign(
            manifestJsonBytes = manifestJsonBytes,
            signerKeyPair = keyPair,
            role = ext.signing.role,
        )

        val outputFile = outputDir.resolve("${ext.packageId.get()}-${ext.version.get()}.nspkg")
        outputDir.mkdirs()

        NspkgBuilder.build(
            outputPath = outputFile.toPath(),
            manifestJson = manifestJsonBytes,
            manifestSig = sigBlock,
            files = fileMap,
        )

        logger.lifecycle("nspkg: ${outputFile.absolutePath}")
    }

    private fun buildManifestModel(
        ext: NspkgExtension,
        fileMap: Map<String, Path>,
    ): ManifestModel {
        val components = ext.components.components.map { spec ->
            val isJvmWithMainClass = spec.runtime.lowercase() == "jvm" && spec.mainClass != null
            Component(
                id = spec.id,
                type = when (spec) {
                    is ComponentDsl.AppComponentSpec -> ComponentType.app
                    is ComponentDsl.ServiceComponentSpec -> ComponentType.service
                    else -> throw GradleException("Unknown component type for '${spec.id}'")
                },
                runtime = when (spec.runtime.lowercase()) {
                    "native" -> RuntimeType.native
                    "jvm" -> RuntimeType.jvm
                    else -> throw GradleException("Unknown runtime '${spec.runtime}' for component '${spec.id}'")
                },
                entry = if (isJvmWithMainClass) "lib/${spec.id}.jar" else spec.entry,
                nativeLibDir = spec.nativeLibDir,
                launchMode = when (spec.launchMode) {
                    "always-on" -> LaunchMode.always_on
                    "on-demand" -> LaunchMode.on_demand
                    "manual" -> LaunchMode.manual
                    else -> throw GradleException("Unknown launch_mode '${spec.launchMode}' for component '${spec.id}'")
                },
                criticality = spec.criticality?.let { c ->
                    when (c.lowercase()) {
                        "optional" -> Criticality.optional
                        "required" -> Criticality.required
                        "vital" -> Criticality.vital
                        else -> throw GradleException("Unknown criticality '$c' for component '${spec.id}'")
                    }
                },
                disableable = spec.disableable,
                exports = spec.exports.exports.takeIf { it.isNotEmpty() }?.map { e ->
                    Export(`interface` = e.interfaceName, visibility = e.visibility)
                },
                interfaces = spec.interfaces,
                idleTimeoutSec = spec.idleTimeoutSec,
                limits = spec.limits.run {
                    if (memoryMaxMb != null || cpuQuotaPercent != null || tasksMax != null) {
                        ResourceLimits(
                            memoryMaxMb = memoryMaxMb,
                            cpuQuotaPercent = cpuQuotaPercent,
                            tasksMax = tasksMax,
                        )
                    } else null
                },
            )
        }

        val digests = DigestCalculator.calculate(fileMap)

        return ManifestModel(
            packageId = ext.packageId.get(),
            label = ext.label.get(),
            labels = ext.labels.orNull?.takeIf { it.isNotEmpty() },
            icon = ext.icon.orNull,
            version = ext.version.get(),
            versionCode = ext.versionCode.get().toULong(),
            minNervusApi = ext.minNervusApi.get(),
            targetNervusApi = ext.targetNervusApi.get(),
            supportedAbis = ext.supportedAbis.get(),
            runtimeDeps = ext.runtimeDeps.minJavaRelease?.let { RuntimeDeps(minJavaRelease = it) },
            permissions = ext.permissions.orNull?.toList(),
            usesFeatures = ext.usesFeatures.features.takeIf { it.isNotEmpty() }?.map { f ->
                UsesFeature(id = f.id, required = f.required)
            },
            components = components,
            digests = digests,
        )
    }

    private fun buildFileMap(
        ext: NspkgExtension,
        jarFiles: Set<java.io.File>,
        nativeFiles: Map<String, java.io.File>,
        resourceFiles: Set<java.io.File>,
        iconFile: java.io.File?,
        iconPath: String?,
        thinJarDir: java.io.File,
    ): Map<String, Path> {
        val map = mutableMapOf<String, Path>()

        for (jar in jarFiles) {
            map["lib/${jar.name}"] = jar.toPath()
        }

        val mainJarName = jarFiles.firstOrNull()?.name
        if (mainJarName != null) {
            for (spec in ext.components.components) {
                if (spec.runtime.lowercase() == "jvm" && spec.mainClass != null) {
                    val thinJarFile = thinJarDir.resolve("${spec.id}.jar")
                    generateThinJar(thinJarFile, spec.mainClass!!, mainJarName)
                    map["lib/${spec.id}.jar"] = thinJarFile.toPath()
                }
            }
        }

        for ((abi, file) in nativeFiles) {
            map["bin/$abi/${file.name}"] = file.toPath()
        }

        for (res in resourceFiles) {
            val relativePath = project.file("src/main/resources").toPath().relativize(res.toPath())
            map["resources/$relativePath"] = res.toPath()
        }

        if (iconFile != null && iconPath != null) {
            map[iconPath] = iconFile.toPath()
        }

        return map
    }

    private fun collectNativeLibs(ext: NspkgExtension): Map<String, java.io.File> {
        val result = mutableMapOf<String, java.io.File>()
        val nativeDir = project.file("src/main/native")
        if (nativeDir.exists()) {
            nativeDir.listFiles()?.forEach { abiDir ->
                if (abiDir.isDirectory) {
                    abiDir.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            result[abiDir.name] = file
                        }
                    }
                }
            }
        }
        return result
    }

    private fun generateThinJar(outputFile: java.io.File, mainClass: String, classPath: String) {
        JarOutputStream(outputFile.outputStream().buffered()).use { jos ->
            jos.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
            jos.write(
                """
Manifest-Version: 1.0
Main-Class: $mainClass
Class-Path: $classPath

""".trimStart().toByteArray(Charsets.UTF_8)
            )
            jos.closeEntry()
        }
    }
}
