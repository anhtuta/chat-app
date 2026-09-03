package com.hello.mediaprocessing.constant;

/**
 * Describes how a source video should be turned into the chat playback MP4.
 */
public enum VideoTranscodeMode {
    /**
     * Source is already H.264 + AAC in MP4, so the original object is the playback asset.
     */
    REUSE_ORIGINAL,
    /**
     * Codecs are already chat-friendly, but the container should be remuxed to MP4.
     */
    REMUX,
    /**
     * Video and/or audio must be re-encoded to H.264 + AAC in MP4.
     */
    REENCODE
}
