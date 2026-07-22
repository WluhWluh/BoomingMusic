package com.mardous.booming.util.backup

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SystemBackupRulesTest {

    @Test
    fun `legacy full backup includes only the music database`() {
        val root = parseResource("xml/backup_descriptor.xml")

        assertEquals("full-backup-content", root.tagName)
        assertDatabaseOnly(root)
    }

    @Test
    fun `android 12 backup and transfer include only the music database`() {
        val root = parseResource("xml-v31/data_extraction_rules.xml")
        val destinations = root.childElements()

        assertEquals("data-extraction-rules", root.tagName)
        assertEquals(listOf("cloud-backup", "device-transfer"), destinations.map(Element::getTagName))
        destinations.forEach(::assertDatabaseOnly)
    }

    private fun assertDatabaseOnly(parent: Element) {
        val rules = parent.childElements()

        assertEquals(1, rules.size)
        assertEquals("include", rules.single().tagName)
        assertEquals("database", rules.single().getAttribute("domain"))
        assertEquals("music_database.db", rules.single().getAttribute("path"))
        assertEquals(2, rules.single().attributes.length)
    }

    private fun parseResource(relativePath: String): Element {
        val source = locateResource(relativePath)
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder().parse(source).documentElement
    }

    private fun locateResource(relativePath: String): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val candidates = listOf(
            File(workingDirectory, "src/main/res/$relativePath"),
            File(workingDirectory, "app/src/main/res/$relativePath"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Missing Android backup resource: $relativePath"
        }
    }

    private fun Element.childElements(): List<Element> = buildList {
        val children = childNodes
        for (index in 0 until children.length) {
            (children.item(index) as? Element)?.let(::add)
        }
    }
}
