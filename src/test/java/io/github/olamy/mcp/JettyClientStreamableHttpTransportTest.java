/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.github.olamy.mcp;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import com.github.victools.jsonschema.module.swagger2.Swagger2Module;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpTransportContext;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.awaitility.Awaitility;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JettyClientStreamableHttpTransportTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(JettyClientStreamableHttpTransportTest.class);

    private static final SchemaGenerator SUBTYPE_SCHEMA_GENERATOR;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    static HttpClient httpClient = new HttpClient();
    static Server server = new Server(0);
    static String url;

    static {
        com.github.victools.jsonschema.generator.Module jacksonModule =
                new JacksonModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED);
        com.github.victools.jsonschema.generator.Module openApiModule = new Swagger2Module();

        SchemaGeneratorConfigBuilder schemaGeneratorConfigBuilder = new SchemaGeneratorConfigBuilder(
                        SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                .with(jacksonModule)
                .with(openApiModule)
                .with(Option.EXTRA_OPEN_API_FORMAT_VALUES)
                .with(Option.PLAIN_DEFINITION_KEYS);

        SchemaGeneratorConfig subtypeSchemaGeneratorConfig = schemaGeneratorConfigBuilder
                .without(Option.SCHEMA_VERSION_INDICATOR)
                .build();
        SUBTYPE_SCHEMA_GENERATOR = new SchemaGenerator(subtypeSchemaGeneratorConfig);
    }

    @BeforeAll
    public static void start() throws Exception {
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);

        HttpServletStreamableServerTransportProvider provider = HttpServletStreamableServerTransportProvider.builder()
                .objectMapper(OBJECT_MAPPER)
                .mcpEndpoint("/mcp")
                .contextExtractor((serverRequest, transportContext) -> {
                    var userId = serverRequest.getHeader("Authorization");
                    if (userId != null) {
                        transportContext.put("Authorization", userId);
                    }
                    return transportContext;
                })
                .build();

        McpSchema.ServerCapabilities serverCapabilities = McpSchema.ServerCapabilities.builder()
                .tools(true)
                .prompts(true)
                .resources(true, true)
                .build();

        io.modelcontextprotocol.server.McpServer.sync(provider)
                .capabilities(serverCapabilities)
                .tools(getAllTools())
                .prompts(Collections.emptyList())
                .resources(Collections.emptyList())
                .build();

        ServletHolder servletHolder = new ServletHolder(provider);
        servletHolder.setInitOrder(1);
        context.addServlet(servletHolder, "/mcp-server/mcp");
        try {
            server.start();
            int port = server.getBean(NetworkConnector.class).getLocalPort();
            url = "http://localhost:" + port + "/mcp-server/mcp";
            LOGGER.info("MCP Stream Server started on {}", url);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        httpClient.start();
    }

    @AfterAll
    public static void stop() throws Exception {
        if (server != null) {
            try {
                server.stop();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        httpClient.stop();
    }

    static List<McpServerFeatures.SyncToolSpecification> getAllTools() {
        McpServerFeatures.SyncToolSpecification hello = McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("test_hellowho")
                        .description("Say hello to the world")
                        .inputSchema("{}")
                        .build())
                .callHandler((mcpSyncServerExchange, callToolRequest) -> {
                    McpTransportContext transportContext = mcpSyncServerExchange.transportContext();
                    Optional<String> authz = Optional.ofNullable((String) transportContext.get("Authorization"));
                    return McpSchema.CallToolResult.builder()
                            .addTextContent("Hello World " + authz.orElse(""))
                            .build();
                })
                .build();

        McpServerFeatures.SyncToolSpecification add = McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("test_add")
                        .description("Add two numbers")
                        .inputSchema(addSchemaString())
                        .build())
                .callHandler((mcpSyncServerExchange, callToolRequest) -> {
                    McpTransportContext transportContext = mcpSyncServerExchange.transportContext();
                    String authz = (String) transportContext.get("Authorization");
                    String a = (String) callToolRequest.arguments().get("a");
                    String b = (String) callToolRequest.arguments().get("b");
                    return McpSchema.CallToolResult.builder()
                            .addTextContent("Result is " + Math.addExact(Integer.parseInt(a), Integer.parseInt(b))
                                    + " with " + authz)
                            .build();
                })
                .build();
        return List.of(hello, add);
    }

    private static String addSchemaString() {

        ObjectNode schema = OBJECT_MAPPER.createObjectNode();
        schema.put("$schema", SchemaVersion.DRAFT_2020_12.getIdentifier());
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        List<String> required = new ArrayList<>();

        {
            String parameterName = "a";
            Type parameterType = String.class;
            required.add(parameterName);
            ObjectNode parameterNode = SUBTYPE_SCHEMA_GENERATOR.generateSchema(parameterType);
            parameterNode.put("description", "a");
            properties.set(parameterName, parameterNode);
        }

        {
            String parameterName = "b";
            Type parameterType = String.class;
            required.add(parameterName);
            ObjectNode parameterNode = SUBTYPE_SCHEMA_GENERATOR.generateSchema(parameterType);
            parameterNode.put("description", "b");
            properties.set(parameterName, parameterNode);
        }
        var requiredArray = schema.putArray("required");
        required.forEach(requiredArray::add);

        return schema.toPrettyString();
    }

    McpAsyncClient getMcpAsyncClient(McpClientTransport transport) {
        return McpClient.async(transport)
                .initializationTimeout(Duration.ofMinutes(10))
                .requestTimeout(Duration.ofMinutes(10))
                .capabilities(McpSchema.ClientCapabilities.builder().roots(true).build())
                .build();
    }

    McpClientTransport getMcpClientTransport() {
        return JettyClientStreamableHttpTransport.builder(httpClient)
                .endpoint(url)
                .build();
    }

    @Test
    void simpleMCPCallTest() throws Exception {

        LOGGER.info("start simpleMCPCallTest");

        httpClient
                .getRequestListeners()
                .addBeginListener(event ->
                        event.headers(httpFields -> httpFields.add("Authorization", "really complicated password")));

        McpClientTransport transport = getMcpClientTransport();

        // we expect remote tools to be registered
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                // it looks it may takes time for quarkus to finish startup
                .pollDelay(Duration.ofSeconds(5))
                .pollInterval(Duration.ofSeconds(3))
                .until(
                        () -> {
                            McpAsyncClient client = getMcpAsyncClient(transport);
                            try {
                                client.initialize().block();
                                return client.listTools().block().tools().size();
                            } finally {
                                client.close();
                            }
                        },
                        greaterThanOrEqualTo(2));

        McpAsyncClient client = getMcpAsyncClient(transport);
        try {
            client.initialize().block();
            {
                Map<String, Object> args = Map.of("a", "1", "b", "2");

                McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                        .name("test_add")
                        .arguments(args)
                        .meta(Map.of("Authorization", "really complicated password"))
                        .build();

                McpSchema.CallToolResult result = client.callTool(request).block();
                LOGGER.debug("result: {}", result.content());

                assertThat(result.content().size(), is(1));
                McpSchema.Content content = result.content().get(0);

                assertThat(content, instanceOf(McpSchema.TextContent.class));
                McpSchema.TextContent textContent = (McpSchema.TextContent) content;
                assertThat(textContent.text(), containsString("Result is 3 with really complicated password"));
            }

        } finally {
            client.close();
        }
    }
}
