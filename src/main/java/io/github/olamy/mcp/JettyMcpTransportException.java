package io.github.olamy.mcp;

import io.modelcontextprotocol.spec.McpTransportException;

public class JettyMcpTransportException extends McpTransportException {

    private int httpStatusCode = -1;

    private String reasonPhrase;

    public JettyMcpTransportException(String message, Throwable cause) {
        super(message, cause);
    }

    public JettyMcpTransportException(String message) {
        super(message);
    }

    public JettyMcpTransportException(String message, int httpStatusCode, String reasonPhrase) {
        super(message);
        this.httpStatusCode = httpStatusCode;
        this.reasonPhrase = reasonPhrase;
    }

    public int getHttpStatusCode() {
        return httpStatusCode;
    }

    public void setHttpStatusCode(int httpStatusCode) {
        this.httpStatusCode = httpStatusCode;
    }

    public String getReasonPhrase() {
        return reasonPhrase;
    }

    public void setReasonPhrase(String reasonPhrase) {
        this.reasonPhrase = reasonPhrase;
    }
}
