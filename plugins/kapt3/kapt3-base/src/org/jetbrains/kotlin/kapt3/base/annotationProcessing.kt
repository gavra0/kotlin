/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.kapt3.base

import com.sun.tools.javac.comp.CompileStates.CompileState
import com.sun.tools.javac.main.JavaCompiler
import com.sun.tools.javac.processing.AnnotationProcessingError
import com.sun.tools.javac.processing.JavacFiler
import com.sun.tools.javac.processing.JavacProcessingEnvironment
import com.sun.tools.javac.tree.JCTree
import org.jetbrains.kotlin.base.kapt3.KaptFlag
import org.jetbrains.kotlin.kapt3.base.util.KaptBaseError
import org.jetbrains.kotlin.kapt3.base.util.isJava9OrLater
import org.jetbrains.kotlin.kapt3.base.util.measureTimeMillisWithResult
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.TypeElement
import javax.tools.JavaFileObject
import kotlin.system.measureTimeMillis
import com.sun.tools.javac.util.List as JavacList

fun KaptContext.doAnnotationProcessing(
    javaSourceFiles: List<File>,
    processors: List<IncrementalProcessor>,
    additionalSources: JavacList<JCTree.JCCompilationUnit> = JavacList.nil()
) {
    val processingEnvironment = JavacProcessingEnvironment.instance(context)
    val wrappedProcessors = processors.map { ProcessorWrapper(it) }

    val incrementalProcessing = options.incrementalAnnotationProcessing?.let{
        IncrementalAnnotationProcessing(it)
    }

    val compilerAfterAP: JavaCompiler
    try {
        if (isJava9OrLater()) {
            val initProcessAnnotationsMethod = JavaCompiler::class.java.declaredMethods.single { it.name == "initProcessAnnotations" }
            initProcessAnnotationsMethod.invoke(compiler, wrappedProcessors, emptyList<JavaFileObject>(), emptyList<String>())
        } else {
            compiler.initProcessAnnotations(wrappedProcessors)
        }

        val additionalFiles = incrementalProcessing?.getAdditionalFiles(javaSourceFiles.toSet()) ?: emptySet()

        val filesToProcess = javaSourceFiles + additionalFiles.toList()
        val parsedJavaFiles = parseJavaFiles(filesToProcess)

        compilerAfterAP = try {
            javaLog.interceptorData.files = parsedJavaFiles.map { it.sourceFile to it }.toMap()
            val analyzedFiles = compiler.stopIfErrorOccurred(
                CompileState.PARSE, compiler.enterTrees(parsedJavaFiles + additionalSources)
            )

            if (isJava9OrLater()) {
                val processAnnotationsMethod = compiler.javaClass.getMethod("processAnnotations", JavacList::class.java)
                processAnnotationsMethod.invoke(compiler, analyzedFiles)
                compiler
            } else {
                compiler.processAnnotations(analyzedFiles)
            }
        } catch (e: AnnotationProcessingError) {
            throw KaptBaseError(KaptBaseError.Kind.EXCEPTION, e.cause ?: e)
        }

        val log = compilerAfterAP.log

        val filer = processingEnvironment.filer as JavacFiler
        val errorCount = log.nerrors
        val warningCount = log.nwarnings

        if (logger.isVerbose) {
            logger.info("Annotation processing complete, errors: $errorCount, warnings: $warningCount")
        }

        val showProcessorTimings = options[KaptFlag.SHOW_PROCESSOR_TIMINGS]
        if (logger.isVerbose || showProcessorTimings) {
            val loggerFun = if (showProcessorTimings) logger::warn else logger::info
            showProcessorTimings(wrappedProcessors, loggerFun)
        }

        if (logger.isVerbose) {
            filer.displayState()
        }

        if (log.nerrors > 0) {
            throw KaptBaseError(KaptBaseError.Kind.ERROR_RAISED)
        }

        // get full cache state after this run
        if (incrementalProcessing != null && processors.all { it.isIncremental() }) {
            val (aggregating, isolating) =
                    processors.map { it.getDependencyInfo() }.partition { it.runtimeProcType == RuntimeProcType.AGGREGATING }

            // changing any of these, should re-run annotation processing for all origins, and remove all aggregatingGenerated
            val aggregatingOrigins: Set<File> = aggregating.flatMap { it.getDependencies().keys }.toSet()
            val aggregatingGenerated = mutableSetOf<File>()
            aggregating.forEach { deps ->
                deps.getDependencies().values.forEach { aggregatingGenerated.addAll(it) }
            }

            // changing any of these should re-run annotation processing for those files, and remove files that were generated from them
            val isolatingMapping = mutableMapOf<File, MutableSet<File>>()
            isolating.forEach { deps ->
                deps.getDependencies().forEach {
                    val generated = isolatingMapping.getOrDefault(it.key, mutableSetOf())
                    generated.addAll(it.value)
                    isolatingMapping[it.key] = generated
                }
            }

            incrementalProcessing.updateState(aggregatingOrigins, aggregatingGenerated, isolatingMapping)
        }
    } finally {
        processingEnvironment.close()
        this@doAnnotationProcessing.close()
        incrementalProcessing?.storeDependencyData()
    }
}

private class IncrementalAnnotationProcessing(stateDir: File) {

