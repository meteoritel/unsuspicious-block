package tools;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

/**
 * 猫之手占位结构模板生成器——生成只含一个战利品箱的最小原版 Structure NBT。
 */
public final class PlaceholderStructureNbtGenerator {
    private static final byte TAG_END = 0;
    private static final byte TAG_INT = 3;
    private static final byte TAG_STRING = 8;
    private static final byte TAG_LIST = 9;
    private static final byte TAG_COMPOUND = 10;

    private PlaceholderStructureNbtGenerator() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("需要传入目标 NBT 路径");
        }
        Path output = Path.of(args[0]);
        Files.createDirectories(output.getParent());
        try (DataOutputStream data = new DataOutputStream(
                new GZIPOutputStream(Files.newOutputStream(output)))) {
            data.writeByte(TAG_COMPOUND);
            data.writeUTF("");
            writeInt(data, "DataVersion", 3955);
            writeIntList(data, "size", 1, 1, 1);
            writePalette(data);
            writeBlocks(data);
            writeEmptyCompoundList(data, "entities");
            data.writeByte(TAG_END);
        }
    }

    private static void writePalette(DataOutputStream data) throws IOException {
        writeNamedListHeader(data, "palette", TAG_COMPOUND, 1);
        writeString(data, "Name", "minecraft:chest");
        writeNamedCompoundHeader(data, "Properties");
        writeString(data, "facing", "north");
        writeString(data, "type", "single");
        writeString(data, "waterlogged", "false");
        data.writeByte(TAG_END);
        data.writeByte(TAG_END);
    }

    private static void writeBlocks(DataOutputStream data) throws IOException {
        writeNamedListHeader(data, "blocks", TAG_COMPOUND, 1);
        writeIntList(data, "pos", 0, 0, 0);
        writeInt(data, "state", 0);
        writeNamedCompoundHeader(data, "nbt");
        writeString(data, "id", "minecraft:chest");
        writeString(data, "LootTable", "unsuspiciousblock:chests/hand_of_cat_cache");
        data.writeByte(TAG_END);
        data.writeByte(TAG_END);
    }

    private static void writeEmptyCompoundList(DataOutputStream data, String name) throws IOException {
        writeNamedListHeader(data, name, TAG_COMPOUND, 0);
    }

    private static void writeInt(DataOutputStream data, String name, int value) throws IOException {
        data.writeByte(TAG_INT);
        data.writeUTF(name);
        data.writeInt(value);
    }

    private static void writeString(DataOutputStream data, String name, String value) throws IOException {
        data.writeByte(TAG_STRING);
        data.writeUTF(name);
        data.writeUTF(value);
    }

    private static void writeIntList(DataOutputStream data, String name, int... values) throws IOException {
        writeNamedListHeader(data, name, TAG_INT, values.length);
        for (int value : values) {
            data.writeInt(value);
        }
    }

    private static void writeNamedCompoundHeader(DataOutputStream data, String name) throws IOException {
        data.writeByte(TAG_COMPOUND);
        data.writeUTF(name);
    }

    private static void writeNamedListHeader(DataOutputStream data, String name,
                                             byte elementType, int size) throws IOException {
        data.writeByte(TAG_LIST);
        data.writeUTF(name);
        data.writeByte(elementType);
        data.writeInt(size);
    }
}
