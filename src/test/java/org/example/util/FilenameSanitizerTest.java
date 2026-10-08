package org.example.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FilenameSanitizerTest {

    @Test
    void getFileExtension_txtFile_returnsTxt() {
        assertThat(FilenameSanitizer.getFileExtension("report.txt")).isEqualTo("txt");
    }

    @Test
    void getFileExtension_noExtension_returnsEmptyString() {
        assertThat(FilenameSanitizer.getFileExtension("README")).isEmpty();
    }

    @Test
    void getFileExtension_uppercaseExtension_returnsLowercase() {
        assertThat(FilenameSanitizer.getFileExtension("REPORT.TXT")).isEqualTo("txt");
    }

    @Test
    void isAllowedExtension_txtInWhitelist_returnsTrue() {
        List<String> allowed = Arrays.asList("txt", "md");
        assertThat(FilenameSanitizer.isAllowedExtension("txt", allowed)).isTrue();
    }

    @Test
    void isAllowedExtension_mdInWhitelist_returnsTrue() {
        List<String> allowed = Arrays.asList("txt", "md");
        assertThat(FilenameSanitizer.isAllowedExtension("md", allowed)).isTrue();
    }

    @Test
    void isAllowedExtension_exeNotInWhitelist_returnsFalse() {
        List<String> allowed = Arrays.asList("txt", "md");
        assertThat(FilenameSanitizer.isAllowedExtension("exe", allowed)).isFalse();
    }

    @Test
    void isAllowedExtension_emptyWhitelist_returnsFalse() {
        assertThat(FilenameSanitizer.isAllowedExtension("txt", Collections.emptyList())).isFalse();
    }
}
