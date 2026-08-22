package com.saas.apkeditorplus

import org.junit.Assert.assertThrows
import org.junit.Test

class CommonEditEngineTest {
    private fun changes(
        packageName: String = "com.example.app",
        minSdk: Int? = 24,
        targetSdk: Int? = 36,
        maxSdk: Int? = null,
        installLocation: Int? = null
    ) = CommonEditEngine.Changes(
        packageName = packageName,
        versionCode = 1,
        versionName = "1.0",
        appLabel = "Aplicativo",
        minSdk = minSdk,
        targetSdk = targetSdk,
        maxSdk = maxSdk,
        installLocation = installLocation,
        renameResourcePackage = true
    )

    @Test
    fun acceptsConsistentManifestValues() {
        CommonEditEngine.validateChanges(changes(maxSdk = 37, installLocation = 2))
    }

    @Test
    fun rejectsMalformedPackage() {
        assertThrows(IllegalArgumentException::class.java) {
            CommonEditEngine.validateChanges(changes(packageName = "invalid"))
        }
    }

    @Test
    fun rejectsTargetBelowMinimum() {
        assertThrows(IllegalArgumentException::class.java) {
            CommonEditEngine.validateChanges(changes(minSdk = 35, targetSdk = 24))
        }
    }

    @Test
    fun rejectsInvalidInstallLocation() {
        assertThrows(IllegalArgumentException::class.java) {
            CommonEditEngine.validateChanges(changes(installLocation = 4))
        }
    }
}
