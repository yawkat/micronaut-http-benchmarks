package org.example;

import io.helidon.common.http.Http;
import io.helidon.nima.common.tls.Tls;
import io.helidon.nima.http2.webserver.Http2ConnectionProvider;
import io.helidon.nima.webserver.WebServer;
import io.helidon.nima.webserver.http.HttpRouting;
import io.helidon.nima.webserver.http1.Http1ConnectionProvider;
import io.netty.handler.ssl.util.SelfSignedCertificate;

import java.security.cert.CertificateException;
import java.util.List;

public class Main {
    public static void main(String[] args) throws CertificateException {
        start(8080, 8443);
        System.out.println("Helidon bound");
    }

    static WebServer start(int httpPort, int httpsPort) throws CertificateException {
        SelfSignedCertificate ssc = new SelfSignedCertificate();
        HttpRouting routing = HttpRouting.builder()
                .post("/search/find", (req, res) -> {
                    Input input = req.content().as(Input.class);
                    Result result = find(input.haystack, input.needle);
                    if (result == null) {
                        res.status(Http.Status.NOT_FOUND_404).send();
                    } else {
                        res.send(result);
                    }
                })
                .get("/status", (req, res) -> res.send(new Status()))
                .build();
        WebServer.Builder builder = WebServer.builder()
                .addConnectionProvider(Http1ConnectionProvider.builder().build())
                .addConnectionProvider(Http2ConnectionProvider.builder().build())
                .socket("http", s -> s.host("0.0.0.0").port(httpPort))
                .socket("https", s -> s.tls(Tls.builder()
                        .applicationProtocols(List.of("h2", "http/1.1"))
                        .privateKey(ssc.key())
                        .privateKeyCertChain(List.of(ssc.cert()))
                        .build())
                        .host("0.0.0.0").port(httpsPort));
        builder.routerBuilder("http").addRouting(routing);
        builder.routerBuilder("https").addRouting(routing);
        return builder.start();
    }

    private static Result find(List<String> haystack, String needle) {
        for (int listIndex = 0; listIndex < haystack.size(); listIndex++) {
            String s = haystack.get(listIndex);
            int stringIndex = s.indexOf(needle);
            if (stringIndex != -1) {
                return new Result(listIndex, stringIndex);
            }
        }
        return null;
    }

    public static final class Input {
        private List<String> haystack;
        private String needle;

        public List<String> getHaystack() {
            return haystack;
        }

        public void setHaystack(List<String> haystack) {
            this.haystack = haystack;
        }

        public String getNeedle() {
            return needle;
        }

        public void setNeedle(String needle) {
            this.needle = needle;
        }
    }

    public static final class Result {
        private int listIndex;
        private int stringIndex;

        public Result(int listIndex, int stringIndex) {
            this.listIndex = listIndex;
            this.stringIndex = stringIndex;
        }

        public Result() {
        }

        public int getListIndex() {
            return listIndex;
        }

        public void setListIndex(int listIndex) {
            this.listIndex = listIndex;
        }

        public int getStringIndex() {
            return stringIndex;
        }

        public void setStringIndex(int stringIndex) {
            this.stringIndex = stringIndex;
        }
    }

    public static final class Status {
    }
}