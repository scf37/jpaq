package io.github.scf37.jpaq.libzpaq;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates, for a model (the COMP section of a block header), a class with unrolled
 * {@code predict} / {@code update} methods that call the component steps of {@link PredOps} with
 * the component numbers as constants.
 */
final class PredictorJit {
  private PredictorJit() {}

  private static final boolean ENABLED = !"false".equals(System.getProperty("jpaq.predictorJit"));
  private static final Object NONE = new Object();
  /** Upper bound for the number of generated classes kept alive. */
  private static final int MAX_CLASSES = 512;
  private static final Map<ByteBuffer, Object> CACHE = new ConcurrentHashMap<>();

  private static final String PKG = PredictorJit.class.getPackageName();
  private static final ClassDesc CD_PRED = ClassDesc.of(PKG, "Predictor");
  private static final ClassDesc CD_CODE = ClassDesc.of(PKG, "Predictor$Code");
  private static final ClassDesc CD_COMP = ClassDesc.of(PKG, "Predictor$Component");
  private static final ClassDesc CD_OPS = ClassDesc.of(PKG, "PredOps");
  private static final ClassDesc CD_INTS = ConstantDescs.CD_int.arrayType();
  private static final ClassDesc CD_COMPS = CD_COMP.arrayType();
  private static final ClassDesc I = ConstantDescs.CD_int;
  private static final ClassDesc V = ConstantDescs.CD_void;

  /**
   * Returns generated code for the n components starting at header[7], or null. A model that has
   * not been compiled yet is only compiled if {@code compile} is set.
   */
  static Predictor.Code get(byte[] header, int n, boolean compile) {
    if (!ENABLED) return null;
    int len = 0;
    for (int i = 0, cp = 7; i < n; i++) {
      int t = header[cp] & 255;
      len += Components.size(t);
      cp += Components.size(t);
    }
    byte[] key = Arrays.copyOfRange(header, 6, 7 + len);  // n + components
    ByteBuffer bb = ByteBuffer.wrap(key);
    Object o = CACHE.get(bb);
    if (o == null) {
      if (!compile || CACHE.size() >= MAX_CLASSES) return null;  // too many distinct models
      Predictor.Code c = null;
      try {
        c = generate(key);
      } catch (RuntimeException | LinkageError | ReflectiveOperationException e) {
        // use the generic loop
      }
      o = c != null ? c : NONE;
      CACHE.put(bb, o);
    }
    return o == NONE ? null : (Predictor.Code) o;
  }

  private static MethodTypeDesc mt(ClassDesc ret, ClassDesc... args) {
    return MethodTypeDesc.of(ret, args);
  }

