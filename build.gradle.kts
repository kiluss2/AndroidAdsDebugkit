import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.plugins.signing.SigningExtension

plugins {
    id("com.android.library") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.2.21"
    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = "io.github.kiluss2"
version = "0.1.0"

android {
    namespace = "fxc.dev.ads_debug_kit"
    compileSdk = 35

    defaultConfig {
        minSdk = 25
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    implementation("com.jakewharton.timber:timber:5.0.1")
}

mavenPublishing {
    configure(
        AndroidSingleVariantLibrary(
            javadocJar = JavadocJar.Empty(),
            sourcesJar = SourcesJar.Sources(),
            variant = "release"
        )
    )

    publishToMavenCentral()
    if (hasSigningConfig()) {
        signAllPublications()
    }

    coordinates(
        groupId = "io.github.kiluss2",
        artifactId = "android-ads-debug-kit",
        version = project.version.toString()
    )

    pom {
        name.set("AndroidAdsDebugKit")
        description.set("Android in-app ads debug overlay with hidden unlock gesture, shake toggle, ad events, revenue logs, and runtime ad id override modes.")
        inceptionYear.set("2026")
        url.set("https://github.com/kiluss2/AndroidAdsDebugkit")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("kiluss2")
                name.set("Son Le Van")
                url.set("https://github.com/kiluss2")
            }
        }
        scm {
            url.set("https://github.com/kiluss2/AndroidAdsDebugkit")
            connection.set("scm:git:https://github.com/kiluss2/AndroidAdsDebugkit.git")
            developerConnection.set("scm:git:ssh://git@github.com/kiluss2/AndroidAdsDebugkit.git")
        }
    }
}

fun Project.hasSigningConfig(): Boolean {
    return hasProperty("signingInMemoryKey") ||
            hasProperty("signingInMemoryKeyFile") ||
            hasProperty("signing.secretKeyRingFile")
}

extensions.configure<SigningExtension> {
    val signingKeyFile = providers.gradleProperty("signingInMemoryKeyFile")
    val signingKey = providers.gradleProperty("signingInMemoryKey")
        .orElse(signingKeyFile.map { file(it).readText() })
    val signingPassword = providers.gradleProperty("signingInMemoryKeyPassword")

    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.orNull)
    }
}
