/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

import com.intellij.psi.PsiJavaFile
import com.intellij.util.io.DataExternalizer
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.findClassAcrossModuleDependencies
import org.jetbrains.kotlin.load.java.JavaClassesTracker
import org.jetbrains.kotlin.load.java.descriptors.JavaClassDescriptor
import org.jetbrains.kotlin.load.java.lazy.descriptors.LazyJavaClassDescriptor
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.builtins.BuiltInsProtoBuf
import org.jetbrains.kotlin.metadata.deserialization.Flags
import org.jetbrains.kotlin.metadata.deserialization.NameResolverImpl
import org.jetbrains.kotlin.metadata.deserialization.getExtensionOrNull
import org.jetbrains.kotlin.metadata.java.JavaClassProtoBuf
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.protobuf.ExtensionRegistryLite
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.descriptorUtil.classId
import org.jetbrains.kotlin.resolve.jvm.JvmClassName
import org.jetbrains.kotlin.resolve.source.PsiSourceElement
import org.jetbrains.kotlin.serialization.DescriptorSerializer
import org.jetbrains.kotlin.serialization.deserialization.builtins.BuiltInSerializerProtocol
import org.jetbrains.kotlin.serialization.deserialization.getClassId
import org.jetbrains.kotlin.util.PerformanceCounter
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.kotlin.utils.sure
import java.io.DataInput
import java.io.DataOutput
import java.io.File

val CONVERTING_JAVA_CLASSES_TO_PROTO = PerformanceCounter.create("Converting Java sources to proto")

class JavaClassesTrackerImpl(
        private val cache: IncrementalJvmCache,
        private val untrackedJavaClasses: Set<ClassId>,
        private val reporter: ICReporter
) : JavaClassesTracker {
    private val classToSourceSerialized: MutableMap<ClassId, SerializedJavaClassWithSource> = hashMapOf()

    val javaClassesUpdates: Collection<SerializedJavaClassWithSource>
        get() = classToSourceSerialized.values

    private val classDescriptors: MutableList<JavaClassDescriptor> = mutableListOf()

    override fun reportClass(classDescriptor: JavaClassDescriptor) {
        val classId = classDescriptor.classId!!
        if (!cache.isJavaClassToTrack(classId) || classDescriptor.javaSourceFile == null) return

        classDescriptors.add(classDescriptor)
    }

    override fun onCompletedAnalysis(module: ModuleDescriptor) {
        val l = System.currentTimeMillis()
        for (classId in cache.getObsoleteJavaClasses() + untrackedJavaClasses) {
            // Just force the loading obsolete classes
            // We assume here that whenever an LazyJavaClassDescriptor instances is created
            // it's being passed to JavaClassesTracker::reportClass
            module.findClassAcrossModuleDependencies(classId)
        }

        for (classDescriptor in classDescriptors.toList()) {
            val classId = classDescriptor.classId!!
            if (cache.isJavaClassAlreadyInCache(classId) || classId in untrackedJavaClasses || classDescriptor.wasContentRequested()) {
                assert(classId !in classToSourceSerialized) {
                    "Duplicated JavaClassDescriptor $classId reported to IC"
                }
                classToSourceSerialized[classId] = CONVERTING_JAVA_CLASSES_TO_PROTO.time {
                    classDescriptor.convertToProto()
                }
            }
        }

        reporter.reportImportant("Time took to process java sources: ${System.currentTimeMillis() - l}")
    }

    private fun JavaClassDescriptor.wasContentRequested() =
            this.safeAs<LazyJavaClassDescriptor>()?.wasScopeContentRequested() != false
}

private val JavaClassDescriptor.javaSourceFile: File?
    get() = source.safeAs<PsiSourceElement>()
            ?.psi?.containingFile?.takeIf { it is PsiJavaFile }
            ?.virtualFile?.path?.let(::File)

fun JavaClassDescriptor.convertToProto(): SerializedJavaClassWithSource {
    val file = javaSourceFile.sure { "convertToProto should only be called for source based classes" }

    val extension = JavaClassesSerializerExtension()
    val classProto = try {
        DescriptorSerializer.create(this, extension, null).classProto(this).build()
    } catch (e: Exception) {
        throw IllegalStateException(
            "Error during writing proto for descriptor: ${DescriptorRenderer.DEBUG_TEXT.render(this)}\n" +
                    "Source file: $file",
            e
        )
    }

    val (stringTable, qualifiedNameTable) = extension.stringTable.buildProto()

    return SerializedJavaClassWithSource(file, SerializedJavaClass(classProto, stringTable, qualifiedNameTable))
}

