package com.mardous.booming.separation.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SourceSeparationForegroundServiceManifestTest {
    @Test
    fun `inference service owns only media processing`() {
        val services = manifest.childElements("application")
            .single()
            .childElements("service")
        val inferenceService = services.single {
            it.androidAttribute("name") == INFERENCE_SERVICE
        }

        assertEquals("false", inferenceService.androidAttribute("exported"))
        assertEquals(":source_separation", inferenceService.androidAttribute("process"))
        assertEquals("mediaProcessing", inferenceService.androidAttribute("foregroundServiceType"))
    }

    @Test
    fun `playback service retains media playback ownership`() {
        val playbackService = manifest.childElements("application")
            .single()
            .childElements("service")
            .single { it.androidAttribute("name") == PLAYBACK_SERVICE }
        val types = playbackService.androidAttribute("foregroundServiceType")
            .split('|')
            .filter(String::isNotBlank)

        assertTrue("PlaybackService must retain mediaPlayback", "mediaPlayback" in types)
    }

    @Test
    fun `foreground service permissions cover playback and processing`() {
        val permissions = manifest.childElements("uses-permission")
            .map { it.androidAttribute("name") }
            .toSet()

        assertTrue("android.permission.FOREGROUND_SERVICE" in permissions)
        assertTrue("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" in permissions)
        assertTrue("android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING" in permissions)
    }

    private val manifest: Element
        get() {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isExpandEntityReferences = false
            }
            return factory.newDocumentBuilder().parse(locateManifest()).documentElement
        }

    private fun locateManifest(): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(
            File(workingDirectory, "src/main/AndroidManifest.xml"),
            File(workingDirectory, "app/src/main/AndroidManifest.xml"),
        ).firstOrNull(File::isFile)
            ?: error("Cannot locate the app manifest from $workingDirectory")
    }

    private fun Element.childElements(tagName: String): List<Element> = buildList {
        val children = childNodes
        for (index in 0 until children.length) {
            val child = children.item(index) as? Element ?: continue
            if (child.tagName == tagName) add(child)
        }
    }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(ANDROID_NAMESPACE, name)

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val INFERENCE_SERVICE =
            ".separation.process.ipc.SourceSeparationExecutionService"
        const val PLAYBACK_SERVICE = ".playback.PlaybackService"
    }
}
