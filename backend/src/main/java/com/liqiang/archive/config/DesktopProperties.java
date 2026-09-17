package com.liqiang.archive.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.desktop")
public class DesktopProperties {

    private boolean openFolderEnabled;

    public boolean isOpenFolderEnabled() {
        return openFolderEnabled;
    }

    public void setOpenFolderEnabled(boolean openFolderEnabled) {
        this.openFolderEnabled = openFolderEnabled;
    }
}
