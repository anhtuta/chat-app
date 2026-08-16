import { render, screen } from "@testing-library/react";
import App from "./App";

jest.mock("./services/api", () => ({
  checkAuth: jest.fn().mockResolvedValue({
    authenticated: false,
    username: null,
    fullname: null,
  }),
  logout: jest.fn(),
}));

jest.mock("./context/WebSocketProvider", () => ({
  WebSocketProvider: ({ children }) => children,
}));

test("renders login screen for unauthenticated users", async () => {
  render(<App />);
  expect(await screen.findByText(/login to continue/i)).toBeInTheDocument();
});
