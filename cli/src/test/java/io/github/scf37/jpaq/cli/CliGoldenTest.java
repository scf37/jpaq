package io.github.scf37.jpaq.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs the scenarios in process and compares archive hashes, listing hashes and extracted tree
 * hashes with those recorded from the reference C++ zpaq 7.15 ({@code cli-golden.txt}).
 */
class CliGoldenTest {
  static Stream<String> scenarios() throws IOException {
    return ScenarioRunner.parse(ScenarioRunner.loadScenarios()).keySet().stream();
  }

  static List<String> golden(String name) throws IOException {
    List<String> r = new ArrayList<>();
    try (BufferedReader br = new BufferedReader(new InputStreamReader(
        CliGoldenTest.class.getResourceAsStream("/cli-golden.txt"), StandardCharsets.UTF_8))) {
      String l;
      while ((l = br.readLine()) != null) if (l.startsWith(name + "|")) r.add(l);
    }
    return r;
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  void matchesReference(String name, @TempDir Path dir) throws Exception {
    Map<String, List<String>> all = ScenarioRunner.parse(ScenarioRunner.loadScenarios());
    ScenarioRunner runner = new ScenarioRunner(null, null);
    List<String> got = runner.run(name, all.get(name), dir);
    assertEquals(golden(name), got);
  }
}
