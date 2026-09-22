package com.anthrobyte.camunda.aiagent.support;

/**
 * Canned responses of an OpenAI-compatible backend, used to prove that non-Bedrock providers keep
 * Camunda's standard behaviour.
 */
public final class OpenAiResponses {

  private OpenAiResponses() {}

  public static String text(String content) {
    return """
        {
          "id": "chatcmpl-1",
          "object": "chat.completion",
          "created": 1767225600,
          "model": "gpt-4o",
          "choices": [{
            "index": 0,
            "message": {"role": "assistant", "content": %s},
            "finish_reason": "stop"
          }],
          "usage": {"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18}
        }
        """
        .formatted(quote(content));
  }

  private static String quote(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
