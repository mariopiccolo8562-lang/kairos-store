package com.kairos.store;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.DisplayMetrics;
import android.view.Display;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Scheda del dispositivo per «App consigliata» (11 novembre 2026): processore, RAM (totale e libera), memoria interna,
 * Android, schermo, HDR e se i decoder hardware reggono H.265 e il 4K. Tutto letto sul dispositivo, niente rete; ogni
 * pezzo che non si legge resta fuori (la pagina mostra solo quello che c'è).
 */
final class DeviceSpecs {
    private DeviceSpecs() { }

    static String json(Activity a) {
        JSONObject o = new JSONObject();
        put(o, "brand", SpecParse.clean(Build.MANUFACTURER));
        put(o, "model", SpecParse.clean(Build.MODEL));
        // processore
        String soc = "";
        if (Build.VERSION.SDK_INT >= 31) {
            try { soc = SpecParse.join(Build.SOC_MANUFACTURER, Build.SOC_MODEL); } catch (Throwable ignored) { }
        }
        if (soc.isEmpty()) soc = SpecParse.socFromCpuinfo(read("/proc/cpuinfo", 64 * 1024));
        if (soc.isEmpty()) soc = SpecParse.clean(Build.HARDWARE);
        put(o, "soc", soc);
        put(o, "cores", Runtime.getRuntime().availableProcessors());
        long khz = 0;
        for (int i = 0; i < 16; i++) {
            long v = SpecParse.num(read("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq", 64));
            if (v > khz) khz = v;
        }
        if (khz > 0) put(o, "ghz", SpecParse.ghz(khz));
        put(o, "bits", Build.SUPPORTED_64_BIT_ABIS.length > 0 ? 64 : 32);
        if (Build.SUPPORTED_ABIS.length > 0) put(o, "abi", Build.SUPPORTED_ABIS[0]);
        // memoria
        try {
            ActivityManager am = (ActivityManager) a.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            put(o, "ramMb", mi.totalMem >> 20);
            put(o, "ramFreeMb", mi.availMem >> 20);
            put(o, "lowRam", am.isLowRamDevice());
        } catch (Throwable ignored) { }
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            put(o, "romMb", fs.getTotalBytes() >> 20);
            put(o, "romFreeMb", fs.getAvailableBytes() >> 20);
        } catch (Throwable ignored) { }
        // Android e schermo (risoluzione vera del pannello, anche se l'interfaccia gira più piccola)
        put(o, "android", Build.VERSION.RELEASE);
        put(o, "sdk", Build.VERSION.SDK_INT);
        try {
            Display d = a.getWindowManager().getDefaultDisplay();
            int w = 0, h = 0;
            Display.Mode m = d.getMode();
            if (m != null) { w = m.getPhysicalWidth(); h = m.getPhysicalHeight(); }
            if (w <= 0 || h <= 0) { DisplayMetrics dm = new DisplayMetrics(); d.getRealMetrics(dm); w = dm.widthPixels; h = dm.heightPixels; }
            put(o, "w", Math.max(w, h));
            put(o, "h", Math.min(w, h));
            Display.HdrCapabilities hc = d.getHdrCapabilities();
            put(o, "hdr", hc != null && hc.getSupportedHdrTypes().length > 0);
        } catch (Throwable ignored) { }
        // video: decoder hardware per H.265 e per il 4K
        try {
            boolean hevc = false, uhd = false;
            for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (ci.isEncoder() || SpecParse.softCodec(ci.getName())) continue;
                if (Build.VERSION.SDK_INT >= 29 && !ci.isHardwareAccelerated()) continue;
                for (String t : ci.getSupportedTypes()) {
                    boolean isHevc = "video/hevc".equalsIgnoreCase(t), isAvc = "video/avc".equalsIgnoreCase(t);
                    if (!isHevc && !isAvc) continue;
                    if (isHevc) hevc = true;
                    try {
                        MediaCodecInfo.VideoCapabilities vc = ci.getCapabilitiesForType(t).getVideoCapabilities();
                        if (vc != null && vc.isSizeSupported(3840, 2160)) uhd = true;
                    } catch (Throwable ignored) { }
                }
            }
            put(o, "hevc", hevc);
            put(o, "uhd", uhd);
        } catch (Throwable ignored) { }
        return o.toString();
    }

    private static void put(JSONObject o, String k, Object v) {
        try { if (v != null && !"".equals(v)) o.put(k, v); } catch (Exception ignored) { }
    }

    /** file breve del sistema (al massimo [max] byte); "" se non si può leggere */
    private static String read(String path, int max) {
        try (InputStream in = new FileInputStream(new File(path))) {
            byte[] b = new byte[max];
            int n = 0, r;
            while (n < max && (r = in.read(b, n, max - n)) > 0) n += r;
            return new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }
}
