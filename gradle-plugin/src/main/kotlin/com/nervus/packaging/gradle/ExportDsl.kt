package com.nervus.packaging.gradle

class ExportDsl {

    private val _exports = mutableListOf<ExportSpec>()
    val exports: List<ExportSpec> get() = _exports

    fun register(interfaceName: String, configure: ExportSpec.() -> Unit) {
        val spec = ExportSpec(interfaceName).apply(configure)
        _exports.add(spec)
    }

    class ExportSpec(val interfaceName: String) {
        var visibility: String = "package"
    }
}
