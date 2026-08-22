package com.saas.apkeditorplus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import android.content.Intent
import com.android.apksig.ApkVerifier
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApkPipelineInstrumentedTest {
    @Test
    fun composeFileBrowserStartsWithoutCrash() {
        ActivityScenario.launch(FileListActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertTrue(!activity.isFinishing) }
        }
    }

    @Test
    fun composeFullEditorHostsAllFragments() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, FullEditActivity::class.java)
            .putExtra("apkPath", context.applicationInfo.sourceDir)
        ActivityScenario.launch<FullEditActivity>(intent).use { scenario ->
            scenario.onActivity { activity -> assertTrue(!activity.isFinishing) }
        }
    }

    @Test
    fun composeFileManagersAndTextEditorStartWithoutCrash() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val apkPath = context.applicationInfo.sourceDir
        AppSettings.prefs(context).edit().putBoolean(AppSettings.EXTERNAL_EDITOR, false).commit()

        ActivityScenario.launch(SettingActivity::class.java).use { scenario ->
            scenario.onActivity { assertTrue(!it.isFinishing) }
        }

        ActivityScenario.launch<SimpleEditActivity>(
            Intent(context, SimpleEditActivity::class.java).putExtra("apkPath", apkPath)
        ).use { scenario -> scenario.onActivity { assertTrue(!it.isFinishing) } }

        ActivityScenario.launch<AxmlEditActivity>(
            Intent(context, AxmlEditActivity::class.java).putExtra("apkPath", apkPath)
        ).use { scenario -> scenario.onActivity { assertTrue(!it.isFinishing) } }

        ActivityScenario.launch(SelectFileActivity::class.java).use { scenario ->
            scenario.onActivity { assertTrue(!it.isFinishing) }
        }

        val textFile = File(context.cacheDir, "compose_editor_test.xml").apply {
            writeText("<manifest package=\"com.example.test\" />")
        }
        ActivityScenario.launch<TextEditBigActivity>(
            Intent(context, TextEditBigActivity::class.java)
                .putExtra("filePath", textFile.absolutePath)
                .putExtra("fileName", textFile.name)
        ).use { scenario -> scenario.onActivity { assertTrue(!it.isFinishing) } }
    }

    @Test
    fun commonEditRebuildAndSignProducesValidApk() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.applicationInfo.sourceDir)
        val original = CommonEditEngine.read(source)
        val edited = CommonEditEngine.apply(
            context,
            source,
            CommonEditEngine.Changes(
                packageName = original.packageName,
                versionCode = original.versionCode + 1,
                versionName = original.versionName + ".test",
                appLabel = (original.appLabel ?: "APK Editor Plus") + " Test",
                minSdk = original.minSdk,
                targetSdk = original.targetSdk,
                maxSdk = original.maxSdk,
                installLocation = original.installLocation,
                renameResourcePackage = false
            )
        )
        val unsigned = File(context.cacheDir, "pipeline_test_unsigned.apk")
        val signed = File(context.cacheDir, "pipeline_test_signed.apk")
        ApkBuildPipeline.rebuild(
            context,
            source,
            unsigned,
            ApkBuildPipeline.ChangeSet(
                replacements = buildMap {
                    put("AndroidManifest.xml", edited.manifestFile)
                    edited.resourcesFile?.let { put("resources.arsc", it) }
                }
            )
        )
        val key = KeyStoreManager(context).getTestKey()
        assertTrue(
            ApkSignerManager().signApk(
                unsigned,
                signed,
                key,
                "testkey".toCharArray(),
                "testkey",
                "testkey".toCharArray(),
                enableV4 = true
            )
        )
        assertTrue(File(signed.absolutePath + ".idsig").isFile)
        assertTrue(ApkVerifier.Builder(signed).build().verify().isVerified)
        assertEquals(original.versionCode + 1, CommonEditEngine.read(signed).versionCode)
        ZipFile(signed).use { zip ->
            assertEquals(ZipEntry.STORED, zip.getEntry("resources.arsc")?.method)
        }
    }
}
