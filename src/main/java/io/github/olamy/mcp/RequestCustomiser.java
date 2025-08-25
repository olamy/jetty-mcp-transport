package io.github.olamy.mcp;

import java.util.function.Supplier;
import org.eclipse.jetty.client.Request;

public interface RequestCustomiser {

    Supplier<String> customiseBody(String body, Request request);

    RequestCustomiser NOOP = (body, request) -> () -> body;
}
