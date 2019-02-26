/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.incremental

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.build.DEFAULT_KOTLIN_SOURCE_FILES_EXTENSIONS
import org.jetbrains.kotlin.build.GeneratedFile
import org.jetbrains.kotlin.build.GeneratedJvmClass
import org.jetbrains.kotlin.build.JvmSourceRoot
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.compilerRunner.ArgumentUtils
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.IncrementalCompilation
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.incremental.components.ExpectActualTracker
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.incremental.multiproject.EmptyModulesApiHistory
import org.jetbrains.kotlin.incremental.multiproject.ModulesApiHistory
import org.jetbrains.kotlin.load.java.JavaClassesTracker
import org.jetbrains.kotlin.load.kotlin.header.KotlinClassHeader
import org.jetbrains.kotlin.load.kotlin.incremental.components.IncrementalCompilationComponents
import org.jetbrains.kotlin.metadata.jvm.deserialization.ModuleMapping
import org.jetbrains.kotlin.modules.TargetId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.jvm.JvmClassName
import java.io.Closeable
import java.io.File

fun makeIncrementally(
        cachesDir: File,
        sourceRoots: Iterable<File>,
        args: K2JVMCompilerArguments,
        messageCollector: MessageCollector = MessageCollector.NONE,
        reporter: ICReporter = EmptyICReporter
) {
    val kotlinExtensions = DEFAULT_KOTLIN_SOURCE_FILES_EXTENSIONS
    val allExtensions = kotlinExtensions + "java"
    val rootsWalk = sourceRoots.asSequence().flatMap { it.walk() }
    val files = rootsWalk.filter(File::isFile)
    val sourceFiles = files.filter { it.extension.toLowerCase() in allExtensions }.toList()
    val buildHistoryFile = File(cachesDir, "build-history.bin")

    withIC {
        val compiler = IncrementalJvmCompilerRunner(
            cachesDir,
            sourceRoots.map { JvmSourceRoot(it, null) }.toSet(),
            reporter,
            // Use precise setting in case of non-Gradle build
            usePreciseJavaTracking = true,
            outputFiles = emptyList(),
            buildHistoryFile = buildHistoryFile,
            modulesApiHistory = EmptyModulesApiHistory,
            kotlinSourceFilesExtensions = kotlinExtensions
        )
        compiler.compile(sourceFiles, args, messageCollector, providedChangedFiles = null)
    }
}

object EmptyICReporter : ICReporter {
    override fun report(message: () -> String) {
    }
}

inline fun <R> withIC(enabled: Boolean = true, fn: ()->R): R {
    val isEnabledBackup = IncrementalCompilation.isEnabledForJvm()
    IncrementalCompilation.setIsEnabledForJvm(enabled)

    try {
        return fn()
    }
    finally {
        IncrementalCompilation.setIsEnabledForJvm(isEnabledBackup)
    }
}