  private static Predictor.Code generate(byte[] key) throws ReflectiveOperationException {
    final int n = key[0] & 255;
    final int[] types = new int[n];
    final int[][] par = new int[n][6];  // c1..c5 at 1..5
    for (int i = 0, cp = 1; i < n; i++) {
      types[i] = key[cp] & 255;
      for (int k = 1; k < Components.size(types[i]); k++) par[i][k] = key[cp + k] & 255;
      cp += Components.size(types[i]);
    }
    final ClassDesc self = ClassDesc.of(PKG, "PredictorCode");
    byte[] bytes = ClassFile.of().build(self, cb -> {
      cb.withFlags(ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC);
      cb.withInterfaceSymbols(CD_CODE);
      cb.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC,
          cob -> cob.aload(0)
              .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                  ConstantDescs.MTD_void)
              .return_());
      cb.withMethodBody("predict", mt(I, CD_PRED), ClassFile.ACC_PUBLIC,
          cob -> emitPredict(cob, types, par));
      cb.withMethodBody("update", mt(V, CD_PRED, I), ClassFile.ACC_PUBLIC,
          cob -> emitUpdate(cob, types, par));
    });
    MethodHandles.Lookup l = MethodHandles.lookup().defineHiddenClass(bytes, true);
    try {
      return (Predictor.Code) l.findConstructor(l.lookupClass(), MethodType.methodType(void.class))
          .invoke();
    } catch (Throwable t) {
      throw new ReflectiveOperationException(t);
    }
  }

  private static void field(CodeBuilder c, String name, ClassDesc type) {
    c.aload(1).getfield(CD_PRED, name, type);
  }

  private static int mask(long size) {
    return (int) (size - 1);
  }

  // predict(Predictor pr): slots 2 p, 3 h, 4 comp, 5 c8, 6 hmap4
  private static void emitPredict(CodeBuilder c, int[] types, int[][] par) {
    field(c, "p", CD_INTS); c.astore(2);
    field(c, "h", CD_INTS); c.astore(3);
    field(c, "comp", CD_COMPS); c.astore(4);
    field(c, "c8", I); c.istore(5);
    field(c, "hmap4", I); c.istore(6);
    for (int i = 0; i < types.length; i++) {
      final int t = types[i];
      final int[] q = par[i];
      if (t == Components.CONS) continue;
      if (t != Components.AVG) c.aload(4).loadConstant(i).aaload();  // cr
      switch (t) {
        case Components.CM -> {
          c.aload(2).aload(3).loadConstant(i).iload(6).loadConstant(mask(1L << q[1]));
          c.invokestatic(CD_OPS, "predictCm", mt(V, CD_COMP, CD_INTS, CD_INTS, I, I, I));
        }
        case Components.ICM -> {
          c.aload(2).aload(3).loadConstant(i).iload(5).iload(6).loadConstant(q[1] + 2);
          c.invokestatic(CD_OPS, "predictIcm", mt(V, CD_COMP, CD_INTS, CD_INTS, I, I, I, I));
        }
        case Components.MATCH -> {
          c.aload(2).loadConstant(i).loadConstant(mask(1L << q[2]));
          c.invokestatic(CD_OPS, "predictMatch", mt(V, CD_COMP, CD_INTS, I, I));
        }
        case Components.AVG -> {
          c.aload(2).loadConstant(i).loadConstant(q[1]).loadConstant(q[2]).loadConstant(q[3]);
          c.invokestatic(CD_OPS, "predictAvg", mt(V, CD_INTS, I, I, I, I));
        }
        case Components.MIX2 -> {
          c.aload(2).aload(3).loadConstant(i).iload(5).loadConstant(q[2]).loadConstant(q[3])
              .loadConstant(q[5]).loadConstant(mask(1L << q[1]));
          c.invokestatic(CD_OPS, "predictMix2",
              mt(V, CD_COMP, CD_INTS, CD_INTS, I, I, I, I, I, I));
        }
        case Components.MIX -> {
          c.aload(2).aload(3).loadConstant(i).iload(5).loadConstant(q[3]).loadConstant(q[2])
              .loadConstant(q[5]).loadConstant(mask(1L << q[1]));
          c.invokestatic(CD_OPS, "predictMix",
              mt(V, CD_COMP, CD_INTS, CD_INTS, I, I, I, I, I, I));
        }
        case Components.ISSE -> {
          c.aload(2).aload(3).loadConstant(i).iload(5).iload(6).loadConstant(q[2])
              .loadConstant(q[1] + 2);
          c.invokestatic(CD_OPS, "predictIsse",
              mt(V, CD_COMP, CD_INTS, CD_INTS, I, I, I, I, I));
        }
        case Components.SSE -> {
          c.aload(2).aload(3).loadConstant(i).iload(5).loadConstant(q[2])
              .loadConstant(mask(32L << q[1]));
          c.invokestatic(CD_OPS, "predictSse", mt(V, CD_COMP, CD_INTS, CD_INTS, I, I, I, I));
        }
        default -> throw new IllegalStateException();
      }
    }
    c.aload(2).loadConstant(types.length - 1).iaload();
    c.invokestatic(CD_OPS, "squash", mt(I, I));
    c.ireturn();
  }

  // update(Predictor pr, int y): slots 2 y, 3 p, 4 h, 5 comp, 6 hmap4
  private static void emitUpdate(CodeBuilder c, int[] types, int[][] par) {
    field(c, "p", CD_INTS); c.astore(3);
    field(c, "h", CD_INTS); c.astore(4);
    field(c, "comp", CD_COMPS); c.astore(5);
    field(c, "hmap4", I); c.istore(6);
    for (int i = 0; i < types.length; i++) {
      final int t = types[i];
      final int[] q = par[i];
      if (t == Components.CONS || t == Components.AVG) continue;
      c.aload(5).loadConstant(i).aaload();  // cr
      switch (t) {
        case Components.CM -> {
          c.iload(2).loadConstant(mask(1L << q[1])).loadConstant(q[2] * 4);
          c.invokestatic(CD_OPS, "updateCm", mt(V, CD_COMP, I, I, I));
        }
        case Components.ICM -> {
          c.iload(2).iload(6);
          c.invokestatic(CD_OPS, "updateIcm", mt(V, CD_COMP, I, I));
        }
        case Components.MATCH -> {
          c.aload(4).loadConstant(i).iload(2).loadConstant(mask(1L << q[2]))
              .loadConstant(mask(1L << q[1]));
          c.invokestatic(CD_OPS, "updateMatch", mt(V, CD_COMP, CD_INTS, I, I, I, I));
        }
        case Components.MIX2 -> {
          c.aload(3).loadConstant(i).iload(2).loadConstant(q[2]).loadConstant(q[3])
              .loadConstant(q[4]);
          c.invokestatic(CD_OPS, "updateMix2", mt(V, CD_COMP, CD_INTS, I, I, I, I, I));
        }
        case Components.MIX -> {
          c.aload(3).loadConstant(i).iload(2).loadConstant(q[3]).loadConstant(q[2])
              .loadConstant(q[4]);
          c.invokestatic(CD_OPS, "updateMix", mt(V, CD_COMP, CD_INTS, I, I, I, I, I));
        }
        case Components.ISSE -> {
          c.aload(3).loadConstant(i).iload(2).iload(6).loadConstant(q[2]);
          c.invokestatic(CD_OPS, "updateIsse", mt(V, CD_COMP, CD_INTS, I, I, I, I));
        }
        case Components.SSE -> {
          c.iload(2).loadConstant(mask(32L << q[1])).loadConstant(q[4] * 4);
          c.invokestatic(CD_OPS, "updateSse", mt(V, CD_COMP, I, I, I));
        }
        default -> throw new IllegalStateException();
      }
    }
    c.aload(1).iload(2).invokevirtual(CD_PRED, "endUpdate", mt(V, I));
    c.return_();
  }
}
