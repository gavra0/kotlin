package org.jetbrains.kotlin.gradle

import org.jetbrains.kotlin.gradle.util.getFileByName
import org.jetbrains.kotlin.gradle.util.modify
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class KotlinGradlePluginMultiVersionIT : BaseMultiGradleVersionIT() {
    @Test
    fun testKt19179() {
        val project = Project("kt19179", gradleVersion, directoryPrefix = "kapt2")

        project.build("build") {
            assertSuccessful()
            assertFileExists("processor/build/tmp/kapt3/classes/main/META-INF/services/javax.annotation.processing.Processor")

            val processorJar = fileInWorkingDir("processor/build/libs/processor.jar")
            assert(processorJar.exists())

            val zip = ZipFile(processorJar)
            @Suppress("ConvertTryFinallyToUseCall")
            try {
                assert(zip.getEntry("META-INF/services/javax.annotation.processing.Processor") != null)
            } finally {
                zip.close()
            }

            assertTasksExecuted(
                ":processor:kaptGenerateStubsKotlin",
                ":processor:kaptKotlin",
                ":app:kaptGenerateStubsKotlin",
                ":app:kaptKotlin"
            )
        }

        project.projectDir.getFileByName("Test.kt").modify { text ->
            assert("SomeClass()" in text)
            text.replace("SomeClass()", "SomeClass(); val a = 5")
        }

        project.build("build") {
            assertSuccessful()
            assertTasksUpToDate(":processor:kaptGenerateStubsKotlin", ":processor:kaptKotlin", ":app:kaptKotlin")
            assertTasksExecuted(":app:kaptGenerateStubsKotlin")
        }

        project.projectDir.getFileByName("Test.kt").modify { text ->
            text + "\n\nfun t() {}"
        }

        project.build("build") {
            assertSuccessful()
            assertTasksUpToDate(":processor:kaptGenerateStubsKotlin", ":processor:kaptKotlin")
            assertTasksExecuted(":app:kaptGenerateStubsKotlin", ":app:kaptKotlin")
        }
    }

    @Test
    fun testJavaIcCompatibility() {
        val project = Project("kotlinJavaProject", gradleVersion)

        val expectIncrementalCompilation = project.testGradleVersionAtLeast("2.14")
        val expectVerboseIncrementalLogs = project.testGradleVersionBelow("3.4")

        project.setupWorkingDir()

        val buildScript = File(project.projectDir, "build.gradle")

        buildScript.modify { "$it\n" + "compileJava.options.incremental = true" }
        project.build("build") {
            assertSuccessful()
        }

        // Then modify a Java source and check that compileJava is incremental:
        File(project.projectDir, "src/main/java/demo/HelloWorld.java").modify { "$it\n" + "class NewClass { }" }
        project.build("build") {
            assertSuccessful()
            if (expectIncrementalCompilation && expectVerboseIncrementalLogs)
                assertContains("Incremental compilation")
            if (expectIncrementalCompilation)
                assertNotContains("not incremental") else
                assertContains("not incremental")
        }

        // Then modify a Kotlin source and check that Gradle sees that Java is not up-to-date:
        File(project.projectDir, "src/main/kotlin/helloWorld.kt").modify {
            it.trim('\r', '\n').trimEnd('}') + "\nval z: Int = 0 }"
        }
        project.build("build") {
            assertSuccessful()
            assertTasksExecuted(":compileKotlin", ":compileJava")
            if (expectIncrementalCompilation)
                assertNotContains("not incremental") else
                assertContains("not incremental")
            assertNotContains("None of the classes needs to be compiled!")
        }
    }

    @Test
    fun testApplyPluginFromBuildSrc() {
        val project = Project("kotlinProjectWithBuildSrc", gradleVersion)
        project.setupWorkingDir()
        File(project.projectDir, "buildSrc/build.gradle").modify { it.replace("\$kotlin_version", KOTLIN_VERSION) }
        project.build("build") {
            assertSuccessful()
        }
    }

    @Test
    fun testInternalTest() {
        Project("internalTest", gradleVersion).build("build") {
            assertSuccessful()
            assertReportExists()
            assertTasksExecuted(":compileKotlin", ":compileTestKotlin")
        }
    }

    @Test
    fun testJavaLibraryCompatibility() {
        val project = Project("javaLibraryProject", gradleVersion)

        Assume.assumeTrue(
            "The java-library plugin is supported only in Gradle 3.4+ (current: $gradleVersion)",
            project.testGradleVersionAtLeast("3.4")
        )

        val compileKotlinTasks = listOf(":libA:compileKotlin", ":libB:compileKotlin", ":app:compileKotlin")
        project.build("build") {
            assertSuccessful()
            assertNotContains("Could not register Kotlin output")
            assertTasksExecuted(compileKotlinTasks)
        }

        // Modify a library source and its usage and re-build the project:
        for (path in listOf("libA/src/main/kotlin/HelloA.kt", "libB/src/main/kotlin/HelloB.kt", "app/src/main/kotlin/App.kt")) {
            File(project.projectDir, path).modify { original ->
                original.replace("helloA", "helloA1")
                    .replace("helloB", "helloB1")
                    .apply { assert(!equals(original)) }
            }
        }

        project.build("build") {
            assertSuccessful()
            assertNotContains("Could not register Kotlin output")
            assertTasksExecuted(compileKotlinTasks)
        }
    }
}