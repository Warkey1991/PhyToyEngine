import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.api.tasks.Exec
import org.gradle.api.configuration.BuildFeatures

abstract class PackagingBuildFeatures @Inject constructor(val features: BuildFeatures)

// Run through the existing wrapper: ./gradlew packagePlay|packageGalaxy|packageGalaxyFull|packageAll.
// Keep builds in this Gradle invocation; never launch a nested Gradle build.
val repository = rootDir.parentFile
val packageTasks = setOf(
    "packagePlay", "packageGalaxy", "packageGalaxyFull", "packageAll", "checkReleaseSigning",
    "auditPlayPackage", "auditGalaxyPackage", "auditGalaxyFullPackage",
)
val packagingRequested = gradle.startParameter.taskNames.any { it.substringAfterLast(':') in packageTasks }
val storeReady = providers.gradleProperty("packageStoreReady").getOrElse("false").let {
    require(it in setOf("true", "false")) { "packageStoreReady must be true or false" }
    it.toBoolean()
}
val signingFields = linkedMapOf(
    "storeFile" to "PHYTOY_KEYSTORE", "storePassword" to "PHYTOY_STORE_PASSWORD",
    "keyAlias" to "PHYTOY_KEY_ALIAS", "keyPassword" to "PHYTOY_KEY_PASSWORD",
)

fun loadPackageSigning(): Map<String, String> {
    val inherited = signingFields.values.associateWith { System.getenv(it).orEmpty() }
    if (inherited.values.any(String::isNotEmpty)) {
        require(inherited.values.all(String::isNotEmpty)) {
            "Supply all four signing environment variables, or none to use android/keystore.properties"
        }
        return inherited
    }
    val config = rootDir.resolve("keystore.properties")
    require(config.isFile) { "Missing android/keystore.properties; copy the .example and fill it privately" }
    if (Files.getFileStore(config.toPath()).supportsFileAttributeView("posix")) {
        val permissions = Files.getPosixFilePermissions(config.toPath())
        val privatePermissions = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        require(permissions.all { it in privatePermissions }) {
            "Signing config must be private: chmod 600 android/keystore.properties"
        }
    }
    val values = linkedMapOf<String, String>()
    config.readLines().forEach { line ->
        if (line.isNotBlank() && !line.trimStart().startsWith('#') && !line.trimStart().startsWith('!')) {
            val separator = line.indexOf('=')
            val key = if (separator >= 0) line.substring(0, separator).trim() else ""
            require(separator >= 0 && key in signingFields && key !in values) {
                "Signing config requires one unique key=value line per documented field"
            }
            // Literal values, just like the Python packaging entry point. Never shell-source passwords.
            values[key] = line.substring(separator + 1)
        }
    }
    require(signingFields.keys.all { !values[it].isNullOrEmpty() }) { "Signing config is incomplete" }
    return signingFields.map { (key, variable) -> variable to values.getValue(key) }.toMap()
}

val packageSigning = if (packagingRequested) {
    require(!objects.newInstance(PackagingBuildFeatures::class.java).features.configurationCache.requested.getOrElse(false)) {
        "Packaging reads private signing inputs; run with --no-configuration-cache"
    }
    loadPackageSigning().also {
        val keystore = file(it.getValue("PHYTOY_KEYSTORE"))
        require(java.io.File(it.getValue("PHYTOY_KEYSTORE")).isAbsolute && keystore.isFile) {
            "Signing keystore must be an existing absolute file path"
        }
    }
} else emptyMap()

// Only the packaging tasks opt into the local signing file. Normal IDE/debug builds are unchanged.
extra["phytoyPackageSigning"] = packageSigning
extra["phytoyPackagingRequested"] = packagingRequested
extra["phytoyPackagingStoreReady"] = packagingRequested && storeReady

val python = providers.gradleProperty("packagePython").getOrElse("python3")
val signingOutput = ByteArrayOutputStream()
val checkSigning = tasks.register<Exec>("checkReleaseSigning") {
    group = "distribution"
    description = "Validate the shared release keystore, alias and passwords without building."
    workingDir(repository)
    commandLine(python, repository.resolve("tools/package_android_release.py"), "--check-signing")
    environment(packageSigning)
    environment("JAVA_HOME", System.getProperty("java.home"))
    environment("PATH", java.io.File(System.getProperty("java.home"), "bin").path + java.io.File.pathSeparator + System.getenv("PATH").orEmpty())
    standardOutput = signingOutput
    doFirst {
        require(packageSigning.isNotEmpty()) { "Use the full packaging task name (for example packagePlay)" }
        signingOutput.reset()
    }
    doLast {
        val output = signingOutput.toString("UTF-8")
        val certificate = Regex("Certificate SHA-256: ([0-9a-f]{64})").find(output)?.groupValues?.get(1)
            ?: error("Signing validation did not return a certificate fingerprint")
        rootProject.extra["phytoyPackageCertificate"] = certificate
        logger.lifecycle("Signing key validated. Certificate SHA-256: $certificate")
    }
}

