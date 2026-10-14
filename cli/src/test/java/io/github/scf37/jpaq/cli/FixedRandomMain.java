package io.github.scf37.jpaq.cli;

/**
 * Test launcher: runs the CLI with the deterministic random generator that the patched reference
 * build (tools/difftest/build-ref.sh) uses for encryption salts, so that encrypted archives can be
 * compared byte for byte.
 */
public final class FixedRandomMain {
  private FixedRandomMain() {}

  public static void main(String[] args) {
    ScenarioRunner.installFixedRandom();
    int rc = Main.run(args);
    System.out.flush();
    System.exit(rc);
  }
}
