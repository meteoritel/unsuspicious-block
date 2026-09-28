package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 管理页语言 JSON 导入的文件级校验。
 * <p>
 * 只做"能不能读、是不是合法对象、条目数是否超限"的纯数据判定，不依赖 Screen/Minecraft 客户端类，
 * 也不产生网络或界面副作用。校验结果只给出原因分类（{@link Failure}），文案与日志由调用方决定，
 * 便于把"文件过大 / IO 错误 / 条目超限 / 格式无效"分开反馈而不是一律折叠成"JSON 格式无效"。
 */
public final class LootTableImportValidator {
    /** 单个导入文件的大小上限。 */
    public static final long MAX_FILE_BYTES = 2L * 1024L * 1024L;
    /** 单个导入文件的条目数上限。 */
    public static final int MAX_ENTRIES = 4096;
    /** 语言码允许的字符与长度：与资源包语言文件命名口径一致。 */
    private static final String LANGUAGE_CODE_PATTERN = "[a-z0-9_-]{2,16}";

    private LootTableImportValidator() {
    }

    /** 导入失败原因——调用方据此挑独立文案键，并在日志里区分真实原因。 */
    public enum Failure {
        NOT_A_FILE,
        TOO_LARGE,
        IO_ERROR,
        INVALID_JSON,
        ROOT_NOT_OBJECT,
        TOO_MANY_ENTRIES
    }

    /** 导入校验失败；{@code detail} 只用于日志，不参与玩家可见文案。 */
    public static final class ImportException extends Exception {
        private final Failure failure;
        private final String detail;

        ImportException(Failure failure, String detail, Throwable cause) {
            super(failure + ": " + detail, cause);
            this.failure = failure;
            this.detail = detail;
        }

        public Failure failure() {
            return this.failure;
        }

        public String detail() {
            return this.detail;
        }
    }

    /** 已通过文件级校验的导入内容：推断出的语言码 + 原始键值对象（保持 JSON 顺序）。 */
    public record ParsedImport(String languageCode, JsonObject entries) {
    }

    // 读取并做文件级校验；失败时抛出带原因分类的 ImportException
    public static ParsedImport read(Path file, String fallbackLanguageCode) throws ImportException {
        if (!Files.isRegularFile(file)) {
            throw new ImportException(Failure.NOT_A_FILE, String.valueOf(file), null);
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException exception) {
            throw new ImportException(Failure.IO_ERROR, "size " + file, exception);
        }
        if (size > MAX_FILE_BYTES) {
            throw new ImportException(Failure.TOO_LARGE, size + " bytes", null);
        }
        JsonElement root;
        try (Reader reader = Files.newBufferedReader(file)) {
            root = JsonParser.parseReader(reader);
        } catch (IOException exception) {
            throw new ImportException(Failure.IO_ERROR, String.valueOf(file), exception);
        } catch (RuntimeException exception) {
            throw new ImportException(Failure.INVALID_JSON, String.valueOf(file), exception);
        }
        if (root == null || !root.isJsonObject()) {
            throw new ImportException(Failure.ROOT_NOT_OBJECT, String.valueOf(file), null);
        }
        JsonObject object = root.getAsJsonObject();
        if (object.size() > MAX_ENTRIES) {
            throw new ImportException(Failure.TOO_MANY_ENTRIES, object.size() + " entries", null);
        }
        return new ParsedImport(inferLanguage(file, fallbackLanguageCode), object);
    }

    /** 判断语言码是否合法；管理页输入框与导入文件名推断共用同一口径。 */
    public static boolean isValidLanguageCode(String languageCode) {
        return languageCode != null && languageCode.matches(LANGUAGE_CODE_PATTERN);
    }

    // 优先取文件名（去扩展名）作为语言码，非法时回退到调用方给出的当前语言
    private static String inferLanguage(Path file, String fallbackLanguageCode) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int extension = fileName.lastIndexOf('.');
        String inferred = extension > 0 ? fileName.substring(0, extension) : fileName;
        return isValidLanguageCode(inferred) ? inferred : fallbackLanguageCode;
    }
}
