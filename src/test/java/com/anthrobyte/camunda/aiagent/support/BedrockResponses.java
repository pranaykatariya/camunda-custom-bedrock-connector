package com.anthrobyte.camunda.aiagent.support;

/** Canned Bedrock Converse responses, as the gateway (or AWS Bedrock) returns them. */
public final class BedrockResponses {

  private BedrockResponses() {}

  /** Path of the Converse API as the fake server sees it (percent-decoded). */
  public static String conversePath(String modelId) {
    return "/model/" + modelId + "/converse";
  }

  public static String text(String content) {
    return """
        {"output":{"message":{"role":"assistant","content":[{"text":%s}]}},"stopReason":"end_turn",
         "usage":{"inputTokens":1,"outputTokens":1,"totalTokens":2},"metrics":{"latencyMs":1}}
        """
        .formatted(quote(content));
  }

  public static String toolUse(String toolUseId, String name, String inputJson) {
    return """
        {"output":{"message":{"role":"assistant","content":[
           {"toolUse":{"toolUseId":"%s","name":"%s","input":%s}}]}},"stopReason":"tool_use",
         "usage":{"inputTokens":3,"outputTokens":2,"totalTokens":5},"metrics":{"latencyMs":1}}
        """
        .formatted(toolUseId, name, inputJson);
  }

  /** An error body in the shape Bedrock uses. */
  public static String error(String message) {
    return "{\"message\":" + quote(message) + "}";
  }

  private static String quote(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
