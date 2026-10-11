import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/**
 * Batch constant-folder for JAR files.
 *
 * Usage:
 *   java -cp ".;asm-9.7.1.jar;asm-tree-9.7.1.jar" AsmJarConstantFolder input.jar output.jar
 * Linux/macOS classpath separator is ":" instead of ";".
 *
 * This performs conservative peephole folding of adjacent constant pushes followed by
 * JVM arithmetic/bitwise instructions, and folds Long.parseUnsignedLong(constantString,
 * constantRadix) when both arguments are literal constants.
 *
 * It does NOT deobfuscate arbitrary methods or fully undo control-flow flattening.
 */
public final class AsmJarConstantFolder {
    private AsmJarConstantFolder() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: java AsmJarConstantFolder <input.jar> <output.jar>");
            System.exit(2);
        }
        Path input = Paths.get(args[0]);
        Path output = Paths.get(args[1]);
        if (!Files.isRegularFile(input)) throw new FileNotFoundException(input.toString());

        int classes = 0, changedClasses = 0, folded = 0;
        try (JarFile in = new JarFile(input.toFile());
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(output))) {
            Enumeration<JarEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                JarEntry old = entries.nextElement();
                byte[] bytes;
                try (InputStream is = in.getInputStream(old)) {
                    bytes = is.readAllBytes();
                }

                if (!old.isDirectory() && old.getName().endsWith(".class")) {
                    classes++;
                    try {
                        ClassReader cr = new ClassReader(bytes);
                        ClassNode cn = new ClassNode();
                        cr.accept(cn, 0);
                        boolean classChanged = false;
                        for (MethodNode mn : cn.methods) {
                            if (mn.instructions == null || mn.instructions.size() == 0) continue;
                            int n;
                            do {
                                n = foldMethod(mn);
                                if (n > 0) {
                                    folded += n;
                                    classChanged = true;
                                }
                            } while (n > 0);
                        }
                        if (classChanged) {
                            // Preserve existing frames; this pass does not alter control-flow edges.
                            ClassWriter cw = new ClassWriter(0);
                            cn.accept(cw);
                            bytes = cw.toByteArray();
                            changedClasses++;
                        }
                    } catch (Throwable ex) {
                        // Keep the original class if it cannot be parsed/transformed safely.
                        System.err.println("Skipping " + old.getName() + ": " + ex);
                    }
                }

                JarEntry copy = new JarEntry(old.getName());
                copy.setTime(old.getTime());
                if (old.getComment() != null) copy.setComment(old.getComment());
                if (old.getExtra() != null) copy.setExtra(old.getExtra());
                out.putNextEntry(copy);
                if (!old.isDirectory()) out.write(bytes);
                out.closeEntry();
            }
        }
        System.out.printf(Locale.ROOT,
                "Done. Classes scanned=%d, changed classes=%d, fold operations=%d%n",
                classes, changedClasses, folded);
    }

    private static int foldMethod(MethodNode mn) {
        int changes = 0;
        InsnList insns = mn.instructions;

        // Fold Long.parseUnsignedLong("literal", radixLiteral) -> LDC long.
        for (AbstractInsnNode n = insns.getFirst(); n != null; ) {
            AbstractInsnNode next = n.getNext();
            if (n instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("java/lang/Long")
                    && call.name.equals("parseUnsignedLong")
                    && call.desc.equals("(Ljava/lang/String;I)J")) {
                AbstractInsnNode radixNode = previousReal(n);
                AbstractInsnNode stringNode = previousReal(radixNode);
                Object radixObj = constantValue(radixNode);
                Object stringObj = constantValue(stringNode);
                if (radixObj instanceof Integer radix && stringObj instanceof String s
                        && radix >= Character.MIN_RADIX && radix <= Character.MAX_RADIX) {
                    try {
                        long value = Long.parseUnsignedLong(s, radix);
                        InsnList replacement = new InsnList();
                        replacement.add(new LdcInsnNode(value));
                        insns.insertBefore(stringNode, replacement);
                        removeRealAndIntervening(insns, stringNode, n);
                        changes++;
                        n = replacement.getLast().getNext();
                        continue;
                    } catch (NumberFormatException ignored) {
                        // Leave malformed/nonconstant expression unchanged.
                    }
                }
            }
            n = next;
        }

        // Fold adjacent literal constants + a single JVM operation.
        // Repeat passes so chains such as (a ^ b) ^ c collapse completely.
        for (AbstractInsnNode op = insns.getFirst(); op != null; ) {
            AbstractInsnNode next = op.getNext();
            int opcode = op.getOpcode();
            if (isBinaryIntOp(opcode) || isBinaryLongOp(opcode)
                    || isBinaryFloatOp(opcode) || isBinaryDoubleOp(opcode)) {
                AbstractInsnNode right = previousReal(op);
                AbstractInsnNode left = previousReal(right);
                Object a = constantValue(left);
                Object b = constantValue(right);
                Object result = evaluate(opcode, a, b);
                if (result != null) {
                    InsnList replacement = new InsnList();
                    replacement.add(pushConstant(result));
                    insns.insertBefore(left, replacement);
                    removeRealAndIntervening(insns, left, op);
                    changes++;
                    op = replacement.getLast();
                    next = op.getNext();
                }
            }
            op = next;
        }
        return changes;
    }

    private static Object evaluate(int op, Object a, Object b) {
        try {
            if (a instanceof Integer x && b instanceof Integer y) {
                return switch (op) {
                    case Opcodes.IADD -> x + y;
                    case Opcodes.ISUB -> x - y;
                    case Opcodes.IMUL -> x * y;
                    case Opcodes.IDIV -> y == 0 ? null : x / y;
                    case Opcodes.IREM -> y == 0 ? null : x % y;
                    case Opcodes.ISHL -> x << y;
                    case Opcodes.ISHR -> x >> y;
                    case Opcodes.IUSHR -> x >>> y;
                    case Opcodes.IAND -> x & y;
                    case Opcodes.IOR -> x | y;
                    case Opcodes.IXOR -> x ^ y;
                    default -> null;
                };
            }
            if (a instanceof Long x && b instanceof Long y) {
                return switch (op) {
                    case Opcodes.LADD -> x + y;
                    case Opcodes.LSUB -> x - y;
                    case Opcodes.LMUL -> x * y;
                    case Opcodes.LDIV -> y == 0L ? null : x / y;
                    case Opcodes.LREM -> y == 0L ? null : x % y;
                    case Opcodes.LSHL -> x << b.intValue();
                    case Opcodes.LSHR -> x >> b.intValue();
                    case Opcodes.LUSHR -> x >>> b.intValue();
                    case Opcodes.LAND -> x & y;
                    case Opcodes.LOR -> x | y;
                    case Opcodes.LXOR -> x ^ y;
                    default -> null;
                };
            }
            if (a instanceof Float x && b instanceof Float y) {
                return switch (op) {
                    case Opcodes.FADD -> x + y;
                    case Opcodes.FSUB -> x - y;
                    case Opcodes.FMUL -> x * y;
                    case Opcodes.FDIV -> x / y;
                    case Opcodes.FREM -> x % y;
                    default -> null;
                };
            }
            if (a instanceof Double x && b instanceof Double y) {
                return switch (op) {
                    case Opcodes.DADD -> x + y;
                    case Opcodes.DSUB -> x - y;
                    case Opcodes.DMUL -> x * y;
                    case Opcodes.DDIV -> x / y;
                    case Opcodes.DREM -> x % y;
                    default -> null;
                };
            }
        } catch (ArithmeticException ignored) {
            return null;
        }
        return null;
    }

    private static boolean isBinaryIntOp(int o) {
        return o == Opcodes.IADD || o == Opcodes.ISUB || o == Opcodes.IMUL
                || o == Opcodes.IDIV || o == Opcodes.IREM || o == Opcodes.ISHL
                || o == Opcodes.ISHR || o == Opcodes.IUSHR || o == Opcodes.IAND
                || o == Opcodes.IOR || o == Opcodes.IXOR;
    }
    private static boolean isBinaryLongOp(int o) {
        return o == Opcodes.LADD || o == Opcodes.LSUB || o == Opcodes.LMUL
                || o == Opcodes.LDIV || o == Opcodes.LREM || o == Opcodes.LSHL
                || o == Opcodes.LSHR || o == Opcodes.LUSHR || o == Opcodes.LAND
                || o == Opcodes.LOR || o == Opcodes.LXOR;
    }
    private static boolean isBinaryFloatOp(int o) {
        return o == Opcodes.FADD || o == Opcodes.FSUB || o == Opcodes.FMUL
                || o == Opcodes.FDIV || o == Opcodes.FREM;
    }
    private static boolean isBinaryDoubleOp(int o) {
        return o == Opcodes.DADD || o == Opcodes.DSUB || o == Opcodes.DMUL
                || o == Opcodes.DDIV || o == Opcodes.DREM;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode n) {
        if (n == null) return null;
        n = n.getPrevious();
        while (n != null && (n.getType() == AbstractInsnNode.LINE
                || n.getType() == AbstractInsnNode.FRAME)) {
            n = n.getPrevious();
        }
        return n;
    }

    private static Object constantValue(AbstractInsnNode n) {
        if (n == null) return null;
        if (n instanceof LdcInsnNode ldc) {
            Object c = ldc.cst;
            if (c instanceof Integer || c instanceof Long || c instanceof Float
                    || c instanceof Double || c instanceof String) return c;
        }
        if (n instanceof InsnNode) {
            return switch (n.getOpcode()) {
                case Opcodes.ACONST_NULL -> null;
                case Opcodes.ICONST_M1 -> -1;
                case Opcodes.ICONST_0 -> 0;
                case Opcodes.ICONST_1 -> 1;
                case Opcodes.ICONST_2 -> 2;
                case Opcodes.ICONST_3 -> 3;
                case Opcodes.ICONST_4 -> 4;
                case Opcodes.ICONST_5 -> 5;
                case Opcodes.LCONST_0 -> 0L;
                case Opcodes.LCONST_1 -> 1L;
                case Opcodes.FCONST_0 -> 0.0f;
                case Opcodes.FCONST_1 -> 1.0f;
                case Opcodes.FCONST_2 -> 2.0f;
                case Opcodes.DCONST_0 -> 0.0d;
                case Opcodes.DCONST_1 -> 1.0d;
                default -> null;
            };
        }
        if (n instanceof IntInsnNode i
                && (n.getOpcode() == Opcodes.BIPUSH || n.getOpcode() == Opcodes.SIPUSH)) {
            return i.operand;
        }
        return null;
    }

    private static AbstractInsnNode pushConstant(Object c) {
        if (c instanceof Integer i) {
            if (i >= -1 && i <= 5) return new InsnNode(i == -1 ? Opcodes.ICONST_M1 : Opcodes.ICONST_0 + i);
            if (i >= Byte.MIN_VALUE && i <= Byte.MAX_VALUE) return new IntInsnNode(Opcodes.BIPUSH, i);
            if (i >= Short.MIN_VALUE && i <= Short.MAX_VALUE) return new IntInsnNode(Opcodes.SIPUSH, i);
            return new LdcInsnNode(i);
        }
        if (c instanceof Long l) {
            if (l == 0L) return new InsnNode(Opcodes.LCONST_0);
            if (l == 1L) return new InsnNode(Opcodes.LCONST_1);
            return new LdcInsnNode(l);
        }
        if (c instanceof Float f) {
            if (f == 0.0f) return new InsnNode(Opcodes.FCONST_0);
            if (f == 1.0f) return new InsnNode(Opcodes.FCONST_1);
            if (f == 2.0f) return new InsnNode(Opcodes.FCONST_2);
            return new LdcInsnNode(f);
        }
        if (c instanceof Double d) {
            if (d == 0.0d) return new InsnNode(Opcodes.DCONST_0);
            if (d == 1.0d) return new InsnNode(Opcodes.DCONST_1);
            return new LdcInsnNode(d);
        }
        return new LdcInsnNode(c);
    }

    private static void removeRealAndIntervening(InsnList list,
                                                  AbstractInsnNode first,
                                                  AbstractInsnNode last) {
        // Remove every node in [first, last], including labels/line/frame nodes between them.
        AbstractInsnNode n = first;
        while (n != null) {
            AbstractInsnNode next = n.getNext();
            list.remove(n);
            if (n == last) break;
            n = next;
        }
    }
}
