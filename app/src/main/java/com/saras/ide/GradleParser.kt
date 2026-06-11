package com.saras.ide

import java.io.File
import java.util.regex.Pattern
import java.util.Stack

// ==========================================
// 1. THE ADVANCED DATA MODELS
// ==========================================
data class CompileOptions(
var sourceCompatibility: String = "JavaVersion.VERSION_17",
var targetCompatibility: String = "JavaVersion.VERSION_17"
)

data class BuildTypeConfig(
var isMinifyEnabled: Boolean = false,
var shrinkResources: Boolean = false,
var debuggable: Boolean = true
)

data class BuildFeatures(
var viewBinding: Boolean = false,
var dataBinding: Boolean = false
)

data class PackagingOptions(
val excludes: MutableList<String> = mutableListOf(),
val merges: MutableList<String> = mutableListOf(),
val doNotStrip: MutableList<String> = mutableListOf()
)

data class ExcludeRule(val group: String, val module: String)

data class Dependency(
val configuration: String, // e.g., "implementation", "api"
val notation: String,      // e.g., "androidx.core:core-ktx:1.12.0"
val excludes: MutableList<ExcludeRule> = mutableListOf()
)

data class GradleConfig(
var applicationId: String = "com.example.app",
var compileSdk: Int = 34,
var minSdk: Int = 21,
var targetSdk: Int = 34,
var versionCode: Int = 1,
var versionName: String = "1.0",
val compileOptions: CompileOptions = CompileOptions(),
val buildFeatures: BuildFeatures = BuildFeatures(),
val packagingOptions: PackagingOptions = PackagingOptions(),
val buildTypes: MutableMap<String, BuildTypeConfig> = mutableMapOf(
"debug" to BuildTypeConfig(debuggable = true),
"release" to BuildTypeConfig(debuggable = false)
),
val dependencies: MutableList<Dependency> = mutableListOf()
)

