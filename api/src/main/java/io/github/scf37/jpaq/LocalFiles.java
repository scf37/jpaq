package io.github.scf37.jpaq;

import io.github.scf37.jpaq.engine.Ctx;
import io.github.scf37.jpaq.engine.Jidac;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** The local files as {@link ZpaqArchive#add} sees them. */
public final class LocalFiles {
  private LocalFiles() {}

  /**
   * The local files and directories under {@code files} (directories end with '/'), in name
   * order, with the dates, sizes and attributes an update would store.
   *
   * @param dir directory against which relative names are resolved, null for the current one
   * @param prune names not looked at (directories not entered); null for none
   * @param accept names to return, of those looked at; null for all
   * @param listener receives {@link Event.FileError}s for files that can't be read; may be null
   */
  public static List<LocalFile> scan(Path dir, List<String> files, Predicate<String> prune,
      Predicate<String> accept, boolean noAttributes, Consumer<Event> listener) {
    Ctx ctx = new Ctx(listener != null ? listener : e -> { }, dir, null);
    Jidac j = new Jidac(ctx, new LocalFsStorage(dir), Format.CLASSIC);
    for (String f : files) j.roots.add(Names.bytes(f));
    if (prune != null) j.prune = Names.wrap(prune);
    if (accept != null) j.accept = Names.wrap(accept);
    j.noattributes = noAttributes;
    return j.scanLocal();
  }
}
