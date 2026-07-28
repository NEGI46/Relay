package com.example.relay.gateway;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Opens Gateway HTTPS connections with both platform PKI validation and an out-of-band SPKI pin.
 *
 * <p>The pin is the lowercase SHA-256 hex digest of a certificate public key's
 * SubjectPublicKeyInfo. Keeping the platform trust manager in the chain preserves hostname,
 * validity, and CA checks; the additional pin prevents a different otherwise-trusted certificate
 * from impersonating a Gateway.
 */
public final class GatewayTlsPinning {
    private static final int SHA256_HEX_LENGTH = 64;

    private GatewayTlsPinning() {}

    public static HttpURLConnection open(URL url, String tlsSpkiSha256)
            throws IOException, GeneralSecurityException {
        URLConnection opened = url.openConnection();
        if (!(opened instanceof HttpsURLConnection)) {
            return (HttpURLConnection) opened;
        }
        HttpsURLConnection connection = (HttpsURLConnection) opened;

        byte[] pin = normalizePin(tlsSpkiSha256);
        if (pin == null) {
            throw new MissingGatewayTlsPinException();
        }
        X509TrustManager platformTrustManager = platformTrustManager();
        X509TrustManager pinningTrustManager = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                platformTrustManager.checkClientTrusted(chain, authType);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                platformTrustManager.checkServerTrusted(chain, authType);
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("Gateway TLS certificate chain is empty");
                }
                for (X509Certificate certificate : chain) {
                    if (MessageDigest.isEqual(sha256(certificate.getPublicKey().getEncoded()), pin)) {
                        return;
                    }
                }
                throw new CertificateException("Gateway TLS public-key pin mismatch");
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return platformTrustManager.getAcceptedIssuers();
            }
        };

        // Keep this explicit so both human review and CodeQL can trace the
        // SSLContext -> socket factory -> HTTPS connection chain.
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {pinningTrustManager}, new SecureRandom());
        connection.setSSLSocketFactory(context.getSocketFactory());
        return connection;
    }

    private static X509TrustManager platformTrustManager() throws GeneralSecurityException {
        TrustManagerFactory factory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        X509TrustManager result = null;
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager) {
                if (result != null) {
                    throw new GeneralSecurityException("multiple platform X509 trust managers");
                }
                result = (X509TrustManager) manager;
            }
        }
        if (result == null) {
            throw new GeneralSecurityException("platform X509 trust manager unavailable");
        }
        return result;
    }

    private static byte[] normalizePin(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder normalized = new StringBuilder(SHA256_HEX_LENGTH);
        for (int index = 0; index < raw.length(); index++) {
            char character = raw.charAt(index);
            if (character == ':' || character == '-' || Character.isWhitespace(character)) {
                continue;
            }
            char lower = Character.toLowerCase(character);
            if (!((lower >= '0' && lower <= '9') || (lower >= 'a' && lower <= 'f'))) {
                return null;
            }
            normalized.append(lower);
        }
        if (normalized.length() != SHA256_HEX_LENGTH) {
            return null;
        }

        byte[] pin = new byte[SHA256_HEX_LENGTH / 2];
        for (int index = 0; index < pin.length; index++) {
            int high = Character.digit(normalized.charAt(index * 2), 16);
            int low = Character.digit(normalized.charAt(index * 2 + 1), 16);
            pin[index] = (byte) ((high << 4) | low);
        }
        return pin;
    }

    private static byte[] sha256(byte[] value) throws CertificateException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException error) {
            throw new CertificateException("SHA-256 unavailable", error);
        }
    }
}

final class MissingGatewayTlsPinException extends SecurityException {
    MissingGatewayTlsPinException() {
        super("HTTPS Gateway connection requires an enrolled TLS SPKI pin");
    }
}
