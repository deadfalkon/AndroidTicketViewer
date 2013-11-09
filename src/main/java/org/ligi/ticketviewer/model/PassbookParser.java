package org.ligi.ticketviewer.model;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.ligi.axt.AXT;
import org.ligi.ticketviewer.Tracker;
import org.ligi.ticketviewer.helper.BarcodeHelper;
import org.ligi.tracedroid.logging.Log;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import beacon.PassBeacon;

public class PassbookParser {


    private String path;
    private String problem_str = "";
    private boolean passbook_valid = true; // be positive
    private String barcode_msg;
    private Bitmap barcodeBitmap = null;
    private com.google.zxing.BarcodeFormat barcodeFormat;
    private Bitmap icon_bitmap;
    private int bgcolor;
    private String description;
    private String type;
    private List<Field> primaryFields, secondaryFields, backFields, auxiliaryFields, headerFields;
    private List<PassLocation> locations = new ArrayList<PassLocation>();
    private PassBeacon[] beacons = new PassBeacon[0];
    private int fgcolor;
    private JSONObject eventTicket = null;

    private JSONObject getJSONObjectWithFixing(String str) throws JSONException {
        /**
         * I got a really broken pass with invalid json from a user. The source was Virgin Australia
         * the bad part looked like this:
         *
         * "value": "NTL",}
         *
         * this code fixes this problem
         */
        try {
            return new JSONObject(str);
        } catch (JSONException e) {
            // I got a pass with invalid json
            if (e.getMessage().startsWith("Expected")) {
                return new JSONObject(str.replaceAll(",[\n\r ]*\\}", "}"));
            } else {
                throw e;
            }
        }
    }

