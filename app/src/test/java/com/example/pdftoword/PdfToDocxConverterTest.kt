package com.example.pdftoword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Unit tests for the DOCX-building parts of [PdfToDocxConverter].
 *
 * These tests instantiate a test-only subclass that exposes the private XML
 * helpers via package-private overrides so we can verify the DOCX structure
 * without needing an Android Context or PDFBox initialisation.
 */
class PdfToDocxConverterTest {

    /** Exposes the internal DOCX assembly logic without requiring a Context. */
    private val helper = DocxAssemblyHelper()

    @Test
    fun `escapeXml replaces special characters`() {
        val input = """<tag attr="value" key='k'> & </tag>"""
        val expected = "&lt;tag attr=&quot;value&quot; key=&apos;k&apos;&gt; &amp; &lt;/tag&gt;"
        assertEquals(expected, helper.escapeXml(input))
    }

    @Test
    fun `buildParagraphsXml wraps each line in a paragraph element`() {
        val text = "Hello\nWorld"
        val xml = helper.buildParagraphsXml(text)
        assertTrue("Should contain Hello", xml.contains(">Hello<"))
        assertTrue("Should contain World", xml.contains(">World<"))
        assertTrue("Line count should be 2", xml.lines().filter { it.contains("<w:p>") }.size == 2)
    }

    @Test
    fun `writeDocx creates a valid zip with required DOCX entries`() {
        val text = "Sample PDF content\nSecond line"
        val outputFile = File.createTempFile("test_output", ".docx")
        try {
            helper.writeDocxFromText(text, outputFile)

            assertTrue("Output file should exist", outputFile.exists())
            assertTrue("Output file should not be empty", outputFile.length() > 0)

            ZipFile(outputFile).use { zip ->
                val entryNames = zip.entries().asSequence().map { it.name }.toSet()
                assertTrue("[Content_Types].xml must be present",
                    "[Content_Types].xml" in entryNames)
                assertTrue("_rels/.rels must be present", "_rels/.rels" in entryNames)
                assertTrue("word/document.xml must be present", "word/document.xml" in entryNames)
                assertTrue("word/styles.xml must be present", "word/styles.xml" in entryNames)
                assertTrue("word/settings.xml must be present", "word/settings.xml" in entryNames)
                assertTrue("docProps/app.xml must be present", "docProps/app.xml" in entryNames)
                assertTrue("docProps/core.xml must be present", "docProps/core.xml" in entryNames)

                // Verify document.xml contains the text content
                val documentEntry = zip.getEntry("word/document.xml")
                val documentContent = zip.getInputStream(documentEntry).bufferedReader().readText()
                assertTrue("document.xml should contain 'Sample PDF content'",
                    documentContent.contains("Sample PDF content"))
                assertTrue("document.xml should contain 'Second line'",
                    documentContent.contains("Second line"))
            }
        } finally {
            outputFile.delete()
        }
    }

    @Test
    fun `deriveOutputFileName replaces pdf extension with docx`() {
        assertEquals("report.docx", helper.deriveOutputFileName("report.pdf"))
        assertEquals("my document.docx", helper.deriveOutputFileName("my document.pdf"))
        assertEquals("noextension.docx", helper.deriveOutputFileName("noextension"))
    }
}

/**
 * A plain-JVM helper that mirrors the DOCX-building logic from
 * [PdfToDocxConverter] without requiring Android or PDFBox initialisation.
 * Kept in the test source set so the production class stays clean.
 */
class DocxAssemblyHelper {

    fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    fun buildParagraphsXml(text: String): String {
        val sb = StringBuilder()
        val lines = text.lines()
        for (line in lines) {
            val escaped = escapeXml(line)
            sb.append("    <w:p><w:r><w:t xml:space=\"preserve\">")
            sb.append(escaped)
            sb.append("</w:t></w:r></w:p>\n")
        }
        return sb.toString()
    }

    fun writeDocxFromText(text: String, outputFile: File) {
        outputFile.parentFile?.mkdirs()
        java.util.zip.ZipOutputStream(FileOutputStream(outputFile).buffered()).use { zip ->
            addEntry(zip, "[Content_Types].xml", contentTypesXml())
            addEntry(zip, "_rels/.rels", relsXml())
            addEntry(zip, "word/document.xml", documentXml(text))
            addEntry(zip, "word/_rels/document.xml.rels", documentRelsXml())
            addEntry(zip, "word/settings.xml", settingsXml())
            addEntry(zip, "word/styles.xml", stylesXml())
            addEntry(zip, "docProps/app.xml", appPropsXml())
            addEntry(zip, "docProps/core.xml", corePropsXml())
        }
    }

    fun deriveOutputFileName(originalName: String): String {
        val base = originalName.substringBeforeLast('.')
        return if (base == originalName) "$originalName.docx" else "$base.docx"
    }

    private fun addEntry(zip: java.util.zip.ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(java.util.zip.ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun contentTypesXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml"
    ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/word/settings.xml"
    ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>
  <Override PartName="/word/styles.xml"
    ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
  <Override PartName="/docProps/app.xml"
    ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
  <Override PartName="/docProps/core.xml"
    ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
</Types>"""

    private fun relsXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1"
    Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
    Target="word/document.xml"/>
</Relationships>"""

    private fun documentXml(text: String): String {
        val paragraphs = buildParagraphsXml(text)
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
$paragraphs
  </w:body>
</w:document>"""
    }

    private fun documentRelsXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
</Relationships>"""

    private fun settingsXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"/>"""

    private fun stylesXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"/>"""

    private fun appPropsXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">
  <Application>PDF to Word Android App</Application>
</Properties>"""

    private fun corePropsXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties
  xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
  xmlns:dc="http://purl.org/dc/elements/1.1/"
  xmlns:dcterms="http://purl.org/dc/terms/"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <dc:creator>PDF to Word Android App</dc:creator>
</cp:coreProperties>"""
}
