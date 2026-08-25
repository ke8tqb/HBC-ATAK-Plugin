import com.atakmap.android.hbc.HbcDecoder;
import com.atakmap.android.hbc.HbcEncoder;

public class SpotTest {
    public static void main(String[] args) throws Exception {
        String xml =
            "<event version=\"2.0\" uid=\"f1907ab4-7402-43e1-b64f-91cb426318c1\" type=\"a-u-G\""
          + " time=\"2025-12-17T17:48:15.29Z\" start=\"2025-12-17T17:48:15.29Z\""
          + " stale=\"2026-12-17T17:48:15.29Z\" how=\"h-g-i-g-o\" access=\"Undefined\">"
          + "<point lat=\"40.621743\" lon=\"-85.204462\" hae=\"219.631\" ce=\"9999999\" le=\"9999999\"/>"
          + "<detail>"
          + "<status readiness=\"true\"/>"
          + "<archive/>"
          + "<creator uid=\"ANDROID-8f2a5d78d919931a\" callsign=\"ONYX\" time=\"2025-12-17T17:48:05.118Z\" type=\"a-f-G-U-C\"/>"
          + "<usericon iconsetpath=\"COT_MAPPING_2525C/a-u/a-u-G\"/>"
          + "<link uid=\"ANDROID-8f2a5d78d919931a\" production_time=\"2025-12-17T17:48:05.118Z\" type=\"a-f-G-U-C\" parent_callsign=\"ONYX\" relation=\"p-p\"/>"
          + "<remarks/>"
          + "<precisionlocation altsrc=\"SRTM1\"/>"
          + "<archive/>"
          + "<color argb=\"-1\"/>"
          + "<contact callsign=\"U.17.124805\"/>"
          + "</detail></event>";
        try {
            HbcEncoder.Encoded enc = HbcEncoder.encode(xml);
            System.out.println("ENCODED mode " + enc.mode + " callsign " + enc.callsign
                    + " bytes " + enc.bytes.length);
            HbcDecoder.Decoded dec = HbcDecoder.decode(enc.bytes);
            System.out.println("DECODED: " + dec.summary());
        } catch (Exception e) {
            System.out.println("ENCODE FAILED: " + e);
            e.printStackTrace();
        }
    }
}