class IncrementalJvmCompilerRunner(
    workingDir: File,
    private val javaSourceRoots: Set<JvmSourceRoot>,
    reporter: ICReporter,
    private val usePreciseJavaTracking: Boolean,
    buildHistoryFile: File,
    outputFiles: Collection<File>,
    private val modulesApiHistory: ModulesApiHistory,
    override val kotlinSourceFilesExtensions: List<String> = DEFAULT_KOTLIN_SOURCE_FILES_EXTENSIONS
) : IncrementalCompilerRunner<K2JVMCompilerArguments, IncrementalJvmCachesManager>(
    workingDir,
    "caches-jvm",
    reporter,
    outputFiles = outputFiles,
    buildHistoryFile = buildHistoryFile
) {
    override fun isICEnabled(): Boolean =
            IncrementalCompilation.isEnabledForJvm()

    override fun createCacheManager(args: K2JVMCompilerArguments): IncrementalJvmCachesManager {
        cacheManager = IncrementalJvmCachesManager(cacheDirectory, File(args.destination), reporter)
        return cacheManager
    }

    override fun destinationDir(args: K2JVMCompilerArguments): File =
            args.destinationAsFile

    private val psiFileFactory: PsiFileFactory by lazy {
        val rootDisposable = Disposer.newDisposable()
        val configuration = CompilerConfiguration()
        val environment = KotlinCoreEnvironment.createForProduction(rootDisposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
        val project = environment.project
        PsiFileFactory.getInstance(project)
    }

    private val changedUntrackedJavaClasses = mutableSetOf<ClassId>()

    private lateinit var cacheManager: IncrementalJvmCachesManager

    private var javaFilesProcessor =
            if (!usePreciseJavaTracking)
                ChangedJavaFilesProcessor(reporter) { it.psiFile() }
            else
                null

    override fun calculateSourcesToCompile(
        caches: IncrementalJvmCachesManager,
        changedFiles: ChangedFiles.Known,
        args: K2JVMCompilerArguments
    ): CompilationMode {
        val dirtyFiles = DirtyFilesContainer(caches, reporter, kotlinSourceFilesExtensions)
        initDirtyFiles(dirtyFiles, changedFiles)

        val lastBuildInfo = BuildInfo.read(lastBuildInfoFile) ?: return CompilationMode.Rebuild { "No information on previous build" }
        reporter.report { "Last Kotlin Build info -- $lastBuildInfo" }

        val classpathChanges = getClasspathChanges(args.classpathAsList, changedFiles, lastBuildInfo, modulesApiHistory, reporter)

        @Suppress("UNUSED_VARIABLE") // for sealed when
        val unused = when (classpathChanges) {
            is ChangesEither.Unknown -> return CompilationMode.Rebuild {
                // todo: we can recompile all files incrementally (not cleaning caches), so rebuild won't propagate
                "Could not get classpath's changes${classpathChanges.reason?.let { ": $it" }}"
            }
            is ChangesEither.Known -> {
                dirtyFiles.addByDirtySymbols(classpathChanges.lookupSymbols)
                dirtyFiles.addByDirtyClasses(classpathChanges.fqNames)
            }
        }

        if (!usePreciseJavaTracking) {
            val javaFilesChanges = javaFilesProcessor!!.process(changedFiles)
            val affectedJavaSymbols = when (javaFilesChanges) {
                is ChangesEither.Known -> javaFilesChanges.lookupSymbols
                is ChangesEither.Unknown -> return CompilationMode.Rebuild { "Could not get changes for java files" }
            }
            dirtyFiles.addByDirtySymbols(affectedJavaSymbols)
        } else {
            val changedClasspathFqNames = classpathChanges.fqNames.takeIf { args.shouldKeepTrackOfJavaChanges() } ?: emptySet()
            if (!processChangedJava(changedFiles, caches, changedClasspathFqNames)) {
                return CompilationMode.Rebuild { "Could not get changes for java files" }
            }
        }

        val androidLayoutChanges = processLookupSymbolsForAndroidLayouts(changedFiles)
        val removedClassesChanges = getRemovedClassesChanges(caches, changedFiles)

        dirtyFiles.addByDirtySymbols(androidLayoutChanges)
        dirtyFiles.addByDirtySymbols(removedClassesChanges.dirtyLookupSymbols)
        dirtyFiles.addByDirtyClasses(removedClassesChanges.dirtyClassesFqNames)

        return CompilationMode.Incremental(dirtyFiles)
    }

    /** If we should keep track of all Java source file changes. Changes can be caused by changes in Kotlin/classpath. */
    private fun K2JVMCompilerArguments.shouldKeepTrackOfJavaChanges(): Boolean =
        this.sourcesToReprocessWithAP != null && usePreciseJavaTracking

    private fun processChangedJava(
        changedFiles: ChangedFiles.Known,
        caches: IncrementalJvmCachesManager,
        dirtyClasspathFqNames: Collection<FqName> = emptyList()
    ): Boolean {
        val javaFiles = (changedFiles.modified + changedFiles.removed).filter(File::isJavaFile)

        for (javaFile in javaFiles) {
            if (javaFile.exists()) {
                if (!processChangedJavaFile(javaFile)) return false
            } else if (!caches.platformCache.isTrackedFile(javaFile)){
                // todo: can we do this more optimal?
                reporter.report { "Could not get changed for untracked removed java file $javaFile" }
                return false
            }
        }

        caches.platformCache.markDirty(javaFiles)

        // also mark dirty all java files that are impacted by classpath changes
        for (classpathFqName in dirtyClasspathFqNames) {
            val dependants = caches.platformCache.javaSourcesProtoMap.getDependants(classpathFqName)

            caches.platformCache.markDirty(
                dependants.mapNotNull { caches.platformCache.getSourceFileIfClass(it) }
            )
        }

        return true
    }

    // Returns if we are able to process the specified Java file
    private fun processChangedJavaFile(javaFile: File): Boolean {
        val psiFile = javaFile.psiFile()
        if (psiFile !is PsiJavaFile) {
            reporter.report { "[Precise Java tracking] Expected PsiJavaFile, got ${psiFile?.javaClass}" }
            return false
        }

        for (psiClass in psiFile.classes) {
            val qualifiedName = psiClass.qualifiedName
            if (qualifiedName == null) {
                reporter.report { "[Precise Java tracking] Class with unknown qualified name in $javaFile" }
                return false
            }

            processChangedUntrackedJavaClass(psiClass, ClassId.topLevel(FqName(qualifiedName)))
        }

        return true
    }

    private class IncrementalApt(private val file: File, private val reporter: ICReporter): Closeable {
        private var isIncremental: Boolean = true
        private val setOfFiles = mutableSetOf<File>()
        private var rebuildReason: String? = null

        fun addSource(src: File) {
            if (isIncremental) {
                setOfFiles.add(src)
            }
        }

        fun fullRebuild(reason: String) {
            if (isIncremental) {
                isIncremental = false
                rebuildReason = reason
            }
        }

        override fun close() {
            file.delete()

            if (isIncremental) {
                file.bufferedWriter().use { writer ->
                    setOfFiles.sorted().forEach { writer.appendln(it.absolutePath) }
                }
            }

            rebuildReason?.also {
                reporter.report { "Annotation processing will run on all files. Reason: $rebuildReason" }
            }
        }
    }

    override fun additionalProcessing(
        compilationMode: CompilationMode,
        buildDirtyFqNames: HashSet<FqName>,
        args: K2JVMCompilerArguments) {
        args.sourcesToReprocessWithAP?.let { outputFile ->
            IncrementalApt(File(outputFile), reporter).use {
                if (!usePreciseJavaTracking) {
                    it.fullRebuild("Precise java tracking has to be enabled.")
                } else if (compilationMode !is CompilationMode.Incremental) {
                    it.fullRebuild("Not an incremental run.")
                } else {
                    computeSourcesForIncrementalApt(buildDirtyFqNames, it)
                }
            }
        }
    }

    /**
     * For set of dirty fully-qualified names, this finds all java classes that might be impacted, and that should be analyzed again in
     * order to get updated proto information about them.
     */
    override fun updateAdditionalSourcesState(
        dirtyClassFqNames: Collection<FqName>,
        hasAnyConstantChanged: Boolean,
        services: Services,
        args: K2JVMCompilerArguments) {
        if (!args.shouldKeepTrackOfJavaChanges()) return

        val javaClassesTracker = services.get(JavaClassesTracker::class.java) as? JavaClassesTrackerImpl ?: return

        val javaSourcesInThisRound = javaClassesTracker.javaClassesUpdates.map { it.source }.toSet()
        val javaSourcesToReprocess = mutableSetOf<File>()

        if (hasAnyConstantChanged) {
            // get all java sources not processed in this round
            for (key in cacheManager.platformCache.javaSourcesProtoMap.keys()) {
                cacheManager.platformCache.sourcesByInternalName(key)
                    .filter { !javaSourcesInThisRound.contains(it) }
                    .let {
                        javaSourcesToReprocess.addAll(it)
                    }
            }
        } else {
            // TODO (gavra): handle case when adding a class changes the resolved type. E.g. for some class, type T can resolved to just
            // added top-level class in the same package, because classes from the same package are implicitly in the same scope. See
            // https://docs.oracle.com/javase/specs/jls/se8/html/jls-6.html#jls-6.3 for details.

            fun maybeMarkDirtyFq(dirtyFqName: FqName) {
                cacheManager.platformCache.getSourceFileIfClass(dirtyFqName)?.let { sourceFile ->
                    if (sourceFile.extension == "java" && !javaSourcesInThisRound.contains(sourceFile)) {
                        javaSourcesToReprocess.add(sourceFile)
                    }
                }
            }

            for (dirtyFq in dirtyClassFqNames) {
                maybeMarkDirtyFq(dirtyFq)

                cacheManager.platformCache.javaSourcesProtoMap.getDependants(dirtyFq).forEach { maybeMarkDirtyFq(it) }
            }
        }

        cacheManager.platformCache.markDirty(javaSourcesToReprocess)
    }

    /** Converts a fully qualified name (e.g. com.example.test.Outter.Inner) to JvmClassName (e.g. com/example/tests/Outter$Inner). */
    private fun fqNametoJvm(fq: FqName): JvmClassName? {
        // TODO (gavra): write this in a more performant way
        val sourceFile = cacheManager.platformCache.getSourceFileIfClass(fq) ?: return null

        return cacheManager.platformCache.classesBySources(listOf(sourceFile)).firstOrNull {
            it.fqNameForClassNameWithoutDollars.asString() == fq.asString()
        }
    }

    private fun collectChanges(jvmName: JvmClassName, change: ChangesCollector): Boolean {
        return cacheManager.platformCache.javaSourcesProtoMap[jvmName]?.let {
            change.collectProtoChanges(null, it.toProtoData(), true)
            true
        } ?: cacheManager.platformCache.protoMap[jvmName]?.let {
            change.collectProtoChanges(null, it.toProtoData(jvmName.packageFqName), true)
            true
        } ?: false
    }

    private fun computeSourcesForIncrementalApt(buildDirtyFqNames: HashSet<FqName>, incrementalApt: IncrementalApt) {
        if (buildDirtyFqNames.isEmpty()) return // we are done, no files to reprocess with apts

        val platformCache = cacheManager.platformCache

        val changes = ChangesCollector()
        for (buildDirtyFqName in buildDirtyFqNames) {
            val jvmClassName = fqNametoJvm(buildDirtyFqName)
            if (jvmClassName == null) {
                incrementalApt.fullRebuild("Unable to find internal name for $buildDirtyFqName")
                return
            }

            if (!collectChanges(jvmClassName, changes)) {
                incrementalApt.fullRebuild("Unable to get class data for $jvmClassName")
                return
            }
        }
        // At this point changes contains all API AST elements of changed files (both Kotlin and Java).
        var (dirtySymbols, dirtyFqNames) = changes.getDirtyData(listOf(platformCache), reporter)
        val visitedSymbols = dirtySymbols.toMutableSet()
        val visitedFqNames = dirtyFqNames.toMutableSet()

        while (dirtySymbols.isNotEmpty() || dirtyFqNames.isNotEmpty()) {
            val currentDirtySources = mutableSetOf<File>()
            // TODO (gavra): If a kotlin source defines multiple types (produces multiple stubs), we can process only some of them. Now we process all.
            currentDirtySources.addAll(mapLookupSymbolsToFiles(cacheManager.lookupCache, dirtySymbols, reporter))
            currentDirtySources.addAll(mapClassesFqNamesToFiles(listOf(platformCache), dirtyFqNames, reporter))
            for (source in currentDirtySources) {
                platformCache.sourceToGeneratedStubs[source].forEach { incrementalApt.addSource(it) }
            }

            val javaSources = mutableSetOf<FqName>()
            for (dirtyFqName in dirtyFqNames) {
                val dependants = platformCache.javaSourcesProtoMap.getDependants(dirtyFqName)
                javaSources.addAll(dependants)
            }

            javaSources.forEach {
                val srcFile = platformCache.getSourceFileIfClass(it)
                if (srcFile == null) {
                    incrementalApt.fullRebuild("Unable to load Java sources file $it")
                    return
                }
                currentDirtySources.add(srcFile)
                incrementalApt.addSource(srcFile)
            }

            // compute for the next round dirty symbols and
            val jvmClassNames =
                platformCache.classesBySources(currentDirtySources)
                    .filter { it.internalName != ".${ModuleMapping.MAPPING_FILE_EXT}" }

            val nextChanges = ChangesCollector()
            jvmClassNames.forEach { jvmName ->
                if (!collectChanges(jvmName, nextChanges)) {
                    incrementalApt.fullRebuild("Unable to get class data for $jvmName")
                    return
                }
            }

            val (newSymbols, newFqNames) = nextChanges.getDirtyData(listOf(platformCache), reporter)

            dirtySymbols = newSymbols.filter { visitedSymbols.add(it) }
            dirtyFqNames = newFqNames.filter { visitedFqNames.add(it) }
        }
    }

    private fun File.psiFile(): PsiFile? =
            psiFileFactory.createFileFromText(nameWithoutExtension, JavaLanguage.INSTANCE, readText())

    private fun processChangedUntrackedJavaClass(psiClass: PsiClass, classId: ClassId) {
        if (!cacheManager.platformCache.isJavaClassAlreadyInCache(classId)) {
            changedUntrackedJavaClasses.add(classId)
        }

        for (innerClass in psiClass.innerClasses) {
            val name = innerClass.name ?: continue
            processChangedUntrackedJavaClass(innerClass, classId.createNestedClassId(Name.identifier(name)))
        }
    }

    private fun processLookupSymbolsForAndroidLayouts(changedFiles: ChangedFiles.Known): Collection<LookupSymbol> {
        val result = mutableListOf<LookupSymbol>()
        for (file in changedFiles.modified + changedFiles.removed) {
            if (file.extension.toLowerCase() != "xml") continue
            val layoutName = file.name.substringBeforeLast('.')
            result.add(LookupSymbol(ANDROID_LAYOUT_CONTENT_LOOKUP_NAME, layoutName))
        }

        return result
    }

    override fun preBuildHook(args: K2JVMCompilerArguments, compilationMode: CompilationMode) {
        if (compilationMode is CompilationMode.Incremental) {
            val destinationDir = args.destinationAsFile
            destinationDir.mkdirs()
            args.classpathAsList = listOf(destinationDir) + args.classpathAsList
        } else if (args.shouldKeepTrackOfJavaChanges()) {
            val l = System.currentTimeMillis()
            javaSourceRoots.map { sourceRoot ->
                sourceRoot.file.walk().filter { it.extension == "java" }.forEach { javaFile ->
                    processChangedJavaFile(javaFile)
                }
            }

            reporter.reportImportant("Pre build hook took to execute: ${System.currentTimeMillis() - l}")
        }
    }

    override fun postCompilationHook(exitCode: ExitCode) {}

    override fun updateCaches(
            services: Services,
            caches: IncrementalJvmCachesManager,
            generatedFiles: List<GeneratedFile>,
            changesCollector: ChangesCollector
    ) {
        updateIncrementalCache(
                generatedFiles, caches.platformCache, changesCollector,
                services[JavaClassesTracker::class.java] as? JavaClassesTrackerImpl,
                reporter
        )
    }

    override fun runWithNoDirtyKotlinSources(caches: IncrementalJvmCachesManager): Boolean =
            caches.platformCache.getObsoleteJavaClasses().isNotEmpty() || changedUntrackedJavaClasses.isNotEmpty()

    override fun additionalDirtyFiles(
            caches: IncrementalJvmCachesManager,
            generatedFiles: List<GeneratedFile>
    ): Iterable<File> {
        val cache = caches.platformCache
        val result = HashSet<File>()

        fun partsByFacadeName(facadeInternalName: String): List<File> {
            val parts = cache.getStableMultifileFacadeParts(facadeInternalName) ?: emptyList()
            return parts.flatMap { cache.sourcesByInternalName(it) }
        }

        for (generatedFile in generatedFiles) {
            if (generatedFile !is GeneratedJvmClass) continue

            val outputClass = generatedFile.outputClass

            when (outputClass.classHeader.kind) {
                KotlinClassHeader.Kind.CLASS -> {
                    val fqName = outputClass.className.fqNameForClassNameWithoutDollars
                    val cachedSourceFile = cache.getSourceFileIfClass(fqName)

                    if (cachedSourceFile != null) {
                        // todo: seems useless, remove?
                        result.add(cachedSourceFile)
                    }
                }
                // todo: more optimal is to check if public API or parts list changed
                KotlinClassHeader.Kind.MULTIFILE_CLASS -> {
                    result.addAll(partsByFacadeName(outputClass.className.internalName))
                }
                KotlinClassHeader.Kind.MULTIFILE_CLASS_PART -> {
                    result.addAll(partsByFacadeName(outputClass.classHeader.multifileClassName!!))
                }
            }
        }

        return result
    }

    override fun additionalDirtyLookupSymbols(): Iterable<LookupSymbol> =
            javaFilesProcessor?.allChangedSymbols ?: emptyList()

    override fun makeServices(
            args: K2JVMCompilerArguments,
            lookupTracker: LookupTracker,
            expectActualTracker: ExpectActualTracker,
            caches: IncrementalJvmCachesManager,
            compilationMode: CompilationMode
    ): Services.Builder =
        super.makeServices(args, lookupTracker, expectActualTracker, caches, compilationMode).apply {
            val targetId = TargetId(args.moduleName!!, "java-production")
            val targetToCache = mapOf(targetId to caches.platformCache)
            val incrementalComponents = IncrementalCompilationComponentsImpl(targetToCache)
            register(IncrementalCompilationComponents::class.java, incrementalComponents)
            if (usePreciseJavaTracking) {
                val changesTracker = JavaClassesTrackerImpl(caches.platformCache, changedUntrackedJavaClasses.toSet(), reporter)
                changedUntrackedJavaClasses.clear()
                register(JavaClassesTracker::class.java, changesTracker)
            }
        }

    override fun runCompiler(
            sourcesToCompile: Set<File>,
            args: K2JVMCompilerArguments,
            caches: IncrementalJvmCachesManager,
            services: Services,
            messageCollector: MessageCollector
    ): ExitCode {
        val compiler = K2JVMCompiler()
        val outputDir = args.destinationAsFile
        val classpath = args.classpathAsList
        val moduleFile = makeModuleFile(
            args.moduleName!!,
            isTest = false,
            outputDir = outputDir,
            sourcesToCompile = sourcesToCompile,
            commonSources = args.commonSources?.map(::File).orEmpty(),
            javaSourceRoots = javaSourceRoots,
            classpath = classpath,
            friendDirs = listOf()
        )
        val destination = args.destination
        args.destination = null
        args.buildFile = moduleFile.absolutePath

        try {
            reporter.report { "compiling with args: ${ArgumentUtils.convertArgumentsToStringList(args)}" }
            reporter.report { "compiling with classpath: ${classpath.toList().sorted().joinToString()}" }
            val exitCode = compiler.exec(messageCollector, services, args)
            reporter.reportCompileIteration(sourcesToCompile, exitCode)
            return exitCode
        }
        finally {
            args.destination = destination
            moduleFile.delete()
        }
    }
}

var K2JVMCompilerArguments.destinationAsFile: File
        get() = File(destination)
        set(value) { destination = value.path }

var K2JVMCompilerArguments.classpathAsList: List<File>
    get() = classpath.orEmpty().split(File.pathSeparator).map(::File)
    set(value) { classpath = value.joinToString(separator = File.pathSeparator, transform = { it.path }) }
