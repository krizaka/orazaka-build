package com.krizaka.orazaka.test.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What counts as a {@code job.*} producer for [BILL-001]: a class that sends to the jobs exchange,
 * never one that only listens on it. A listener's {@code @Exchange} binding names the exchange, and
 * counting it made {@code JobCancelListener} (a consumer) fail the rule on main.
 */
class MeteringMarkerRulesTest {

  private static final String LISTENER =
      """
      @RabbitListener(
          bindings =
              @QueueBinding(
                  value = @Queue(exclusive = "true", autoDelete = "true"),
                  exchange =
                      @Exchange(
                          name = "orazaka.jobs",
                          type = ExchangeTypes.TOPIC,
                          durable = "true"),
                  key = "job.cancel.*"))
      public void onCancel(Message message) {}
      """;

  @Test
  @DisplayName("a listener that only binds the jobs exchange is not a producer")
  void listenerBindingIsNotAProducer() {
    assertThat(MeteringMarkerRules.sendsToJobsExchange(LISTENER)).isFalse();
  }

  @Test
  @DisplayName("a class that sends to the jobs exchange is a producer")
  void senderIsAProducer() {
    assertThat(
            MeteringMarkerRules.sendsToJobsExchange(
                "outbox.append(new OutboxMessage(\"orazaka.jobs\", key, payload));"))
        .isTrue();
    assertThat(
            MeteringMarkerRules.sendsToJobsExchange("rabbit.convertAndSend(JOBS_EXCHANGE, k, p);"))
        .isTrue();
  }

  @Test
  @DisplayName("a listener that also sends is still a producer")
  void listenerThatSendsIsAProducer() {
    assertThat(
            MeteringMarkerRules.sendsToJobsExchange(
                LISTENER + "\nrabbit.convertAndSend(\"orazaka.jobs\", k, p);"))
        .isTrue();
  }
}
