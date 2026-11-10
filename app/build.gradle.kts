@file:Suppress("UnstableApiUsage")

import com.android.build.api.variant.ResValue

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
}

setupApp()

android {
    testOptions.unitTests.isIncludeAndroidResources = true
    sourceSets.getByName("test").resources.srcDir("schemas")

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
    ksp {
        arg("room.incremental", "true")
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    bundle {
        language {
            enableSplit = false
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        aidl = true
    }
    namespace = "io.nekohasekai.sagernet"
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
}

dependencies {

    implementation(fileTree("libs"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.work.multiprocess)

    implementation(libs.material)
    implementation(libs.gson)

    implementation(libs.zxing.lite)
    implementation(libs.editorkit)
    implementation(libs.editorkit.language.base)
    implementation(libs.editorkit.language.json)

    implementation(libs.okhttp)
    implementation(libs.snakeyaml)
    implementation(libs.material.about.library)
    implementation(libs.process.phoenix)
    implementation(libs.kryo)
    implementation(libs.guava)
    implementation(libs.ini4j)

    implementation(libs.recyclerview.fastscroll) {
        exclude(group = "androidx.recyclerview")
        exclude(group = "androidx.appcompat")
    }

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)
    implementation(libs.roomigrant.lib)
    ksp(libs.roomigrant.compiler)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation("androidx.room:room-testing:2.6.1")
}


// The subsequent Go integration step must consume output produced by this test run.
tasks.withType<Test>().configureEach {
    val fixtures = layout.buildDirectory.dir("generated/config-fixtures/$name")
    systemProperty("thbox.configFixtures", fixtures.get().asFile.absolutePath)
    outputs.dir(fixtures)
    doFirst { fixtures.get().asFile.deleteRecursively(); fixtures.get().asFile.mkdirs() }
    val screenshots = layout.buildDirectory.dir("reports/ui")
    systemProperty("thbox.uiSnapshots", screenshots.get().asFile.absolutePath)
    outputs.dir(screenshots)
}

// Static launcher shortcuts must target the final ID, including flavor/build-type suffixes.
androidComponents.onVariants { variant ->
    variant.resValues.put(variant.makeResValueKey("string", "shortcut_target_package"),
        variant.applicationId.map { ResValue(it, "Final application ID for this variant") })
}
