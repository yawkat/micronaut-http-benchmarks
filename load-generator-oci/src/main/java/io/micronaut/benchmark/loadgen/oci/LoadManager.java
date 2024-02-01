package io.micronaut.benchmark.loadgen.oci;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.EachProperty;
import jakarta.inject.Singleton;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

@Singleton
public class LoadManager {
    private static final byte[] ALPHABET = "abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);

    private final LoadConfiguration loadConfiguration;

    public LoadManager(LoadConfiguration loadConfiguration) {
        this.loadConfiguration = loadConfiguration;
    }

    public List<LoadVariant> getLoadVariants() {
        return loadConfiguration.documents.stream()
                .flatMap(doc -> {
                    byte[] testBody = createTestBody(doc);
                    return loadConfiguration.protocols.stream().map(prot -> new LoadVariant(loadName(prot.protocol(), doc), prot, doc.stringCount, doc.stringLength, testBody));
                })
                .toList();
    }

    private static String loadName(Protocol protocol, LoadConfiguration.DocumentConfiguration doc) {
        return protocol.name().toLowerCase(Locale.ROOT) + "-" + doc.stringCount + "-" + doc.stringLength;
    }

    private static byte[] createTestBody(LoadConfiguration.DocumentConfiguration configuration) {
        Random rng = new Random(0);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JsonGenerator generator = JsonFactory.builder().build().createGenerator(baos)) {
            generator.writeStartObject();
            generator.writeFieldName("haystack");
            generator.writeStartArray();
            List<byte[]> strings = new ArrayList<>();
            for (int i = 0; i < configuration.stringCount; i++) {
                byte[] str = new byte[configuration.stringLength];
                for (int j = 0; j < str.length; j++) {
                    str[j] = ALPHABET[rng.nextInt(ALPHABET.length)];
                }
                strings.add(str);
                generator.writeUTF8String(str, 0, str.length);
            }
            generator.writeEndArray();
            generator.writeFieldName("needle");
            int i = rng.nextInt(strings.size());
            int j = rng.nextInt(configuration.stringLength - 3);
            generator.writeUTF8String(strings.get(i), j, 3);

            generator.writeEndObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return baos.toByteArray();
    }

    @ConfigurationProperties("load")
    record LoadConfiguration(List<ProtocolSettings> protocols, List<DocumentConfiguration> documents) {
        @EachProperty(value = "documents", list = true)
        record DocumentConfiguration(int stringCount, int stringLength) {
        }
    }
}