    private val aggregatingOrigins: MutableSet<File>
    private val aggregatingGenerated: MutableSet<File>
    private val isolatingMapping: MutableMap<File, MutableSet<File>>

    val originsFile = stateDir.resolve("aggregating-origins.txt")
    val generatedFile = stateDir.resolve("aggregating-generated.txt")
    val isolatingFile = stateDir.resolve("isolating.txt")

    init {
        if (!originsFile.exists() || !generatedFile.exists() || !isolatingFile.exists()) {
            aggregatingOrigins = mutableSetOf()
            aggregatingGenerated = mutableSetOf()
            isolatingMapping = mutableMapOf()
        } else {
            aggregatingOrigins = originsFile.readText().split(File.pathSeparator).map { File(it) }.toMutableSet()
            aggregatingGenerated = generatedFile.readText().split(File.pathSeparator).map { File(it) }.toMutableSet()

            isolatingMapping =  ObjectInputStream(isolatingFile.inputStream()).use {
                @Suppress("UNCHECKED_CAST")
                it.readObject() as MutableMap<File, MutableSet<File>>
            }
        }
        originsFile.delete()
        generatedFile.delete()
        isolatingFile.delete()
        stateDir.mkdirs()
    }

    fun getAdditionalFiles(sources: Set<File>): Set<File> {
        val additionalSources = mutableSetOf<File>()

        if (sources.any { aggregatingOrigins.contains(it) }) {
            val unchangedAggregating = aggregatingOrigins.filter { !sources.contains(it) }
            additionalSources.addAll(unchangedAggregating)
            aggregatingGenerated.forEach { it.delete() }
        }
        sources.mapNotNull { isolatingMapping[it] }.flatMap { it }.forEach { it.delete() }

        return mutableSetOf()
    }

    internal fun updateState(newOrigins: Set<File>, newGenerated: Set<File>, newIsolating: Map<File, MutableSet<File>>) {
        aggregatingOrigins.clear()
        aggregatingOrigins.addAll(newOrigins)

        aggregatingGenerated.clear()
        aggregatingGenerated.addAll(newGenerated)

        for (isolating in newIsolating) {
            isolatingMapping[isolating.key] = isolating.value
        }
    }

    internal fun storeDependencyData() {
        originsFile.writeText(aggregatingOrigins.joinToString(separator = File.pathSeparator))
        generatedFile.writeText(aggregatingGenerated.joinToString(separator = File.pathSeparator))
        ObjectOutputStream(isolatingFile.outputStream()).use {
            it.writeObject(isolatingMapping)
        }
    }
}

private fun showProcessorTimings(wrappedProcessors: List<ProcessorWrapper>, logger: (String) -> Unit) {
    logger("Annotation processor stats:")
    wrappedProcessors.forEach { processor ->
        logger(processor.renderSpentTime())
    }
}

private class ProcessorWrapper(private val delegate: Processor) : Processor by delegate {
    private var initTime: Long = 0
    private val roundTime = mutableListOf<Long>()

    override fun process(annotations: MutableSet<out TypeElement>?, roundEnv: RoundEnvironment?): Boolean {
        val (time, result) = measureTimeMillisWithResult {
            delegate.process(annotations, roundEnv)
        }

        roundTime += time
        return result
    }

    override fun init(processingEnv: ProcessingEnvironment?) {
        initTime += measureTimeMillis {
            delegate.init(processingEnv)
        }
    }

    override fun getSupportedOptions(): MutableSet<String> {
        val (time, result) = measureTimeMillisWithResult { delegate.supportedOptions }
        initTime += time
        return result
    }

    override fun getSupportedSourceVersion(): SourceVersion {
        val (time, result) = measureTimeMillisWithResult { delegate.supportedSourceVersion }
        initTime += time
        return result
    }

    override fun getSupportedAnnotationTypes(): MutableSet<String> {
        val (time, result) = measureTimeMillisWithResult { delegate.supportedAnnotationTypes }
        initTime += time
        return result
    }

    fun renderSpentTime(): String {
        val processorName = delegate.javaClass.simpleName
        val totalTime = initTime + roundTime.sum()

        return "$processorName: " +
                "total: $totalTime ms, " +
                "init: $initTime ms, " +
                "${roundTime.size} round(s): ${roundTime.joinToString { "$it ms" }}"
    }
}

fun KaptContext.parseJavaFiles(javaSourceFiles: List<File>): JavacList<JCTree.JCCompilationUnit> {
    val javaFileObjects = fileManager.getJavaFileObjectsFromFiles(javaSourceFiles)

    return compiler.stopIfErrorOccurred(
        CompileState.PARSE,
        initModulesIfNeeded(
            compiler.stopIfErrorOccurred(
                CompileState.PARSE,
                compiler.parseFiles(javaFileObjects)
            )
        )
    )
}

private fun KaptContext.initModulesIfNeeded(files: JavacList<JCTree.JCCompilationUnit>): JavacList<JCTree.JCCompilationUnit> {
    if (isJava9OrLater()) {
        val initModulesMethod = compiler.javaClass.getMethod("initModules", JavacList::class.java)

        @Suppress("UNCHECKED_CAST")
        return compiler.stopIfErrorOccurred(
            CompileState.PARSE,
            initModulesMethod.invoke(compiler, files) as JavacList<JCTree.JCCompilationUnit>
        )
    }

    return files
}