package dev.antennalab.core;

import dev.antennalab.core.domain.AntennaPath;
import dev.antennalab.core.domain.RssiSample;
import dev.antennalab.core.source.CommandChannel;
import dev.antennalab.core.source.ReconnectingProducer;
import dev.antennalab.core.source.SampleProducer;
import dev.antennalab.core.source.SampleSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reconnect behaviour, driven by a delegate that fails on cue.
 *
 * <p>These run with a near-zero retry delay: the policy under test is "what
 * happens across a reconnect", not "how long we wait", and a test that sleeps
 * for the real two seconds per attempt buys nothing.
 */
class ReconnectingProducerTest {

    private static final Instant T0 = Instant.parse("2026-08-13T10:00:00Z");
    private static final Duration FAST = Duration.ofMillis(1);

    /**
     * Emits {@code perLife} samples, then either dies or ends cleanly.
     *
     * <p>Each instance numbers its samples from zero, exactly as a real
     * {@link dev.antennalab.core.source.SerialProducer} does -- which is the
     * behaviour the wrapper has to correct for.
     */
    private static final class FlakyProducer implements SampleProducer, CommandChannel {
        private final int perLife;
        private final boolean dieAtEnd;
        private final AtomicInteger lives;
        boolean closed;

        FlakyProducer(int perLife, boolean dieAtEnd, AtomicInteger lives) {
            this.perLife = perLife;
            this.dieAtEnd = dieAtEnd;
            this.lives = lives;
        }

        @Override
        public void produce(SampleSink sink) throws Exception {
            int life = lives.incrementAndGet();
            for (int i = 0; i < perLife; i++) {
                sink.accept(new RssiSample(i, T0.plusSeconds(i),
                        AntennaPath.CHIP, -40.0 - life));
            }
            if (dieAtEnd) {
                throw new IOException("COM6 disconnected");
            }
        }

        @Override
        public String describe() {
            return "flaky";
        }

        @Override
        public Optional<CommandChannel> commands() {
            return Optional.of(this);
        }

