package com.nervus.packaging.gradle

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
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 产出**系统镜像树**：可直接拷进 `/usr/lib/nervus/system-packages/` 的目录。
 *
 * ```text
 * <outputDir>/<package_id>/
 * ├── manifest.json     ← 被签名的那份原始字节
 * ├── manifest.sig      ← 平台角色的 Ed25519 签名
 * └── lib/                ← 载荷 jar，全部被 manifest.digests 覆盖
 * ```
 *
 * ## 与 [NspkgTask] 的关系
 *
 * 包内布局与 manifest 是**同一份**（[PackageLayout]），差别只有两点：
 *
 * 1. **落盘方式**：这里直接摆目录，不打 zstd+tar。系统镜像区是只读挂载，
 *    没有解包这一步——内核的启动扫描直接 glob 每个包目录下的 manifest.json 读它。
 * 2. **签名角色**：镜像树只接受平台角色（缺省 `platform-systemapp`，核心系统
 *    服务用 `platform-release`），`.nspkg` 用 `developer`。
 *
 * ## 为什么不能用 nervusctl install 装进去
 *
 * `pkgregistry.Install` 只接受 `SourceDynamicInstall`，系统包走它会被
 * `ErrSystemPackageImmutable` 直接拒。系统包靠刷镜像或 OTA 进去，这是有意的：
 * 那条路径上的 developer 必签、OEM 副署等准入是给第三方包用的，系统包走另一套。
 *
 * 产物形态必须与 `nervus-system-server/scripts/build-image-tree.sh`（Go 侧的同一件事）
 * 一致——两边的 manifest 由内核 `pkgregistry.ParseManifest` 统一约束。
 */
abstract class NspkgImageTreeTask : DefaultTask() {

    @get:Internal
    abstract val extension: Property<NspkgExtension>

    @OutputDirectory
    val outputDir: File = project.layout.buildDirectory.get().asFile.resolve("outputs/image-tree")

    /**
     * prettyPrint = false：manifest.json 的**原始字节**就是被签名的对象，
     * 也是内核复核签名时读的那份。格式化与否不影响正确性，但必须与
     * [NspkgTask] 保持一致——否则同一份 DSL 产出两种字节，digest 与签名都对不上。
     */
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        prettyPrint = false
    }

    /** manifest.sig 的序列化器。配置与 NspkgBuilder 内部那份一致 */
    private val sigJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    init {
        // 本任务【永不】被判为 up-to-date。
        //
        // 它的输入是整个 nspkg DSL（package_id、权限、组件、launch_mode…），
        // 而那是一个 Serializable 的复杂对象树，没法用 @Input 逐项声明。只声明
        // @OutputDirectory 的后果是：产物目录一旦存在，Gradle 就跳过任务 ——
        // 改了 DSL 重新构建，manifest 里还是旧内容，而且 BUILD SUCCESSFUL。
        // 已经真实踩过：把 launch_mode 从 on-demand 改成 manual、权限换成
        // perm.system.launch，构建"成功"了但 manifest 一个字没变。
        //
        // 打包不是高频路径（一次几十秒），拿增量换正确性不划算。
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun build() {
        val ext = extension.get()
        val packageId = ext.packageId.get()
        val thinJarDir = project.layout.buildDirectory.get().asFile.resolve("tmp/thin-jars-image")

        val fileMap = PackageLayout.collectFiles(project, ext, thinJarDir)
        val manifestJsonBytes = json.encodeToString(PackageLayout.buildManifest(ext, fileMap)).toByteArray()

        val keyFile = ext.signing.keyFile
            ?: throw GradleException("signing.keyFile must be configured")

        // 角色：缺省 platform-systemapp（随镜像发布的系统 App）。
        //
        // 与 platform-release 的分界是【权限影响面】，不是位置：
        //   platform-release   内核 + 核心系统服务，能拿 perm.safety.rearm /
        //                      perm.authority.reboot 这类 RequireSignerRole 限定的权限
        //   platform-systemapp 系统 App，拿 Platform trust，但拿不到上面那些
        // 两者都装在 /usr/lib/nervus/system-packages/，deploy/README.md §3.3 有对照表。
        //
        // 拒绝 developer：镜像树【只】用于系统包，用 developer 签会被内核降成
        // Ordinary trust，而症状是运行期某个 endpoint 注册失败或权限被拒，
        // 离根因很远。宁可在构建期直接失败
        val role = ext.signing.role.takeIf { it.isNotBlank() && it != DEFAULT_DSL_ROLE }
            ?: DEFAULT_IMAGE_TREE_ROLE
        if (role !in PLATFORM_ROLES) {
            throw GradleException(
                "signing.role='$role' 不能用于系统镜像树；镜像树只接受 $PLATFORM_ROLES。" +
                    "第三方应用请用 nspkg 任务（动态安装）。"
            )
        }

        val sigBlock = ManifestSigner.sign(
            manifestJsonBytes = manifestJsonBytes,
            signerKeyPair = KeyLoader.loadKeyPair(keyFile),
            role = role,
        )

        // 目录名必须等于 package_id：内核按 manifest 内容认包，目录名只是容器，
        // 但两者不一致会让人排查时照着目录名找错包
        val pkgDir = outputDir.resolve(packageId)
        if (pkgDir.exists()) pkgDir.deleteRecursively()
        pkgDir.mkdirs()

        for ((relPath, source) in fileMap) {
            val dest = pkgDir.resolve(relPath)
            dest.parentFile?.mkdirs()
            Files.copy(source, dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        pkgDir.resolve(MANIFEST_NAME).writeBytes(manifestJsonBytes)
        // ManifestSigner 产出的是 SigBlock 模型，落盘形态是 JSON。
        // 序列化配置必须与 NspkgBuilder 一致（encodeDefaults = true）——内核对
        // manifest.sig 只做 JSON 解析不做字节比对，但两条产物路径给出不同的
        // 字节表示会让「同一个包的两种形态」难以对照排查
        pkgDir.resolve(SIGNATURE_NAME).writeBytes(sigJson.encodeToString(sigBlock).toByteArray())

        logger.lifecycle("image-tree: ${pkgDir.absolutePath}")
        logger.lifecycle("  abis=${ext.supportedAbis.get()}  files=${fileMap.size}  role=$role")
        logger.lifecycle("  安装: cp -r ${pkgDir.absolutePath} <镜像>/usr/lib/nervus/system-packages/")
    }

    private companion object {
        /** 与内核 sigblock.go 的 SignerRole 常量一致 */
        const val ROLE_PLATFORM_RELEASE = "platform-release"
        const val ROLE_PLATFORM_SYSTEMAPP = "platform-systemapp"

        /** SigningDsl.role 的缺省值。等于它说明调用方没显式指定 */
        const val DEFAULT_DSL_ROLE = "developer"

        /** 镜像树的缺省角色：系统 App */
        const val DEFAULT_IMAGE_TREE_ROLE = ROLE_PLATFORM_SYSTEMAPP

        val PLATFORM_ROLES = setOf(ROLE_PLATFORM_RELEASE, ROLE_PLATFORM_SYSTEMAPP)

        /** 与内核 pkgregistry 的 ManifestFileName / SignatureFileName 一致 */
        const val MANIFEST_NAME = "manifest.json"
        const val SIGNATURE_NAME = "manifest.sig"
    }
}
