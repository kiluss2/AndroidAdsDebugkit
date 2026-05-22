plugins {
    id("com.android.library") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.2.21"
    id("maven-publish")
}

group = "fxc.dev"
version = "0.0.1-SNAPSHOT"

android {
    namespace = "fxc.dev.ads_debug_kit"
    compileSdk = 35

    defaultConfig {
        minSdk = 25
        consumerProguardFiles("consumer-rules.pro")
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
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

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "fxc.dev"
            artifactId = "android-ads-debug-kit"
            version = project.version.toString()

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("AndroidAdsDebugKit")
                description.set("Android in-app ads debug overlay with hidden unlock gesture, shake toggle, ad events, revenue logs, and runtime ad id override modes.")
                url.set("https://github.com/kiluss2/AndroidAdsDebugkit")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                scm {
                    url.set("https://github.com/kiluss2/AndroidAdsDebugkit")
                    connection.set("scm:git:https://github.com/kiluss2/AndroidAdsDebugkit.git")
                    developerConnection.set("scm:git:ssh://git@github.com/kiluss2/AndroidAdsDebugkit.git")
                }
            }
        }
    }
}
