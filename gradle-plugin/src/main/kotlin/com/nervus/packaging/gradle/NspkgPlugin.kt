package com.nervus.packaging.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

class NspkgPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("nspkg", NspkgExtension::class.java)
        project.tasks.register("nspkg", NspkgTask::class.java) { task ->
            task.extension.set(extension)
            task.group = "nervus packaging"
            task.description = "Build .nspkg package"
            project.tasks.findByName("jar")?.let { task.dependsOn(it) }
        }
    }
}
