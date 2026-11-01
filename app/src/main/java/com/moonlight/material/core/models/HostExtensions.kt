package com.moonlight.material.core.models

import com.moonlight.material.core.security.CryptoUtils
import kotlinx.serialization.Serializable
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

@Serializable
data class HostCapabilities(
    val permission: Long? = null,
    val virtualDisplayCapable: Boolean = false,
    val virtualDisplayReady: Boolean = false,
    val virtualDisplayHDR: Boolean = false,
    val hardwareMetricsVersion: Int = 0,
    val sleepAvailable: Boolean = false,
    val commands: List<String> = emptyList()
) {
    fun allows(perm: Long): Boolean = permission?.let { (it and perm) != 0L } ?: true
    val canView: Boolean get() = allows((1L shl 25) or (1L shl 26))
    val canLaunch: Boolean get() = allows(1L shl 26)
    val canRunCommands: Boolean get() = allows(1L shl 20)

    companion object {
        fun parse(values: Map<String, String>, commands: List<String> = emptyList()): HostCapabilities {
            fun flag(key: String): Boolean {
                val v = values[key]?.lowercase() ?: ""
                return v == "1" || v == "true"
            }
            val permLong = values["Permission"]?.toLongOrNull()
            val hwMetrics = values["HardwareMetricsVersion"]?.toIntOrNull() ?: 0
            val sleepAvail = values["PowerActionVersion"] == "1" && flag("SleepSupported") && flag("SleepAllowed")

            return HostCapabilities(
                permission = permLong,
                virtualDisplayCapable = flag("VirtualDisplayCapable") || flag("VirtualDisplay") || flag("VirtualDisplaySupported"),
                virtualDisplayReady = flag("VirtualDisplayDriverReady"),
                virtualDisplayHDR = flag("VirtualDisplayHDRCapable"),
                hardwareMetricsVersion = hwMetrics,
                sleepAvailable = sleepAvail,
                commands = commands
            )
        }
    }
}

object HostXMLValues {
    fun elements(name: String, xml: String): List<String> {
        if (xml.isEmpty()) return emptyList()
        val list = mutableListOf<String>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            val root = doc.documentElement
            val childNodes = root.childNodes
            for (i in 0 until childNodes.length) {
                val node = childNodes.item(i)
                if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == name) {
                    list.add(node.textContent)
                }
            }
        } catch (e: Exception) {
            val pattern = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
            for (match in pattern.findAll(xml)) {
                var content = match.groupValues[1]
                if (content.startsWith("<![CDATA[") && content.endsWith("]]>")) {
                    content = content.removeSurrounding("<![CDATA[", "]]>")
                }
                list.add(content
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&amp;", "&")
                )
            }
        }
        return list
    }
}

@Serializable
data class HostCPU(
    val cpu_usage_percent: Double? = null
)

@Serializable
data class HostGPU(
    val id: Int = 0,
    val name: String = "",
    val metrics: Map<String, Double> = emptyMap()
)

typealias CPU = HostCPU
typealias GPU = HostGPU

@Serializable
data class HostHardwareMetrics(
    val version: Int = 0,
    val sampled_at_unix_ms: Double = 0.0,
    val interval_ms: Int = 0,
    val cpu: HostCPU = HostCPU(),
    val gpus: List<HostGPU> = emptyList()
)

data class OTPPairing(
    val code: String,
    val passphrase: String
) {
    val isValid: Boolean
        get() = code.length == 4 &&
                passphrase.toByteArray(Charsets.UTF_8).size >= 4 &&
                code.all { it in '0'..'9' }

    fun authentication(saltHex: String): String {
        val bytes = (code + saltHex + passphrase).toByteArray(Charsets.UTF_8)
        return CryptoUtils.hexString(CryptoUtils.sha256(bytes)).uppercase()
    }
}
