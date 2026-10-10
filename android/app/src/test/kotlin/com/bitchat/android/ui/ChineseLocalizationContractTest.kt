package com.bitchat.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ChineseLocalizationContractTest {
    private val resourcesDirectory = File("src/main/res")

    private fun catalog(directory: String): Map<String, String> = buildMap {
        File(resourcesDirectory, directory).listFiles().orEmpty()
            .filter { it.extension == "xml" }
            .forEach { file ->
                val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                val strings = document.getElementsByTagName("string")
                for (index in 0 until strings.length) {
                    val node = strings.item(index)
                    val key = node.attributes.getNamedItem("name").nodeValue
                    assertTrue("Duplicate string $key in $directory", !containsKey(key))
                    put(key, node.textContent)
                }
            }
    }

    @Test
    fun `every Chinese locale has a translation for every default string`() {
        val defaults = catalog("values")
        val generic = catalog("values-zh")
        for (locale in listOf("values-zh", "values-zh-rCN", "values-zh-rTW")) {
            val translated = generic + catalog(locale)
            assertEquals("English fallback in $locale", emptySet<String>(), defaults.keys - translated.keys)
        }
    }

    @Test
    fun `Chinese translations preserve format arguments`() {
        val defaults = catalog("values")
        val generic = catalog("values-zh")
        val format = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z]""")
        fun arguments(text: String): List<String> = format.findAll(text.replace("%%", ""))
            .map { it.value }.sorted().toList()
        for (locale in listOf("values-zh", "values-zh-rCN", "values-zh-rTW")) {
            val translated = generic + catalog(locale)
            defaults.forEach { (key, value) ->
                assertEquals("Format mismatch: $locale/$key", arguments(value), arguments(translated.getValue(key)))
            }
        }
    }
}
