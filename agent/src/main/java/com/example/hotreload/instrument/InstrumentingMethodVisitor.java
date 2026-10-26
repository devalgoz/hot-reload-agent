package com.example.hotreload.instrument;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * MethodVisitor that intercepts field reads (getfield), redirecting them through
 * HotReloadDispatcher.getField(object, className, fieldName).
 *
 * Method call interception (INVOKEVIRTUAL) and field writes (PUTFIELD) are
 * intentionally NOT instrumented in this starter. Full method call interception
 * requires complex stack manipulation to capture and repackage arguments into
 * an Object[] for the dispatcher, plus proper handling of primitive types,
 * varargs, and constructor invocations. Future work.
 */
class InstrumentingMethodVisitor extends MethodVisitor {

    private static final String DISPATCHER = "com/example/hotreload/instrument/HotReloadDispatcher";
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
     *   "I"                  -> "I" (primitives shouldn't reach CHECKCAST, but handle gracefully)
     */
    private static String descriptorToInternalName(String descriptor) {
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            return descriptor.substring(1, descriptor.length() - 1);
        }
        return descriptor;
    }
}