    public PassbookParser(String path) {

        this.path = path;

        JSONObject pass_json = null;

        try {
            pass_json = getJSONObjectWithFixing(AXT.at(new File(path + "/pass.json")).loadToString());
        } catch (Exception e) {
            Log.i("PassParse Exception " + e);
        }

        if (pass_json == null) {
            // I had got a strange passbook with UCS-2 which could not be parsed before
            // was searching for a auto-detection, but could not find one with support for this encoding
            // and the right license

            String[] encodings = {"UTF-8", "UTF-16", "UCS-2", "UTF-8", "UTF-16BE", "UTF-16LE"};

            for (String encoding : encodings) {
                try {
                    pass_json = getJSONObjectWithFixing(AXT.at(new File(path + "/pass.json")).loadToString(Charset.forName(encoding)));
                } catch (Exception e) {
                }

                if (pass_json != null) {
                    break;
                }
            }
            ;
        }

        if (pass_json == null) {
            Log.w("could not load pass.json from passcode ");
            Tracker.get().trackEvent("problem_event", "pass", "without_pass_json", null);
            problem_str += "Problem with pass.json ";
            passbook_valid = false;
            return;
        }

        try {
            JSONObject barcode_json = pass_json.getJSONObject("barcode");

            barcodeFormat = com.google.zxing.BarcodeFormat.QR_CODE; // DEFAULT

            barcode_msg = barcode_json.getString("message");

            if (barcode_json.getString("format").contains("417"))
                barcodeFormat = com.google.zxing.BarcodeFormat.PDF_417;

            // TODO should check a bit more with barcode here - this can be dangerous

        } catch (Exception e) {
            problem_str += "Problem with pass.json";
        }

        if (pass_json != null) {
            try {
                JSONArray beacons_array = pass_json.getJSONArray("beacons");
                beacons = new PassBeacon[beacons_array.length()];
                for (int i = 0; i < beacons_array.length(); i++) {
                    JSONObject obj = beacons_array.getJSONObject(i);
                    PassBeacon beacon = new PassBeacon();
                    beacon.major = obj.getInt("major");
                    beacon.minor = obj.getInt("minor");
                    beacon.proximityUUID = UUID.fromString(obj.getString("proximityUUID"));
                    beacon.relevantText = obj.getString("relevantText");
                    beacons[i] = beacon;
                }


            } catch (JSONException e) {
            }


            try {
                JSONArray locations_json = pass_json.getJSONArray("locations");
                for (int i = 0; i < locations_json.length(); i++) {
                    JSONObject obj = locations_json.getJSONObject(i);

                    PassLocation location = new PassLocation();
                    location.latlng.lat = obj.getDouble("latitude");
                    location.latlng.lon = obj.getDouble("longitude");
                    location.description = obj.getString("relevantText");
                    locations.add(location);
                }


            } catch (JSONException e) {
            }

            try {
                String backgroundColor = pass_json.getString("backgroundColor");
                bgcolor = parseColor(backgroundColor, 0);
            } catch (JSONException e) {
            }

            try {
                String foregroundColor = pass_json.getString("foregroundColor");
                fgcolor = parseColor(foregroundColor, 0xffffffff);
            } catch (JSONException e) {
            }

            try {
                description = pass_json.getString("description");
            } catch (JSONException e) {
            }


            // try to find in a predefined set of tickets
            String[] types = {"coupon", "eventTicket", "boardingPass", "generic", "storeCard"};

            for (String atype : types) {
                if (pass_json.has(atype))
                    type = atype;
            }

            // try to rescue the situation and find types
            if (type == null) {
                type = findType(pass_json);
                Tracker.get().trackEvent("problem_event", "strange_type", type, null);
            }

            if (type == null) {
                try {
                    Tracker.get().trackEvent("problem_event", "pass", "without_type", null);
                    Log.i("pass without type " + pass_json.toString(2));
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            } else try {
                eventTicket = pass_json.getJSONObject(type);
            } catch (JSONException e) {
            }

        }

        primaryFields = getFieldListFromJsonArr(eventTicket, "primaryFields");
        secondaryFields = getFieldListFromJsonArr(eventTicket, "secondaryFields");
        auxiliaryFields = getFieldListFromJsonArr(eventTicket, "auxiliaryFields");
        backFields = getFieldListFromJsonArr(eventTicket, "backFields");
        headerFields = getFieldListFromJsonArr(eventTicket, "headerFields");

    }

    public String findType(JSONObject obj) {

        Iterator keys = obj.keys();
        for (String key = ""; keys.hasNext(); key = (String) (keys.next())) {
            try {
                JSONObject pass_obj = obj.getJSONObject(key);
                JSONArray arr = null;
                try {
                    arr = pass_obj.getJSONArray("primaryFields");
                } catch (JSONException e) {
                }

                try {
                    arr = pass_obj.getJSONArray("backFields");
                } catch (JSONException e) {
                }

                if (arr != null) {
                    Log.i("foundtype " + key);
                    return key;
                }
            } catch (JSONException e) {
            }
        }

        return null;

    }

    public String getDescription() {
        return description;
    }

    public String getType() {
        return type;
    }

    public List<Field> getPrimaryFields() {
        return primaryFields;
    }

    public List<Field> getSecondaryFields() {
        return secondaryFields;
    }

    public List<Field> getBackFields() {
        return backFields;
    }

    public List<Field> getAuxiliaryFields() {
        return auxiliaryFields;
    }

    public List<Field> getHeaderFields() {
        return headerFields;
    }

    public List<PassLocation> getLocations() {
        return locations;
    }

    public PassBeacon[] getBeacons() {
        return beacons;
    }

    /**
     * returns a list of Fields for the key - empty list when no elements - not nul
     *
     * @param obj
     * @param key
     * @return
     */
    public List<Field> getFieldListFromJsonArr(JSONObject obj, String key) {
        ArrayList<Field> res = new ArrayList<Field>();


        JSONArray arr = null;

        if (obj != null) try {
            arr = obj.getJSONArray(key);
            for (int i = 0; i < arr.length(); i++) {
                Field f = new Field();
                try {
                    f.label = arr.getJSONObject(i).getString("label");
                    f.value = arr.getJSONObject(i).getString("value");
                    res.add(f);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        } catch (JSONException e) {
        }
        return res;
    }

    private int parseColor(String color_str, int defaultValue) {
        if (color_str == null) {
            return defaultValue;
        }

        if (color_str.startsWith("rgb")) {
            return parseColorRGBStyle(color_str, defaultValue);
        }

        if (color_str.startsWith("#")) {
            return parseColorPoundStyle(color_str, defaultValue);
        }

        return defaultValue;
    }

    private int parseColorPoundStyle(String color_str, int defaultValue) {
        return Color.parseColor(color_str);
    }

    private int parseColorRGBStyle(String color_str, int defaultValue) {
        Pattern pattern = Pattern.compile("rgb *\\( *([0-9]+), *([0-9]+), *([0-9]+) *\\)");
        Matcher matcher = pattern.matcher(color_str);

        if (matcher.matches()) {
            return (255 << 24 |
                    Integer.valueOf(matcher.group(1)) << 16 |  // r
                    Integer.valueOf(matcher.group(2)) << 8 |  // g
                    Integer.valueOf(matcher.group(3))); // b

        }

        return defaultValue;
    }

    public boolean isValid() {
        return passbook_valid;
    }

    public Bitmap getBarcodeBitmap() {
        if (barcodeBitmap == null) {
            if (barcode_msg != null && barcodeFormat != null)
                barcodeBitmap = BarcodeHelper.generateBarCode(barcode_msg, barcodeFormat);
            else
                Log.w("Barcode msg or format is null");
        }
        return barcodeBitmap;
    }

    public Bitmap getIconBitmap() {
        if (icon_bitmap == null && path != null) {
            /*
            icon_bitmap = BitmapFactory.decodeFile(path + "/logo@2x.png");

            if (icon_bitmap == null)
                icon_bitmap = BitmapFactory.decodeFile(path + "/logo.png");
                                                 */
            if (icon_bitmap == null) {
                icon_bitmap = BitmapFactory.decodeFile(path + "/icon@2x.png");
            }

            if (icon_bitmap == null) {
                icon_bitmap = BitmapFactory.decodeFile(path + "/icon.png");
            }

        }
        return icon_bitmap;
    }

    public int getBgcolor() {
        return bgcolor;
    }

    public String getPath() {
        return path;
    }

    public int getFGcolor() {
        return fgcolor;
    }



    public class PassLocation {

        public class LatLng {
            public double lat;
            public double lon;
        }

        public LatLng latlng = new LatLng();
        public String description = "";
    }

    public class Field {
        public String label;
        public String value;
    }

}
