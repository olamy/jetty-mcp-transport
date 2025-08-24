# jetty-mcp-transport
Transport for mcp java sdk based on Jetty reactive http client.

The class is heavily inspired by https://github.com/modelcontextprotocol/java-sdk/blob/713ee1add0e29d184224aabdf06d024ef30a2754/mcp-spring/mcp-spring-webflux/src/main/java/io/modelcontextprotocol/client/transport/WebClientStreamableHttpTransport.java

but uses Jetty reactive http client instead of Spring WebFlux.

To use this transport, you need to add the following dependency to your project:

```xml
<dependency>
  <groupId>io.github.olamy.mcp</groupId>
  <artifactId>jetty-mcp-transport</artifactId>
  <version>0.11.2</version>
</dependency>
```

In your code you can create a transport instance like this:

```java
import io.github.olamy.mcp.transport.jetty.JettyStreamableHttpTransport;

HttpClient httpClient = new HttpClient();
// start it as the transport will not control the lifecycle of the client
httpClient.start();
McpClientTransport getMcpClientTransport() {
    return JettyClientStreamableHttpTransport.builder(httpClient)
            .endpoint(url)
            .build();
}

// then you can use the transport instance with the mcp sdk

return McpClient.async(getMcpClientTransport())
        .initializationTimeout(Duration.ofMinutes(10))
.requestTimeout(Duration.ofMinutes(10))
.capabilities(McpSchema.ClientCapabilities.builder()
                .roots(true)
                .build())
.build();

```        