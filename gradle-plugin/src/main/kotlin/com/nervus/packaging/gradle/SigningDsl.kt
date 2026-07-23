package com.nervus.packaging.gradle

import java.io.File

class SigningDsl {

    var keyFile: File? = null

    var countersignKeyFile: String? = null
    var countersignRole: String? = null

    var role: String = "developer"

    var previousLineageFile: String? = null
    var newKeyFile: String? = null
}
