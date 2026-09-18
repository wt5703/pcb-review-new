package com.leapmotor.pcbreview.convention;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 校验后端生产 Java 源文件在导入声明结束后均具备约定的作者、创建日期和中文类描述，防止后续编码偏离项目统一注释规范。
 */
class JavaSourceConventionTest {
    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");
    private static final Pattern ALLOWED_DATE = Pattern.compile("@date 2026-09-(09|10|11|14|15|16|18)");
    private static final Pattern CHINESE = Pattern.compile("[\\p{IsHan}]");

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
            assertThat(source).as("%s 的作者", sourcePath).contains("@author 王涛");
            assertThat(ALLOWED_DATE.matcher(source).find()).as("%s 的创建日期", sourcePath).isTrue();
            assertThat(source).as("%s 的类描述", sourcePath).contains("@description");
            assertThat(CHINESE.matcher(source.substring(source.indexOf("@description"))).find())
                    .as("%s 的类描述应包含中文", sourcePath).isTrue();
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
