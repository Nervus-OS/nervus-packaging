package com.nervus.packaging.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

class NspkgPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("nspkg", NspkgExtension::class.java)

        // .nspkg：动态安装产物，developer 签名
        project.tasks.register("nspkg", NspkgTask::class.java) { task ->
            task.extension.set(extension)
            task.group = GROUP
            task.description = "Build .nspkg package (dynamic install)"
            project.tasks.findByName("jar")?.let { task.dependsOn(it) }
        }

        // 镜像树：系统内置包产物，platform-release 签名。
        // 两个任务共用同一份 DSL 与 PackageLayout，只是落盘方式与签名角色不同
        project.tasks.register("nspkgImageTree", NspkgImageTreeTask::class.java) { task ->
            task.extension.set(extension)
            task.group = GROUP
            task.description = "Build system image tree (/usr/lib/nervus/system-packages)"
            project.tasks.findByName("jar")?.let { task.dependsOn(it) }
        }
    }

    private companion object {
        const val GROUP = "nervus packaging"
    }
}
