package com.autotestforge.mcp;

/** Runtime failure while communicating with an MCP server or tool. */
public class McpToolException extends RuntimeException {

    public McpToolException(String message) {
        super(message);
    }

    public McpToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
