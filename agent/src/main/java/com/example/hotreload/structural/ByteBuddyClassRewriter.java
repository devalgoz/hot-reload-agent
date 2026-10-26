package com.example.hotreload.structural;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Bytecode engine for structural class modification.
 *
 * This is the component that JRebel uses to create structurally-modified
 * class versions (add/remove methods/fields) that the JVM Instrumentation
 * API cannot produce via {@code redefineClasses}.
 *
 * Two modes:
 *   1. {@link #readClassFile(Path)} — reads already-compiled {@code .class}
 *      bytes from disk (javac produced the new method). This is the simplest
 *      and most reliable path.
 *   2. {@link #rewriteWithByteBuddy(byte[])} — programmatically modifies
 *      class bytes using ByteBuddy (future: add methods/fields without javac).
 *
 * Modified bytes are then loaded by a {@link StructuralClassLoader} so the
 * new class version coexists with the old one.
 */
public class ByteBuddyClassRewriter {

    /**
     * Read compiled class bytes from disk. The .class file was produced by
     * javac and already contains any new methods/fields.
     */
    public static byte[] readClassFile(Path classFile) throws IOException {
        return Files.readAllBytes(classFile);
    }

    /**
     * Placeholder for programmatic ByteBuddy rewriting. Takes existing class
     * bytes and returns new bytes with additional members.
     *
     * <p>This is where a production implementation would use ByteBuddy's
     * {@code TypePool} / {@code ClassFileVersion} to parse the original
     * class, inject new methods/fields, and emit modified bytes.</p>
     *
     * @param classBytes original compiled class bytes
     * @return the same bytes (ByteBuddy rewrite not yet implemented)
     */
    public static byte[] rewriteWithByteBuddy(byte[] classBytes) {
        // ByteBuddy programmatic rewrite of arbitrary class bytes requires
        // a TypePool built from the class files, which is complex. For now,
        // return the bytes unchanged — the .class file on disk already has
        // the structural changes from javac.
        return classBytes;
    }
}
