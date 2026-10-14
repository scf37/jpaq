package io.github.scf37.jpaq.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Options of a command (the zpaq command line options that have a meaning for the command).
 * Mutable builder style: {@code new Options().key("secret").threads(2)}. Unset options have the
 * defaults of the command line.
 */
public final class Options {
  String key;
  int threads;
  String method;
  int fragment = -1;
  boolean force;
  boolean noAttributes;
  int summary;
  Integer all;
  String until;
  final List<String> only = new ArrayList<>();
  final List<String> not = new ArrayList<>();

  public Options() {}

  /** Password for encrypted archives (-key). */
  public Options key(String v) {
    key = v;
    return this;
  }

  /** Number of threads, 0 for the number of cores (-threads). */
  public Options threads(int v) {
    threads = v;
    return this;
  }

  /** Compression method, "0".."5" or an advanced method string (-method). */
  public Options method(String v) {
    method = v;
    return this;
  }

  /** Average fragment size 2^n KiB (-fragment). */
  public Options fragment(int v) {
    fragment = v;
    return this;
  }

  /** add: also add files whose content changed with the same date; extract: overwrite (-force). */
  public Options force(boolean v) {
    force = v;
    return this;
  }

  /** Don't store or restore permissions (-noattributes). */
  public Options noAttributes(boolean v) {
    noAttributes = v;
    return this;
  }

  /** Brief progress when &gt; 0, listing of the largest files, -1 shows fragment IDs (-summary). */
  public Options summary(int v) {
    summary = v;
    return this;
  }

  /** Show every version in directories of n digits (-all). */
  public Options all(int digits) {
    all = digits;
    return this;
  }

  /** Version number, negative for counting from the end, or a date (-until). */
  public Options until(String v) {
    until = v;
    return this;
  }

  /** Only these files (-only). */
  public Options only(String... v) {
    only.addAll(List.of(v));
    return this;
  }

  /** Not these files (-not). */
  public Options not(String... v) {
    not.addAll(List.of(v));
    return this;
  }

  /** The command line options for these settings. */
  public List<String> toArgs() {
    List<String> a = new ArrayList<>();
    if (key != null) a.addAll(List.of("-key", key));
    if (threads > 0) a.addAll(List.of("-threads", Integer.toString(threads)));
    if (method != null && !method.isEmpty()) a.addAll(List.of("-method", method));
    if (fragment >= 0) a.addAll(List.of("-fragment", Integer.toString(fragment)));
    if (force) a.add("-force");
    if (noAttributes) a.add("-noattributes");
    if (summary != 0) a.addAll(List.of("-summary", Integer.toString(summary)));
    if (all != null) a.addAll(List.of("-all", Integer.toString(all)));
    if (until != null) a.addAll(List.of("-until", until));
    if (!only.isEmpty()) {
      a.add("-only");
      a.addAll(only);
    }
    if (!not.isEmpty()) {
      a.add("-not");
      a.addAll(not);
    }
    return a;
  }
}
