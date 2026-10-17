package com.bms.convention;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 校验后端生产 Java 源文件在导入声明结束后具备非空类注释，不限制作者或创建日期。
 */
class JavaSourceConventionTest {
    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    @Test
    void shouldKeepRequiredClassJavadocImmediatelyAfterImports() throws IOException {
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .forEach(this::assertSourceConvention);
        }
    }

    private void assertSourceConvention(Path sourcePath) {
        try {
            List<String> lines = Files.readAllLines(sourcePath);
            int insertionPoint = lastImportOrPackageLine(lines);
            String nextMeaningfulLine = lines.subList(insertionPoint + 1, lines.size()).stream()
                    .filter(line -> !line.isBlank())
                    .findFirst()
                    .orElse("");
            String source = String.join("\n", lines);

            assertThat(nextMeaningfulLine).as("%s 的文件头注释位置", sourcePath)
                    .startsWith("/**");
            int commentStart = source.indexOf("/**", source.indexOf(nextMeaningfulLine));
            int commentEnd = source.indexOf("*/", commentStart);
            assertThat(commentEnd).as("%s 的类注释结束标记", sourcePath).isGreaterThan(commentStart + 3);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取源文件：" + sourcePath, exception);
        }
    }

    private int lastImportOrPackageLine(List<String> lines) {
        int lastIndex = -1;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).trim();
            if (line.startsWith("package ") || line.startsWith("import ")) {
                lastIndex = index;
            }
        }
        return lastIndex;
    }
}
