package com.sbro.emucorec

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.isDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class LocaleResourceContractTest {
    private val expectedLocales = setOf("ar", "cs", "de", "es", "fa", "fr", "hi", "id", "it", "ja", "ko", "pl", "pt", "ru", "tr", "uk", "zh")
    private val formatSpecifier = Regex("""%(?:\d+\$)?[-#+, 0(<]*\d*(?:\.\d+)?[a-zA-Z%]""")
    private val knownEnglishSentenceFragments = setOf(
        "Disables RSX FIFO optimizations completely",
        "Disables use of hardware-native color-space",
        "This can cause severe performance degradation",
        "May degrade performance",
        "Controls how much time it takes for RSX",
        "Fixes excessive shadow flickering",
        "Enables audio buffering",
        "This increases CPU usage",
        "Enables time stretching",
        "This will automatically forward ports",
        "The button used for enter/accept/confirm",
    )

    @Test
    fun localeConfigAndResourceDirectoriesStayInSync() {
        val resourceRoot = locateResourceRoot()
        val configured = parseLocaleConfig(resourceRoot.resolve("xml/locales_config.xml")) - "en"
        val directories = localizedDirectories(resourceRoot).mapTo(mutableSetOf()) {
            it.fileName.toString().removePrefix("values-").let { tag -> if (tag == "in") "id" else tag }
        }

        assertEquals(expectedLocales, configured)
        assertEquals(expectedLocales, directories)
    }

    @Test
    fun everyLocaleHasEveryStringAndPluralResource() {
        val resourceRoot = locateResourceRoot()
        val defaults = parseResources(resourceRoot.resolve("values/strings.xml"))

        localizedDirectories(resourceRoot).forEach { directory ->
            val localized = parseResources(directory.resolve("strings.xml"))
            val label = directory.fileName.toString()

            assertEquals("String keys differ in $label", defaults.strings.keys, localized.strings.keys)
            assertEquals("Plural keys differ in $label", defaults.plurals.keys, localized.plurals.keys)
            assertTrue("Blank strings in $label", localized.strings.values.none(String::isBlank))

            defaults.strings.forEach { (key, defaultValue) ->
                if (key in defaults.unformattedStrings) return@forEach
                val localizedValue = localized.strings.getValue(key)
                assertEquals(
                    "Format arguments differ for $key in $label",
                    formatArguments(defaultValue),
                    formatArguments(localizedValue),
                )
            }

            defaults.plurals.forEach { (key, defaultQuantities) ->
                val localizedQuantities = localized.plurals.getValue(key)
                assertTrue("Missing plural 'other' for $key in $label", "other" in localizedQuantities)
                assertTrue("Blank plural value for $key in $label", localizedQuantities.values.none(String::isBlank))
                assertEquals(
                    "Plural format arguments differ for $key in $label",
                    formatArguments(defaultQuantities.getValue("other")),
                    formatArguments(localizedQuantities.getValue("other")),
                )
            }
        }
    }

    @Test
    fun auditedEnglishSentencesDoNotLeakIntoTranslations() {
        val resourceRoot = locateResourceRoot()
        localizedDirectories(resourceRoot).forEach { directory ->
            val values = parseResources(directory.resolve("strings.xml")).strings
            val leaks = values.filterValues { value ->
                knownEnglishSentenceFragments.any(value::contains)
            }
            assertTrue("English sentences leaked into ${directory.fileName}: $leaks", leaks.isEmpty())
        }
    }

    private fun formatArguments(value: String): List<String> =
        formatSpecifier.findAll(value).map { it.value }.sorted().toList()

    private fun localizedDirectories(resourceRoot: Path): List<Path> = Files.list(resourceRoot).use { paths ->
        paths.filter {
            it.isDirectory() && it.fileName.toString().startsWith("values-") && it.fileName.toString() != "values-night"
        }.sorted().toList()
    }

    private fun parseLocaleConfig(path: Path): Set<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path.toFile())
        val nodes = document.getElementsByTagName("locale")
        return buildSet {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as Element
                add(element.getAttribute("android:name"))
            }
        }
    }

    private fun parseResources(path: Path): ResourceContract {
        val unformattedStrings = mutableSetOf<String>()
        val strings = mutableMapOf<String, String>()
        val plurals = mutableMapOf<String, Map<String, String>>()
        Files.list(path.parent).use { files ->
            files.filter { it.fileName.toString().endsWith(".xml") }.forEach { file ->
                val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile())
                val stringNodes = document.getElementsByTagName("string")
                val pluralNodes = document.getElementsByTagName("plurals")
                if (file != path) {
                    assertEquals("Strings belong in strings.xml: $file", 0, stringNodes.length)
                    assertEquals("Plurals belong in strings.xml: $file", 0, pluralNodes.length)
                }
                for (index in 0 until stringNodes.length) {
                    val element = stringNodes.item(index) as Element
                    val name = element.getAttribute("name")
                    if (element.getAttribute("formatted") == "false") unformattedStrings += name
                    assertTrue("Duplicate string $name in $file", strings.put(name, element.textContent.trim()) == null)
                }
                for (index in 0 until pluralNodes.length) {
                    val element = pluralNodes.item(index) as Element
                    val quantities = buildMap {
                        val items = element.getElementsByTagName("item")
                        for (itemIndex in 0 until items.length) {
                            val item = items.item(itemIndex) as Element
                            put(item.getAttribute("quantity"), item.textContent.trim())
                        }
                    }
                    val name = element.getAttribute("name")
                    assertTrue("Duplicate plural $name in $file", plurals.put(name, quantities) == null)
                }
            }
        }
        return ResourceContract(strings, plurals, unformattedStrings)
    }

    private fun locateResourceRoot(): Path {
        val workingDirectory = Path.of(System.getProperty("user.dir"))
        return sequenceOf(workingDirectory.resolve("src/main/res"), workingDirectory.resolve("app/src/main/res"))
            .firstOrNull(Path::isDirectory)
            ?: error("Unable to locate app/src/main/res from $workingDirectory")
    }

    private data class ResourceContract(
        val strings: Map<String, String>,
        val plurals: Map<String, Map<String, String>>,
        val unformattedStrings: Set<String>,
    )
}
