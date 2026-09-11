import { getPreferredVideoPlaybackUrl } from "./mediaUtils";
import type { ChatAttachment } from "../../types/chat";

const attachment: ChatAttachment = {
  playbackUrl: "https://media.example/video.mp4",
  videoSources: [
    {
      url: "https://media.example/video.mp4",
      mimeType: "video/mp4",
      height: 1080,
      role: "CANONICAL",
    },
    {
      url: "https://media.example/video.480p.mp4",
      mimeType: "video/mp4",
      height: 480,
      role: "MOBILE",
    },
  ],
};

test("prefers the mobile rendition on narrow viewports", () => {
  expect(getPreferredVideoPlaybackUrl(attachment, { viewportWidth: 390 }))
    .toBe("https://media.example/video.480p.mp4");
});

test("prefers the mobile rendition when data saver is enabled", () => {
  expect(getPreferredVideoPlaybackUrl(attachment, { viewportWidth: 1440, saveData: true }))
    .toBe("https://media.example/video.480p.mp4");
});

test("keeps canonical playback on a wide fast connection", () => {
  expect(getPreferredVideoPlaybackUrl(attachment, {
    viewportWidth: 1440,
    saveData: false,
    effectiveType: "4g",
  })).toBe("https://media.example/video.mp4");
});

test("falls back to playbackUrl when no rendition list is available", () => {
  expect(getPreferredVideoPlaybackUrl(
    { playbackUrl: "https://media.example/fallback.mp4" },
    { viewportWidth: 390 },
  )).toBe("https://media.example/fallback.mp4");
});
