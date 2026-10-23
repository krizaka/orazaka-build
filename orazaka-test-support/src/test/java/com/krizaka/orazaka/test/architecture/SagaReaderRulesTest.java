package com.krizaka.orazaka.test.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The verdict behind [SAGA-003], on synthetic sources.
 *
 * <p>The rule reads {@code RunSagaService} as text, and every way text can fool it has to be a
 * test: an overload left unread, a generation of readers half renamed.
 */
class SagaReaderRulesTest {

  private static String saga(Set<String> readers, String extra) {
    StringBuilder source = new StringBuilder("public class RunSagaService {\n");
    for (String reader : new TreeSet<>(readers)) {
      source.append("  private Object ").append(reader).append("(String a) {\n");
      source.append("    return jdbc.query(\"SELECT 1 FROM studio_run_step\");\n  }\n");
    }
    source.append(extra).append("}\n");
    return source.toString();
  }

  private static List<String> offenders(String source) {
    List<String> offenders = new ArrayList<>();
    GovernanceRules.sagaReaderVerdict(source, new ArrayList<>(), offenders);
    return offenders;
  }

  private static Set<String> with(Set<String> generation) {
    Set<String> readers = new TreeSet<>(GovernanceRules.SAGA_READERS);
    readers.addAll(generation);
    return readers;
  }

  @Test
  @DisplayName("each generation of step readers passes on its own")
  void eachGenerationPasses() {
    for (Set<String> generation : GovernanceRules.SAGA_STEP_READER_GENERATIONS) {
      assertThat(offenders(saga(with(generation), ""))).as(generation.toString()).isEmpty();
    }
  }

  @Test
  @DisplayName("a generation half renamed is a violation by name")
  void aHalfRenamedGenerationFails() {
    Set<String> readers = with(Set.of("stepStates", "stepCauses"));
    assertThat(offenders(saga(readers, ""))).isNotEmpty();
  }

  @Test
  @DisplayName("a reader that writes is an offender")
  void aWritingReaderFails() {
    Set<String> readers = with(Set.of("stepSnapshot"));
    readers.remove("loadRun");
    String writing =
        "  private Object loadRun(String a) {\n"
            + "    jdbc.update(\"UPDATE studio_run SET status = 'X' WHERE id = ?\");\n"
            + "    return null;\n  }\n";
    assertThat(offenders(saga(readers, writing))).anyMatch(o -> o.startsWith("loadRun writes"));
  }

  @Test
  @DisplayName("every overload of a reader is read, not only the first")
  void everyOverloadIsRead() {
    Set<String> readers = with(Set.of("stepSnapshot"));
    String overload =
        "  private void advance(Object run, Object blueprint) {\n"
            + "    jdbc.update(\"DELETE FROM studio_run_step WHERE run_id = ?\");\n  }\n";
    assertThat(offenders(saga(readers, overload))).anyMatch(o -> o.startsWith("advance writes"));
  }
}
