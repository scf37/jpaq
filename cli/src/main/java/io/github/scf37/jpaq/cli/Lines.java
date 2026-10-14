package io.github.scf37.jpaq.cli;

import io.github.scf37.jpaq.api.Line;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inside the program file names and messages are "byte strings" (chars 0..255 holding the UTF-8
 * bytes, so that names that are not valid UTF-8 survive). Lines given to API callers have real
 * text; this converts every String component of a line.
 */
final class Lines {
  private Lines() {}

  private record Shape(Constructor<?> ctor, RecordComponent[] components) {}

  private static final Map<Class<?>, Shape> SHAPES = new ConcurrentHashMap<>();

  private static Shape shape(Class<?> c) {
    return SHAPES.computeIfAbsent(c, k -> {
      try {
        RecordComponent[] rc = k.getRecordComponents();
        Class<?>[] types = new Class<?>[rc.length];
        for (int i = 0; i < rc.length; i++) types[i] = rc[i].getType();
        Constructor<?> ctor = k.getDeclaredConstructor(types);
        ctor.setAccessible(true);
        return new Shape(ctor, rc);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    });
  }

  /** The line with all text converted from byte strings to real text. */
  static Line decode(Line line) {
    Shape sh = shape(line.getClass());
    Object[] v = new Object[sh.components().length];
    try {
      for (int i = 0; i < v.length; i++) {
        Object o = sh.components()[i].getAccessor().invoke(line);
        v[i] = o instanceof String s ? Strs.fromBytes(s) : o;
      }
      return (Line) sh.ctor().newInstance(v);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
