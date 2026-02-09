package org.bezsahara.customindy.impl.asm

import org.bezsahara.customindy.annotations.StableThread
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodType

internal data class GeneratedStableClass(
    val bytes: ByteArray,
    val invokeType: MethodType,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GeneratedStableClass

        if (!bytes.contentEquals(other.bytes)) return false
        if (invokeType != other.invokeType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + invokeType.hashCode()
        return result
    }
}

internal class StableAsmGenerator(
    private val internalName: String,
    private val callType: MethodType,
    private val stableThread: StableThread,
    private val isPure: Boolean,
    private val useCheckerHandle: Boolean,
    private val defaultMask: Long,
    private val defaultParamCount: Int,
    private val loader: ClassLoader,
) {
    fun generate(): GeneratedStableClass {
        val argClasses = callType.parameterArray()
        val argTypes = argClasses.map { Type.getType(it) }
        val returnType = Type.getType(callType.returnType())
        val hasResult = returnType.sort != Type.VOID
        val withChecker = !isPure && useCheckerHandle

        val invokeParamTypes = ArrayList<Type>()
        val invokeParamClasses = ArrayList<Class<*>>()
        invokeParamTypes += Type.getType(MethodHandle::class.java)
        invokeParamClasses += MethodHandle::class.java
        if (withChecker) {
            invokeParamTypes += Type.getType(MethodHandle::class.java)
            invokeParamClasses += MethodHandle::class.java
        }
        for (clazz in argClasses) {
            invokeParamTypes += Type.getType(clazz)
            invokeParamClasses += clazz
        }
        val invokeDesc = Type.getMethodDescriptor(returnType, *invokeParamTypes.toTypedArray())
        val invokeType = MethodType.methodType(callType.returnType(), invokeParamClasses.toTypedArray())

        val cw = LoaderAwareClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS, loader)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null)

        cw.visitField(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_VOLATILE,
            "stateVersion",
            "I",
            null,
            null
        ).visitEnd()

        if (stableThread == StableThread.SYNCHRONIZED) {
            cw.visitField(
                Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                "LOCK",
                "Ljava/lang/Object;",
                null,
                null
            ).visitEnd()
        }

        val argFieldNames = ArrayList<String>()
        if (!isPure) {
            for (i in argTypes.indices) {
                val name = "arg$i"
                argFieldNames += name
                cw.visitField(
                    Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC,
                    name,
                    argTypes[i].descriptor,
                    null,
                    null
                ).visitEnd()
            }
        }

        if (hasResult) {
            cw.visitField(
                Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC,
                "cachedResult",
                returnType.descriptor,
                null,
                null
            ).visitEnd()
        }

        generateConstructor(cw)
        if (stableThread == StableThread.SYNCHRONIZED) {
            generateClinit(cw)
        }
        generateInvoke(
            cw,
            invokeDesc,
            argTypes,
            returnType,
            hasResult,
            withChecker,
            argFieldNames
        )

        cw.visitEnd()
        return GeneratedStableClass(cw.toByteArray(), invokeType)
    }

    private fun generateConstructor(cw: ClassWriter) {
        val mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun generateClinit(cw: ClassWriter) {
        val mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
        mv.visitCode()
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/Object")
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        mv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "LOCK", "Ljava/lang/Object;")
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun generateInvoke(
        cw: ClassWriter,
        invokeDesc: String,
        argTypes: List<Type>,
        returnType: Type,
        hasResult: Boolean,
        withChecker: Boolean,
        argFieldNames: List<String>,
    ) {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "invoke", invokeDesc, null, null)
        mv.visitCode()

        var index = 0
        val actualIndex = index
        index += 1
        val checkerIndex = if (withChecker) index.also { index += 1 } else -1
        val argIndexes = IntArray(argTypes.size)
        for (i in argTypes.indices) {
            argIndexes[i] = index
            index += argTypes[i].size
        }
        var nextLocal = index
        val oldArgIndexes = if (!isPure) {
            IntArray(argTypes.size).also { locals ->
                for (i in argTypes.indices) {
                    locals[i] = nextLocal
                    nextLocal += argTypes[i].size
                }
            }
        } else {
            IntArray(0)
        }
        val v1Index = nextLocal++
        val v2Index = nextLocal++
        val lockIndex = if (stableThread == StableThread.SYNCHRONIZED) nextLocal++ else -1
        val resultIndex = if (hasResult) {
            val r = nextLocal
            nextLocal += returnType.size
            r
        } else {
            -1
        }

        val slowPath = Label()
        val fastReturn = Label()

        // v1 = stateVersion
        mv.visitFieldInsn(Opcodes.GETSTATIC, internalName, "stateVersion", "I")
        mv.visitVarInsn(Opcodes.ISTORE, v1Index)
        mv.visitVarInsn(Opcodes.ILOAD, v1Index)
        mv.visitJumpInsn(Opcodes.IFEQ, slowPath)
        mv.visitVarInsn(Opcodes.ILOAD, v1Index)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IAND)
        mv.visitJumpInsn(Opcodes.IFNE, slowPath)

        if (!isPure) {
            for (i in argTypes.indices) {
                val type = argTypes[i]
                mv.visitFieldInsn(Opcodes.GETSTATIC, internalName, argFieldNames[i], type.descriptor)
                mv.visitVarInsn(storeOpcode(type), oldArgIndexes[i])
            }
        }

        if (hasResult) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, internalName, "cachedResult", returnType.descriptor)
            mv.visitVarInsn(storeOpcode(returnType), resultIndex)
        }

        mv.visitFieldInsn(Opcodes.GETSTATIC, internalName, "stateVersion", "I")
        mv.visitVarInsn(Opcodes.ISTORE, v2Index)
        mv.visitVarInsn(Opcodes.ILOAD, v2Index)
        mv.visitVarInsn(Opcodes.ILOAD, v1Index)
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, slowPath)

        if (!isPure) {
            if (withChecker) {
                emitCheckerCallWithLocals(
                    mv,
                    argTypes,
                    oldArgIndexes,
                    argIndexes,
                    checkerIndex,
                    slowPath
                )
            } else {
                emitSelfCheckWithLocals(
                    mv,
                    argTypes,
                    oldArgIndexes,
                    argIndexes,
                    slowPath
                )
            }
        }

        if (hasResult) {
            mv.visitVarInsn(loadOpcode(returnType), resultIndex)
        }
        mv.visitJumpInsn(Opcodes.GOTO, fastReturn)

        mv.visitLabel(slowPath)

        if (stableThread == StableThread.SYNCHRONIZED) {
            generateSynchronizedBlock(
                mv,
                argTypes,
                argFieldNames,
                argIndexes,
                returnType,
                hasResult,
                actualIndex,
                checkerIndex,
                v2Index,
                lockIndex,
                resultIndex,
                withChecker
            )
        } else {
            generateUnsynchronizedBlock(
                mv,
                argTypes,
                argFieldNames,
                argIndexes,
                returnType,
                hasResult,
                actualIndex,
                checkerIndex,
                v2Index,
                resultIndex,
                withChecker
            )
        }

        mv.visitLabel(fastReturn)
        if (hasResult) {
            mv.visitInsn(returnOpcode(returnType))
        } else {
            mv.visitInsn(Opcodes.RETURN)
        }
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun generateUnsynchronizedBlock(
        mv: MethodVisitor,
        argTypes: List<Type>,
        argFieldNames: List<String>,
        argIndexes: IntArray,
        returnType: Type,
        hasResult: Boolean,
        actualIndex: Int,
        checkerIndex: Int,
        v2Index: Int,
        resultIndex: Int,
        withChecker: Boolean,
    ) {
        val compute = Label()
        val done = Label()

        mv.visitFieldInsn(Opcodes.GETSTATIC, internalName, "stateVersion", "I")
        mv.visitVarInsn(Opcodes.ISTORE, v2Index)
        mv.visitVarInsn(Opcodes.ILOAD, v2Index)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IADD)
        mv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "stateVersion", "I")

        emitActualCall(mv, argTypes, argIndexes, actualIndex, returnType)
        if (hasResult) {
            mv.visitVarInsn(storeOpcode(returnType), resultIndex)
        }

        if (!isPure) {
            storeArgs(mv, argTypes, argFieldNames, argIndexes)
        }
        if (hasResult) {
            mv.visitVarInsn(loadOpcode(returnType), resultIndex)
            mv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "cachedResult", returnType.descriptor)
        }

        mv.visitVarInsn(Opcodes.ILOAD, v2Index)
        mv.visitInsn(Opcodes.ICONST_2)
        mv.visitInsn(Opcodes.IADD)
        mv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "stateVersion", "I")

        if (hasResult) {
            mv.visitVarInsn(loadOpcode(returnType), resultIndex)
            mv.visitJumpInsn(Opcodes.GOTO, done)
        }
        mv.visitLabel(done)
    }

    private fun generateSynchronizedBlock(
        mv: MethodVisitor,
        argTypes: List<Type>,
        argFieldNames: List<String>,
        argIndexes: IntArray,
        returnType: Type,
        hasResult: Boolean,
        actualIndex: Int,
        checkerIndex: Int,
        v2Index: Int,
        lockIndex: Int,
        resultIndex: Int,
        withChecker: Boolean,
    ) {
        val tryStart = Label()
        val tryEnd = Label()
        val handler = Label()
        val compute = Label()
        val syncReturn = Label()

        mv.visitFieldInsn(Opcodes.GETSTATIC, internalName, "LOCK", "Ljava/lang/Object;")
        mv.visitVarInsn(Opcodes.ASTORE, lockIndex)
        mv.visitVarInsn(Opcodes.ALOAD, lockIndex)
        mv.visitInsn(Opcodes.MONITORENTER)

        mv.visitLabel(tryStart)

        mv.visitFieldInsn(Opcodes.GETSTATIC, internalName, "stateVersion", "I")
        mv.visitVarInsn(Opcodes.ISTORE, v2Index)
        mv.visitVarInsn(Opcodes.ILOAD, v2Index)
        mv.visitJumpInsn(Opcodes.IFEQ, compute)
        mv.visitVarInsn(Opcodes.ILOAD, v2Index)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IAND)
        mv.visitJumpInsn(Opcodes.IFNE, compute)

        if (!isPure) {
            if (withChecker) {
                emitCheckerCall(
                    mv,
                    argTypes,
                    argFieldNames,
                    argIndexes,
                    checkerIndex,
                    compute
                )
            } else {
                emitSelfCheck(
                    mv,
                    argTypes,
                    argFieldNames,
                    argIndexes,
                    compute
                )
            }
        }

        if (hasResult) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, internalName, "cachedResult", returnType.descriptor)
            mv.visitVarInsn(storeOpcode(returnType), resultIndex)
        }
        mv.visitJumpInsn(Opcodes.GOTO, syncReturn)

        mv.visitLabel(compute)
        mv.visitVarInsn(Opcodes.ILOAD, v2Index)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IADD)
        mv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "stateVersion", "I")

        emitActualCall(mv, argTypes, argIndexes, actualIndex, returnType)
        if (hasResult) {
            mv.visitVarInsn(storeOpcode(returnType), resultIndex)
        }

        if (!isPure) {
            storeArgs(mv, argTypes, argFieldNames, argIndexes)
        }
        if (hasResult) {
            mv.visitVarInsn(loadOpcode(returnType), resultIndex)
            mv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "cachedResult", returnType.descriptor)
        }

        mv.visitVarInsn(Opcodes.ILOAD, v2Index)
        mv.visitInsn(Opcodes.ICONST_2)
        mv.visitInsn(Opcodes.IADD)
        mv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "stateVersion", "I")

        mv.visitJumpInsn(Opcodes.GOTO, syncReturn)

        mv.visitLabel(tryEnd)

        mv.visitLabel(syncReturn)
        mv.visitVarInsn(Opcodes.ALOAD, lockIndex)
        mv.visitInsn(Opcodes.MONITOREXIT)
        if (hasResult) {
            mv.visitVarInsn(loadOpcode(returnType), resultIndex)
            mv.visitInsn(returnOpcode(returnType))
        } else {
            mv.visitInsn(Opcodes.RETURN)
        }

        mv.visitLabel(handler)
        mv.visitVarInsn(Opcodes.ASTORE, v2Index)
        mv.visitVarInsn(Opcodes.ALOAD, lockIndex)
        mv.visitInsn(Opcodes.MONITOREXIT)
        mv.visitVarInsn(Opcodes.ALOAD, v2Index)
        mv.visitInsn(Opcodes.ATHROW)

        mv.visitTryCatchBlock(tryStart, tryEnd, handler, null)
    }

    private fun emitActualCall(
        mv: MethodVisitor,
        argTypes: List<Type>,
        argIndexes: IntArray,
        actualIndex: Int,
        returnType: Type,
    ) {
        val desc = Type.getMethodDescriptor(returnType, *argTypes.toTypedArray())
        mv.visitVarInsn(Opcodes.ALOAD, actualIndex)
        for (i in argTypes.indices) {
            mv.visitVarInsn(loadOpcode(argTypes[i]), argIndexes[i])
        }
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/invoke/MethodHandle",
            "invokeExact",
            desc,
            false
        )
    }

    private fun emitCheckerCall(
        mv: MethodVisitor,
        argTypes: List<Type>,
        argFieldNames: List<String>,
        argIndexes: IntArray,
        checkerIndex: Int,
        mismatch: Label,
    ) {
        val params = ArrayList<Type>()
        for (type in argTypes) {
            params += type
            params += type
        }
        val desc = Type.getMethodDescriptor(Type.BOOLEAN_TYPE, *params.toTypedArray())
        mv.visitVarInsn(Opcodes.ALOAD, checkerIndex)
        for (i in argTypes.indices) {
            val type = argTypes[i]
            mv.visitFieldInsn(Opcodes.GETSTATIC, internalName, argFieldNames[i], type.descriptor)
            mv.visitVarInsn(loadOpcode(type), argIndexes[i])
        }
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/invoke/MethodHandle",
            "invokeExact",
            desc,
            false
        )
        mv.visitJumpInsn(Opcodes.IFNE, mismatch)
    }

    private fun emitCheckerCallWithLocals(
        mv: MethodVisitor,
        argTypes: List<Type>,
        oldArgIndexes: IntArray,
        argIndexes: IntArray,
        checkerIndex: Int,
        mismatch: Label,
    ) {
        val params = ArrayList<Type>()
        for (type in argTypes) {
            params += type
            params += type
        }
        val desc = Type.getMethodDescriptor(Type.BOOLEAN_TYPE, *params.toTypedArray())
        mv.visitVarInsn(Opcodes.ALOAD, checkerIndex)
        for (i in argTypes.indices) {
            val type = argTypes[i]
            mv.visitVarInsn(loadOpcode(type), oldArgIndexes[i])
            mv.visitVarInsn(loadOpcode(type), argIndexes[i])
        }
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/invoke/MethodHandle",
            "invokeExact",
            desc,
            false
        )
        mv.visitJumpInsn(Opcodes.IFNE, mismatch)
    }

    private fun emitSelfCheck(
        mv: MethodVisitor,
        argTypes: List<Type>,
        argFieldNames: List<String>,
        argIndexes: IntArray,
        mismatch: Label,
    ) {
        for (i in argTypes.indices) {
            if (!shouldCheckArg(i)) continue
            val type = argTypes[i]
            mv.visitFieldInsn(Opcodes.GETSTATIC, internalName, argFieldNames[i], type.descriptor)
            mv.visitVarInsn(loadOpcode(type), argIndexes[i])
            when {
                type.sort == Type.ARRAY -> {
                    val desc = if (isPrimitiveArray(type)) {
                        Type.getMethodDescriptor(Type.BOOLEAN_TYPE, type, type)
                    } else {
                        "([Ljava/lang/Object;[Ljava/lang/Object;)Z"
                    }
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Arrays", "equals", desc, false)
                    mv.visitJumpInsn(Opcodes.IFEQ, mismatch)
                }
                type.sort == Type.OBJECT -> {
                    mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "java/util/Objects",
                        "equals",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                        false
                    )
                    mv.visitJumpInsn(Opcodes.IFEQ, mismatch)
                }
                else -> {
                    when (type.sort) {
                        Type.LONG -> {
                            mv.visitInsn(Opcodes.LCMP)
                            mv.visitJumpInsn(Opcodes.IFNE, mismatch)
                        }
                        Type.FLOAT -> {
                            mv.visitInsn(Opcodes.FCMPL)
                            mv.visitJumpInsn(Opcodes.IFNE, mismatch)
                        }
                        Type.DOUBLE -> {
                            mv.visitInsn(Opcodes.DCMPL)
                            mv.visitJumpInsn(Opcodes.IFNE, mismatch)
                        }
                        else -> mv.visitJumpInsn(Opcodes.IF_ICMPNE, mismatch)
                    }
                }
            }
        }
    }

    private fun emitSelfCheckWithLocals(
        mv: MethodVisitor,
        argTypes: List<Type>,
        oldArgIndexes: IntArray,
        argIndexes: IntArray,
        mismatch: Label,
    ) {
        for (i in argTypes.indices) {
            if (!shouldCheckArg(i)) continue
            val type = argTypes[i]
            mv.visitVarInsn(loadOpcode(type), oldArgIndexes[i])
            mv.visitVarInsn(loadOpcode(type), argIndexes[i])
            when {
                type.sort == Type.ARRAY -> {
                    val desc = if (isPrimitiveArray(type)) {
                        Type.getMethodDescriptor(Type.BOOLEAN_TYPE, type, type)
                    } else {
                        "([Ljava/lang/Object;[Ljava/lang/Object;)Z"
                    }
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Arrays", "equals", desc, false)
                    mv.visitJumpInsn(Opcodes.IFEQ, mismatch)
                }
                type.sort == Type.OBJECT -> {
                    mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "java/util/Objects",
                        "equals",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                        false
                    )
                    mv.visitJumpInsn(Opcodes.IFEQ, mismatch)
                }
                else -> {
                    when (type.sort) {
                        Type.LONG -> {
                            mv.visitInsn(Opcodes.LCMP)
                            mv.visitJumpInsn(Opcodes.IFNE, mismatch)
                        }
                        Type.FLOAT -> {
                            mv.visitInsn(Opcodes.FCMPL)
                            mv.visitJumpInsn(Opcodes.IFNE, mismatch)
                        }
                        Type.DOUBLE -> {
                            mv.visitInsn(Opcodes.DCMPL)
                            mv.visitJumpInsn(Opcodes.IFNE, mismatch)
                        }
                        else -> mv.visitJumpInsn(Opcodes.IF_ICMPNE, mismatch)
                    }
                }
            }
        }
    }

    private fun storeArgs(
        mv: MethodVisitor,
        argTypes: List<Type>,
        argFieldNames: List<String>,
        argIndexes: IntArray,
    ) {
        for (i in argTypes.indices) {
            val type = argTypes[i]
            mv.visitVarInsn(loadOpcode(type), argIndexes[i])
            mv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, argFieldNames[i], type.descriptor)
        }
    }

    private fun shouldCheckArg(index: Int): Boolean {
        if (isPure) return false
        if (useCheckerHandle) return true
        if (index >= defaultParamCount) return false
        if (index >= 64) return true
        return ((defaultMask ushr index) and 1L) == 0L
    }

    private fun isPrimitiveArray(type: Type): Boolean {
        if (type.sort != Type.ARRAY) return false
        val elem = type.elementType
        return elem.sort != Type.OBJECT && elem.sort != Type.ARRAY
    }



    private fun storeOpcode(type: Type): Int = when (type.sort) {
        Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.ISTORE
        Type.LONG -> Opcodes.LSTORE
        Type.FLOAT -> Opcodes.FSTORE
        Type.DOUBLE -> Opcodes.DSTORE
        else -> Opcodes.ASTORE
    }


}

internal fun loadOpcode(type: Type): Int = when (type.sort) {
    Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.ILOAD
    Type.LONG -> Opcodes.LLOAD
    Type.FLOAT -> Opcodes.FLOAD
    Type.DOUBLE -> Opcodes.DLOAD
    else -> Opcodes.ALOAD
}

internal fun returnOpcode(type: Type): Int = when (type.sort) {
    Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.IRETURN
    Type.LONG -> Opcodes.LRETURN
    Type.FLOAT -> Opcodes.FRETURN
    Type.DOUBLE -> Opcodes.DRETURN
    else -> Opcodes.ARETURN
}

internal class LoaderAwareClassWriter(
    flags: Int,
    private val loader: ClassLoader,
) : ClassWriter(flags) {
    override fun getCommonSuperClass(type1: String, type2: String): String {
        return try {
            val c1 = Class.forName(type1.replace('/', '.'), false, loader)
            val c2 = Class.forName(type2.replace('/', '.'), false, loader)
            if (c1.isAssignableFrom(c2)) {
                type1
            } else if (c2.isAssignableFrom(c1)) {
                type2
            } else {
                "java/lang/Object"
            }
        } catch (_: Throwable) {
            "java/lang/Object"
        }
    }
}
