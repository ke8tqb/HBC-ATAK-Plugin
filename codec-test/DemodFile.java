import java.io.DataInputStream;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import sivantoledo.ax25.Afsk1200MultiDemodulator;
import sivantoledo.ax25.Packet;
import sivantoledo.ax25.PacketDemodulator;
import sivantoledo.ax25.PacketHandler;

import com.atakmap.android.hbc.HbcDecoder;

/** Feeds a raw float32 little-endian PCM file through the plugin's demodulator. */
public class DemodFile {
    public static void main(String[] args) throws Exception {
        String path = args[0];
        int fs = args.length > 1 ? Integer.parseInt(args[1]) : 48000;

        final int[] count = {0};
        PacketDemodulator demod = new Afsk1200MultiDemodulator(fs, new PacketHandler() {
            public void handlePacket(byte[] bytes) {
                count[0]++;
                System.out.println("FRAME " + bytes.length + "B: " + Packet.format(bytes));
                try {
                    // strip AX.25 header: dest7+src7(+path)+ctrl+pid
                    int off = 14;
                    while (off < bytes.length && (bytes[off - 1] & 0x01) == 0) off += 7;
                    off += 2;
                    byte[] payload = java.util.Arrays.copyOfRange(bytes, off, bytes.length);
                    HbcDecoder.Decoded d = HbcDecoder.decode(payload);
                    System.out.println("  HBC: " + d.summary());
                } catch (Exception e) {
                    System.out.println("  (payload not HBC: " + e.getMessage() + ")");
                }
            }
        });

        DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(path)));
        byte[] buf = new byte[4096 * 4];
        float[] samples = new float[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            int ns = n / 4;
            ByteBuffer bb = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < ns; i++) samples[i] = bb.getFloat();
            demod.addSamples(samples, ns);
        }
        in.close();
        System.out.println(count[0] + " CRC-valid frame(s) decoded at fs=" + fs);
    }
}
