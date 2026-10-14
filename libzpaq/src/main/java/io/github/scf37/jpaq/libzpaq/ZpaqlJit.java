package io.github.scf37.jpaq.libzpaq;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Translates a ZPAQL program to JVM bytecode (the counterpart of the x86 JIT of libzpaq). The
 * generated hidden class implements {@link Code}; the machine state lives in the {@link Zpaql}
 * object and is cached in locals while the program runs. Programs that cannot be translated
 * statically (jumps into the middle of an instruction, out of range, ...) are left to the
 * interpreter.
 */
final class ZpaqlJit {
  private ZpaqlJit() {}

  /** A compiled program. */
  interface Code {
    void run(Zpaql z, int input);
  }

  private static final boolean ENABLED = !"false".equals(System.getProperty("jpaq.zpaqlJit"));
  private static final Object NONE = new Object();
  /** Upper bound for the number of generated classes kept alive. */
  private static final int MAX_CLASSES = 512;
  private static final Map<ByteBuffer, Object> CACHE = new ConcurrentHashMap<>();

  private static final ClassDesc CD_ZPAQL = ClassDesc.of(ZpaqlJit.class.getPackageName(), "Zpaql");
  private static final ClassDesc CD_CODE = ClassDesc.of(ZpaqlJit.class.getPackageName(), "ZpaqlJit$Code");
  private static final ClassDesc CD_BYTES = ConstantDescs.CD_byte.arrayType();
  private static final ClassDesc CD_INTS = ConstantDescs.CD_int.arrayType();

  // local variable slots of run(Zpaql z, int input)
  private static final int Z = 1, A = 2, B = 3, C = 4, D = 5, F = 6, M = 7, H = 8, R = 9, MM = 10,
      HM = 11;

  /**
   * Returns the compiled program in header[begin..end), or null to use the interpreter. A program
   * that has not been compiled yet is only compiled if {@code compile} is set (generating a class
   * costs more than interpreting a small input).
   */
  static Code get(byte[] header, int begin, int end, boolean compile) {
    if (!ENABLED || end <= begin) return null;
    byte[] prog = java.util.Arrays.copyOfRange(header, begin, end);
    ByteBuffer key = ByteBuffer.wrap(prog);
    Object o = CACHE.get(key);
    if (o == null) {
      if (!compile) return null;
      if (CACHE.size() >= MAX_CLASSES) return null;  // too many distinct models
      Code c = null;
      try {
        c = compile(prog);
      } catch (RuntimeException | LinkageError | ReflectiveOperationException e) {
        // fall back to the interpreter
      }
      o = c != null ? c : NONE;
      CACHE.put(key, o);
    }
    return o == NONE ? null : (Code) o;
  }

