plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.21"
    id("org.jetbrains.intellij") version "1.16.1"
}

group = "com.debricked"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // HTTP client
    implementation("com.google.code.gson:gson:2.10.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.2.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
}

intellij {
    version.set("2023.2")
    type.set("IC") // IntelliJ IDEA Community Edition
    // Git4Idea plugin as dependency for development/build
    plugins.set(listOf("Git4Idea"))
}

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        version.set(project.version.toString())
        sinceBuild.set("232")
        untilBuild.set("243")
        changeNotes.set("""
            <html>
            <b>0.1.0</b><br/>
            <ul>
              <li>Initial release - Phase 1 MVP</li>
              <li>Remote vulnerability findings from Debricked</li>
              <li>Repository and branch/commit awareness</li>
              <li>Basic tool window UI</li>
            </ul>
            </html>
        """.trimIndent())
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
