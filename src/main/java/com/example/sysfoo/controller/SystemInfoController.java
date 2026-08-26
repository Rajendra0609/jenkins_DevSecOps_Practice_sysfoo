package com.example.sysfoo.controller;

import com.example.sysfoo.service.SystemInfoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

@RestController
public class SystemInfoController {

    @Autowired
    private SystemInfoService systemInfoService;

    @GetMapping("/system-info")
    public Map<String, Object> getSystemInfo() throws UnknownHostException {
        Map<String, Object> info = new HashMap<>();
        info.put("Hostname",               systemInfoService.getHostname());
        info.put("IP Address",             systemInfoService.getIpAddress());
        info.put("Running in Docker",      systemInfoService.isRunningInDocker());
        info.put("Running in Kubernetes",  systemInfoService.isRunningInKubernetes());
        info.put("App Version",            systemInfoService.getAppVersion());
        return info;
    }

    // BUG FIX: this used to read its own separate @Value("${app.version}") field
    // instead of calling the service. SystemInfoControllerTest mocks
    // SystemInfoService.getAppVersion() and expects GET /version to reflect it —
    // that test only "passed" before because application.properties happened to
    // have the same value as the mock, not because the code path was actually
    // exercised. Routing through the service makes /system-info and /version
    // impossible to accidentally diverge, and makes the existing test meaningful.
    @GetMapping("/version")
    public ResponseEntity<String> getVersion() {
        return ResponseEntity.ok(systemInfoService.getAppVersion());
    }

    @GetMapping("/database-info")
    public Map<String, String> getDatabaseInfo() {
        return systemInfoService.getDatabaseInfo();
    }
}
