package xyz.wagyourtail.unimined.providers.mod

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import java.nio.file.FileSystem
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

object ModLoaderPatchClasspath {
    fun fixURIisNotHierarchicalException(fileSystem: FileSystem) {
        val modLoader = fileSystem.getPath("/ModLoader.class")
        if (!modLoader.exists()) return
        val classNode = ClassNode()
        val classReader = ClassReader(modLoader.readBytes())
        classReader.accept(classNode, 0)

        if (classNode.fields.any { it.name == "fmlMarker" }) return
        if (fileSystem.getPath("/cpw/mods/fml/common/modloader/ModLoaderHelper.class").exists()) return

        if (classNode.methods.any { it.name == "readFromClassPath" }) {
            System.out.println("ModLoader patch using newer method")
            newerURIFix(classNode)
        } else {
            System.out.println("ModLoader patch using older method")
            olderURIFix(classNode)
        }

        val classWriter = object : ClassWriter(COMPUTE_MAXS or COMPUTE_FRAMES) {
            override fun getCommonSuperClass(type1: String, type2: String): String {
                try {
                    return super.getCommonSuperClass(type1, type2)
                } catch (e: Exception) {
                    // one of the classes was not found, so we now need to calculate it
                    val it1 = buildInheritanceTree(type1)
                    val it2 = buildInheritanceTree(type2)
                    val common = it1.intersect(it2)
                    return common.first()
                }
            }

            fun buildInheritanceTree(type1: String): List<String> {
                val tree = mutableListOf<String>()
                var current = type1
                while (current != "java/lang/Object") {
                    tree.add(current)
                    val currentClassFile = fileSystem.getPath("/${current}.class")
                    if (!currentClassFile.exists()) {
                        current = "java/lang/Object"
                    } else {
                        val classReader = ClassReader(currentClassFile.readBytes())
                        current = classReader.superName
                    }
                }
                tree.add("java/lang/Object")
                return tree
            }
        }
        classNode.accept(classWriter)
        modLoader.writeBytes(classWriter.toByteArray(), StandardOpenOption.TRUNCATE_EXISTING)
    }