class SerializedJavaClass(
        val proto: ProtoBuf.Class,
        val stringTable: ProtoBuf.StringTable,
        val qualifiedNameTable: ProtoBuf.QualifiedNameTable
) {
    val classId: ClassId
        get() = NameResolverImpl(stringTable, qualifiedNameTable).getClassId(proto.fqName)

    /**
     * Get all constants from this class. This returns a map which keys are the field names, and values are the field constant values.
     * An empty map means there are no constants in the class.
     */
    fun getAllConstants(): Map<String, Any> {
        val constMap = mutableMapOf<String, Any>()

        val nameResolver = NameResolverImpl(stringTable, qualifiedNameTable)
        for(property in proto.propertyList) {
            if (!Flags.HAS_CONSTANT.get(property.flags)) continue

            val value = property.getExtensionOrNull(BuiltInSerializerProtocol.compileTimeValue) ?: continue
            val result: Any? = when (value.type) {
                ProtoBuf.Annotation.Argument.Value.Type.INT -> value.intValue.toInt()
                ProtoBuf.Annotation.Argument.Value.Type.SHORT -> value.intValue.toInt()
                ProtoBuf.Annotation.Argument.Value.Type.BOOLEAN -> value.intValue.toInt()
                ProtoBuf.Annotation.Argument.Value.Type.CHAR -> value.intValue.toInt()
                ProtoBuf.Annotation.Argument.Value.Type.LONG -> value.intValue
                ProtoBuf.Annotation.Argument.Value.Type.FLOAT -> value.floatValue
                ProtoBuf.Annotation.Argument.Value.Type.DOUBLE -> value.doubleValue
                ProtoBuf.Annotation.Argument.Value.Type.STRING -> nameResolver.getString(value.stringValue)
                else -> null
            }

            result?.let {
                constMap.put(nameResolver.getString(property.name), it)
            }
        }
        return constMap
    }

    /** Returns all types mentioned in the serialized java class. */
    fun getAllMentionedTypes(): Set<FqName> {
        val mentionedTypes = mutableSetOf<FqName>()
        val nameResolver = NameResolverImpl(stringTable, qualifiedNameTable)

        for (i in 0 until qualifiedNameTable.qualifiedNameCount) {
            val qualifiedName: ProtoBuf.QualifiedNameTable.QualifiedName = qualifiedNameTable.getQualifiedName(i)
            if (qualifiedName.kind != ProtoBuf.QualifiedNameTable.QualifiedName.Kind.PACKAGE) {

                val name = nameResolver.getQualifiedClassName(i)
                if (name == "kotlin.Any") continue

                mentionedTypes.add(JvmClassName.byInternalName(name).fqNameForClassNameWithoutDollars)
            }
        }
        return mentionedTypes
    }
}

data class SerializedJavaClassWithSource(
        val source: File,
        val proto: SerializedJavaClass
)

fun SerializedJavaClass.toProtoData() = ClassProtoData(proto, NameResolverImpl(stringTable, qualifiedNameTable))

val JAVA_CLASS_PROTOBUF_REGISTRY =
        ExtensionRegistryLite.newInstance()
                .also(JavaClassProtoBuf::registerAllExtensions)
                // Built-ins extensions are used for annotations' serialization
                .also(BuiltInsProtoBuf::registerAllExtensions)

object JavaClassProtoMapValueExternalizer : DataExternalizer<SerializedJavaClass> {
    override fun save(output: DataOutput, value: SerializedJavaClass) {
        output.writeBytesWithSize(value.proto.toByteArray())
        output.writeBytesWithSize(value.stringTable.toByteArray())
        output.writeBytesWithSize(value.qualifiedNameTable.toByteArray())
    }

    private fun DataOutput.writeBytesWithSize(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInput.readBytesWithSize(): ByteArray {
        val bytesLength = readInt()
        return ByteArray(bytesLength).also {
            readFully(it, 0, bytesLength)
        }
    }

    override fun read(input: DataInput): SerializedJavaClass {
        val proto = ProtoBuf.Class.parseFrom(input.readBytesWithSize(), JAVA_CLASS_PROTOBUF_REGISTRY)
        val stringTable = ProtoBuf.StringTable.parseFrom(input.readBytesWithSize(), JAVA_CLASS_PROTOBUF_REGISTRY)
        val qualifiedNameTable = ProtoBuf.QualifiedNameTable.parseFrom(input.readBytesWithSize(), JAVA_CLASS_PROTOBUF_REGISTRY)

        return SerializedJavaClass(proto, stringTable, qualifiedNameTable)
    }
}
