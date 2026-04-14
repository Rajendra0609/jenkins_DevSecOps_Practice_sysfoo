package com.example.sysfoo.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

@Service
public class SystemInfoService {

    @Value("${app.version}")
    private String appVersion;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public String getHostname() throws UnknownHostException {
        return InetAddress.getLocalHost().getHostName();
    }

    public String getIpAddress() throws UnknownHostException {
        return InetAddress.getLocalHost().getHostAddress();
    }

    public boolean isRunningInDocker() {
        return new java.io.File("/.dockerenv").exists();
    }

    public boolean isRunningInKubernetes() {
        return System.getenv("KUBERNETES_SERVICE_HOST") != null;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public Map<String, String> getDatabaseInfo() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            String dbName = dataSource.getConnection().getMetaData().getDatabaseProductName();
            return Map.of(
                "status",       "Connected",
                "databaseType", dbName
            );
        } catch (Exception e) {
            return Map.of(
                "status",       "Disconnected",
                "databaseType", "Unknown"
            );
        }
    }
}
