package io.github.olamy.mcp;

import io.modelcontextprotocol.spec.McpTransportException;

public class JettyMcpTransportException extends McpTransportException {

    private int httpStatusCode = -1;

    private String reasonPhrase;

    private String responseBody;

    public JettyMcpTransportException(String message, Throwable cause) {
        super(message, cause);
    }

    public JettyMcpTransportException(String message) {
        super(message);
    }

    public JettyMcpTransportException(String message, int httpStatusCode, String reasonPhrase, String responseBody) {
        super(message);
        this.httpStatusCode = httpStatusCode;
        this.reasonPhrase = reasonPhrase;
        this.responseBody = responseBody;
    }

    public int getHttpStatusCode() {
        return httpStatusCode;
    }

    public void setHttpStatusCode(int httpStatusCode) {
        this.httpStatusCode = httpStatusCode;
    }

    public JettyMcpTransportException withHttpStatusCode(int httpStatusCode) {
        this.httpStatusCode = httpStatusCode;
        return this;
    }

    public String getReasonPhrase() {
        return reasonPhrase;
    }

    public void setReasonPhrase(String reasonPhrase) {
        this.reasonPhrase = reasonPhrase;
    }

    public JettyMcpTransportException withReasonPhrase(String reasonPhrase) {
        this.reasonPhrase = reasonPhrase;
        return this;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public JettyMcpTransportException withResponseBody(String responseBody) {
        this.responseBody = responseBody;
        return this;
    }
}
