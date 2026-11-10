package com.kairos.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** scheda del dispositivo per «App consigliata»: i pezzi che si calcolano senza Android */
public class SpecParseTest {
    @Test
    public void chipDaCpuinfo() {
        assertEquals("Amlogic S905X3", SpecParse.socFromCpuinfo("processor\t: 0\nBogoMIPS\t: 48.00\nHardware\t: Amlogic S905X3\nRevision\t: 0000\n"));
        assertEquals("MT8695", SpecParse.socFromCpuinfo("Processor : AArch64\nhardware: MT8695\n"));
        assertEquals("", SpecParse.socFromCpuinfo("processor : 0\nmodel name : ARMv8\n"));
        assertEquals("", SpecParse.socFromCpuinfo("Hardware : unknown\n"));
        assertEquals("", SpecParse.socFromCpuinfo(null));
    }

    @Test
    public void produttoreEModello() {
        assertEquals("Mediatek MT8695", SpecParse.join("Mediatek", "MT8695"));
        assertEquals("Qualcomm SM8250", SpecParse.join("Qualcomm", "Qualcomm SM8250"));
        assertEquals("SM8250", SpecParse.join("unknown", "SM8250"));
        assertEquals("Amlogic", SpecParse.join("Amlogic", "unknown"));
        assertEquals("", SpecParse.join(null, ""));
    }

    @Test
    public void frequenza() {
        assertEquals(1.7, SpecParse.ghz(1704000), 1e-9);
        assertEquals(2.0, SpecParse.ghz(2016000), 1e-9);
        assertEquals(0.0, SpecParse.ghz(0), 1e-9);
        assertEquals(1704000L, SpecParse.num("1704000\n"));
        assertEquals(0L, SpecParse.num(""));
        assertEquals(0L, SpecParse.num(null));
    }

    @Test
    public void decoderSoftware() {
        assertTrue(SpecParse.softCodec("OMX.google.h264.decoder"));
        assertTrue(SpecParse.softCodec("c2.android.hevc.decoder"));
        assertFalse(SpecParse.softCodec("OMX.amlogic.hevc.decoder.awesome2"));
        assertFalse(SpecParse.softCodec("c2.mtk.hevc.decoder"));
    }

    @Test
    public void testoPulito() {
        assertEquals("Amazon AFTSS", SpecParse.clean("  Amazon\tAFTSS\n"));
        assertEquals("", SpecParse.clean(null));
    }
}
