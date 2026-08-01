package com.example.relay.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.example.relay.qr.QrTransferCodec;
import com.example.relay.qr.QrTransferFrame;

import java.util.List;

/**
 * Java/Jazzer entry point for the production QR transport decoder.
 *
 * The Kotlin targets exercise the same boundary through JUnit 5. This Java target keeps the
 * standard Jazzer FuzzedDataProvider entry point discoverable by security tooling as well.
 */
final class RelayQrJazzerFuzzTest {
    @FuzzTest(maxDuration = "10s")
    void decodeAndAssembleNeverCrash(FuzzedDataProvider data) {
        QrTransferFrame frame = QrTransferCodec.INSTANCE.decodeFrame(data.consumeRemainingAsBytes());
        if (frame != null) {
            QrTransferCodec.INSTANCE.assemble(List.of(frame), 0L);
        }
    }
}
