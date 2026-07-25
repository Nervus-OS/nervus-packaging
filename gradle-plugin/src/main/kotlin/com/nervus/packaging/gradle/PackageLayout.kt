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
import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest as JarManifest

/**
 * PackageLayout 是「包内该有哪些文件、manifest 长什么样」的唯一实现。
 *
 * 两个产物形态共用它：
 *   - [NspkgTask]          → .nspkg（zstd+tar，动态安装，developer 签名）
 *   - [NspkgImageTreeTask] → 目录树（系统镜像区，platform-release 签名）
 *
 * 两者的差别【只有落盘方式】。布局与 manifest 若各写一份，两条路迟早分叉，
 * 而分叉的表现是「动态装能跑、进镜像跑不起来」这类最难查的问题。
 */
internal object PackageLayout {

    /**
     * 收集包内全部文件，返回 包内相对路径 -> 本地绝对路径。
     *
     * ## 为什么必须带上 runtimeClasspath
     *
     * 早先只收 `jar` 任务的产物（项目自己那一个 jar），结果是任何有依赖的应用
     * 打出来都跑不起来——Compose Desktop 光运行时就有上百个 jar（skiko、
     * kotlin-stdlib、coroutines、protobuf、SDK……），一个都不在包里，
     * 启动时直接 NoClassDefFoundError。
     *
     * 这里取 `runtimeClasspath` 配置：它正是「运行这个应用需要哪些 jar」的
     * 权威答案，由 Gradle 依赖解析得出，不需要我们自己猜。
     */
    fun collectFiles(
        project: Project,
        ext: NspkgExtension,
        thinJarDir: File,
    ): Map<String, Path> {
        val map = LinkedHashMap<String, Path>()

        // 项目自身的 jar + 全部运行时依赖 jar。
        // 用 LinkedHashMap 保序：Class-Path 的顺序即类加载顺序，构建可复现要求它稳定。
        val runtimeJars = LinkedHashMap<String, File>()
        for (jar in project.files(project.tasks.named("jar")).files) {
            runtimeJars[jar.name] = jar
        }
        val runtimeClasspath = project.configurations.findByName("runtimeClasspath")
        if (runtimeClasspath != null) {
            for (dep in runtimeClasspath.files.sortedBy { it.name }) {
                if (!dep.name.endsWith(".jar")) continue
                // 同名 jar（不同 group 的同名 artifact）会互相覆盖。加 group 前缀消歧，
                // 而不是静默丢一个——丢掉的那个的类在运行期才会以 NoClassDefFoundError 暴露
                var name = dep.name
                if (runtimeJars.containsKey(name) && runtimeJars[name] != dep) {
                    name = "${dep.parentFile?.parentFile?.name ?: "dep"}-${dep.name}"
                }
                runtimeJars.putIfAbsent(name, dep)
            }
        }
        for ((name, file) in runtimeJars) {
            map["lib/$name"] = file.toPath()
        }

        // 每个 jvm 组件一个 thin jar：只有 MANIFEST，指明 Main-Class 与 Class-Path。
        // 一个包里可以有多个 jvm 组件（如 launcher 的 desktop 与 sessiond），
        // 它们共享同一批依赖 jar，只是入口类不同
        val classPathEntries = runtimeJars.keys.toList()
        for (spec in ext.components.components) {
            if (spec.runtime.lowercase() != "jvm") continue
            val mainClass = spec.mainClass
                ?: throw GradleException("Component '${spec.id}' with runtime 'jvm' must specify mainClass")
            val thinJar = thinJarDir.resolve("${spec.id}.jar")
            writeThinJar(thinJar, mainClass, classPathEntries)
            map["lib/${spec.id}.jar"] = thinJar.toPath()
        }

        for ((abi, file) in collectNativeLibs(project)) {
            map["bin/$abi/${file.name}"] = file.toPath()
        }

        val resourceRoot = project.file("src/main/resources")
        if (resourceRoot.exists()) {
            for (res in project.files(resourceRoot).asFileTree.files) {
                val rel = resourceRoot.toPath().relativize(res.toPath()).toString().replace(File.separatorChar, '/')
                map["resources/$rel"] = res.toPath()
            }
        }

        val iconPath = ext.icon.orNull
        if (iconPath != null) {
            val iconFile = project.file(iconPath)
            if (!iconFile.exists()) {
                throw GradleException("icon '$iconPath' not found at ${iconFile.absolutePath}")
            }
            map[iconPath] = iconFile.toPath()
        }

        return map
    }

    /**
     * 写一个 thin jar：不含任何 class，只有 MANIFEST 指向 Main-Class 与 Class-Path。
     *
     * ## 为什么用 java.util.jar.Manifest 而不是手写字符串
     *
     * MANIFEST.MF 有一条硬性格式规则：**每行最多 72 字节，超出必须折行，且续行
     * 以一个空格开头**（JAR 规范）。Compose Desktop 的 Class-Path 是上百个 jar 名
     * 拼起来的，长达数千字节——手写字符串必然超限，而超限的后果不是报错，是
     * **JVM 静默截断 Class-Path**，于是只有前几个 jar 被加载，报一个和真实原因
     * 毫无关系的 NoClassDefFoundError。
     *
     * Manifest.write() 自己处理折行，交给它就不会错。
     */
    private fun writeThinJar(outputFile: File, mainClass: String, classPath: List<String>) {
        outputFile.parentFile?.mkdirs()
        val manifest = JarManifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        manifest.mainAttributes[Attributes.Name.MAIN_CLASS] = mainClass
        // Class-Path 相对于本 jar 所在目录，因此写成同级文件名即可（都在 lib/ 下）
        manifest.mainAttributes[Attributes.Name.CLASS_PATH] = classPath.joinToString(" ")
        JarOutputStream(outputFile.outputStream().buffered()).use { jos ->
            jos.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
            manifest.write(jos)
            jos.closeEntry()
        }
    }

    private fun collectNativeLibs(project: Project): Map<String, File> {
        val result = mutableMapOf<String, File>()
        val nativeDir = project.file("src/main/native")
        if (!nativeDir.exists()) return result
        nativeDir.listFiles()?.forEach { abiDir ->
            if (!abiDir.isDirectory) return@forEach
            abiDir.listFiles()?.forEach { file ->
                if (file.isFile) result[abiDir.name] = file
            }
        }
        return result
    }

    /** 由 DSL 与文件清单构造 manifest.json 的模型。digests 由 [DigestCalculator] 现算。 */
    fun buildManifest(ext: NspkgExtension, fileMap: Map<String, Path>): ManifestModel {
        val components = ext.components.components.map { spec ->
            val isJvm = spec.runtime.lowercase() == "jvm"
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
                // jvm 组件的入口恒为它自己那个 thin jar
                entry = if (isJvm) "lib/${spec.id}.jar" else spec.entry,
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
                        // 具名实参：ResourceLimits 三个字段同为 Int?，位置传参写错
                        // 顺序编译器一句话都不会说，而后果是内存上限被当成 CPU 配额
                        ResourceLimits(
                            memoryMaxMb = memoryMaxMb,
                            cpuQuotaPercent = cpuQuotaPercent,
                            tasksMax = tasksMax,
                        )
                    } else null
                },
            )
        }

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
            digests = DigestCalculator.calculate(fileMap),
        )
    }
}
