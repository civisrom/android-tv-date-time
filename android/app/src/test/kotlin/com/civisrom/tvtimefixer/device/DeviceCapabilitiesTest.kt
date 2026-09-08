package com.civisrom.tvtimefixer.device

import org.junit.Assert.*
import org.junit.Test

class DeviceCapabilitiesTest {
    // Формат AOSP DisplayDeviceInfo / Display.Mode, включая вложенные массивы Android 12+.
    private val display = """
        DisplayDeviceInfo{"HDMI": uniqueId="local:0", 3840 x 2160, modeId 2, defaultModeId 1, supportedModes [{id=1, width=1920, height=1080, fps=60.0, alternativeRefreshRates=[30.0], supportedHdrTypes=[2]}, {id=2, width=3840, height=2160, fps=59.94, alternativeRefreshRates=[], supportedHdrTypes=[1, 2, 3, 4]}], colorMode 0, supportedColorModes [0], hdrCapabilities HdrCapabilities{mSupportedHdrTypes=[1, 2, 3, 4], mMaxLuminance=1000.0}, allmSupported true, type EXTERNAL, FLAG_DEFAULT_DISPLAY}
    """.trimIndent()

    @Test fun `display modes and HDR belong to the primary physical display`() {
        val virtual = display.replace("FLAG_DEFAULT_DISPLAY", "FLAG_PRESENTATION").replace("type EXTERNAL", "type VIRTUAL").replace("fps=59.94", "fps=120.0")
        val info = parseDisplayDetails("$virtual\n$display")
        assertEquals("3840 × 2160 @ 59.94 Hz", info.activeMode)
        assertEquals("1920 × 1080 @ 60 Hz\n3840 × 2160 @ 59.94 Hz", info.supportedModes)
        assertEquals(listOf(1, 2, 3, 4), info.hdrTypes)
        assertEquals(true, info.allm)
    }

    @Test fun `missing HDR is different from an explicit empty list`() {
        assertNull(parseDisplayDetails("Permission Denial").hdrTypes)
        assertEquals(emptyList<Int>(), parseDisplayDetails(display.replace("mSupportedHdrTypes=[1, 2, 3, 4]", "mSupportedHdrTypes=[]")).hdrTypes)
        assertNull(parseDisplayDetails(display.replace("hdrCapabilities HdrCapabilities{mSupportedHdrTypes=[1, 2, 3, 4], mMaxLuminance=1000.0}", "hdrCapabilities null")).hdrTypes)
    }

    @Test fun `ambiguous physical displays do not get merged`() {
        val first = display.replace("FLAG_DEFAULT_DISPLAY", "FLAG_PRESENTATION")
        assertEquals(DisplayDetails(), parseDisplayDetails("$first\n${first.replace("local:0", "local:1")}"))
        assertEquals("", parseDisplayDetails(display.replace("modeId 2", "modeId 99")).activeMode)
    }

    @Test fun `data storage uses available blocks and supports wrapped filesystem names`() {
        val row = "8388608 4194304 2097152 50% /data"
        assertEquals("8.00 GiB" to "2.00 GiB", parseDataStorage("Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/dm-8 $row"))
        assertEquals("8.00 GiB" to "2.00 GiB", parseDataStorage("/dev/very-long-name\n   $row"))
        assertEquals("" to "", parseDataStorage("/dev/block 10 0 20 0% /data"))
        assertEquals("" to "", parseDataStorage("/dev/block 10 5 5 50% /system"))
        assertEquals("" to "", parseDataStorage("Permission denied"))
    }

    @Test fun `unknown time settings never become disabled`() {
        assertEquals(true, parseAutomaticSetting("1\n"))
        assertEquals(false, parseAutomaticSetting("0\n"))
        for (value in listOf("", "null", "2", "Permission Denial")) assertNull(parseAutomaticSetting(value))
    }

    @Test fun `audio ignores unavailable output profiles and input formats`() {
        val audio = """
            AudioPolicyManager:
             Available output devices (2):
              1. Device: AUDIO_DEVICE_OUT_SPEAKER
                 Format: AUDIO_FORMAT_PCM_16_BIT
              2. Device: AUDIO_DEVICE_OUT_HDMI
                 Format: AUDIO_FORMAT_AC3
                 Format: AUDIO_FORMAT_PCM_16_BIT
             Available input devices (1):
              Device: AUDIO_DEVICE_IN_BUILTIN_MIC
              Format: AUDIO_FORMAT_PCM_FLOAT
             HW Modules:
              Device: AUDIO_DEVICE_OUT_BLUETOOTH_A2DP
              Format: AUDIO_FORMAT_DTS
        """.trimIndent()
        assertEquals("HDMI, SPEAKER" to "AC3, PCM_16_BIT", parseAudioOutputs(audio))
        assertEquals("HDMI, SPEAKER" to "AC3, PCM_16_BIT", parseAudioOutputs(audio.replace("Available output devices (2):", "- Available output devices:")))
        assertEquals("" to "", parseAudioOutputs("Permission denied"))
    }

    @Test fun `addresses contain global IPs but no local loopback or MAC data`() {
        val addresses = """
            1: lo    inet 127.0.0.1/8 scope host lo
            2: eth0    inet 192.168.1.2/24 brd 192.168.1.255 scope global eth0
            2: eth0    inet6 2001:db8::2/64 scope global dynamic
            2: eth0    inet6 fe80::2/64 scope link
        """.trimIndent()
        assertEquals("eth0: 192.168.1.2/24\neth0: 2001:db8::2/64", parseNetworkAddresses(addresses))
    }

    @Test fun `codec declarations accept simple and nested syntax but exclude encoders and comments`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <!-- <!DOCTYPE MediaCodecs [example only]> -->
            <MediaCodecs><Decoders>
              <MediaCodec name="c2.vendor.avc.decoder" type="video/avc" />
              <MediaCodec name="OMX.google.aac.decoder"><Type name="audio/mp4a-latm" /></MediaCodec>
              <!-- <MediaCodec name="not.enabled" type="video/av01" /> -->
            </Decoders><Encoders><MediaCodec name="encoder.only" type="video/hevc" /></Encoders></MediaCodecs>
        """.trimIndent()
        val result = parseDeclaredDecoders("$CODEC_XML_MARKER\n$xml\n$CODEC_XML_MARKER\n$xml")
        assertEquals("video/avc: c2.vendor.avc.decoder" to "audio/mp4a-latm: OMX.google.aac.decoder", result)
    }

    @Test fun `malformed truncated and entity XML never contributes partial declarations`() {
        val good = "<MediaCodecs><Decoders><MediaCodec name='decoder' type='video/avc'/></Decoders></MediaCodecs>"
        val bad = "<MediaCodecs><Decoders><MediaCodec name='broken' type='video/hevc'/>"
        val entity = "<!DOCTYPE MediaCodecs [<!ENTITY e SYSTEM 'file:///must-not-read'>]>$good"
        assertEquals("video/avc: decoder" to "", parseDeclaredDecoders("$CODEC_XML_MARKER$bad$CODEC_XML_MARKER$good"))
        assertEquals("" to "", parseDeclaredDecoders(entity))
        assertEquals("" to "", parseDeclaredDecoders("<!--" + "x".repeat(131073) + "-->" + good))
    }
}
