import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.AbstractAppExtension
import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.getByName
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions
import java.util.Base64
import java.util.Properties
import kotlin.system.exitProcess

private val Project.android get() = extensions.getByName<ApplicationExtension>("android")

private lateinit var metadata: Properties
private lateinit var localProperties: Properties

fun Project.requireMetadata(): Properties {
    if (!::metadata.isInitialized) {
        metadata = Properties().apply {
            load(rootProject.file("nb4a.properties").inputStream())
        }
    }
    return metadata
}

fun Project.requireLocalProperties(): Properties {
    if (!::localProperties.isInitialized) {
        localProperties = Properties()

        val base64 = System.getenv("LOCAL_PROPERTIES")
        if (!base64.isNullOrBlank()) {

            localProperties.load(Base64.getDecoder().decode(base64).inputStream())
        } else if (project.rootProject.file("local.properties").exists()) {
            localProperties.load(rootProject.file("local.properties").inputStream())
        }
    }
    return localProperties
}

fun Project.setupCommon() {
    android.apply {
        buildToolsVersion = "35.0.1"
        compileSdk = 35
        defaultConfig {
            minSdk = 29
            targetSdk = 35
        }
        buildTypes {
            getByName("release") {
                isMinifyEnabled = true
            }
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        (android as ExtensionAware).extensions.getByName<KotlinJvmOptions>("kotlinOptions").apply {
            jvmTarget = JavaVersion.VERSION_17.toString()
        }
        lint {
            showAll = true
            checkAllWarnings = true
            checkReleaseBuilds = true
            textOutput = project.file("build/lint.txt")
            htmlOutput = project.file("build/lint.html")
        }
        packaging {
            resources.excludes.addAll(
                listOf(
                    "**/*.kotlin_*",
                    "/META-INF/*.version",
                    "/META-INF/native/**",
                    "/META-INF/native-image/**",
                    "/META-INF/INDEX.LIST",
                    "DebugProbesKt.bin",
                    "com/**",
                    "org/**",
                    "**/*.java",
                    "**/*.proto",
                    "okhttp3/**"
                )
            )
        }
        (this as? AbstractAppExtension)?.apply {
            buildTypes {
                getByName("release") {
                    isShrinkResources = true
                    if (System.getenv("nkmr_minify") == "0") {
                        isShrinkResources = false
                        isMinifyEnabled = false
                    }
                }
                getByName("debug") {
                    applicationIdSuffix = "debug"
                    debuggable(true)
                    jniDebuggable(true)
                }
            }
            applicationVariants.forEach { variant ->
                variant.outputs.forEach {
                    it as BaseVariantOutputImpl
                    it.outputFileName = it.outputFileName.replace(
                        "app", "${project.name}-" + variant.versionName
                    ).replace("-release", "").replace("-oss", "")
                }
            }
        }
    }
}

fun Project.setupAppCommon() {
    setupCommon()

    // F-Droid supplies its own signing identity after building an unsigned APK.
    val fdroidUnsigned = findProperty("FDROID_UNSIGNED") == "1" || gradle.startParameter.taskNames.any {
        it.substringAfterLast(':') in setOf("assembleArm64FdroidRelease", "assembleFdroidRelease")
    }
    if (fdroidUnsigned) {
        android.buildTypes.getByName("release").signingConfig = null
        gradle.taskGraph.whenReady {
            check(allTasks.none { task -> task.project == this@setupAppCommon &&
                Regex("(?i)(assemble|bundle|install|package|sign|validateSigning)(oss|preview|play)Release(?:Bundle)?").matches(task.name)
            }) { "Unsigned F-Droid builds must run separately from other release variants" }
        }
        return
    }

    // Release signing never falls back to a key or password stored in the repo.
    // Provide the key via KEYSTORE_FILE (a path outside the repo) or KEYSTORE_BASE64,
    // plus KEYSTORE_PASS / ALIAS_NAME / ALIAS_PASS, in local.properties or the environment.
    val lp = requireLocalProperties()
    val envSuppliesKey = !System.getenv("KEYSTORE_FILE").isNullOrBlank() || !System.getenv("KEYSTORE_BASE64").isNullOrBlank()
    fun signingProp(name: String) = if (envSuppliesKey)
        System.getenv(name)?.takeIf { it.isNotBlank() }
    else lp.getProperty(name)?.takeIf { it.isNotBlank() }

    val configuredFile = signingProp("KEYSTORE_FILE")
    val keystoreBase64 = signingProp("KEYSTORE_BASE64")
    check(configuredFile == null || keystoreBase64 == null) { "Specify exactly one of KEYSTORE_FILE and KEYSTORE_BASE64 in the selected signing source" }
    var keystoreFile = configuredFile?.let { file(it) }
    if (keystoreFile != null) check(keystoreFile.isFile) { "The configured signing keystore file does not exist" }
    if (keystoreFile == null && keystoreBase64 != null) {
        keystoreFile = layout.buildDirectory.file("signing/release.keystore").get().asFile.apply {
            parentFile.mkdirs()
            writeBytes(Base64.getDecoder().decode(keystoreBase64))
        }
    }
    val keystorePwd = signingProp("KEYSTORE_PASS")
    val alias = signingProp("ALIAS_NAME")
    val aliasPwd = signingProp("ALIAS_PASS") ?: keystorePwd

    android.apply {
        val hasReleaseKey = keystoreFile != null && keystorePwd != null && alias != null
        if (hasReleaseKey) {
            signingConfigs {
                create("release") {
                    storeFile = keystoreFile
                    storePassword = keystorePwd
                    keyAlias = alias
                    keyPassword = aliasPwd
                }
            }
            buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
        } else {
            // Without a release key an APK cannot upgrade an installed release build.
            // Only allow that for preview builds, or when explicitly requested.
            if (System.getenv("ALLOW_DEBUG_SIGNING") != "1") {
                gradle.taskGraph.whenReady {
                    val createsRelease = allTasks.any { task ->
                        task.project == this@setupAppCommon &&
                            Regex("(?i)(assemble|bundle|install|package|sign|validateSigning)(oss|fdroid|play)Release(?:Bundle)?")
                                .matches(task.name)
                    }
                    if (createsRelease) throw GradleException(
                        "Release signing is not configured: set KEYSTORE_FILE or KEYSTORE_BASE64, " +
                            "KEYSTORE_PASS and ALIAS_NAME (see docs/release-signing.md)"
                    )
                }
            }
            logger.warn("Release signing is not configured; release builds use the debug key and cannot upgrade an installed release.")
            buildTypes.getByName("release").signingConfig = signingConfigs.getByName("debug")
        }
    }
}

fun Project.setupApp() {
    val pkgName = requireMetadata().getProperty("PACKAGE_NAME")
    val verName = findProperty("VERSION_NAME")?.toString()?.takeIf { it.isNotBlank() }
        ?: requireMetadata().getProperty("VERSION_NAME")
    val verCode = (requireMetadata().getProperty("VERSION_CODE").toInt()) * 5
    android.apply {
        defaultConfig {
            applicationId = pkgName
            versionCode = verCode
            versionName = verName
            buildConfigField("String", "PRE_VERSION_NAME", "\"\"")
        }
    }
    setupAppCommon()

    android.apply {
        this as AbstractAppExtension

        buildTypes {
            getByName("release") {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    file("proguard-rules.pro")
                )
            }
        }

        splits.abi {
            reset()
            isEnable = true
            isUniversalApk = false
            include("arm64-v8a")
        }

        flavorDimensions += "vendor"
        productFlavors {
            create("oss")
            create("fdroid")
            create("play")
            create("preview") {
                applicationIdSuffix = ".preview"
                resValue("string", "app_name", "ThBox Preview")
                buildConfigField(
                    "String",
                    "PRE_VERSION_NAME",
                    "\"${requireMetadata().getProperty("PRE_VERSION_NAME")}\""
                )
            }
        }

        applicationVariants.all {
            outputs.all {
                this as BaseVariantOutputImpl
                val isPreview = outputFileName.contains("-preview")
                outputFileName = if (isPreview) {
                    outputFileName.replace(
                        project.name,
                        "ThBox-" + requireMetadata().getProperty("PRE_VERSION_NAME")
                    ).replace("-preview", "")
                } else {
                    outputFileName.replace(project.name, "ThBox-$versionName")
                        .replace("-release", "")
                        .replace("-oss", "")
                }
            }
        }

        tasks.register("assembleArm64FdroidRelease") {
            dependsOn("assembleFdroidRelease")
        }
        for (abi in listOf("Arm", "X64", "X86")) {
            tasks.register("assemble" + abi + "FdroidRelease") {
                doLast { throw GradleException("$abi APKs are not supported: this project packages arm64-v8a only.") }
            }
        }

        sourceSets.getByName("main").apply {
            jniLibs.srcDir("executableSo")
        }
    }
}