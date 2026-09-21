package com.meteorite.unsuspiciousblock.platform;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.platform.services.IClientSimulationPreference;
import java.nio.file.*;
import java.io.*;
import java.util.*;
/** 与平台无关的文件读写；路径由两个平台的 SPI 实现提供。 */
public class FileSimulationPreference implements IClientSimulationPreference {
    private final Path path;
    public FileSimulationPreference(Path directory) {
        path = directory.resolve("unsuspiciousblock-simulation.properties");
    }
    @Override public Map<String, String> load() {
        Properties properties = new Properties();
        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path)) { properties.load(reader); }
            catch (IOException | IllegalArgumentException e) { Constants.LOG.warn("读取模拟偏好失败", e); }
        }
        Map<String, String> result = new LinkedHashMap<>();
        properties.stringPropertyNames().stream().limit(1024).forEach(k -> result.put(k, properties.getProperty(k)));
        return result;
    }
    @Override public void save(Map<String, String> values) {
        Properties properties = new Properties();
        properties.putAll(values);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(temporary)) { properties.store(writer, null); }
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) { Constants.LOG.warn("保存模拟偏好失败", e); }
    }
}