fun fileHash(file: java.io.File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val auditTasks = listOf("play", "galaxy", "galaxyFull").associateWith { channel ->
    val flavor = channel.replaceFirstChar(Char::uppercaseChar)
    val apk = rootDir.resolve("sample/build/outputs/apk/$channel/release/sample-$channel-release.apk")
    val aab = rootDir.resolve("sample/build/outputs/bundle/${channel}Release/sample-$channel-release.aab")
    val report = repository.resolve("release-artifacts/$channel-packaging.json")
    val appId = if (channel == "play") {
        providers.gradleProperty("phytoyApplicationId").orElse(providers.environmentVariable("PHYTOY_APPLICATION_ID"))
            .getOrElse("com.ycolor.team.phytoy.camera.android.gpapp")
    } else if (channel == "galaxy") {
        providers.gradleProperty("phytoyGalaxyApplicationId").orElse(providers.environmentVariable("PHYTOY_GALAXY_APPLICATION_ID"))
            .getOrElse("com.ycolor.team.phytoy.camera.android.galaxyapp")
    } else {
        providers.gradleProperty("phytoyGalaxyFullApplicationId").orElse(providers.environmentVariable("PHYTOY_GALAXY_FULL_APPLICATION_ID"))
            .getOrElse("com.ycolor.team.phytoy.camera.android.galaxyfull")
    }
    val audit = tasks.register<Exec>("audit${flavor}Package") {
        group = "distribution"
        description = "Audit and archive the signed $channel APK/AAB, mappings and native symbols."
        dependsOn(checkSigning, ":sample:assemble${flavor}Release", ":sample:bundle${flavor}Release", ":sample:lint${flavor}Release")
        workingDir(repository)
        // The audit has no need for signing passwords, even when inherited from the caller.
        setEnvironment(System.getenv().filterKeys { it !in signingFields.values })
        environment("JAVA_HOME", System.getProperty("java.home"))
        environment("PATH", java.io.File(System.getProperty("java.home"), "bin").path + java.io.File.pathSeparator + System.getenv("PATH").orEmpty())
        doFirst {
            val command = mutableListOf(
                python, repository.resolve("tools/check_android_release.py").absolutePath,
                "--channel", channel, "--apk", apk.absolutePath, "--aab", aab.absolutePath,
                "--expected-application-id", appId,
                "--expected-cert-sha256", rootProject.extra["phytoyPackageCertificate"].toString(),
                "--output", report.absolutePath,
            )
            if (storeReady) command.add("--store-ready")
            commandLine(command)
        }
        // The checker writes its JSON report; keep the terminal output concise.
        val auditOutput = ByteArrayOutputStream()
        standardOutput = auditOutput
        isIgnoreExitValue = true
        doLast {
            check(executionResult.get().exitValue == 0) { "Package audit failed: ${auditOutput.toString("UTF-8")}" }
            val result = JsonSlurper().parse(report) as Map<*, *>
            check(result["status"] == "passed") { "Package audit failed; see $report" }
            val metadata = (result["aab"] as Map<*, *>)["manifest"] as Map<*, *>
            val version = metadata["version_name"].toString().replace(Regex("[^A-Za-z0-9._-]"), "-")
            val code = metadata["version_code"].toString()
            val mode = if (storeReady) "store-checked" else "candidate"
            val outputRoot = providers.gradleProperty("packageOutputDir").orNull?.let { file(it) }
                ?: repository.resolve("release-artifacts/packages")
            val output = outputRoot.resolve("$channel/$version-$code-$mode")
            check(output.mkdirs() || output.isDirectory) { "Cannot create package output directory" }
            val artifactBrand = if (channel == "galaxyFull") "PomiCamera" else "ToviCam"
            val copies = linkedMapOf(
                apk to "$artifactBrand-$channel-$version-$code.apk",
                aab to "$artifactBrand-$channel-$version-$code.aab",
                report to "packaging-audit.json",
                rootDir.resolve("sample/build/outputs/mapping/${channel}Release/mapping.txt") to "mapping.txt",
                rootDir.resolve("sample/build/outputs/native-debug-symbols/${channel}Release/native-debug-symbols.zip") to "native-debug-symbols.zip",
            )
            copies.forEach { (source, name) ->
                check(source.isFile) { "Required release artifact missing: $source" }
                source.copyTo(output.resolve(name), overwrite = true)
            }
            output.resolve("SHA256SUMS.txt").writeText(copies.values.joinToString("") { name ->
                "${fileHash(output.resolve(name))}  $name\n"
            })
            logger.lifecycle("$channel: signed APK/AAB audit passed. Output: $output")
            if (!storeReady) logger.lifecycle("Candidate build; use -PpackageStoreReady=true to enforce applicable channel and publisher configuration.")
        }
    }
    tasks.register("package$flavor") {
        group = "distribution"
        description = "Build, sign, check and archive the $channel release APK and AAB."
        dependsOn(audit)
    }
    audit
}

tasks.register("packageAll") {
    group = "distribution"
    description = "Build, sign, check and archive Google Play and Galaxy Store releases."
    dependsOn(auditTasks.getValue("play"), auditTasks.getValue("galaxy"))
}

if (packagingRequested) {
    subprojects {
        if (path == ":sample") tasks.matching {
            it.name in setOf("prePlayReleaseBuild", "preGalaxyReleaseBuild", "preGalaxyFullReleaseBuild")
        }.configureEach { dependsOn(checkSigning) }
    }
}
