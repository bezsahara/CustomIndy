package org.bezsahara.customindy.impl.asm

import com.sun.tools.javac.tree.TreeInfo.args
import org.bezsahara.customindy.annotations.StableThread
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.invoke.MutableCallSite

internal class PureAsm(
    private val classLoader: ClassLoader,
    private val methodType: MethodType,
    private val name: String,
    private val stableThread: StableThread
) {
    private val classWriter = LoaderAwareClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS, classLoader)
    private val argClasses = methodType.parameterArray()
    private val returnType = Type.getType(methodType.returnType())
    private val isSync = stableThread == StableThread.SYNCHRONIZED
    private val returnTypeDesc = returnType.descriptor

    fun generate(): GeneratedStableClass {
        classWriter.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER or Opcodes.ACC_FINAL,
            name,
            null,
            "java/lang/Object",
            null
        )

        classWriter.visitField(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC,
            "cache",
            returnType.descriptor,
            null,
            null
        ).visitEnd()

        classWriter.visitField(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC,
            "state",
            "Z",
            null,
            null
        ).visitEnd()

        if (isSync) {
            classWriter.visitField(
                Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                "LOCK",
                "Ljava/lang/Object;",
                null,
                null
            ).visitEnd()
        }
        generateConstructor()

        if (isSync) {
            generateClinit()
        }

        val params = generateFun()

        classWriter.visitEnd()

        return GeneratedStableClass(classWriter.toByteArray(), MethodType.methodType(methodType.returnType(), params))
    }

    private fun generateFun(): Array<Class<*>> {
        @Suppress("UNCHECKED_CAST")
        val paramsArray = arrayOfNulls<Type>(argClasses.size + 2) as Array<Type>
        @Suppress("UNCHECKED_CAST")
        val paramsArrayClass = arrayOfNulls<Class<*>>(argClasses.size + 2) as Array<Class<*>>


        paramsArray[0] = Type.getType(MutableCallSite::class.java)
        paramsArray[1] = Type.getType(MethodHandle::class.java)

        paramsArrayClass[0] = MutableCallSite::class.java
        paramsArrayClass[1] = MethodHandle::class.java

        for (i in argClasses.indices) {
            paramsArray[i+2] = Type.getType(argClasses[i])
            paramsArrayClass[i+2] = argClasses[i]
        }

        val desc = Type.getMethodDescriptor(
            returnType,
            *paramsArray
        )

        val mv = classWriter.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "invoke", desc, null, null)

        val computeLabel = Label()

        mv.addReturnAndMaybeAddLock {
            mv.visitFieldInsn(Opcodes.GETSTATIC, name, "state", "Z")
            mv.visitJumpInsn(Opcodes.IFEQ, computeLabel) // if 0 then compute
            mv.visitFieldInsn(Opcodes.GETSTATIC, name, "cache", returnTypeDesc)
            mv.visitJumpInsn(Opcodes.GOTO, it)

            // Otherwise
            mv.visitLabel(computeLabel)
            mv.visitVarInsn(Opcodes.ALOAD, 1) // MethodHandle

            val indexMap = buildIndexMap(paramsArray)

            for (i in 2 until paramsArray.size) {
                mv.visitVarInsn(loadOpcode(paramsArray[i]), indexMap[i])
            }

            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandle",
                "invokeExact",
                Type.getMethodDescriptor(returnType, *paramsArray.copyOfRange(2, paramsArray.size)),
                false
            )

            mv.dupFor(returnType)

            mv.visitFieldInsn(Opcodes.PUTSTATIC, name, "cache", returnTypeDesc)
            mv.visitInsn(Opcodes.ICONST_1)
            mv.visitFieldInsn(Opcodes.PUTSTATIC, name, "state", "Z")

            mv.dupFor(returnType)

            mv.boxIfNeeded(returnType)
            mv.visitVarInsn(Opcodes.ALOAD, 0) // MutableCallSite
            mv.pushClass(returnType)
            mv.visitVarInsn(Opcodes.ALOAD, 1) // MethodHandle

            if (isSync) {
                mv.visitInsn(Opcodes.ICONST_1)
            } else {
                mv.visitInsn(Opcodes.ICONST_0)
            }

            //org.bezsahara.customindy.impl.asm.PureAsmHelper.changeCallSite
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "org/bezsahara/customindy/impl/asm/PureAsmHelper",
                "changeCallSite",
                "(Ljava/lang/Object;Ljava/lang/invoke/MutableCallSite;Ljava/lang/Class;Ljava/lang/invoke/MethodHandle;Z)V",
                false
            )

            // value of cache from dup
            mv.visitJumpInsn(Opcodes.GOTO, it)
        }

        mv.visitMaxs(0,0)
        mv.visitEnd()

        return paramsArrayClass
    }

    private fun buildIndexMap(params: Array<Type>): IntArray {
        var index = 0
        return IntArray(params.size) {
            val ai = index
            index += when (params[it]) {
                Type.DOUBLE_TYPE, Type.LONG_TYPE -> 2
                else -> 1
            }
            ai
        }
    }

    private inline fun MethodVisitor.addReturnAndMaybeAddLock(block: (returnLabel: Label) -> Unit) {
        val start = Label()
        val end = Label()
        val handler = Label()

        if (isSync) {
            visitFieldInsn(Opcodes.GETSTATIC, name, "LOCK", "Ljava/lang/Object;")
            visitInsn(Opcodes.MONITORENTER)
            visitLabel(start)
        }
        block(end)
        visitLabel(end)
        if (isSync) {
            visitFieldInsn(Opcodes.GETSTATIC, name, "LOCK", "Ljava/lang/Object;")
            visitInsn(Opcodes.MONITOREXIT)
        }
        visitInsn(returnOpcode(returnType))

        if (isSync) {
            visitLabel(handler)
            visitFieldInsn(Opcodes.GETSTATIC, name, "LOCK", "Ljava/lang/Object;")
            visitInsn(Opcodes.MONITOREXIT)
            visitInsn(Opcodes.ATHROW)

            visitTryCatchBlock(start, end, handler, null)
        }
    }


    private fun generateClinit() {
        val mv = classWriter.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
        mv.visitCode()
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/Object")
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        mv.visitFieldInsn(Opcodes.PUTSTATIC, name, "LOCK", "Ljava/lang/Object;")
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun generateConstructor() {
        val mv = classWriter.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun MethodVisitor.dupFor(type: Type) {
        when (type.sort) {
            Type.LONG, Type.DOUBLE ->
                visitInsn(Opcodes.DUP2)
            else ->
                visitInsn(Opcodes.DUP)
        }
    }

    private fun MethodVisitor.boxIfNeeded(type: Type) {
        when (type.sort) {
            Type.BOOLEAN ->
                visitMethodInsn(Opcodes.INVOKESTATIC,
                    "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)

            Type.BYTE ->
                visitMethodInsn(Opcodes.INVOKESTATIC,
                    "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false)

            Type.SHORT ->
                visitMethodInsn(Opcodes.INVOKESTATIC,
                    "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false)

            Type.CHAR ->
                visitMethodInsn(Opcodes.INVOKESTATIC,
                    "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false)

            Type.INT ->
                visitMethodInsn(Opcodes.INVOKESTATIC,
                    "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false)

            Type.LONG ->
                visitMethodInsn(Opcodes.INVOKESTATIC,
                    "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false)

            Type.FLOAT ->
                visitMethodInsn(Opcodes.INVOKESTATIC,
                    "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false)

            Type.DOUBLE ->
                visitMethodInsn(Opcodes.INVOKESTATIC,
                    "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false)

            else -> Unit
        }
    }

    private fun MethodVisitor.pushClass(type: Type) {
        when (type.sort) {
            Type.BOOLEAN ->
                visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", "TYPE", "Ljava/lang/Class;")
            Type.BYTE ->
                visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Byte", "TYPE", "Ljava/lang/Class;")
            Type.SHORT ->
                visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Short", "TYPE", "Ljava/lang/Class;")
            Type.CHAR ->
                visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Character", "TYPE", "Ljava/lang/Class;")
            Type.INT ->
                visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Integer", "TYPE", "Ljava/lang/Class;")
            Type.LONG ->
                visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Long", "TYPE", "Ljava/lang/Class;")
            Type.FLOAT ->
                visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Float", "TYPE", "Ljava/lang/Class;")
            Type.DOUBLE ->
                visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Double", "TYPE", "Ljava/lang/Class;")
            Type.VOID ->
                visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Void", "TYPE", "Ljava/lang/Class;")
            else ->
                visitLdcInsn(type)
        }
    }
}

public object PureAsmHelper {
    @JvmStatic
    public fun changeCallSite(
        v: Any?,
        site: MutableCallSite,
        vClass: Class<*>,
        original: MethodHandle,
        threadSafe: Boolean
    ) {
        val t = site.type() // (args...)R

        var mh = MethodHandles.constant(vClass, v)                 // ()R
        mh = MethodHandles.dropArguments(mh, 0, *t.parameterArray()) // (args...)R

        site.target = mh.asType(t)

        if (threadSafe) {
            MutableCallSite.syncAll(arrayOf(site))
        }
    }
}
