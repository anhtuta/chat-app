import { messageFromApiError, toUserErrorMessage } from "./apiError";

describe("messageFromApiError", () => {
  it("reads message from the common ErrorResponse JSON", () => {
    expect(
      messageFromApiError(
        {
          timestamp: "2026-08-25T16:00:00Z",
          status: 401,
          error: "Unauthorized",
          message: "Invalid username or password",
          path: "/api/auth/login",
        },
        "Login failed",
      ),
    ).toBe("Invalid username or password");
  });

  it("falls back when message is missing", () => {
    expect(messageFromApiError({ status: 500 }, "Request failed")).toBe("Request failed");
    expect(messageFromApiError(null, "Request failed")).toBe("Request failed");
  });
});

describe("toUserErrorMessage", () => {
  it("uses Error.message when present", () => {
    expect(toUserErrorMessage(new Error("Invalid username or password"), "An error occurred")).toBe(
      "Invalid username or password",
    );
  });

  it("uses the fallback for unknown values", () => {
    expect(toUserErrorMessage("nope", "An error occurred")).toBe("An error occurred");
  });
});
