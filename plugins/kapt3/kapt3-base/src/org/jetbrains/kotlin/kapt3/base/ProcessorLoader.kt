/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.kapt3.base

import com.sun.tools.javac.code.Symbol
import org.jetbrains.kotlin.base.kapt3.KaptFlag
import org.jetbrains.kotlin.base.kapt3.KaptOptions
import org.jetbrains.kotlin.kapt3.base.util.KaptLogger
import org.jetbrains.kotlin.kapt3.base.util.info
import java.io.Closeable
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.util.*
import javax.annotation.processing.Filer
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.Processor
import javax.lang.model.element.Element
import javax.lang.model.element.PackageElement
import javax.tools.FileObject
import javax.tools.JavaFileManager
import javax.tools.JavaFileObject

class LoadedProcessors(val processors: List<IncrementalProcessor>, val classLoader: ClassLoader)

class IncrementalProcessor(private val processor: Processor, val kind: DeclaredProcType) : Processor by processor {

    private var dependencyInfo: AnnotationProcessorDependencyInfo? = null

    override fun init(processingEnv: ProcessingEnvironment) {

        if (kind == DeclaredProcType.NON_INCREMENTAL) {
            processor.init(processingEnv)
            dependencyInfo = createDependencyInfo()
        } else {
            val originalFiler = processingEnv.filer
            val incrementalFiler = IncrementalFiler(originalFiler)
            val incProcEnvironment = IncrementalProcessingEnvironment(processingEnv, incrementalFiler)
            processor.init(incProcEnvironment)
            dependencyInfo = createDependencyInfo()
            incrementalFiler.dependencyInfo = dependencyInfo
        }
    }

    private fun createDependencyInfo(): AnnotationProcessorDependencyInfo {
        val type = if (kind == DeclaredProcType.DYNAMIC) {
            val fromOptions = supportedOptions.singleOrNull { it.startsWith("org.gradle.annotation.processing.") }
            if (fromOptions == null) {
                RuntimeProcType.NON_INCREMENTAL
            } else {
                val declaredType = fromOptions.drop("org.gradle.annotation.processing.".length).toUpperCase()
                if (ALLOWED_RUNTIME_TYPES.contains(declaredType)) {
                    enumValueOf(declaredType)
                } else {
                    kind.toRuntimeType()
                }
            }
        } else {
            kind.toRuntimeType()
        }

        return AnnotationProcessorDependencyInfo(type)
    }

    fun getDependencyInfo() = dependencyInfo!!
    fun isIncremental() = dependencyInfo!!.isIncremental()
}

class IncrementalProcessingEnvironment(val processingEnv: ProcessingEnvironment, val incFiler: IncrementalFiler) :
    ProcessingEnvironment by processingEnv {
    override fun getFiler(): Filer = incFiler
}

class IncrementalFiler(val filer: Filer) : Filer by filer {

    internal var dependencyInfo: AnnotationProcessorDependencyInfo? = null

    override fun createSourceFile(name: CharSequence?, vararg originatingElements: Element?): JavaFileObject {
        val createdSourceFile = filer.createSourceFile(name, *originatingElements)
        dependencyInfo!!.add(createdSourceFile, originatingElements)
        return createdSourceFile
    }

    override fun createClassFile(name: CharSequence?, vararg originatingElements: Element?): JavaFileObject {
        val createdClassFile = filer.createClassFile(name, *originatingElements)
        dependencyInfo!!.add(createdClassFile, originatingElements)
        return createdClassFile
    }

    override fun createResource(
        location: JavaFileManager.Location?,
        pkg: CharSequence?,
        relativeName: CharSequence?,
        vararg originatingElements: Element?
    ): FileObject {
        val createdResource = filer.createResource(location, pkg, relativeName, *originatingElements)
        dependencyInfo!!.add(createdResource, originatingElements)

        return createdResource
    }
}

class AnnotationProcessorDependencyInfo(val runtimeProcType: RuntimeProcType) {
    // source files to list of files it generated in the AP run
    private val srcToGenerated = mutableMapOf<File, MutableSet<File>>()
    private var isFullRebuild = !runtimeProcType.isIncremental

    fun add(createdFile: FileObject, originatingElements: Array<out Element?>) {
        if (isFullRebuild) return

        val newFile = File(createdFile.toUri())
        val srcFiles = getSrcFiles(originatingElements)

        if (srcFiles.size != 1 && runtimeProcType == RuntimeProcType.ISOLATING) {
            isFullRebuild = true
        } else {
            srcFiles.forEach {
                val files = srcToGenerated.getOrDefault(it, mutableSetOf())
                files.add(newFile)
                srcToGenerated[it] = files
            }
        }
    }

    fun getDependencies():MutableMap<File, MutableSet<File>> = srcToGenerated
    fun isIncremental() = !isFullRebuild
}

private fun getSrcFiles(elements: Array<out Element?>): List<File> {
    return elements.filterNotNull().mapNotNull {
        var origin = it
        while (origin.enclosingElement != null && origin.enclosingElement !is PackageElement) {
            origin = origin.enclosingElement
        }
        (origin as? Symbol.ClassSymbol)?.sourcefile?.let { src -> File(src.toUri()) }
    }
}