// ==========================================
// 2. THE ADVANCED BLOCK-AWARE PARSER ENGINE
// ==========================================
object GradleParser {

fun parse(gradleFile: File): GradleConfig {
val config = GradleConfig()
if (!gradleFile.exists()) return config

// Base Extractors
val numberPattern = Pattern.compile("\\b(?:compileSdk|minSdk|targetSdk|versionCode)\\s*=?\\s*(\\d+)")
val stringConfigPattern = Pattern.compile("\\b(?:applicationId|versionName)\\s*=?\\s*[\"']([^\"']+)[\"']")
val dependencyPattern = Pattern.compile("^(implementation|api|compile|compileOnly|kapt|annotationProcessor)\\s*\\(?\\s*[\"']([^\"']+)[\"']")
val booleanPattern = Pattern.compile("\\b(?:minifyEnabled|shrinkResources|debuggable|viewBinding|dataBinding)\\s*=?\\s*(true|false)")

// Exclude / Packaging Extractors
val excludeDepPattern = Pattern.compile("exclude\\s+(?:group\\s*=?\\s*['\"]([^'\"]+)['\"])?(?:\\s*,\\s*)?(?:module\\s*=?\\s*['\"]([^'\"]+)['\"])?")
val packagingPattern = Pattern.compile("(exclude|merge|doNotStrip)\\s*[\"']([^\"']+)[\"']")

val scopeStack = Stack<String>()
var currentBuildType: String? = null
var currentDependency: Dependency? = null // Tracks nested exclude blocks inside dependencies

gradleFile.forEachLine { rawLine ->
val line = rawLine.trim()
if (line.isEmpty() || line.startsWith("//") || line.startsWith("*")) return@forEachLine

// 1. Manage Scopes
val openBraceIndex = line.indexOf('{')
val closeBraceIndex = line.indexOf('}')

if (openBraceIndex != -1) {
val scopeHeader = line.substring(0, openBraceIndex).trim()
val scopeName = when {
scopeHeader.contains("android") -> "android"
scopeHeader.contains("defaultConfig") -> "defaultConfig"
scopeHeader.contains("compileOptions") -> "compileOptions"
scopeHeader.contains("buildFeatures") -> "buildFeatures"
scopeHeader.contains("packagingOptions") -> "packagingOptions"
scopeHeader.contains("buildTypes") -> "buildTypes"
scopeHeader.contains("release") -> { currentBuildType = "release"; "buildTypeItem" }
scopeHeader.contains("debug") -> { currentBuildType = "debug"; "buildTypeItem" }
scopeHeader.contains("dependencies") -> "dependencies"
dependencyPattern.matcher(scopeHeader).find() -> "dependencyBlock" // Catches: implementation("...") {
else -> scopeHeader.split("\\s+".toRegex()).lastOrNull() ?: "unknown"
}
scopeStack.push(scopeName)
}

val activeScope = if (scopeStack.isNotEmpty()) scopeStack.peek() else "root"

// 2. Value Extraction based on Scope
when (activeScope) {
"buildFeatures" -> {
val boolMatcher = booleanPattern.matcher(line)
if (boolMatcher.find()) {
val isTrue = (boolMatcher.group(1) ?: "") == "true"
if (line.contains("viewBinding")) config.buildFeatures.viewBinding = isTrue
if (line.contains("dataBinding")) config.buildFeatures.dataBinding = isTrue
}
}

"packagingOptions" -> {
val packMatcher = packagingPattern.matcher(line)
if (packMatcher.find()) {
// 💥 FIXED: Safely unwrapped with ?: ""
val action = packMatcher.group(1) ?: ""
val value = packMatcher.group(2) ?: ""
when (action) {
"exclude" -> config.packagingOptions.excludes.add(value)
"merge" -> config.packagingOptions.merges.add(value)
"doNotStrip" -> config.packagingOptions.doNotStrip.add(value)
}
}
}

"buildTypeItem" -> {
currentBuildType?.let { type ->
val bType = config.buildTypes.getOrPut(type) { BuildTypeConfig() }
val boolMatcher = booleanPattern.matcher(line)
if (boolMatcher.find()) {
val isTrue = (boolMatcher.group(1) ?: "") == "true"
when {
line.contains("minifyEnabled") -> bType.isMinifyEnabled = isTrue
line.contains("shrinkResources") -> bType.shrinkResources = isTrue
line.contains("debuggable") -> bType.debuggable = isTrue
}
}
}
}

"dependencyBlock" -> {
val exMatcher = excludeDepPattern.matcher(line)
if (exMatcher.find()) {
// 💥 FIXED: Safely unwrapped with ?: ""
val group = exMatcher.group(1) ?: ""
val module = exMatcher.group(2) ?: ""
currentDependency?.excludes?.add(ExcludeRule(group, module))
}
}

else -> {
val numMatcher = numberPattern.matcher(line)
if (numMatcher.find()) {
val value = numMatcher.group(1)?.toIntOrNull() ?: return@forEachLine
when {
line.contains("minSdk") -> config.minSdk = value
line.contains("targetSdk") -> config.targetSdk = value
line.contains("compileSdk") -> config.compileSdk = value
line.contains("versionCode") -> config.versionCode = value
}
}

val strMatcher = stringConfigPattern.matcher(line)
if (strMatcher.find()) {
// 💥 FIXED: Safely unwrapped with ?: ""
val value = strMatcher.group(1) ?: ""
when {
line.contains("applicationId") -> config.applicationId = value
line.contains("versionName") -> config.versionName = value
}
}

val depMatcher = dependencyPattern.matcher(line)
if (depMatcher.find()) {
// 💥 FIXED: Safely unwrapped with ?: ""
val configType = depMatcher.group(1) ?: ""
val library = depMatcher.group(2) ?: ""
val newDep = Dependency(configType, library)
config.dependencies.add(newDep)

if (openBraceIndex != -1) currentDependency = newDep
}
}
}

if (closeBraceIndex != -1) {
if (scopeStack.isNotEmpty()) {
val popped = scopeStack.pop()
if (popped == "buildTypeItem") currentBuildType = null
if (popped == "dependencyBlock") currentDependency = null
}
}
}

return config
}
}

object ManifestSyncer {
fun sync(sourceManifest: File, buildDir: File, config: GradleConfig): File {
var content = sourceManifest.readText()

content = content.replace(Regex("""package\s*=\s*["'][^"']+["']"""), "")
content = content.replace(Regex("""android:versionCode\s*=\s*["'][^"']+["']"""), "")
content = content.replace(Regex("""android:versionName\s*=\s*["'][^"']+["']"""), "")

val dynamicAttributes = """
package="${config.applicationId}"
android:versionCode="${config.versionCode}"
android:versionName="${config.versionName}"
""".trimIndent()

content = content.replaceFirst("<manifest", "<manifest\n    $dynamicAttributes")

val usesSdkRegex = Regex("""<uses-sdk[^>]*/>""")
val newSdk = "<uses-sdk android:minSdkVersion=\"${config.minSdk}\" android:targetSdkVersion=\"${config.targetSdk}\" />"

if (content.contains("<uses-sdk")) {
content = content.replace(usesSdkRegex, newSdk)
} else {
content = content.replaceFirst("<application", "$newSdk\n\n    <application")
}

val mergedManifest = File(buildDir, "AndroidManifest-merged.xml")
mergedManifest.writeText(content)

return mergedManifest
}
}