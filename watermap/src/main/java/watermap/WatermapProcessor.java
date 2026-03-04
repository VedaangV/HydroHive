package watermap;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Generates pixel data entirely IN MEMORY.
 * No filesystem usage. Safe for Railway / containers.
 */

@Service
public class WatermapProcessor {

    public Map<String, Object> run() throws Exception {

        ObjectMapper mapper = new ObjectMapper();

        // Read lake_erie_test.json from classpath
        InputStream jsonStream = WatermapProcessor.class
                .getClassLoader()
                .getResourceAsStream("static/data/lake_okeechobee_test.json");

        if (jsonStream == null) {
            throw new IllegalArgumentException("lake_okeechobee_test.json not found in resources");
        }

        Map<String, Object> root =
                mapper.readValue(jsonStream, Map.class);

        List<Map<String, Object>> entries =
                (List<Map<String, Object>>) root.get("entries");

        Grid grid = new Grid();

        for (Map<String, Object> e : entries) {
            double lat = ((Number) e.get("lat")).doubleValue();
            double lon = ((Number) e.get("lon")).doubleValue();
            double pH = ((Number) e.get("ph")).doubleValue();
            double turbidity = ((Number) e.get("turbidity_v")).doubleValue();
            double tds = ((Number) e.get("tds")).doubleValue();
            double temp = ((Number) e.get("temperature_c")).doubleValue();
            String timestamp = (String) e.get("timestamp");

            int[] coords = grid.latLonToPixel(lat, lon);
            grid.addData(
                coords[0],
                coords[1],
                pH,
                turbidity,
                tds,
                temp,
                timestamp
            );
        }

        // IMPORTANT: return JSON instead of writing to disk
        return grid.exportData();
    }
}

/* ===================================================== */

final class WaterMask {

    private static final BufferedImage mask;

    static {
        try {
            InputStream imageStream =
                    WatermapProcessor.class
                            .getClassLoader()
                            .getResourceAsStream("static/data/water_mask.png");

            if (imageStream == null) {
                throw new IllegalArgumentException("water_mask.png not found in resources");
            }

            mask = ImageIO.read(imageStream);

        } catch (Exception e) {
            throw new RuntimeException("Failed to load water_mask.png", e);
        }
    }

    public static boolean isWater(double lat, double lon) {
        

        if (lat > 90 || lat < -90 || lon > 180 || lon < -180) {
            return false;
        }

        int width = mask.getWidth();
        int height = mask.getHeight();

        int x = (int) ((lon + 180.0) / 360.0 * width);
        int y = (int) ((90.0 - lat) / 180.0 * height);

        if (x < 0 || x >= width || y < 0 || y >= height) {
            return false;
        }

        int value = mask.getRGB(x, y) & 0xFF;
        return value > 128;
    }
}

/* ===================================================== */

final class Grid {

    static final int PIXEL_SIZE_METERS = 100;
    static final double MIN_WEIGHT = 0.01;

    HashMap<Long, Pixel> pixels = new HashMap<>();

    static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    static double getABI(double ph, double turbidity, double tds, double temp) {
        double tempScore = clamp((temp - 15.0) / 15.0, 0.0, 1.0);
        double turbScore = clamp(turbidity / 5.0, 0.0, 1.0);
        double tdsScore = clamp(tds / 500.0, 0.0, 1.0);
        double pHScore = clamp((ph - 7.0) / 2.5, 0.0, 1.0);

        return 0.35 * tempScore +
               0.30 * turbScore +
               0.20 * tdsScore +
               0.15 * pHScore;
    }

    boolean isWaterPixel(int px, int py) {
        double[] latLon = pixelToCenterLatLon(px, py);
        return WaterMask.isWater(latLon[0], latLon[1]);
    }

    static long pixelToKey(int px, int py) {
        return (((long) px) << 32) | (py & 0xffffffffL);
    }

    int[] latLonToPixel(double lat, double lon) {
        double latMeters = lat * 111320;
        double lonMeters = lon * 111320 * Math.cos(lat * Math.PI / 180);
        return new int[] {
            (int) Math.floor(lonMeters / PIXEL_SIZE_METERS),
            (int) Math.floor(latMeters / PIXEL_SIZE_METERS)
        };
    }

    double[] pixelToCenterLatLon(int px, int py) {
        double latMeters = (py + 0.5) * PIXEL_SIZE_METERS;
        double lat = latMeters / 111320;

        double lonMeters = (px + 0.5) * PIXEL_SIZE_METERS;
        double lon = lonMeters / (111320 * Math.cos(lat * Math.PI / 180));

        return new double[]{lat, lon};
    }

    public void addData(
            int startPx,
            int startPy,
            double pH,
            double turbidity,
            double tds,
            double temp,
            String timestamp
    ) {

        if (!isWaterPixel(startPx, startPy)) return;

        Map<Long, Boolean> visited = new HashMap<>();
        Queue<int[]> q = new ArrayDeque<>();
        q.add(new int[]{startPx, startPy, 0});

        while (!q.isEmpty()) {
            int[] n = q.poll();
            int px = n[0];
            int py = n[1];
            int level = n[2];

            double weight = Math.pow(0.8, level);
            if (weight < MIN_WEIGHT) continue;

            long key = pixelToKey(px, py);
            if (visited.containsKey(key)) continue;
            visited.put(key, true);

            if (!isWaterPixel(px, py)) continue;

            Pixel p = pixels.computeIfAbsent(key, k -> new Pixel(px, py));
            p.updateVals(pH, turbidity, tds, temp, weight, timestamp);

            q.add(new int[]{px - 1, py, level + 1});
            q.add(new int[]{px + 1, py, level + 1});
            q.add(new int[]{px, py - 1, level + 1});
            q.add(new int[]{px, py + 1, level + 1});
        }
    }

    public Map<String, Object> exportData() {

        List<Map<String, Object>> outPixels = new ArrayList<>();

        for (Pixel p : pixels.values()) {
            double[] latLon = pixelToCenterLatLon(p.px, p.py);

            Map<String, Object> obj = new HashMap<>();
            obj.put("lat", latLon[0]);
            obj.put("lon", latLon[1]);
            obj.put("px", p.px);
            obj.put("py", p.py);
            obj.put("pH", p.pH);
            obj.put("turbidity", p.turbidity);
            obj.put("tds", p.tds);
            obj.put("temp", p.temp);
            obj.put("abi", p.abi);
            obj.put("weight", p.weight);
            obj.put("timestamp", p.timestamp);

            outPixels.add(obj);
        }

        Map<String, Object> root = new HashMap<>();
        root.put("pixelSizeMeters", PIXEL_SIZE_METERS);
        root.put("pixelCount", outPixels.size());
        root.put("pixels", outPixels);

        return root;
    }
}
