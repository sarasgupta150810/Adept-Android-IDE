package com.saras.ide // Ensure this matches your package

import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

class TitanDependencyEngine(private val cacheDirectory: File) {

private val servers = listOf(
"https://dl.google.com/dl/android/maven2/",
"https://repo1.maven.org/maven2/",
"https://jitpack.io/"
)

suspend fun resolveAndDownload(
library: String,
depth: Int = 0,
resolvedVersions: MutableMap<String, String> = mutableMapOf(), // 💥 Upgraded to Map!
logCallback: (String) -> Unit
) {
if (depth > 8) return

val cleanLib = library.replace("[", "").replace("]", "").replace("(", "").replace(")", "")
val parts = cleanLib.split(":")
if (parts.size < 3) return

val group = parts[0]
val artifact = parts[1]
val version = parts[2]

val baseId = "$group:$artifact"
val existingVersion = resolvedVersions[baseId]

// 💥 THE VERSION UPGRADE LOGIC
if (existingVersion != null) {
if (compareVersions(existingVersion, version) >= 0) {
return // We already have a newer or equal version. Skip!
} else {
logCallback("   └─ ⬆️ UPGRADING: $baseId ($existingVersion -> $version)")
// Destroy the old version so AAPT2 doesn't crash on duplicates!
File(cacheDirectory, "$artifact-$existingVersion.aar").delete()
File(cacheDirectory, "$artifact-$existingVersion.jar").delete()
File(cacheDirectory, "$artifact-$existingVersion-extracted").deleteRecursively()
}
}
resolvedVersions[baseId] = version

val groupPath = group.replace(".", "/")
var successfulServer: String? = null

// 1. Download the AAR/JAR/POM
for (server in servers) {
for (ext in listOf("aar", "jar", "pom")) {
val fileName = "$artifact-$version.$ext"
val targetFile = File(cacheDirectory, fileName)

if (targetFile.exists()) {
if (depth == 0) logCallback("> CACHED: $fileName")
else logCallback("   └─ [CACHED] Transitive: $fileName")

successfulServer = server
if (ext == "aar") extractAar(targetFile, File(cacheDirectory, "$artifact-$version-extracted"))
break
}

try {
val url = URL("$server$groupPath/$artifact/$version/$fileName")
val connection = url.openConnection() as HttpURLConnection
connection.connectTimeout = 10000
connection.readTimeout = 10000

if (connection.responseCode == 200) {
if (depth == 0) logCallback("> DOWNLOADING: $fileName...")
else logCallback("   └─ Transitive: $fileName...")

targetFile.outputStream().use { out -> connection.inputStream.copyTo(out) }
successfulServer = server
if (ext == "aar") extractAar(targetFile, File(cacheDirectory, "$artifact-$version-extracted"))
break
}
} catch (e: Exception) {}
}
if (successfulServer != null) break
}

if (successfulServer == null) {
logCallback("❌ ERROR 404: Could not download $artifact:$version")
return
}

// If depth is > 0, we were told by the Cloud API to just download, not parse!
if (depth > 0 && depth == 8) return

// 2. Try downloading the .module JSON file
val moduleName = "$artifact-$version.module"
val moduleFile = File(cacheDirectory, moduleName)
if (!moduleFile.exists()) downloadFile("$successfulServer$groupPath/$artifact/$version/$moduleName", moduleFile)

// 3. Try downloading the .pom XML file
val pomName = "$artifact-$version.pom"
val pomFile = File(cacheDirectory, pomName)
if (!pomFile.exists()) downloadFile("$successfulServer$groupPath/$artifact/$version/$pomName", pomFile)

// 4. The Execution Waterfall
var moduleSuccess = false
if (moduleFile.exists()) {
// Updated to pass resolvedVersions
moduleSuccess = parseModuleJson(moduleFile, depth, resolvedVersions, logCallback)
}

if (!moduleSuccess && pomFile.exists()) {
// Updated to pass resolvedVersions
parsePom(pomFile, depth, resolvedVersions, logCallback)
}
}

private fun downloadFile(urlString: String, targetFile: File) {
try {
val url = URL(urlString)
val connection = url.openConnection() as HttpURLConnection
if (connection.responseCode == 200) {
targetFile.outputStream().use { out -> connection.inputStream.copyTo(out) }
}
} catch (e: Exception) {}
}

private fun compareVersions(v1: String, v2: String): Int {
val cleanV1 = v1.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
val cleanV2 = v2.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
val length = maxOf(cleanV1.size, cleanV2.size)
for (i in 0 until length) {
val p1 = cleanV1.getOrElse(i) { 0 }
val p2 = cleanV2.getOrElse(i) { 0 }
if (p1 != p2) return p1.compareTo(p2)
}
return 0
}

// 💥 NEW: The Modern JSON Parser (No Variables, No Namespaces!)
private suspend fun parseModuleJson(
moduleFile: File,
depth: Int,
resolvedVersions: MutableMap<String, String>, // Updated!
logCallback: (String) -> Unit
): Boolean {
try {
val jsonText = moduleFile.readText()
val json = JSONObject(jsonText)
val variants = json.optJSONArray("variants") ?: return false

var foundDependencies = false
for (i in 0 until variants.length()) {
val variant = variants.getJSONObject(i)
val name = variant.optString("name", "")

// Only pull production libraries, not testing libraries
if (name.contains("Release") || name.contains("Runtime") || name.contains("Api")) {
val deps = variant.optJSONArray("dependencies") ?: continue
for (j in 0 until deps.length()) {
val dep = deps.getJSONObject(j)
val group = dep.optString("group")
val module = dep.optString("module")
val versionObj = dep.optJSONObject("version")
val version = versionObj?.optString("requires") ?: ""

if (group.isNotEmpty() && module.isNotEmpty() && version.isNotEmpty()) {
foundDependencies = true
// Updated recursive call
resolveAndDownload("$group:$module:$version", depth + 1, resolvedVersions, logCallback)
}
}
}
}
if (foundDependencies) logCallback("   └─ ⚡ Successfully parsed Gradle .module JSON!")
return foundDependencies
} catch (e: Exception) {
return false
}
}

// The Old XML Parser (Fallback)
private suspend fun parsePom(
pomFile: File,
depth: Int,
resolvedVersions: MutableMap<String, String>, // Updated!
logCallback: (String) -> Unit
) {
try {
val dbFactory = DocumentBuilderFactory.newInstance()
dbFactory.isNamespaceAware = false
val dBuilder = dbFactory.newDocumentBuilder()
val doc = dBuilder.parse(pomFile)
doc.documentElement.normalize()

val propertiesMap = mutableMapOf<String, String>()
val propertiesNodes = doc.getElementsByTagName("properties")
if (propertiesNodes.length > 0) {
val props = (propertiesNodes.item(0) as Element).childNodes
for (i in 0 until props.length) {
val prop = props.item(i)
if (prop.nodeType == Node.ELEMENT_NODE) propertiesMap[prop.nodeName] = prop.textContent.trim()
}
}

val dependenciesNodes = doc.getElementsByTagName("dependency")
for (i in 0 until dependenciesNodes.length) {
val depNode = dependenciesNodes.item(i) as Element
if (depNode.parentNode?.parentNode?.nodeName?.contains("dependencyManagement") == true) continue

val scope = getLenientChildValue(depNode, "scope")
if (scope == "test" || scope == "provided") continue

var childGroup = getLenientChildValue(depNode, "groupId") ?: continue
var childArtifact = getLenientChildValue(depNode, "artifactId") ?: continue
var childVersion = getLenientChildValue(depNode, "version") ?: ""

childGroup = resolveVariables(childGroup, propertiesMap)
childArtifact = resolveVariables(childArtifact, propertiesMap)
childVersion = resolveVariables(childVersion, propertiesMap)

if (childVersion.isNotEmpty()) {
// Updated recursive call
resolveAndDownload("$childGroup:$childArtifact:$childVersion", depth + 1, resolvedVersions, logCallback)
}
}
} catch (e: Exception) {}
}

private fun resolveVariables(text: String, props: Map<String, String>): String {
var result = text
val regex = Regex("\\$\\{([^}]+)\\}")
regex.findAll(text).forEach { matchResult ->
val varName = matchResult.groupValues[1]
val value = props[varName]
if (!value.isNullOrEmpty()) result = result.replace(matchResult.value, value)
}
return result
}

private fun getLenientChildElement(element: Element, tagName: String): Element? {
val children = element.childNodes
for (i in 0 until children.length) {
val child = children.item(i)
if (child.nodeType == Node.ELEMENT_NODE) {
val name = child.nodeName
val localName = (child as Element).localName ?: name
if (name == tagName || localName == tagName || name.endsWith(":$tagName")) return child as Element
}
}
return null
}

private fun getLenientChildValue(element: Element, tagName: String): String? {
return getLenientChildElement(element, tagName)?.textContent?.trim()
}

// 💥 NEW: Robust ZipFile Extractor
private fun extractAar(aarFile: File, destFolder: File) {
if (!destFolder.exists()) destFolder.mkdirs()
try {
java.util.zip.ZipFile(aarFile).use { zip ->
val entries = zip.entries()
while (entries.hasMoreElements()) {
val entry = entries.nextElement()
val newFile = File(destFolder, entry.name)

if (entry.isDirectory) {
newFile.mkdirs()
} else {
newFile.parentFile?.mkdirs()
zip.getInputStream(entry).use { input ->
java.io.FileOutputStream(newFile).use { output ->
input.copyTo(output)
}
}
}
}
}
} catch (e: Exception) {
// Logs extraction errors locally if corrupted zip is encountered
e.printStackTrace()
}
}
}