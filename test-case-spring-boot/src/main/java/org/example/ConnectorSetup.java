package org.example;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http2.Http2Protocol;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class ConnectorSetup {
    @Bean
    public ServletWebServerFactory container() {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(8443);
        Connector httpConnector = new Connector();
        httpConnector.setPort(8080);
        factory.addAdditionalTomcatConnectors(httpConnector);
        factory.addConnectorCustomizers(connector -> connector.addUpgradeProtocol(new Http2Protocol()));
        return factory;
    }
}
