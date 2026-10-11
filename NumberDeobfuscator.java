import org.objectweb.asm.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/**
 * 用法:
 *   java NumberDeobfuscator <input.jar|class> [output] [--recompute-frames]
 *
 * 預設: 保留原始 StackMapTable (替換後堆疊效果相同, 原 frame 依然正確), 只重算 maxs。
 * --recompute-frames: 用 jar 內部繼承關係重算 frame (一般不需要)。
 */
public class NumberDeobfuscator {

    static int doubleCount = 0;
    static int floatCount = 0;
    static int failedClasses = 0;
    static int successClasses = 0;
    static int changedClasses = 0;

    static boolean RECOMPUTE = false;
    static final Set<String> UNKNOWN = new TreeSet<>();
    // 類別名稱 -> {superName, isInterface("1"/"0"), interfaces...}
    static final Map<String, String[]> HIERARCHY = new HashMap<>();

    public static void main(String[] args) throws Exception {
        List<String> pos = new ArrayList<>();
        List<Path> extraCp = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--recompute-frames")) RECOMPUTE = true;
            else if (a.equals("--cp") && i + 1 < args.length) {
                for (String x : args[++i].split(File.pathSeparator)) if (!x.isEmpty()) extraCp.add(Paths.get(x));
            }
            else pos.add(a);
        }
        if (pos.isEmpty()) {
            System.out.println("Usage: java NumberDeobfuscator <input.jar|class> [output] [--recompute-frames]");
            return;
        }
        Path input = Paths.get(pos.get(0));
        if (input.toString().endsWith(".jar")) {
            Path output = pos.size() > 1 ? Paths.get(pos.get(1)) : Paths.get("deobf-numbers.jar");
            if (RECOMPUTE) {
                buildHierarchy(input);
                for (Path p : extraCp) buildHierarchy(p);
            }
            processJar(input, output);
        } else {
            byte[] bytes = Files.readAllBytes(input);
            byte[] result = processClass(bytes);
            Path out = pos.size() > 1 ? Paths.get(pos.get(1))
                    : Paths.get(input.getFileName().toString().replace(".class", ".deobf.class"));
            Files.write(out, result);
            System.out.println("Written: " + out.toAbsolutePath());
        }
        System.out.println("\n===== Summary =====");
        System.out.println("Double cleaned : " + doubleCount);
        System.out.println("Float cleaned  : " + floatCount);
        System.out.println("Changed classes: " + changedClasses);
        System.out.println("Success classes: " + successClasses);
        System.out.println("Failed classes : " + failedClasses);
        if (!UNKNOWN.isEmpty()) {
            System.out.println("Unresolved types (treated as Object when merging): " + UNKNOWN.size());
            UNKNOWN.stream().sorted().limit(15).forEach(u -> System.out.println("   " + u));
            System.out.println("  -> 可用 --cp <minecraft與其他依賴jar> 補齊, 以得到更精確的 frame");
        }
    }

    static void buildHierarchy(Path in) throws Exception {
        try (JarFile jar = new JarFile(in.toFile())) {
            Enumeration<JarEntry> en = jar.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) continue;
                try (InputStream is = jar.getInputStream(e)) {
                    ClassReader cr = new ClassReader(is.readAllBytes());
                    String[] ifs = cr.getInterfaces();
                    String[] info = new String[2 + ifs.length];
                    info[0] = cr.getSuperName();
                    info[1] = (cr.getAccess() & Opcodes.ACC_INTERFACE) != 0 ? "1" : "0";
                    System.arraycopy(ifs, 0, info, 2, ifs.length);
                    HIERARCHY.put(cr.getClassName(), info);
                } catch (Throwable ignored) {}
            }
        }
    }

    static void processJar(Path in, Path out) throws Exception {
        int classCount = 0;
        try (JarFile jar = new JarFile(in.toFile());
             JarOutputStream jos = new JarOutputStream(Files.newOutputStream(out))) {
            Enumeration<JarEntry> en = jar.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                jos.putNextEntry(new JarEntry(e.getName()));
                byte[] data;
                try (InputStream is = jar.getInputStream(e)) { data = is.readAllBytes(); }
                if (e.getName().endsWith(".class")) {
                    classCount++;
                    try {
                        data = processClass(data);
                        successClasses++;
                    } catch (Throwable t) {
                        failedClasses++;
                        System.err.println("Keep original: " + e.getName() + " (" + t.getClass().getSimpleName() + ")");
                    }
                }
                jos.write(data);
                jos.closeEntry();
            }
        }
        System.out.println("Total classes: " + classCount);
        System.out.println("Output: " + out.toAbsolutePath());
    }

    // ------------------------------------------------------------------
    // 核心: 先用「保留原始 frame」的方式轉換; 沒變動就回傳原始 bytes
    // ------------------------------------------------------------------
    static byte[] processClass(byte[] bytes) {
        int d0 = doubleCount, f0 = floatCount;

        // 1) 保留原始 frame (替換不改變堆疊形狀)
        byte[] kept = transform(bytes, new ClassWriter(ClassWriter.COMPUTE_MAXS), 0);
        boolean changed = doubleCount != d0 || floatCount != f0;
        if (changed) changedClasses++;
        byte[] fallback = changed ? kept : bytes;
        if (!RECOMPUTE) return fallback;

        // 2) 重算 frame: 原始 jar 中許多方法 (switch 流程混淆) 在跳轉目標處根本沒有 frame
        int major = ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff);
        if (major < 51) return fallback;
        int d1 = doubleCount, f1 = floatCount;
        try {
            byte[] recomputed = transform(bytes,
                    new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS),
                    ClassReader.EXPAND_FRAMES);
            doubleCount = d1; floatCount = f1; // 第二次遍歷會重複計數, 還原
            return recomputed;
        } catch (Throwable t) {
            doubleCount = d1; floatCount = f1;
            System.err.println("Recompute failed (" + t + "), using fallback");
            return fallback;
        }
    }

    static byte[] transform(byte[] bytes, ClassWriter cw, int readFlags) {
        ClassReader cr = new ClassReader(bytes);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new NumberCleanerMV(super.visitMethod(access, name, desc, signature, exceptions));
            }
        }, readFlags);
        return cw.toByteArray();
    }

    // ------------------------------------------------------------------
    // 不載入類別的 ClassWriter: 用 jar 內繼承表 -> JDK 反射 -> Object
    // ------------------------------------------------------------------
    static class SafeClassWriter extends ClassWriter {
        SafeClassWriter(int flags) { super(flags); }

        private boolean isInterface(String n) {
            String[] i = HIERARCHY.get(n);
            if (i != null) return "1".equals(i[1]);
            try { return Class.forName(n.replace('/', '.'), false, getClass().getClassLoader()).isInterface(); }
            catch (Throwable t) { UNKNOWN.add(n); return false; }
        }

        private String superOf(String n) {
            String[] i = HIERARCHY.get(n);
            if (i != null) return i[0];
            try {
                Class<?> s = Class.forName(n.replace('/', '.'), false, getClass().getClassLoader()).getSuperclass();
                return s == null ? null : s.getName().replace('.', '/');
            } catch (Throwable t) { UNKNOWN.add(n); return "java/lang/Object"; }
        }

        private boolean isSubclass(String sub, String sup) {
            for (String c = sub; c != null; c = superOf(c)) {
                if (c.equals(sup)) return true;
                if (c.equals("java/lang/Object")) break;
            }
            return false;
        }

        @Override
        protected String getCommonSuperClass(String a, String b) {
            if (a.equals(b)) return a;
            if (isSubclass(b, a)) return a;
            if (isSubclass(a, b)) return b;
            if (isInterface(a) || isInterface(b)) return "java/lang/Object";
            String c = a;
            while (c != null && !c.equals("java/lang/Object") && !isSubclass(b, c)) c = superOf(c);
            return c == null ? "java/lang/Object" : c;
        }
    }

    // ------------------------------------------------------------------
    static class NumberCleanerMV extends MethodVisitor {
        private final List<Object[]> window = new ArrayList<>();
        private static final int MAX_WINDOW = 14;

        NumberCleanerMV(MethodVisitor mv) { super(Opcodes.ASM9, mv); }

        private void emitBuffered() {
            for (Object[] item : window) emitOne(item);
            window.clear();
        }

        private void emitOne(Object[] item) {
            int type = (Integer) item[0];
            switch (type) {
                case 0: mv.visitInsn((Integer) item[1]); break;
                case 1: mv.visitIntInsn((Integer) item[1], (Integer) item[2]); break;
                case 2: mv.visitLdcInsn(item[1]); break;
                case 3: mv.visitMethodInsn((Integer) item[1], (String) item[2], (String) item[3], (String) item[4], (Boolean) item[5]); break;
                default: break;
            }
        }

        private void push(Object... data) {
            window.add(data);
            if (window.size() > MAX_WINDOW) emitOne(window.remove(0));
            tryMatch();
        }

        private void tryMatch() {
            // 1. Double.longBitsToDouble(Long.parseUnsignedLong(str, radix) ^ longConst)
            if (window.size() >= 6) {
                int n = window.size();
                Object[] a0 = window.get(n-6), a1 = window.get(n-5), a2 = window.get(n-4);
                Object[] a3 = window.get(n-3), a4 = window.get(n-2), a5 = window.get(n-1);
                if ((Integer)a0[0]==2 && a0[1] instanceof String && isIntConst(a1) &&
                    (Integer)a2[0]==3 && "java/lang/Long".equals(a2[2]) && "parseUnsignedLong".equals(a2[3]) &&
                    (Integer)a3[0]==2 && a3[1] instanceof Long &&
                    (Integer)a4[0]==0 && (Integer)a4[1]==Opcodes.LXOR &&
                    (Integer)a5[0]==3 && "java/lang/Double".equals(a5[2]) && "longBitsToDouble".equals(a5[3])) {
                    try {
                        long parsed = Long.parseUnsignedLong((String)a0[1], getInt(a1));
                        double val = Double.longBitsToDouble(parsed ^ (Long)a3[1]);
                        for (int i=0;i<6;i++) window.remove(window.size()-1);
                        window.add(new Object[]{2, val});
                        doubleCount++;
                        return;
                    } catch (Exception ignored) {}
                }
            }

            // 2. Float: LDC int, I2L, LDC int, I2L, LXOR, L2I, intBitsToFloat
            if (window.size() >= 7) {
                int n = window.size();
                Object[] p0 = window.get(n-7), p1 = window.get(n-6), p2 = window.get(n-5);
                Object[] p3 = window.get(n-4), p4 = window.get(n-3), p5 = window.get(n-2), p6 = window.get(n-1);
                if (isIntConst(p0) && isInsn(p1, Opcodes.I2L) &&
                    isIntConst(p2) && isInsn(p3, Opcodes.I2L) &&
                    isInsn(p4, Opcodes.LXOR) && isInsn(p5, Opcodes.L2I) &&
                    isMethod(p6, "java/lang/Float", "intBitsToFloat")) {
                    float val = Float.intBitsToFloat(getInt(p0) ^ getInt(p2));
                    for (int i=0;i<7;i++) window.remove(window.size()-1);
                    window.add(new Object[]{2, val});
                    floatCount++;
                    return;
                }
            }

            // 3. Float: LDC long, L2I, LDC int, IXOR, intBitsToFloat
            if (window.size() >= 5) {
                int n = window.size();
                Object[] q0 = window.get(n-5), q1 = window.get(n-4), q2 = window.get(n-3);
                Object[] q3 = window.get(n-2), q4 = window.get(n-1);
                if (isLongConst(q0) && isInsn(q1, Opcodes.L2I) &&
                    isIntConst(q2) && isInsn(q3, Opcodes.IXOR) &&
                    isMethod(q4, "java/lang/Float", "intBitsToFloat")) {
                    float val = Float.intBitsToFloat(((int) getLong(q0)) ^ getInt(q2));
                    for (int i=0;i<5;i++) window.remove(window.size()-1);
                    window.add(new Object[]{2, val});
                    floatCount++;
                    return;
                }
            }

            // 4. Float: int ^ int -> intBitsToFloat
            if (window.size() >= 4) {
                int n = window.size();
                Object[] r0 = window.get(n-4), r1 = window.get(n-3), r2 = window.get(n-2), r3 = window.get(n-1);
                if (isIntConst(r0) && isIntConst(r1) && isInsn(r2, Opcodes.IXOR) &&
                    isMethod(r3, "java/lang/Float", "intBitsToFloat")) {
                    float val = Float.intBitsToFloat(getInt(r0) ^ getInt(r1));
                    for (int i=0;i<4;i++) window.remove(window.size()-1);
                    window.add(new Object[]{2, val});
                    floatCount++;
                    return;
                }
            }

            // 5. single int -> intBitsToFloat
            if (window.size() >= 2) {
                int n = window.size();
                Object[] s0 = window.get(n-2), s1 = window.get(n-1);
                if (isIntConst(s0) && isMethod(s1, "java/lang/Float", "intBitsToFloat")) {
                    float val = Float.intBitsToFloat(getInt(s0));
                    window.remove(window.size()-1); window.remove(window.size()-1);
                    window.add(new Object[]{2, val});
                    floatCount++;
                }
            }
        }

        private boolean isInsn(Object[] item, int opcode) {
            return (Integer)item[0]==0 && (Integer)item[1]==opcode;
        }
        private boolean isMethod(Object[] item, String owner, String name) {
            return (Integer)item[0]==3 && owner.equals(item[2]) && name.equals(item[3]);
        }
        /** 嚴格判斷: 只接受 int 常數 (ICONST_x / BIPUSH / SIPUSH / LDC Integer) */
        private boolean isIntConst(Object[] item) {
            int t = (Integer)item[0];
            if (t==2) return item[1] instanceof Integer;
            if (t==1) {
                int op = (Integer)item[1];
                return op == Opcodes.BIPUSH || op == Opcodes.SIPUSH; // 排除 NEWARRAY
            }
            if (t==0) {
                int op = (Integer)item[1];
                return op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5;
            }
            return false;
        }
        private boolean isLongConst(Object[] item) {
            int t = (Integer)item[0];
            if (t==2 && item[1] instanceof Long) return true;
            if (t==0) {
                int op = (Integer)item[1];
                return op==Opcodes.LCONST_0 || op==Opcodes.LCONST_1;
            }
            return false;
        }
        private int getInt(Object[] item) {
            int t = (Integer)item[0];
            if (t==2) return (Integer)item[1];
            if (t==1) return (Integer)item[2];
            if (t==0) return (Integer)item[1] - Opcodes.ICONST_0;
            return 0;
        }
        private long getLong(Object[] item) {
            int t = (Integer)item[0];
            if (t==2 && item[1] instanceof Long) return (Long)item[1];
            if (t==0) {
                int op = (Integer)item[1];
                if (op==Opcodes.LCONST_0) return 0L;
                if (op==Opcodes.LCONST_1) return 1L;
            }
            return 0L;
        }

        @Override public void visitInsn(int opcode) { push(0, opcode); }
        @Override public void visitIntInsn(int opcode, int operand) { push(1, opcode, operand); }
        @Override public void visitLdcInsn(Object value) { push(2, value); }
        @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            push(3, opcode, owner, name, desc, itf);
        }
        @Override public void visitTypeInsn(int opcode, String type) { emitBuffered(); mv.visitTypeInsn(opcode, type); }
        @Override public void visitVarInsn(int opcode, int var) { emitBuffered(); mv.visitVarInsn(opcode, var); }
        @Override public void visitJumpInsn(int opcode, Label label) { emitBuffered(); mv.visitJumpInsn(opcode, label); }
        @Override public void visitLabel(Label label) { emitBuffered(); mv.visitLabel(label); }
        @Override public void visitIincInsn(int var, int increment) { emitBuffered(); mv.visitIincInsn(var, increment); }
        @Override public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            emitBuffered(); mv.visitFieldInsn(opcode, owner, name, desc);
        }
        @Override public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
            emitBuffered(); mv.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs);
        }
        @Override public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            emitBuffered(); mv.visitTableSwitchInsn(min, max, dflt, labels);
        }
        @Override public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            emitBuffered(); mv.visitLookupSwitchInsn(dflt, keys, labels);
        }
        @Override public void visitMultiANewArrayInsn(String desc, int dims) {
            emitBuffered(); mv.visitMultiANewArrayInsn(desc, dims);
        }
        @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            emitBuffered(); mv.visitTryCatchBlock(start, end, handler, type);
        }
        @Override public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            emitBuffered(); mv.visitFrame(type, numLocal, local, numStack, stack);
        }
        @Override public void visitLineNumber(int line, Label start) { emitBuffered(); mv.visitLineNumber(line, start); }
        @Override public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
            emitBuffered(); mv.visitLocalVariable(name, desc, signature, start, end, index);
        }
        @Override public void visitMaxs(int maxStack, int maxLocals) { emitBuffered(); mv.visitMaxs(maxStack, maxLocals); }
        @Override public void visitEnd() { emitBuffered(); mv.visitEnd(); }
    }
}
