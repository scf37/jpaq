package io.github.scf37.jpaq;

/** The storage lacks a capability that the requested archive format or command needs. */
public final class UnsupportedStorageException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final Capability missing;

  public UnsupportedStorageException(Capability missing, String why) {
    super("storage does not support " + missing + ": " + why);
    this.missing = missing;
  }

  public Capability missing() {
    return missing;
  }
}
