package io.github.scf37.jpaq.cli;

import io.github.scf37.jpaq.libzpaq.Crypto;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Runs the scenarios of {@code scenarios.txt} either in process with this implementation or with
 * an external reference binary, and records the results (archive hashes, listing hashes, extracted
 * tree hashes) as lines {@code scenario|step|kind|name|size|sha256}.
 *
 * <p>Scenario syntax: {@code scenario NAME}, {@code zpaq ARGS...}, {@code mutate N}, {@code hash
 * FILE}, {@code out} (hash of the standard output of the preceding zpaq command), {@code tree
 * DIR}, {@code fixedrandom}, {@code epoch SECONDS} (current time).
 */
final class ScenarioRunner {
  private final Path ref;        // external reference binary or null for in process
  private final Path refFixed;   // external binary with the fixed random generator
  private boolean fixed;
  private String epoch;  // SOURCE_DATE_EPOCH for the next commands, or null

  ScenarioRunner(Path ref, Path refFixed) {
    this.ref = ref;
    this.refFixed = refFixed;
  }

  static List<String> loadScenarios() throws IOException {
    try (InputStream in = ScenarioRunner.class.getResourceAsStream("/scenarios.txt")) {
      return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"));
    }
  }

  private static String sha256(byte[] b) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Deterministic random source shared with the patched C++ reference build. */
  static void installFixedRandom() {
    final int[] counter = {0};
    Crypto.setRandomSource(buf -> {
      try {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update("seed".getBytes(StandardCharsets.US_ASCII));
        int c = counter[0]++;
        md.update(new byte[] {(byte) (c >> 24), (byte) (c >> 16), (byte) (c >> 8), (byte) c});
        byte[] r = md.digest();
        for (int i = 0; i < buf.length; i++) buf[i] = r[i % 32];
      } catch (java.security.NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    });
  }

  /** Runs one scenario's lines (without the scenario header) in a fresh directory. */
  List<String> run(String name, List<String> lines, Path dir) throws Exception {
    List<String> results = new ArrayList<>();
    TreeBuilder.build(dir);
    fixed = false;
    epoch = null;
    Jidac.clockOverride = null;
    Crypto.setRandomSource(null);
    byte[] lastOut = new byte[0];
    int step = 0;
    try {
      for (String line : lines) {
        line = line.strip();
        if (line.isEmpty() || line.startsWith("#")) continue;
        String[] w = line.split("\\s+");
        step++;
        switch (w[0]) {
          case "fixedrandom" -> {
            fixed = true;
            installFixedRandom();
          }
          case "epoch" -> {
            epoch = w[1];
            Jidac.clockOverride = java.time.Instant.ofEpochSecond(Long.parseLong(epoch));
          }
          case "mutate" -> TreeBuilder.mutate(dir, Integer.parseInt(w[1]));
          case "zpaq" -> {
            String[] args = java.util.Arrays.copyOfRange(w, 1, w.length);
            lastOut = runZpaq(dir, args);
          }
          case "hash" -> {
            byte[] b = Files.readAllBytes(dir.resolve(w[1]));
            results.add(name + "|" + step + "|file|" + w[1] + "|" + b.length + "|" + sha256(b));
          }
          case "out" -> {
            String s = new String(lastOut, StandardCharsets.ISO_8859_1);
            int nl = s.indexOf('\n');
            byte[] body = s.substring(nl + 1).getBytes(StandardCharsets.ISO_8859_1);
            results.add(name + "|" + step + "|out|stdout|" + body.length + "|" + sha256(body));
          }
          case "tree" -> results.add(name + "|" + step + "|tree|" + w[1] + "|0|"
              + TreeBuilder.treeHash(dir.resolve(w[1])));
          default -> throw new IllegalArgumentException(line);
        }
      }
    } finally {
      Crypto.setRandomSource(null);
      Jidac.clockOverride = null;
    }
    return results;
  }

  private byte[] runZpaq(Path dir, String[] args) throws Exception {
    if (ref == null) {
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      PrintStream oldOut = Console.out;
      PrintStream oldErr = Console.err;
      PrintStream out = new PrintStream(bo, false, StandardCharsets.ISO_8859_1);
      Console.setStreams(out, new PrintStream(new ByteArrayOutputStream()));
      try {
        Main.run(dir, args);
      } finally {
        out.flush();
        Console.setStreams(oldOut, oldErr);
      }
      return bo.toByteArray();
    }
    List<String> cmd = new ArrayList<>();
    cmd.add((fixed ? refFixed : ref).toString());
    cmd.addAll(List.of(args));
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
    if (epoch != null) pb.environment().put("SOURCE_DATE_EPOCH", epoch);
    pb.redirectError(ProcessBuilder.Redirect.DISCARD);
    Process p = pb.start();
    byte[] out = p.getInputStream().readAllBytes();
    p.waitFor();
    return out;
  }

  /** Reads the scenarios file into name -> lines. */
  static java.util.Map<String, List<String>> parse(List<String> all) {
    java.util.Map<String, List<String>> m = new java.util.LinkedHashMap<>();
    List<String> cur = null;
    for (String l : all) {
      String t = l.strip();
      if (t.startsWith("scenario ")) {
        cur = new ArrayList<>();
        m.put(t.substring(9).strip(), cur);
      } else if (cur != null) {
        cur.add(l);
      }
    }
    return m;
  }

  /** Generator entry point: prints the golden lines produced by the external reference binary. */
  public static void main(String[] a) throws Exception {
    Path ref = Path.of(a[0]);
    Path refFixed = Path.of(a[1]);
    ScenarioRunner r = new ScenarioRunner(ref, refFixed);
    var scenarios = parse(loadScenarios());
    for (var e : scenarios.entrySet()) {
      Path dir = Files.createTempDirectory("scen");
      try {
        for (String l : r.run(e.getKey(), e.getValue(), dir)) System.out.println(l);
      } finally {
        TreeBuilder.delete(dir);
      }
    }
  }
}
