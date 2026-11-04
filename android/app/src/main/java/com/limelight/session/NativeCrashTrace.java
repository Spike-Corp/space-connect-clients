package com.limelight.session;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

final class NativeCrashTrace {
    // Android debuggerd tombstone.proto: retain only the crashing thread's code frames.
    static String read(byte[] data) throws IOException {
        Reader root = new Reader(ByteBuffer.wrap(data));
        long tid = 0;
        for (int tag; (tag = root.tag()) != 0;) {
            if (tag == 48) tid = root.varint();
            else root.skip(tag);
        }
        root = new Reader(ByteBuffer.wrap(data));
        for (int tag; (tag = root.tag()) != 0;) {
            if (tag != 130) { root.skip(tag); continue; }
            Reader entry = root.message();
            long key = 0;
            Reader thread = null;
            for (int field; (field = entry.tag()) != 0;) {
                if (field == 8) key = entry.varint();
                else if (field == 18) thread = entry.message();
                else entry.skip(field);
            }
            if (tid == 0 || key != tid || thread == null) continue;
            StringBuilder frames = new StringBuilder();
            int count = 0;
            for (int field; (field = thread.tag()) != 0;) {
                if (field == 34 && count < 12) {
                    frames.append(frame(thread.message())).append('\n');
                    count++;
                } else thread.skip(field);
            }
            if (count == 0) throw new IOException("Native frames unavailable");
            return frames.toString();
        }
        throw new IOException("Crashing thread unavailable");
    }

    private static String frame(Reader frame) throws IOException {
        long pc = 0, offset = 0;
        String function = "", module = "native", build = "";
        for (int tag; (tag = frame.tag()) != 0;) {
            switch (tag) {
                case 8: pc = frame.varint(); break;
                case 34: function = frame.text().replaceAll("[\\r\\n\\t]", " "); break;
                case 40: offset = frame.varint(); break;
                case 50:
                    String path = frame.text();
                    String name = path.substring(path.lastIndexOf('/') + 1);
                    module = name.matches("[A-Za-z0-9_.+!-]{1,120}") ? name : "native";
                    break;
                case 66:
                    String id = frame.text();
                    if (id.matches("[A-Fa-f0-9]{1,80}")) build = id;
                    break;
                default: frame.skip(tag);
            }
        }
        return "pc " + Long.toHexString(pc) + " " + module + " " + function + "+" + offset + " build=" + build;
    }

    private static final class Reader {
        final ByteBuffer bytes;
        Reader(ByteBuffer bytes) { this.bytes = bytes; }

        int tag() throws IOException {
            if (!bytes.hasRemaining()) return 0;
            long value = varint();
            if (value < 8 || value > Integer.MAX_VALUE) throw new IOException("Invalid protobuf tag");
            return (int) value;
        }

        long varint() throws IOException {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (!bytes.hasRemaining()) throw new IOException("Truncated protobuf number");
                int next = bytes.get() & 0xff;
                if (shift == 63 && next > 1) throw new IOException("Oversized protobuf number");
                value |= (long) (next & 0x7f) << shift;
                if ((next & 0x80) == 0) return value;
            }
            throw new IOException("Invalid protobuf number");
        }

        Reader message() throws IOException {
            long size = varint();
            if (size < 0 || size > bytes.remaining()) throw new IOException("Truncated protobuf field");
            ByteBuffer view = bytes.slice();
            view.limit((int) size);
            bytes.position(bytes.position() + (int) size);
            return new Reader(view);
        }

        String text() throws IOException {
            ByteBuffer value = message().bytes;
            if (value.remaining() > 4096) throw new IOException("Oversized native symbol");
            byte[] text = new byte[value.remaining()];
            value.get(text);
            return new String(text, StandardCharsets.UTF_8);
        }

        void skip(int tag) throws IOException {
            int size;
            switch (tag & 7) {
                case 0: varint(); return;
                case 1: size = 8; break;
                case 2: message(); return;
                case 5: size = 4; break;
                default: throw new IOException("Unsupported protobuf field");
            }
            if (bytes.remaining() < size) throw new IOException("Truncated protobuf field");
            bytes.position(bytes.position() + size);
        }
    }
}
