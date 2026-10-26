package com.example.hotreload.instrument;

import org.objectweb.asm.*;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Build-time bytecode instrumenter.
 *
 * Scans compiled .class files and instruments them so that field access
 * (getfield/putfield) is routed through {@link HotReloadDispatcher} instead
 * of accessing fields directly. This enables JRebel-like hot-reloading:
 * when a class is reloaded in a StructuralClassLoader, all subsequent
 * field accesses go through the dispatcher which can route to the new
 * class version.
 *
 * Instrumented bytecode:
 *   GETFIELD  -> HotReloadDispatcher.getField(object, className, fieldName)
 *   PUTFIELD  -> HotReloadDispatcher.setField(object, className, fieldName, value)
 *
 * Method call interception (INVOKEVIRTUAL) is NOT implemented in this
 * starter. Full method call interception requires complex argument
 * marshaling into Object[] arrays and is left as future work.
 */
public class ClassFileInstrumenter {

    private static final String DISPATCHER = "com/example/hotreload/instrument/HotReloadDispatcher";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ClassFileInstrumenter <classes-directory>");
            System.exit(1);
        }
        Path dir = Paths.get(args[0]);
        if (!Files.isDirectory(dir)) {
            System.err.println("Not a directory: " + dir);
            System.exit(1);
        }

        AtomicInteger count = new AtomicInteger(0);
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.toString().endsWith(".class"))
                  .forEach(p -> {
                      try {
                          instrumentFile(p);
                          count.incrementAndGet();
                      } catch (Exception e) {
                          System.err.println("Failed to instrument " + p + ": " + e.getMessage());
                      }
                  });
        }
        System.out.println("[Instrumenter] Instrumented " + count.get() + " class files in " + dir);
    }

    private static void instrumentFile(Path classFile) throws Exception {
        String className = classFile.toString()
                .replace(File.separator, "/")
                .replace(".class", "")
                .replaceFirst("^.*/target/classes/", "");

        // Skip JDK, agent, and all hot-reload agent classes
        if (className.startsWith("java/") || className.startsWith("javax/")
            || className.startsWith("jdk/") || className.startsWith("com/example/hotreload/")) {
            return;
        }

        ClassReader reader = new ClassReader(Files.readAllBytes(classFile));
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);

        ClassVisitor cv = new InstrumentingClassVisitor(writer, className);

        reader.accept(cv, ClassReader.EXPAND_FRAMES);
        byte[] bytes = writer.toByteArray();
        Files.write(classFile, bytes);
    }

    static class InstrumentingClassVisitor extends ClassVisitor {
        private final String internalName;

        InstrumentingClassVisitor(ClassVisitor cv, String className) {
            super(Opcodes.ASM9, cv);
            this.internalName = className;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (mv == null) return null;
            if (name.equals("<init>") || name.equals("<clinit>")) {
                return mv;
            }
            return new InstrumentingMethodVisitor(mv, internalName);
        }
    }

    static class InstrumentingMethodVisitor extends MethodVisitor {

        private final String className;

        InstrumentingMethodVisitor(MethodVisitor mv, String className) {
            super(Opcodes.ASM9, mv);
            this.className = className;
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (opcode == Opcodes.GETFIELD) {
                // Stack before: ..., objectref
                // Stack after: ..., fieldValue (cast to field type)
                // Duplicate objectref, then call getField(objectref, className, fieldName)
                mv.visitInsn(Opcodes.DUP);                 // ..., objectref, objectref
                mv.visitLdcInsn(className);                // ..., objectref, objectref, className
                mv.visitLdcInsn(name);                     // ..., objectref, objectref, className, fieldName
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, DISPATCHER,
                        "getField", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;",
                        false);                            // ..., objectref, result
                mv.visitInsn(Opcodes.SWAP);                // ..., result, objectref
                mv.visitInsn(Opcodes.POP);                 // ..., result
                mv.visitTypeInsn(Opcodes.CHECKCAST, descriptorToInternalName(descriptor));
            } else {
                super.visitFieldInsn(opcode, owner, name, descriptor);
            }
        }

        /**
         * Converts a field descriptor to an internal class name for CHECKCAST.
         *   "Ljava/lang/String;" -> "java/lang/String"
         *   "[I"                 -> "[I"
         *   "I"                  -> "I"
         */
        private static String descriptorToInternalName(String descriptor) {
            if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
                return descriptor.substring(1, descriptor.length() - 1);
            }
            return descriptor;
        }
    }
}