        @Override
        public void sendCommand(byte[] bytes) {
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static List<RssiSample> collect(SampleProducer producer) throws Exception {
        List<RssiSample> out = new ArrayList<>();
        producer.produce(out::add);
        return out;
    }

    @Test
    @Timeout(20)
    @DisplayName("sequence numbers stay unique and monotonic across a reconnect")
    void sequenceIsContinuousAcrossReconnects() throws Exception {
        AtomicInteger lives = new AtomicInteger();
        // Three lives of 4 samples: die, die, then end cleanly.
        var producer = new ReconnectingProducer(
                () -> new FlakyProducer(4, lives.get() < 2, lives),
                true, 5, FAST, new ReconnectingProducer.Listener() {
        });

        List<RssiSample> samples = collect(producer);

        assertEquals(12, samples.size(), "every life's samples must be kept");

        // The decisive assertion. Each delegate numbers from zero, so passing
        // its numbering through would give 0,1,2,3,0,1,2,3,... -- duplicate keys
        // in one session, and a silently corrupt saved record.
        for (int i = 0; i < samples.size(); i++) {
            assertEquals(i, samples.get(i).sequence(),
                    "sequence must be continuous across reconnects, index " + i);
        }
    }

    @Test
    @Timeout(20)
    @DisplayName("samples captured before a drop are not lost")
    void samplesBeforeADropSurvive() throws Exception {
        AtomicInteger lives = new AtomicInteger();
        var producer = new ReconnectingProducer(
                () -> new FlakyProducer(3, lives.get() < 1, lives),
                true, 5, FAST, new ReconnectingProducer.Listener() {
        });

        List<RssiSample> samples = collect(producer);

        // The whole point: a five-second reboot must not cost the ten minutes
        // of run that preceded it.
        assertEquals(6, samples.size());
        assertTrue(samples.stream().anyMatch(s -> s.rssiDbm() == -41.0),
                "first life's samples must still be present");
    }

    @Test
    @Timeout(20)
    @DisplayName("the listener is told on the way down and on the way back up")
    void listenerSeesLossAndRecovery() throws Exception {
        AtomicInteger lives = new AtomicInteger();
        List<String> events = new ArrayList<>();

        var producer = new ReconnectingProducer(
                () -> new FlakyProducer(2, lives.get() < 1, lives),
                true, 5, FAST, new ReconnectingProducer.Listener() {
                    @Override
                    public void onConnectionLost(String reason, int attempt, int max) {
                        events.add("lost:" + reason + ":" + attempt);
                    }

                    @Override
                    public void onReconnected(int attempt) {
                        events.add("back:" + attempt);
                    }
                });

        collect(producer);

        assertEquals(List.of("lost:COM6 disconnected:1", "back:1"), events);
    }

    @Test
    @Timeout(20)
    @DisplayName("a device that never returns eventually fails rather than retrying forever")
    void givesUpAfterMaxAttempts() {
        AtomicInteger lives = new AtomicInteger();
        List<String> gaveUp = new ArrayList<>();

        var producer = new ReconnectingProducer(
                () -> new FlakyProducer(1, true, lives),
                true, 3, FAST, new ReconnectingProducer.Listener() {
                    @Override
                    public void onGaveUp(String reason, int attempts) {
                        gaveUp.add(reason + ":" + attempts);
                    }
                });

        // Retrying a genuinely dead device forever would hide the failure behind
        // a status line that never resolves.
        assertThrows(IOException.class, () -> collect(producer));
        assertEquals(List.of("COM6 disconnected:3"), gaveUp);
    }

    @Test
    @Timeout(20)
    @DisplayName("the retry budget is per outage, not per capture")
    void budgetResetsAfterASuccessfulRecovery() throws Exception {
        AtomicInteger lives = new AtomicInteger();
        // Six lives of 4 samples: five drops, then a clean end. With a budget of
        // 2 this only completes if the allowance resets each time the link
        // proves healthy. 4 > HEALTHY_SAMPLES, so each life earns the reset.
        var producer = new ReconnectingProducer(
                () -> new FlakyProducer(4, lives.get() < 5, lives),
                true, 2, FAST, new ReconnectingProducer.Listener() {
        });

        List<RssiSample> samples = collect(producer);

        assertEquals(24, samples.size(),
                "a session surviving five separate reboots is a success, not a failure");
    }

    @Test
    @Timeout(20)
    @DisplayName("a board in a boot loop exhausts its retries instead of flapping forever")
    void aFlappingLinkDoesNotEarnUnlimitedRetries() {
        AtomicInteger lives = new AtomicInteger();
        // Two samples per life -- below HEALTHY_SAMPLES. This is a board that
        // enumerates, emits a little, and dies, repeatedly. Resetting the budget
        // on the first sample would let it retry forever behind a status line
        // that never resolves.
        var producer = new ReconnectingProducer(
                () -> new FlakyProducer(2, true, lives),
                true, 3, FAST, new ReconnectingProducer.Listener() {
        });

        assertThrows(IOException.class, () -> collect(producer));
        assertEquals(4, lives.get(), "one initial life plus exactly maxAttempts retries");
    }

    @Test
    @Timeout(20)
    @DisplayName("commanding while disconnected fails instead of queueing")
    void commandsAreNotBufferedAcrossAReconnect() {
        var producer = new ReconnectingProducer(
                () -> new FlakyProducer(1, true, new AtomicInteger()),
                true, 3, FAST, new ReconnectingProducer.Listener() {
        });

        // No delegate yet: produce() has not run. A command buffered here would
        // reach the device in a state the caller never verified.
        var thrown = assertThrows(IOException.class,
                () -> producer.sendCommand(CommandChannel.CMD_ANT_EXTERNAL));
        assertTrue(thrown.getMessage().contains("reconnecting"), thrown.getMessage());
    }

    @Test
    @Timeout(20)
    @DisplayName("closing stops the retry loop instead of reconnecting to a closed capture")
    void closeEndsTheLoop() throws Exception {
        AtomicInteger lives = new AtomicInteger();
        var producer = new ReconnectingProducer(
                () -> new FlakyProducer(1, true, lives),
                true, 100, Duration.ofMillis(20), new ReconnectingProducer.Listener() {
        });

        var samples = new ArrayList<RssiSample>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                producer.produce(samples::add);
            } catch (Exception ignored) {
                // close() races the retry loop; either exit is acceptable here.
            }
        });

        Thread.sleep(80);
        producer.close();
        worker.join(Duration.ofSeconds(5));

        assertFalse(worker.isAlive(), "close() must end the retry loop");
        int livesAfterClose = lives.get();
        Thread.sleep(80);
        assertEquals(livesAfterClose, lives.get(), "no delegate may be built after close()");
    }
}