enum class DeclaredProcType {
    AGGREGATING {
        override fun toRuntimeType() = RuntimeProcType.AGGREGATING
    },
    ISOLATING {
        override fun toRuntimeType() = RuntimeProcType.ISOLATING
    },
    DYNAMIC {
        override fun toRuntimeType() = throw IllegalStateException("This should not be used")
    },
    NON_INCREMENTAL {
        override fun toRuntimeType() = RuntimeProcType.NON_INCREMENTAL
    };

    abstract fun toRuntimeType(): RuntimeProcType
}

enum class RuntimeProcType(val isIncremental: Boolean) {
    AGGREGATING(true),
    ISOLATING(true),
    NON_INCREMENTAL(false),
}

private val ALLOWED_RUNTIME_TYPES = setOf(RuntimeProcType.AGGREGATING.name, RuntimeProcType.ISOLATING.name)

private const val INCREMENTAL_ANNOTATION_FLAG = "META-INF/gradle/incremental.annotation.processors"

open class ProcessorLoader(private val options: KaptOptions, private val logger: KaptLogger) : Closeable {
    private var annotationProcessingClassLoader: URLClassLoader? = null

    fun loadProcessors(parentClassLoader: ClassLoader = ClassLoader.getSystemClassLoader()): LoadedProcessors {
        clearJarURLCache()

        val classpath = LinkedHashSet<File>().apply {
            addAll(options.processingClasspath)
            if (options[KaptFlag.INCLUDE_COMPILE_CLASSPATH]) {
                addAll(options.compileClasspath)
            }
        }
        val classLoader = URLClassLoader(classpath.map { it.toURI().toURL() }.toTypedArray(), parentClassLoader)
        this.annotationProcessingClassLoader = classLoader

        val processors = if (options.processors.isNotEmpty()) {
            logger.info("Annotation processor class names are set, skip AP discovery")
            options.processors.mapNotNull { tryLoadProcessor(it, classLoader) }
        } else {
            logger.info("Need to discovery annotation processors in the AP classpath")
            doLoadProcessors(classLoader)
        }

        if (processors.isEmpty()) {
            logger.info("No annotation processors available, aborting")
        } else {
            logger.info { "Annotation processors: " + processors.joinToString { it::class.java.canonicalName } }
        }

        val allowedValues = setOf(DeclaredProcType.AGGREGATING.name, DeclaredProcType.ISOLATING.name, DeclaredProcType.DYNAMIC.name)
        val processorToIncremental = mutableMapOf<String, DeclaredProcType>()

        classLoader.getResource(INCREMENTAL_ANNOTATION_FLAG)?.let { incap ->
            for (line in incap.readText(Charsets.UTF_8).lineSequence()) {
                val parts = line.split(",")
                if (parts.size == 2) {
                    val kind = parts[1].toUpperCase(Locale.ENGLISH)
                    if (allowedValues.contains(kind)) {
                        processorToIncremental[parts[0]] = enumValueOf(kind)
                    }
                }
            }
        }

        val incrementalProcessors = processors.map { proc ->
            val kind = processorToIncremental.getOrDefault(proc.javaClass.name, DeclaredProcType.NON_INCREMENTAL)
            IncrementalProcessor(proc, kind)
        }

        return LoadedProcessors(incrementalProcessors, classLoader)
    }

    open fun doLoadProcessors(classLoader: URLClassLoader): List<Processor> {
        return ServiceLoader.load(Processor::class.java, classLoader).toList()
    }

    private fun tryLoadProcessor(fqName: String, classLoader: ClassLoader): Processor? {
        val annotationProcessorClass = try {
            Class.forName(fqName, true, classLoader)
        } catch (e: Throwable) {
            logger.warn("Can't find annotation processor class $fqName: ${e.message}")
            return null
        }

        try {
            val annotationProcessorInstance = annotationProcessorClass.newInstance()
            if (annotationProcessorInstance !is Processor) {
                logger.warn("$fqName is not an instance of 'Processor'")
                return null
            }

            return annotationProcessorInstance
        } catch (e: Throwable) {
            logger.warn("Can't load annotation processor class $fqName: ${e.message}")
            return null
        }
    }

    override fun close() {
        annotationProcessingClassLoader?.close()
        clearJarURLCache()
    }
}

// Copied from com.intellij.ide.ClassUtilCore
private fun clearJarURLCache() {
    fun clearMap(cache: Field) {
        cache.isAccessible = true

        if (!Modifier.isFinal(cache.modifiers)) {
            cache.set(null, hashMapOf<Any, Any>())
        } else {
            val map = cache.get(null) as MutableMap<*, *>
            map.clear()
        }
    }

    try {
        val jarFileFactory = Class.forName("sun.net.www.protocol.jar.JarFileFactory")

        clearMap(jarFileFactory.getDeclaredField("fileCache"))
        clearMap(jarFileFactory.getDeclaredField("urlCache"))
    } catch (ignore: Exception) {
    }
}