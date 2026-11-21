import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

abstract class VerifyForkNative : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aarFile: RegularFileProperty

    @TaskAction
    fun verify() {
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        val source = sourceDirectory.get().asFile
        val records = source.walkTopDown().filter {
            it.isFile && ((it.extension == "go" && !it.name.endsWith("_test.go")) ||
                it.name == "go.mod" || it.name == "go.sum")
        }.map { it.relativeTo(source).invariantSeparatorsPath to it }.sortedBy { it.first }
        val sourceDigest = sha256(records.joinToString("") { (name, file) ->
            name + "\u0000" + sha256(file.readBytes()) + "\n"
        }.toByteArray(Charsets.UTF_8))
        val compiler = source.resolve("go.mod").readLines().single { it.startsWith("toolchain ") }
            .substringAfter("toolchain ").trim()
        ZipFile(aarFile.get().asFile).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            require(names.size == names.toSet().size) { "Duplicate native archive entries" }
            val abis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            require(names.filter { it.endsWith(".so") }.toSet() ==
                abis.map { "jni/$it/libgojni.so" }.toSet()) { "Incomplete or unexpected JNI libraries" }
            val entry = zip.getEntry("assets/linqavo-native.properties")
                ?: error("Missing native provenance; rebuild with scripts/build-wg-aar.sh or .ps1")
            val actual = Properties().apply { zip.getInputStream(entry).use { load(it) } }
            val expected = mutableMapOf("format" to "1", "api" to "2",
                "source.sha256" to sourceDigest, "compiler" to compiler)
            abis.forEach { abi ->
                expected["sha256.$abi"] = sha256(zip.getInputStream(zip.getEntry("jni/$abi/libgojni.so")).use { it.readBytes() })
            }
            require(actual.entries.associate { it.key.toString() to it.value.toString() } == expected) {
                "Stale or modified native library; rebuild it from the current wg source"
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Single version authority shared with Windows; reject accidental upstream tags.
val forkXml = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
}.newDocumentBuilder().parse(rootProject.file("../shared/LinqavoVersion.props"))
fun forkValue(name: String) = forkXml.getElementsByTagName(name).item(0).textContent.trim()
val relayVersion = forkValue("LinqavoVersion")
val parts = relayVersion.split(".").map(String::toInt)
require(parts.size == 3 && parts.joinToString(".") == relayVersion)
require(parts[0] in 1..199 && parts[1] in 0..99 && parts[2] in 0..99)
val relayVersionCode = forkValue("LinqavoVersionCode").toInt()
// Keep upgrades above the last pre-Linqavo build (21101001).
require(relayVersionCode == 21101002 + (parts[0] - 1) * 10000 + parts[1] * 100 + parts[2])
require(forkValue("LinqavoAssemblyVersion") == "$relayVersion.0")
require(project.findProperty("relayVersion")?.toString()?.let { it == relayVersion } != false) {
    "Version override must match shared/LinqavoVersion.props"
}

val forkDebugKey = rootProject.file(".fork-signing/debug.keystore")
val generateForkDebugKey = tasks.register<Exec>("generateForkDebugKey") {
    val keyFile = forkDebugKey
    outputs.file(keyFile)
    onlyIf { !keyFile.exists() }
    doFirst { keyFile.parentFile.mkdirs() }
    commandLine(
        "${System.getProperty("java.home")}/bin/keytool", "-genkeypair",
        "-keystore", keyFile.absolutePath, "-storetype", "PKCS12",
        "-storepass", "android", "-keypass", "android", "-alias", "androiddebugkey",
        "-keyalg", "RSA", "-keysize", "3072", "-validity", "3650",
        "-dname", "CN=Linqavo Development", "-noprompt",
    )
}
tasks.matching { it.name == "validateSigningDebug" }.configureEach {
    dependsOn(generateForkDebugKey)
}

// Release always requires matching native provenance. Developers can still run
// JVM-only debug builds without the NDK; native/device validation opts in below.
val relayRequireWg: Boolean =
    (project.findProperty("relayRequireWg") as String?)?.toBoolean() ?: false
val verifyForkNative = tasks.register<VerifyForkNative>("verifyForkNative") {
    sourceDirectory.set(rootProject.layout.projectDirectory.dir("../wg"))
    aarFile.set(layout.projectDirectory.file("libs/linqavowg.aar"))
}
tasks.matching { it.name == "preReleaseBuild" || (relayRequireWg && it.name == "preBuild") }.configureEach {
    dependsOn(verifyForkNative)
}

android {
    namespace = "io.relay.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.relay.fork"
        minSdk = 26
        targetSdk = 37
        versionCode = relayVersionCode
        versionName = relayVersion
        // Instrumented tests are the device half of the test pyramid: they run
        // the real app on a real Android image in CI (.github/workflows/e2e.yml).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs.getByName("debug") {
        storeFile = forkDebugKey
    }
    // Signing material lives only in GitHub Secrets (docs/release.md); local and
    // PR builds simply have no release signing config.
    val keystorePath = System.getenv("RELAY_FORK_KEYSTORE_FILE")
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("RELAY_FORK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELAY_FORK_KEY_ALIAS")
                keyPassword = System.getenv("RELAY_FORK_KEY_PASSWORD")
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // The primary sideload artifact is the arm64-v8a APK (docs/release.md).
    //
    // The device lab additionally needs an x86_64 APK: a GitHub emulator is
    // x86_64, and while the Google APIs images can translate arm64, the AOSP
    // images cannot — `installDebug` there fails with "Could not find build of
    // variant which supports ... an ABI in x86_64, x86" and no test runs at all.
    // Gated behind a property so release builds are completely unaffected.
    // (They ship two APKs, not one — arm64 and universal. The comment here used
    // to say "exactly one", which contradicted `isUniversalApk = true` twelve
    // lines below and was an invitation to "fix" the code to match the prose.)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            if (project.findProperty("relayTestAbis") == "true") include("x86_64")
            // Also emit an APK carrying every ABI. The arm64 split is the one
            // most people want — it is a third of the size — but it silently
            // fails to install on a 32-bit ARM phone, an x86 Chromebook, or an
            // emulator. The universal build is the answer to "it says the app
            // isn't compatible with my device".
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            // com.wireguard.android:tunnel is here for one class —
            // com.wireguard.crypto.KeyPair, which is pure Java. The rest of it
            // is GoBackend: a second, complete copy of wireguard-go for running
            // a *client* tunnel through a VpnService, which is the opposite of
            // what Relay does and which nothing in this app ever constructs.
            // Left in, it costs 3.5 MB per ABI — 14 MB on the universal APK
            // people download when the arm64 one says "not compatible" —
            // to ship code that cannot execute.
            excludes += listOf(
                "**/libwg-go.so",
                "**/libwg-quick.so",
                "**/libwg.so",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all { it.useJUnit() }
    }
}

// Kotlin 2.4 removed the `kotlinOptions` DSL that used to live in `android { }`,
// and AGP 9 folded Kotlin support into the Android plugin itself — the standalone
// `org.jetbrains.kotlin.android` plugin is gone from the block above because
// applying it alongside AGP 9 is now a hard error.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
    // Official WireGuard key generation + crypto (Full Mode, ADR-0008).
    implementation(libs.wireguard.tunnel)

    // The userspace forwarder itself: /wg, built by gomobile. Picked up from a
    // directory rather than declared as a coordinate because it is built by a
    // Go toolchain and an NDK, which no Android developer should need installed
    // to compile this app. CI builds it once and drops it here (see the
    // wireguard job in ci.yml and `scripts/fetch-wg-aar.sh`); a checkout
    // without it still builds, and Full Mode then reports itself unavailable
    // instead of failing the build or -- worse -- offering a mode that cannot
    // start. `relayRequireWg` turns that tolerance off for the builds that ship.
    implementation(fileTree("libs") { include("linqavowg.aar") })

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
