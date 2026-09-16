import React from "react";
import { render, screen } from "@testing-library/react";
import App from "./App";
import { checkAuth } from "./services/api";
import type { AuthCheckResponse } from "./types/auth";

jest.mock("./services/api", () => ({
  checkAuth: jest.fn(),
  logout: jest.fn(),
}));

jest.mock("./context/WebSocketProvider", () => ({
  WebSocketProvider: ({ children }: { children: React.ReactNode }) => children,
}));

const mockCheckAuth = checkAuth as jest.MockedFunction<typeof checkAuth>;

beforeEach(() => {
  mockCheckAuth.mockResolvedValue({
    authenticated: false,
    username: null,
    fullname: null,
  } satisfies AuthCheckResponse);
});

test("renders login screen for unauthenticated users", async () => {
  render(<App />);
  expect(await screen.findByText(/login to continue/i)).toBeInTheDocument();
});
