import React from "react";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ThemeProvider, createTheme } from "@mui/material/styles";
import ImagePreview, { type ImagePreviewItem } from "./ImagePreview";

const testTheme = createTheme({
  components: {
    MuiButtonBase: {
      defaultProps: {
        disableRipple: true,
      },
    },
  },
});

const images: ImagePreviewItem[] = [
  { url: "https://example.com/a.jpg", alt: "Image A", originalFilename: "a.jpg" },
  { url: "https://example.com/b.jpg", alt: "Image B", originalFilename: "b.jpg" },
];

function renderImagePreview(
  props: Partial<React.ComponentProps<typeof ImagePreview>> = {},
) {
  const onClose = jest.fn();
  const onCurrentIndexChange = jest.fn();

  const view = render(
    <ThemeProvider theme={testTheme}>
      <ImagePreview
        open={false}
        images={images}
        currentIndex={0}
        onClose={onClose}
        onCurrentIndexChange={onCurrentIndexChange}
        {...props}
      />
    </ThemeProvider>,
  );

  return { onClose, onCurrentIndexChange, ...view };
}

function renderImagePreviewTree(element: React.ReactElement) {
  return render(<ThemeProvider theme={testTheme}>{element}</ThemeProvider>);
}

describe("ImagePreview", () => {
  beforeEach(() => {
    document.body.style.overflow = "";
    jest.spyOn(window, "requestAnimationFrame").mockImplementation((callback: FrameRequestCallback) => {
      setTimeout(() => {
        callback(0);
      }, 0);
      return 1;
    });
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it("renders nothing when closed", () => {
    renderImagePreview({ open: false });

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("renders the current image, filename, and counter when open", () => {
    renderImagePreview({ open: true, currentIndex: 1 });

    expect(screen.getByRole("dialog", { name: "Image preview" })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "Image B" })).toHaveAttribute("src", images[1].url);
    expect(screen.getByText("b.jpg")).toBeInTheDocument();
    expect(screen.getByText("2 / 2")).toBeInTheDocument();
  });

  it("hides navigation controls for a single image", () => {
    renderImagePreview({
      open: true,
      images: [images[0]],
      currentIndex: 0,
    });

    expect(screen.getByRole("button", { name: "Close" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Previous image" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Next image" })).not.toBeInTheDocument();
    expect(screen.queryByText("1 / 1")).not.toBeInTheDocument();
  });

  it("locks body scroll while open and restores it on close", () => {
    document.body.style.overflow = "scroll";
    const { rerender } = renderImagePreview({ open: true });

    expect(document.body.style.overflow).toBe("hidden");

    rerender(
      <ThemeProvider theme={testTheme}>
        <ImagePreview
          open={false}
          images={images}
          currentIndex={0}
          onClose={jest.fn()}
          onCurrentIndexChange={jest.fn()}
        />
      </ThemeProvider>,
    );

    expect(document.body.style.overflow).toBe("scroll");
  });

  it("moves focus to the close button when opened", async () => {
    renderImagePreview({ open: true });

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Close" })).toHaveFocus();
    });
  });

  it("restores focus to the element that was focused before opening", async () => {
    const trigger = document.createElement("button");
    trigger.type = "button";
    trigger.textContent = "Open preview";
    document.body.appendChild(trigger);
    act(() => {
      trigger.focus();
    });

    const { rerender } = renderImagePreview({ open: true });

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Close" })).toHaveFocus();
    });

    rerender(
      <ThemeProvider theme={testTheme}>
        <ImagePreview
          open={false}
          images={images}
          currentIndex={0}
          onClose={jest.fn()}
          onCurrentIndexChange={jest.fn()}
        />
      </ThemeProvider>,
    );

    expect(trigger).toHaveFocus();
    trigger.remove();
  });

  it("traps tab navigation within the dialog controls", async () => {
    renderImagePreview({ open: true, currentIndex: 0 });

    const closeButton = screen.getByRole("button", { name: "Close" });
    const previousButton = screen.getByRole("button", { name: "Previous image" });
    const nextButton = screen.getByRole("button", { name: "Next image" });

    await waitFor(() => {
      expect(closeButton).toHaveFocus();
    });

    await userEvent.tab();
    expect(previousButton).toHaveFocus();

    await userEvent.tab();
    expect(nextButton).toHaveFocus();

    await userEvent.tab();
    expect(closeButton).toHaveFocus();

    await userEvent.tab({ shift: true });
    expect(nextButton).toHaveFocus();
  });

  it("pulls focus back into the dialog when it moves outside", async () => {
    renderImagePreview({ open: true });

    const closeButton = screen.getByRole("button", { name: "Close" });
    const outsideButton = document.createElement("button");
    outsideButton.type = "button";
    outsideButton.textContent = "Outside";
    document.body.appendChild(outsideButton);

    await waitFor(() => {
      expect(closeButton).toHaveFocus();
    });

    act(() => {
      outsideButton.focus();
    });

    await waitFor(() => {
      expect(closeButton).toHaveFocus();
    });

    outsideButton.remove();
  });

  it("calls onClose when Escape is pressed", () => {
    const { onClose } = renderImagePreview({ open: true });

    fireEvent.keyDown(window, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("calls onClose when the backdrop is clicked", () => {
    const { onClose } = renderImagePreview({ open: true });

    fireEvent.click(screen.getByRole("dialog", { name: "Image preview" }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("calls onClose when the close button is clicked", () => {
    const { onClose } = renderImagePreview({ open: true });

    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    expect(onClose).toHaveBeenCalled();
  });

  it("navigates images with arrow keys and wraps at the ends", () => {
    const onCurrentIndexChange = jest.fn();
    const { rerender } = renderImagePreviewTree(
      <ImagePreview
        open
        images={images}
        currentIndex={0}
        onClose={jest.fn()}
        onCurrentIndexChange={onCurrentIndexChange}
      />,
    );

    fireEvent.keyDown(window, { key: "ArrowRight" });
    expect(onCurrentIndexChange).toHaveBeenCalledWith(1);

    onCurrentIndexChange.mockClear();
    rerender(
      <ThemeProvider theme={testTheme}>
        <ImagePreview
          open
          images={images}
          currentIndex={1}
          onClose={jest.fn()}
          onCurrentIndexChange={onCurrentIndexChange}
        />
      </ThemeProvider>,
    );

    fireEvent.keyDown(window, { key: "ArrowRight" });
    expect(onCurrentIndexChange).toHaveBeenCalledWith(0);

    fireEvent.keyDown(window, { key: "ArrowLeft" });
    expect(onCurrentIndexChange).toHaveBeenCalledWith(0);
  });

  it("navigates images from the previous and next buttons", () => {
    const onCurrentIndexChange = jest.fn();
    const { rerender } = renderImagePreviewTree(
      <ImagePreview
        open
        images={images}
        currentIndex={0}
        onClose={jest.fn()}
        onCurrentIndexChange={onCurrentIndexChange}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Next image" }));
    expect(onCurrentIndexChange).toHaveBeenCalledWith(1);

    onCurrentIndexChange.mockClear();
    rerender(
      <ThemeProvider theme={testTheme}>
        <ImagePreview
          open
          images={images}
          currentIndex={1}
          onClose={jest.fn()}
          onCurrentIndexChange={onCurrentIndexChange}
        />
      </ThemeProvider>,
    );

    fireEvent.click(screen.getByRole("button", { name: "Previous image" }));
    expect(onCurrentIndexChange).toHaveBeenCalledWith(0);
  });
});
