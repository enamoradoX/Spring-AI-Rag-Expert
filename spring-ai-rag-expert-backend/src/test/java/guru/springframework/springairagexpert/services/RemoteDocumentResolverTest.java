package guru.springframework.springairagexpert.services;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class RemoteDocumentResolverTest {

    private static HttpServer server;
    private static String baseUrl;
    private final RemoteDocumentResolver resolver = new RemoteDocumentResolver();

    private static final byte[] PDF_BYTES = "%PDF-1.4\n% mock pdf\n".getBytes(StandardCharsets.US_ASCII);

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);

        server.createContext("/direct.pdf", exchange ->
                respond(exchange, 200, "application/pdf", null, PDF_BYTES));

        server.createContext("/landing/pb_spt_open_212_f150xb_9-10-2020-occ", exchange -> {
            String html = """
                    <html><body>
                      <h1>Performance Bulletin</h1>
                      <a href="/getmedia/b6d82df1-cce5-4ef4-82c2-666f2ce222e4/pb_spt_open_212_f150xb_9-10-2020_occ">Open bulletin</a>
                    </body></html>
                    """;
            respond(exchange, 200, "text/html; charset=utf-8", null, html.getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/getmedia/b6d82df1-cce5-4ef4-82c2-666f2ce222e4/pb_spt_open_212_f150xb_9-10-2020_occ", exchange ->
                respond(exchange, 200, "application/pdf",
                        "inline; filename=pb_spt_open_212_f150xb_9-10-2020_occ.pdf", PDF_BYTES));

        server.createContext("/plain-html", exchange -> {
            String html = "<html><body><p>No PDF here.</p></body></html>";
            respond(exchange, 200, "text/html; charset=utf-8", null, html.getBytes(StandardCharsets.UTF_8));
        });

        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void resolvesDirectPdfWithoutChangingUrl() throws Exception {
        RemoteDocumentResolver.ResolvedRemoteDocument resolved = resolver.resolve(baseUrl + "/direct.pdf");

        assertEquals(baseUrl + "/direct.pdf", resolved.resolvedUrl());
        assertTrue(resolved.mimeType().contains("pdf"));
        assertTrue(resolved.filename().endsWith(".pdf"));
        assertArrayEquals(PDF_BYTES, resolved.bytes());
    }

    @Test
    void resolvesLandingPageToUnderlyingPdfAsset() throws Exception {
        String landingUrl = baseUrl + "/landing/pb_spt_open_212_f150xb_9-10-2020-occ";

        RemoteDocumentResolver.ResolvedRemoteDocument resolved = resolver.resolve(landingUrl);

        assertEquals(landingUrl, resolved.originalUrl());
        assertTrue(resolved.resolvedUrl().contains("/getmedia/"));
        assertTrue(resolved.mimeType().contains("pdf"));
        assertEquals("pb_spt_open_212_f150xb_9-10-2020_occ.pdf", resolved.filename());
        assertArrayEquals(PDF_BYTES, resolved.bytes());
    }

    @Test
    void fallsBackToHtmlWhenNoPdfCandidateExists() throws Exception {
        RemoteDocumentResolver.ResolvedRemoteDocument resolved = resolver.resolve(baseUrl + "/plain-html");

        assertTrue(resolved.mimeType().contains("html"));
        assertFalse(new String(resolved.bytes(), StandardCharsets.UTF_8).contains("%PDF-"));
    }

    private static void respond(HttpExchange exchange,
                                int responseStatus,
                                String contentType,
                                String contentDisposition,
                                byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (contentDisposition != null) {
            exchange.getResponseHeaders().set("Content-Disposition", contentDisposition);
        }
        exchange.sendResponseHeaders(responseStatus, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}