  private static Code compile(byte[] p) throws ReflectiveOperationException {
    final int n = p.length;
    // pass 1: instruction boundaries
    boolean[] start = new boolean[n + 1];
    for (int i = 0; i < n; ) {
      start[i] = true;
      int op = p[i] & 255;
      i += 1 + operands(op);
      if (i > n) return null;
    }
    // jump targets must be instruction starts
    for (int i = 0; i < n; ) {
      int op = p[i] & 255;
      if (op == 39 || op == 47 || op == 63) {
        int t = i + 2 + p[i + 1];
        if (t < 0 || t >= n || !start[t]) return null;
      } else if (op == 255) {
        int t = (p[i + 1] & 255) + 256 * (p[i + 2] & 255);
        if (t < 0 || t >= n || !start[t]) return null;
      }
      i += 1 + operands(op);
    }
    final String name = ZpaqlJit.class.getPackageName() + ".ZpaqlProg";
    final ClassDesc self = ClassDesc.of(name);
    byte[] bytes = ClassFile.of().build(self, cb -> {
      cb.withFlags(ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC);
      cb.withInterfaceSymbols(CD_CODE);
      cb.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC,
          cob -> cob.aload(0)
              .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                  ConstantDescs.MTD_void)
              .return_());
      cb.withMethodBody("run",
          MethodTypeDesc.of(ConstantDescs.CD_void, CD_ZPAQL, ConstantDescs.CD_int),
          ClassFile.ACC_PUBLIC, cob -> emit(cob, p, start));
    });
    MethodHandles.Lookup l = MethodHandles.lookup().defineHiddenClass(bytes, true);
    try {
      return (Code) l.findConstructor(l.lookupClass(),
          java.lang.invoke.MethodType.methodType(void.class)).invoke();
    } catch (Throwable t) {
      throw new ReflectiveOperationException(t);
    }
  }

  private static int operands(int op) {
    if (op == 255) return 2;
    if (op == 7 || op == 15 || op == 23 || op == 31 || op == 55 || op == 39 || op == 47
        || op == 63) return 1;
    if (op >= 64 && op < 120 && (op & 7) == 7) return 1;
    if (op >= 128 && op < 240 && (op & 7) == 7) return 1;
    return 0;
  }

  private static void getf(CodeBuilder c, String f, ClassDesc t) {
    c.aload(Z).getfield(CD_ZPAQL, f, t);
  }

  private static void putIntFromLocal(CodeBuilder c, String f, int slot) {
    c.aload(Z).iload(slot).putfield(CD_ZPAQL, f, ConstantDescs.CD_int);
  }

  private static void emit(CodeBuilder c, byte[] p, boolean[] start) {
    final int n = p.length;
    // prologue: load state
    c.iload(2).istore(A);  // a = input (slot 2 is the parameter)
    getf(c, "b", ConstantDescs.CD_int); c.istore(B);
    getf(c, "c", ConstantDescs.CD_int); c.istore(C);
    getf(c, "d", ConstantDescs.CD_int); c.istore(D);
    getf(c, "f", ConstantDescs.CD_boolean); c.istore(F);
    getf(c, "m", CD_BYTES); c.astore(M);
    getf(c, "h", CD_INTS); c.astore(H);
    getf(c, "r", CD_INTS); c.astore(R);
    getf(c, "mmask", ConstantDescs.CD_int); c.istore(MM);
    getf(c, "hmask", ConstantDescs.CD_int); c.istore(HM);

    Label[] labels = new Label[n + 1];
    for (int i = 0; i < n; i++) if (start[i]) labels[i] = c.newLabel();
    for (int i = 0; i < n; ) {
      int op = p[i] & 255;
      c.labelBinding(labels[i]);
      emitOp(c, op, i, p, labels);
      i += 1 + operands(op);
    }
    // falling off the end: the interpreter would execute the zero padding (error)
    err(c);
  }

  private static void err(CodeBuilder c) {
    c.invokestatic(CD_ZPAQL, "err", ConstantDescs.MTD_void).return_();
  }

  private static void halt(CodeBuilder c) {
    putIntFromLocal(c, "b", B);
    putIntFromLocal(c, "c", C);
    putIntFromLocal(c, "d", D);
    c.aload(Z).iload(F).putfield(CD_ZPAQL, "f", ConstantDescs.CD_boolean);
    c.return_();
  }

  private static final int[] REG = {A, B, C, D};

  /** Pushes value of source operand s (0 A,1 B,2 C,3 D,4 *B,5 *C,6 *D,7 N) for instruction at i. */
  private static void src(CodeBuilder c, int s, int opnd) {
    switch (s) {
      case 0, 1, 2, 3 -> c.iload(REG[s]);
      case 4 -> { c.aload(M).iload(B).iload(MM).iand().baload(); c.sipush(255).iand(); }
      case 5 -> { c.aload(M).iload(C).iload(MM).iand().baload(); c.sipush(255).iand(); }
      case 6 -> c.aload(H).iload(D).iload(HM).iand().iaload();
      default -> c.loadConstant(opnd);
    }
  }

  private static void mAddr(CodeBuilder c, int reg) {
    c.aload(M).iload(reg).iload(MM).iand();
  }

  private static void hAddr(CodeBuilder c) {
    c.aload(H).iload(D).iload(HM).iand();
  }

  private static void emitOp(CodeBuilder c, int op, int i, byte[] p, Label[] labels) {
    final int opnd = i + 1 < p.length ? p[i + 1] & 255 : 0;
    if (op < 64) {
      emitLow(c, op, i, p, labels, opnd);
    } else if (op < 120) {
      // dest = src
      int d = (op - 64) >> 3, s = op & 7;
      if (d == s) return;  // no-op
      switch (d) {
        case 0, 1, 2, 3 -> { src(c, s, opnd); c.istore(REG[d]); }
        case 4 -> { mAddr(c, B); src(c, s, opnd); c.i2b().bastore(); }
        case 5 -> { mAddr(c, C); src(c, s, opnd); c.i2b().bastore(); }
        default -> { hAddr(c); src(c, s, opnd); c.iastore(); }
      }
    } else if (op >= 128 && op < 240) {
      int k = (op - 128) >> 3, s = op & 7;
      switch (k) {
        case 0 -> { c.iload(A); src(c, s, opnd); c.iadd().istore(A); }
        case 1 -> { c.iload(A); src(c, s, opnd); c.isub().istore(A); }
        case 2 -> { c.iload(A); src(c, s, opnd); c.imul().istore(A); }
        case 3 -> { c.iload(A); src(c, s, opnd);
          c.invokestatic(CD_ZPAQL, "div", MethodTypeDesc.of(ConstantDescs.CD_int,
              ConstantDescs.CD_int, ConstantDescs.CD_int)).istore(A); }
        case 4 -> { c.iload(A); src(c, s, opnd);
          c.invokestatic(CD_ZPAQL, "mod", MethodTypeDesc.of(ConstantDescs.CD_int,
              ConstantDescs.CD_int, ConstantDescs.CD_int)).istore(A); }
        case 5 -> { if (s != 0) { c.iload(A); src(c, s, opnd); c.iand().istore(A); } }
        case 6 -> { c.iload(A); src(c, s, opnd); c.iconst_m1().ixor().iand().istore(A); }
        case 7 -> { if (s != 0) { c.iload(A); src(c, s, opnd); c.ior().istore(A); } }
        case 8 -> { c.iload(A); src(c, s, opnd); c.ixor().istore(A); }
        case 9 -> { c.iload(A); src(c, s, opnd); c.ishl().istore(A); }
        case 10 -> { c.iload(A); src(c, s, opnd); c.iushr().istore(A); }
        case 11 -> cmp(c, s, opnd, 0);
        case 12 -> cmp(c, s, opnd, 1);
        default -> cmp(c, s, opnd, 2);
      }
    } else if (op == 255) {
      int t = (p[i + 1] & 255) + 256 * (p[i + 2] & 255);
      c.goto_(labels[t]);
    } else {
      err(c);
    }
  }

  // f = A (==, <, >) src  (unsigned compare for < and >)
  private static void cmp(CodeBuilder c, int s, int opnd, int kind) {
    if (s == 0) {  // compare with itself
      c.loadConstant(kind == 0 ? 1 : 0).istore(F);
      return;
    }
    Label t = c.newLabel(), e = c.newLabel();
    if (kind == 0) {
      c.iload(A);
      src(c, s, opnd);
      c.if_icmpeq(t);
    } else {
      // unsigned compare: flip the sign bits
      c.iload(A).loadConstant(Integer.MIN_VALUE).ixor();
      src(c, s, opnd);
      c.loadConstant(Integer.MIN_VALUE).ixor();
      if (kind == 1) c.if_icmplt(t); else c.if_icmpgt(t);
    }
    c.iconst_0().istore(F).goto_(e);
    c.labelBinding(t);
    c.iconst_1().istore(F);
    c.labelBinding(e);
  }

  private static void emitLow(CodeBuilder c, int op, int i, byte[] p, Label[] labels, int opnd) {
    int r = op >> 3, k = op & 7;
    switch (op) {
      case 56 -> { halt(c); return; }
      case 57 -> {  // OUT
        c.aload(Z).iload(A).sipush(255).iand()
            .invokevirtual(CD_ZPAQL, "outc", MethodTypeDesc.of(ConstantDescs.CD_void,
                ConstantDescs.CD_int));
        return;
      }
      case 59 -> {  // A = (A + *B + 512) * 773
        c.iload(A);
        c.aload(M).iload(B).iload(MM).iand().baload().sipush(255).iand().iadd();
        c.sipush(512).iadd().sipush(773).imul().istore(A);
        return;
      }
      case 60 -> {  // *D = (*D + A + 512) * 773
        hAddr(c);
        hAddr(c);
        c.iaload();
        c.iload(A).iadd().sipush(512).iadd().sipush(773).imul().iastore();
        return;
      }
      case 39, 47, 63 -> {
        int t = i + 2 + p[i + 1];
        if (op == 63) {
          c.goto_(labels[t]);
        } else {
          c.iload(F);
          if (op == 39) c.ifne(labels[t]); else c.ifeq(labels[t]);
        }
        return;
      }
      case 55 -> { c.aload(R).loadConstant(opnd).iload(A).iastore(); return; }
      default -> { }
    }
    if (r > 6 || k == 5 || k == 6 || (op == 0)) {
      err(c);
      return;
    }
    if (k == 7) {  // reg = R[n]
      if (r > 3) { err(c); return; }
      c.aload(R).loadConstant(opnd).iaload().istore(REG[r]);
      return;
    }
    if (k == 0) {  // swap with A (r>0)
      switch (r) {
        case 1, 2, 3 -> c.iload(A).iload(REG[r]).istore(A).istore(REG[r]);
        case 4, 5 -> {
          int reg = r == 4 ? B : C;
          // t = m[i]&255; m[i] = (byte)a; a = (a & ~255) | t
          c.aload(M).iload(reg).iload(MM).iand().baload().sipush(255).iand();  // t
          mAddr(c, reg); c.iload(A).i2b().bastore();
          c.iload(A).loadConstant(~255).iand().ior().istore(A);
        }
        default -> {
          hAddr(c);
          c.iaload();  // t = h[i]
          hAddr(c); c.iload(A).iastore();
          c.istore(A);
        }
      }
      return;
    }
    // k = 1 ++, 2 --, 3 !, 4 =0
    switch (r) {
      case 0, 1, 2, 3 -> {
        int s = REG[r];
        switch (k) {
          case 1 -> c.iinc(s, 1);
          case 2 -> c.iinc(s, -1);
          case 3 -> c.iload(s).iconst_m1().ixor().istore(s);
          default -> c.iconst_0().istore(s);
        }
      }
      case 4, 5 -> {
        int reg = r == 4 ? B : C;
        mAddr(c, reg);
        switch (k) {
          case 1 -> { mAddr(c, reg); c.baload().iconst_1().iadd(); }
          case 2 -> { mAddr(c, reg); c.baload().iconst_1().isub(); }
          case 3 -> { mAddr(c, reg); c.baload().iconst_m1().ixor(); }
          default -> c.iconst_0();
        }
        c.i2b().bastore();
      }
      default -> {
        hAddr(c);
        switch (k) {
          case 1 -> { hAddr(c); c.iaload().iconst_1().iadd(); }
          case 2 -> { hAddr(c); c.iaload().iconst_1().isub(); }
          case 3 -> { hAddr(c); c.iaload().iconst_m1().ixor(); }
          default -> c.iconst_0();
        }
        c.iastore();
      }
    }
  }
}
