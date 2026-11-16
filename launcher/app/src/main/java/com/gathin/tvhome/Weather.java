package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * The header's weather line (show_weather): current temperature and condition, read from the same source the set's
 * own weather app uses. The position is the one that app already holds (the mitv_location setting the system's
 * location service writes), and the reading comes from Xiaomi's weather service, so the header agrees with
 * what the TV shows in its own weather app. One small request every half hour, on a background thread, and the
 * answer is handed back on the main one.
 */
final class Weather {

    /** Condition buckets the header has an icon for. */
    static final int SUNNY = 0, PARTLY = 1, CLOUDY = 2, FOG = 3, RAIN = 4, SNOW = 5, THUNDER = 6;

    /** The weather service's own client id: the set's weather app asks with these, so the answers match its screen. */
    private static final String API = "https://weatherapi.market.xiaomi.com/wtr-v3/weather/all";
    private static final String APP_KEY = "weather20151024";
    private static final String SIGN = "zsxdcfvgbhnjmk";

    /** One reading: whole degrees and a bucket from above. */
    static final class Reading {
        final int degrees;
        final int condition;

        Reading(int degrees, int condition) {
            this.degrees = degrees;
            this.condition = condition;
        }
    }

    /** Where the reading is for: the label the header prints, and the coordinates asked about. */
    static final class Place {
        final String city;
        final double latitude;
        final double longitude;

        Place(String city, double latitude, double longitude) {
            this.city = city == null ? "" : city;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        boolean usable() {
            return latitude != 0 || longitude != 0;
        }
    }

    interface Callback {
        /** The reading, or null when the request failed (offline, timeout, unreadable answer). */
        void onWeather(Reading reading);
    }

    private Weather() {
    }

    /**
     * The position the set's weather app is showing, as its location service stored it: city, province and
     * coordinates in one JSON string. Null when the set has never been located, in which case the caller falls
     * back to whatever the theme configured.
     */
    static Place devicePlace(Context context) {
        try {
            String raw = Settings.System.getString(context.getContentResolver(), "mitv_location");
            if (raw == null || raw.isEmpty()) {
                raw = Settings.Secure.getString(context.getContentResolver(), "mitv_location");
            }
            if (raw == null || raw.isEmpty()) return null;
            JSONObject o = new JSONObject(raw);
            double latitude = o.optDouble("latitude", 0);
            double longitude = o.optDouble("longitude", 0);
            if (latitude == 0 && longitude == 0) return null;
            return new Place(o.optString("city", ""), latitude, longitude);
        } catch (Exception e) {
            return null;
        }
    }

    static void fetch(final Place place, final Handler handler, final Callback callback) {
        new Thread(() -> {
            Reading reading = null;
            try {
                String body = get(String.format(Locale.US,
                        "%s?latitude=%.4f&longitude=%.4f&isGlobal=false&locale=zh_cn"
                                + "&appKey=%s&sign=%s&timestamp=%d",
                        API, place.latitude, place.longitude, APP_KEY, SIGN, System.currentTimeMillis()));
                JSONObject current = new JSONObject(body).getJSONObject("current");
                String degrees = current.getJSONObject("temperature").optString("value", "");
                reading = new Reading(Math.round(Float.parseFloat(degrees)),
                        condition(Integer.parseInt(current.optString("weather", "2").trim())));
            } catch (Exception e) {
                // Leave reading null: the header keeps what it had rather than printing a made-up number.
            }
            final Reading answer = reading;
            handler.post(() -> callback.onWeather(answer));
        }, "weather").start();
    }

    /**
     * The weather code Xiaomi's service reports, folded into the buckets the launcher draws: 0 clear, 1 partly,
     * 2 overcast, 3-12 and 19-25 rain (4 with thunder, 6 sleet), 13-17 and 26-28 snow, 18 and 32-40 fog or haze,
     * 20 and 29-31 blown sand.
     */
    static int condition(int code) {
        switch (code) {
            case 0:
                return SUNNY;
            case 1:
                return PARTLY;
            case 2:
                return CLOUDY;
            case 4:
            case 5:
                return THUNDER;
            case 6:
            case 13:
            case 14:
            case 15:
            case 16:
            case 17:
            case 26:
            case 27:
            case 28:
                return SNOW;
            case 18:
            case 32:
            case 33:
            case 34:
            case 35:
            case 36:
            case 37:
            case 38:
            case 39:
            case 40:
                return FOG;
            case 20:
            case 29:
            case 30:
            case 31:
                return CLOUDY;
            case 3:
            case 7:
            case 8:
            case 9:
            case 10:
            case 11:
            case 12:
            case 19:
            case 21:
            case 22:
            case 23:
            case 24:
            case 25:
                return RAIN;
            default:
                return CLOUDY;
        }
    }

    /** The white outline icon for a bucket. */
    static int icon(int condition) {
        switch (condition) {
            case SUNNY:
                return R.drawable.ic_wx_sun;
            case PARTLY:
                return R.drawable.ic_wx_partly;
            case FOG:
                return R.drawable.ic_wx_fog;
            case RAIN:
                return R.drawable.ic_wx_rain;
            case SNOW:
                return R.drawable.ic_wx_snow;
            case THUNDER:
                return R.drawable.ic_wx_thunder;
            default:
                return R.drawable.ic_wx_cloud;
        }
    }

    /** The body of one GET, or an exception; the connection is closed either way. */
    private static String get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("User-Agent", "CleanYourTV");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IllegalStateException("HTTP " + status);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                return out.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }
}
