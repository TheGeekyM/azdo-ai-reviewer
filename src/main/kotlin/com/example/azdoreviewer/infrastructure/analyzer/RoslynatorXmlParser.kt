package com.example.azdoreviewer.infrastructure.analyzer

import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses Roslynator CLI XML output:
 *
 * <Roslynator><CodeAnalysis>
 *   <Diagnostics>
 *     <Diagnostic Id="CA1822">
 *       <Message>Mark members as static</Message>
 *       <FilePath>/abs/path/File.cs</FilePath>
 *       <Location Line="120" Character="38" />
 *     </Diagnostic>
 *     ...
 */
object RoslynatorXmlParser {

    fun parse(xml: String): List<RoslynAnalyzerRunner.AnalyzerDiagnostic> {
        if (xml.isBlank()) return emptyList()
        return runCatching {
            val doc = DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = false }
                .newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))

            val nodes = doc.getElementsByTagName("Diagnostic")
            val result = ArrayList<RoslynAnalyzerRunner.AnalyzerDiagnostic>()
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? org.w3c.dom.Element ?: continue
                // Only the detailed entries have child FilePath/Location (skip summary entries).
                val filePath = childText(el, "FilePath") ?: continue
                val message  = childText(el, "Message") ?: ""
                val id       = el.getAttribute("Id").ifBlank { "ANALYZER" }
                val locEl    = firstChild(el, "Location")
                val line     = locEl?.getAttribute("Line")?.toIntOrNull() ?: continue
                val severity = el.getAttribute("Severity").ifBlank { "warning" }
                result.add(RoslynAnalyzerRunner.AnalyzerDiagnostic(filePath, line, id, severity, message))
            }
            result
        }.getOrDefault(emptyList())
    }

    private fun firstChild(parent: org.w3c.dom.Element, tag: String): org.w3c.dom.Element? {
        val list = parent.getElementsByTagName(tag)
        return if (list.length > 0) list.item(0) as? org.w3c.dom.Element else null
    }

    private fun childText(parent: org.w3c.dom.Element, tag: String): String? =
        firstChild(parent, tag)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}
