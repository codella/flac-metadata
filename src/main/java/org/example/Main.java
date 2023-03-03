package org.example;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.IntStream;

public class Main {
  public static void main(String[] args) throws IOException {
    stdout("Specification can be found at: https://xiph.org/flac/format.html#stream\n");
    try (InputStream is = Main.class.getClassLoader().getResourceAsStream("demo.flac")) {
      var signature = new String(is.readNBytes(4));

      if (!signature.equals("fLaC")) {
        stderr("not a flac stream");
        System.exit(-1);
      }

      stdout("Signature: %s", signature);

      do {
        stdout("--------");
      } while(!parseMetadataBlock(is));
      stdout("--------");
    }
  }

  private static boolean parseMetadataBlock(InputStream is) throws IOException {
    var header = readBigEndian(8, is);

    var isLast = (header & 0b10000000) > 0;
    stdout("Is last metadata block? %s", isLast ? "Yes" : "No");

    var blockTypeBits = header & 0b1111111;

    var lengthInBytes = readBigEndian(24, is);
    stdout("Length (in bytes): %s", lengthInBytes);

    switch ((int) blockTypeBits) {
      case 0 -> parseMetadataBlockStreamInfo(is);
      case 1 -> parseMetadataBlockPadding(is, lengthInBytes);
      case 2 -> throw new UnsupportedOperationException("APPLICATION MetaData not implemented yet."); // "APPLICATION";
      case 3 -> parseMetadataBlockSeekTable(is, lengthInBytes);
      case 4 -> parseMetadataBlockVorbisComment(is);
      case 5 -> throw new UnsupportedOperationException("CUESHEET MetaData not implemented yet."); // "CUESHEET";
      case 6 -> parseMetadataBlockPicture(is);
      case 127 -> throw new UnsupportedOperationException("Invalid MetaData handling not implemented yet."); // "<invalid!>";
      default -> String.format("<reserved: %s>", blockTypeBits);
    };

    return isLast;
  }

  private static void parseMetadataBlockStreamInfo(InputStream is) throws IOException {
    stdout("Block type: 0 (STREAMINFO)");

    stdout("Minimum block size (in samples): %s", readBigEndian(16, is));
    stdout("Maximum block size (in samples): %s", readBigEndian(16, is));
    stdout("Minimum frame size (in bytes) used in the stream: %s", readBigEndian(24, is));
    stdout("Maximum frame size (in bytes) used in the stream: %s", readBigEndian(24, is));

    readBigEndian(64, is); // skipping 64-bits, for now

    stdout("MD5 signature of the un-encoded audio data: %s", Arrays.toString(is.readNBytes(16)));
  }

  private static void parseMetadataBlockSeekTable(InputStream is, long lengthInBytes) throws IOException {
    stdout("Block type: 3 (SEEKTABLE)");

    var nOfSeekPoints = lengthInBytes / 18;
    for (int i = 0; i < nOfSeekPoints; i++) {
      stdout(
              "%d) Sample number: %s, Stream offset: %s, Frame samples: %s",
              i, readBigEndian(64, is), readBigEndian(64, is), readBigEndian(16, is)
      );
    }
  }

  private static void parseMetadataBlockVorbisComment(InputStream is) throws IOException {
    stdout("Block type: 4 (VORBIS_COMMENT)");

    var vendorLength = readLittleEndian(32, is);
    var vendor = new String(is.readNBytes((int) vendorLength));
    stdout("Vendor: %s", vendor);

    var userCommentListLength = readLittleEndian(32, is);
    for (int i = 0; i < userCommentListLength; i++) {
      var commentLength = readLittleEndian(32, is);
      var comment = new String(is.readNBytes((int) commentLength));
      stdout("comment[%d]=\"%s\"", i, comment);
    }
  }

  private static void parseMetadataBlockPicture(InputStream is) throws IOException {
    stdout("Block type: 6 (PICTURE)");

    var pictureTypeCode = readBigEndian(32, is);
    var pictureType = switch ((int) pictureTypeCode) {
      case 0 -> "Other";
      case 1 -> "32x32 pixels 'file icon' (PNG only)";
      case 2 -> "Other file icon";
      case 3 -> "Cover (front)";
      case 4 -> "Cover (back)";
      case 5 -> "Leaflet page";
      case 6 -> "Media (e.g. label side of CD)";
      case 7 -> "Lead artist/lead performer/soloist";
      case 8 -> "Artist/performer";
      case 9 -> "Conductor";
      case 10 -> "Band/Orchestra";
      case 11 -> "Composer";
      case 12 -> "Lyricist/text writer";
      case 13 -> "Recording Location";
      case 14 -> "During recording";
      case 15 -> "During performance";
      case 16 -> "Movie/video screen capture";
      case 17 -> "A bright coloured fish";
      case 18 -> "Illustration";
      case 19 -> "Band/artist logotype";
      case 20 -> "Publisher/Studio logotype";
      default -> throw new RuntimeException("Picture type code not supported: " + pictureTypeCode);
    };

    stdout("The picture type according to the ID3v2 APIC frame: %d (\"%s\")", pictureTypeCode, pictureType);

    var mimeTypeLength = readBigEndian(32, is);
    var mimeType = new String(is.readNBytes((int) mimeTypeLength));
    stdout("MIME Type: %s", mimeType);

    var descriptionLength = readBigEndian(32, is);
    var description = new String(is.readNBytes((int) descriptionLength));
    stdout("Description: \"%s\"", description);

    // Skipping what still needs to be implemented

    is.readNBytes(16);

    var pictureBinaryLength = readBigEndian(32, is);
    var binaryData = is.readNBytes((int) pictureBinaryLength);

    Path tempFile = Files.createTempFile(null, ".jpg");
    try (OutputStream out = new FileOutputStream(tempFile.toFile())) {
      out.write(binaryData);
    }

    stdout("Picture extracted in: \"%s\"", tempFile);
  }

  private static void parseMetadataBlockPadding(InputStream is, long lengthInBytes) throws IOException {
    stdout("Block type: 1 (PADDING)");

    is.readNBytes((int) lengthInBytes); // skips the padding
  }

  private static long readBigEndian(int nBits, InputStream is) throws IOException {
    assert nBits % 8 == 0;
    var nBytes = nBits / 8;
    assert nBytes >= 1 && nBytes <= 8;

    var bytes = is.readNBytes(nBytes);
    var value = 0;

    for (int i = 0; i < bytes.length; i++) {
      value |= (bytes[i] & 0xFF) << ((nBytes - i -1) * 8);
    }

    return value;
  }

  private static long readLittleEndian(int nBits, InputStream is) throws IOException {
    assert nBits % 8 == 0;
    var nBytes = nBits / 8;
    assert nBytes >= 1 && nBytes <= 8;

    var bytes = is.readNBytes(nBytes);
    var value = 0;

    for (int i = bytes.length -1; i >= 0; i--) {
      value |= (bytes[i] & 0xFF) << (i * 8);
    }

    return value;
  }

  private static void stdout(String format, Object... args) {
    System.out.println(String.format(format, args));
  }

  private static void stderr(String format, Object... args) {
    System.err.println(String.format(format, args));
  }
}