    fun newerURIFix(classNode: ClassNode) {
        val method = classNode.methods.first { it.name == "init" && it.desc == "()V" }
            // find the lines
            //     L92
            //    LINENUMBER 809 L92
            //    NEW java/io/File
            //    DUP
            //    LDC LModLoader;.class
            //    INVOKEVIRTUAL java/lang/Class.getProtectionDomain ()Ljava/security/ProtectionDomain;
            //    INVOKEVIRTUAL java/security/ProtectionDomain.getCodeSource ()Ljava/security/CodeSource;
            //    INVOKEVIRTUAL java/security/CodeSource.getLocation ()Ljava/net/URL;
            //    INVOKEVIRTUAL java/net/URL.toURI ()Ljava/net/URI;
            //    INVOKESPECIAL java/io/File.<init> (Ljava/net/URI;)V
            //    ASTORE 2
            //   L93
            //    LINENUMBER 810 L93
            //    GETSTATIC ModLoader.modDir : Ljava/io/File;
            //    INVOKEVIRTUAL java/io/File.mkdirs ()Z
            //    POP
            //   L94
            //    LINENUMBER 811 L94
            //    GETSTATIC ModLoader.modDir : Ljava/io/File;
            //    INVOKESTATIC ModLoader.readFromModFolder (Ljava/io/File;)V
            //   L95
            //    LINENUMBER 812 L95
            //    ALOAD 2
            //    INVOKESTATIC ModLoader.readFromClassPath (Ljava/io/File;)V
            // and replace them with
            //     this.modDir.mkdirs();
            //     readFromModFolder(this.modDir);
            //     for (String path : System.getProperty("java.class.path").split(File.pathSeparator)) {
            //         readFromClassPath(new File(path));
            //     }

            val instructions = method.instructions
            val newInstructions = InsnList()
            val iterator = instructions.iterator()
            var slice = false

            while (iterator.hasNext()) {
                val insn = iterator.next()

                if (insn is TypeInsnNode && insn.desc == "java/io/File") {
                    val hold = mutableListOf<AbstractInsnNode>(insn)
                    var next: AbstractInsnNode = insn
                    while (next !is VarInsnNode && iterator.hasNext()) {
                        next = iterator.next()
                        hold.add(next)
                        if (next is MethodInsnNode && next.name == "getProtectionDomain") {
                            slice = true
                        }
                    }
                    if (!slice) {
                        for (i in hold) {
                            newInstructions.add(i)
                        }
                    }
                }
                if (insn is LdcInsnNode && insn.cst == "Done.") {
                    slice = false
                }

                // detect 'NEW java/io/File'
                if (slice && insn is TypeInsnNode && insn.desc == "java/io/File" && insn.opcode == Opcodes.NEW) {
                    val startLbl = LabelNode()
                    val endLbl = LabelNode()

                    newInstructions.add(startLbl)

                    // find where it's stored
                    var storeInsn = iterator.next()
                    while (storeInsn !is VarInsnNode || storeInsn.opcode != Opcodes.ASTORE) {
                        storeInsn = iterator.next()
                    }
                    val baseLVIndex = storeInsn.`var` + 1
                    method.localVariables.add(LocalVariableNode("path", "[Ljava/lang/String;", null, startLbl, endLbl, baseLVIndex))

                    if (classNode.methods.any { it.name == "readFromModFolder" }) {
                        // ModLoader.modDir.mkdirs();
                        newInstructions.add(FieldInsnNode(Opcodes.GETSTATIC, "ModLoader", "modDir", "Ljava/io/File;"))
                        newInstructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/File", "mkdirs", "()Z", false))
                        newInstructions.add(InsnNode(Opcodes.POP))
                        // readFromModFolder(ModLoader.modDir);
                        newInstructions.add(FieldInsnNode(Opcodes.GETSTATIC, "ModLoader", "modDir", "Ljava/io/File;"))
                        newInstructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "ModLoader", "readFromModFolder", "(Ljava/io/File;)V", false))
                    }
                    // var paths = System.getProperty("java.class.path")
                    newInstructions.add(LdcInsnNode("java.class.path"))
                    newInstructions.add(
                        MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "java/lang/System",
                            "getProperty",
                            "(Ljava/lang/String;)Ljava/lang/String;",
                            false
                        )
                    )
                    // paths = paths.split(File.pathSeparator)
                    newInstructions.add(
                        FieldInsnNode(
                            Opcodes.GETSTATIC, "java/io/File", "pathSeparator", "Ljava/lang/String;"
                        )
                    )
                    newInstructions.add(
                        MethodInsnNode(
                            Opcodes.INVOKEVIRTUAL,
                            "java/lang/String",
                            "split",
                            "(Ljava/lang/String;)[Ljava/lang/String;",
                            false
                        )
                    )
                    newInstructions.add(VarInsnNode(Opcodes.ASTORE, baseLVIndex))
                    // for (String path : paths)) {
                    //     readFromClassPath(new File(path));
                    // }
                    newInstructions.add(VarInsnNode(Opcodes.ALOAD, baseLVIndex))
                    newInstructions.add(InsnNode(Opcodes.ARRAYLENGTH))
                    newInstructions.add(VarInsnNode(Opcodes.ISTORE, baseLVIndex + 1))
                    newInstructions.add(InsnNode(Opcodes.ICONST_0))
                    newInstructions.add(VarInsnNode(Opcodes.ISTORE, baseLVIndex + 2))
                    val loopStart = LabelNode()
                    newInstructions.add(loopStart)
                    newInstructions.add(VarInsnNode(Opcodes.ILOAD, baseLVIndex + 2))
                    newInstructions.add(VarInsnNode(Opcodes.ILOAD, baseLVIndex + 1))
                    val loopEnd = LabelNode()
                    newInstructions.add(JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd))
                    newInstructions.add(TypeInsnNode(Opcodes.NEW, "java/io/File"))
                    newInstructions.add(InsnNode(Opcodes.DUP))
                    newInstructions.add(VarInsnNode(Opcodes.ALOAD, baseLVIndex))
                    newInstructions.add(VarInsnNode(Opcodes.ILOAD, baseLVIndex + 2))
                    newInstructions.add(InsnNode(Opcodes.AALOAD))
                    newInstructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/lang/String;)V", false))
                    newInstructions.add(VarInsnNode(Opcodes.ASTORE, baseLVIndex + 3))
                    newInstructions.add(VarInsnNode(Opcodes.ALOAD, baseLVIndex + 3))
                    newInstructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "ModLoader", "readFromClassPath", "(Ljava/io/File;)V", false))
                    newInstructions.add(VarInsnNode(Opcodes.ILOAD, baseLVIndex + 2))
                    newInstructions.add(InsnNode(Opcodes.ICONST_1))
                    newInstructions.add(InsnNode(Opcodes.IADD))
                    newInstructions.add(VarInsnNode(Opcodes.ISTORE, baseLVIndex + 2))
                    newInstructions.add(JumpInsnNode(Opcodes.GOTO, loopStart))
                    newInstructions.add(loopEnd)
                    // skip the original instructions
                    while (iterator.hasNext()) {
                        val insn = iterator.next()
                        if (insn is MethodInsnNode && insn.name == "readFromClassPath" && insn.owner == "ModLoader" && insn.desc == "(Ljava/io/File;)V" && insn.opcode == Opcodes.INVOKESTATIC) {
                            break
                        }
                    }

                    newInstructions.add(endLbl)
                } else {
                    newInstructions.add(insn)
                }
            }
            method.instructions = newInstructions
        }

    fun olderURIFix(classNode: ClassNode) {
        val method = classNode.methods.first { it.name == "<clinit>" }

        // extract part of this function out to a new function
        // and call it from the original function with the whole classpath
        // copy the clinit to new method
        val newMethod = MethodNode(Opcodes.ACC_STATIC, "readFromClassPath", "(Ljava/io/File;)V", null, null)
        val newInstructions = InsnList()
        newMethod.instructions = newInstructions

        val iter = method.instructions.iterator()
        var foundFile = false
        val preFoundFile = InsnList()
        val clonedLabels = mutableMapOf<LabelNode, LabelNode>()
        while (iter.hasNext()) {
            val insn = iter.next()
            // remove new file insns
            if (!foundFile && insn is TypeInsnNode && insn.desc == "java/io/File") {
                // skip
                while (iter.hasNext()) {
                    val insn = iter.next()
                    if (insn is VarInsnNode && insn.opcode == Opcodes.ASTORE) {
                        break
                    }
                }
                foundFile = true
                continue
            }
            if (!foundFile) {
                if (insn is LabelNode) {
                    newInstructions.add(insn)
                    clonedLabels[insn] = LabelNode()
                    preFoundFile.add(clonedLabels[insn])
                } else {
                    preFoundFile.add(insn.clone(clonedLabels))
                }
            } else {
                newInstructions.add(insn)
            }
        }
        if (!foundFile) {
            throw IllegalStateException("Could not find file type")
        }
        classNode.methods.add(newMethod)
        classNode.methods.remove(method)

        // add new method to clinit
        val newClinit = MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
        newClinit.instructions = preFoundFile
        classNode.methods.add(newClinit)
        newClinit.visitCode()
        newClinit.visitLdcInsn("java.class.path")
        newClinit.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperty", "(Ljava/lang/String;)Ljava/lang/String;", false)
        newClinit.visitFieldInsn(Opcodes.GETSTATIC, "java/io/File", "pathSeparator", "Ljava/lang/String;")
        newClinit.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "split", "(Ljava/lang/String;)[Ljava/lang/String;", false)
        newClinit.visitVarInsn(Opcodes.ASTORE, 0)
        newClinit.visitInsn(Opcodes.ICONST_0)
        newClinit.visitVarInsn(Opcodes.ISTORE, 1)
        val loopStart = Label()
        newClinit.visitLabel(loopStart)
        newClinit.visitVarInsn(Opcodes.ILOAD, 1)
        newClinit.visitVarInsn(Opcodes.ALOAD, 0)
        newClinit.visitInsn(Opcodes.ARRAYLENGTH)
        val loopEnd = Label()
        newClinit.visitJumpInsn(Opcodes.IF_ICMPGE, loopEnd)
        newClinit.visitTypeInsn(Opcodes.NEW, "java/io/File")
        newClinit.visitInsn(Opcodes.DUP)
        newClinit.visitVarInsn(Opcodes.ALOAD, 0)
        newClinit.visitVarInsn(Opcodes.ILOAD, 1)
        newClinit.visitInsn(Opcodes.AALOAD)
        newClinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/lang/String;)V", false)
        newClinit.visitVarInsn(Opcodes.ASTORE, 2)
        newClinit.visitVarInsn(Opcodes.ALOAD, 2)
        newClinit.visitMethodInsn(Opcodes.INVOKESTATIC, "ModLoader", "readFromClassPath", "(Ljava/io/File;)V", false)
        newClinit.visitIincInsn(1, 1)
        newClinit.visitJumpInsn(Opcodes.GOTO, loopStart)
        newClinit.visitLabel(loopEnd)
        newClinit.visitInsn(Opcodes.RETURN)
        newClinit.visitMaxs(0, 0)
        newClinit.visitEnd()
    }
}